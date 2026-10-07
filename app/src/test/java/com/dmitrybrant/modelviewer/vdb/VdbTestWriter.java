package com.dmitrybrant.modelviewer.vdb;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.zip.Deflater;

/**
 * Writes small OpenVDB files for testing, following the layout produced by OpenVDB itself
 * (see io/Archive.cc and writeCompressedValues() in io/Compression.h), with trees of the
 * standard 5_4_3 configuration. Supports Blosc (LZ4 with byte shuffling), zlib, and uncompressed
 * data, active mask compression, half-float values, and double-precision grids.
 */
class VdbTestWriter {
    static final int COMPRESS_NONE = 0;
    static final int COMPRESS_ZIP = 0x1;
    static final int COMPRESS_ACTIVE_MASK = 0x2;
    static final int COMPRESS_BLOSC = 0x4;

    private static final int LEAF_LOG2 = 3;
    private static final int LOWER_LOG2 = 4;
    private static final int UPPER_LOG2 = 5;
    private static final int LEAF_SIZE = 1 << (3 * LEAF_LOG2);
    private static final int LOWER_TOTAL = LEAF_LOG2 + LOWER_LOG2;
    private static final int UPPER_TOTAL = LOWER_TOTAL + UPPER_LOG2;

    interface TileFunction {
        /** Value of a tile with the given origin and log2 size, for regions that have no leaves. */
        float value(int x, int y, int z, int log2Size);

        boolean active(int x, int y, int z, int log2Size);
    }

    static class Leaf {
        final float[] values = new float[LEAF_SIZE];
        final boolean[] active = new boolean[LEAF_SIZE];
    }

    static class Grid {
        String name = "grid";
        String valueType = "float";
        String gridClass;
        boolean half;
        int compression = COMPRESS_BLOSC | COMPRESS_ACTIVE_MASK;
        boolean bloscSplit = true;
        float background;
        double[] scale = {1, 1, 1};
        double[] translation = {0, 0, 0};
        /** Leaves keyed by origin; iteration order doesn't matter. */
        final Map<List<Integer>, Leaf> leaves = new LinkedHashMap<>();
        TileFunction tiles;
        /** Root-level tiles: origin (multiple of 4096) and value, written as active. */
        final Map<List<Integer>, Float> rootTiles = new LinkedHashMap<>();
        /** If set, this grid is written with the given type name and opaque contents instead. */
        String rawType;
        byte[] rawPayload;
    }

    static byte[] write(List<Grid> grids) {
        Out out = new Out();
        out.int64(0x56444220L);
        out.int32(224); // file version
        out.int32(12); // library major version
        out.int32(1); // library minor version
        out.int8(1); // has grid offsets
        out.bytes("0EA4B2C5-0F55-4B24-9C25-5CFD16E1E1CE".getBytes(StandardCharsets.US_ASCII));
        out.int32(1); // file metadata
        writeStringMeta(out, "creator", "ModelViewer3D tests");
        out.int32(grids.size());
        for (Grid grid : grids) {
            byte[][] parts = grid.rawPayload != null ? new byte[][] {grid.rawPayload, new byte[0]} : writeGrid(grid);
            String type = grid.rawType != null ? grid.rawType
                    : "Tree_" + grid.valueType + "_5_4_3" + (grid.half ? "_HalfFloat" : "");
            out.string(grid.name);
            out.string(type);
            out.string(""); // instance parent
            long gridPos = out.size() + 24;
            out.int64(gridPos);
            out.int64(gridPos + parts[0].length);
            out.int64(gridPos + parts[0].length + parts[1].length);
            out.bytes(parts[0]);
            out.bytes(parts[1]);
        }
        return out.toByteArray();
    }

    /** Returns the grid's header and topology, and its leaf buffers, as two separate arrays. */
    private static byte[][] writeGrid(Grid grid) {
        int valueSize = grid.valueType.equals("double") ? 8 : 4;
        Out out = new Out();
        out.int32(grid.compression);

        out.int32(grid.gridClass != null ? 2 : 1);
        if (grid.gridClass != null) {
            writeStringMeta(out, "class", grid.gridClass);
        }
        // Metadata of an unknown type, which should be skipped.
        out.string("file_delayed_load");
        out.string("__delayedload");
        out.int32(5);
        out.bytes(new byte[] {1, 2, 3, 4, 5});

        out.string("ScaleTranslateMap");
        out.vec3d(grid.translation);
        out.vec3d(grid.scale);
        out.vec3d(grid.scale); // voxel size
        out.vec3d(new double[] {1 / grid.scale[0], 1 / grid.scale[1], 1 / grid.scale[2]});
        out.vec3d(new double[] {1 / (grid.scale[0] * grid.scale[0]), 1 / (grid.scale[1] * grid.scale[1]), 1 / (grid.scale[2] * grid.scale[2])});
        out.vec3d(new double[] {0.5 / grid.scale[0], 0.5 / grid.scale[1], 0.5 / grid.scale[2]});

        out.int32(1); // buffer count
        out.value(grid.background, valueSize);

        // Group the leaves by their upper and lower internal nodes, in the order that OpenVDB writes them.
        TreeMap<List<Integer>, TreeMap<List<Integer>, TreeMap<List<Integer>, Leaf>>> tree = new TreeMap<>(VdbTestWriter::compareCoords);
        for (Map.Entry<List<Integer>, Leaf> entry : grid.leaves.entrySet()) {
            List<Integer> o = entry.getKey();
            List<Integer> upper = List.of(floor(o.get(0), UPPER_TOTAL), floor(o.get(1), UPPER_TOTAL), floor(o.get(2), UPPER_TOTAL));
            List<Integer> lower = List.of(floor(o.get(0), LOWER_TOTAL), floor(o.get(1), LOWER_TOTAL), floor(o.get(2), LOWER_TOTAL));
            tree.computeIfAbsent(upper, k -> new TreeMap<>(VdbTestWriter::compareCoords))
                    .computeIfAbsent(lower, k -> new TreeMap<>(VdbTestWriter::compareCoords))
                    .put(o, entry.getValue());
        }

        out.int32(grid.rootTiles.size());
        out.int32(tree.size());
        for (Map.Entry<List<Integer>, Float> tile : grid.rootTiles.entrySet()) {
            out.coord(tile.getKey());
            out.value(tile.getValue(), valueSize);
            out.int8(1);
        }
        List<Leaf> leafOrder = new ArrayList<>();
        for (Map.Entry<List<Integer>, TreeMap<List<Integer>, TreeMap<List<Integer>, Leaf>>> upper : tree.entrySet()) {
            out.coord(upper.getKey());
            writeInternalNode(out, grid, valueSize, upper.getKey(), UPPER_LOG2, LOWER_TOTAL, upper.getValue(), (lowerOrigin, lowerChildren) -> {
                @SuppressWarnings("unchecked")
                TreeMap<List<Integer>, Leaf> leaves = (TreeMap<List<Integer>, Leaf>) lowerChildren;
                writeInternalNode(out, grid, valueSize, lowerOrigin, LOWER_LOG2, LEAF_LOG2, leaves, (leafOrigin, leaf) -> {
                    out.mask(((Leaf) leaf).active);
                    leafOrder.add((Leaf) leaf);
                });
            });
        }

        Out buffers = new Out();
        for (Leaf leaf : leafOrder) {
            buffers.mask(leaf.active);
            writeCompressedValues(buffers, grid, valueSize, leaf.values, leaf.active, new boolean[LEAF_SIZE]);
        }
        return new byte[][] {out.toByteArray(), buffers.toByteArray()};
    }

    private interface ChildWriter {
        void write(List<Integer> origin, Object child);
    }

    private static void writeInternalNode(Out out, Grid grid, int valueSize, List<Integer> origin, int log2, int childLog2,
                                          TreeMap<List<Integer>, ?> children, ChildWriter childWriter) {
        int numValues = 1 << (3 * log2);
        int dim = 1 << log2;
        boolean[] childMask = new boolean[numValues];
        boolean[] valueMask = new boolean[numValues];
        float[] values = new float[numValues];
        List<List<Integer>> childOrigins = new ArrayList<>();
        for (int i = 0; i < numValues; i++) {
            int x = origin.get(0) + (((i >> (2 * log2)) & (dim - 1)) << childLog2);
            int y = origin.get(1) + (((i >> log2) & (dim - 1)) << childLog2);
            int z = origin.get(2) + ((i & (dim - 1)) << childLog2);
            List<Integer> childOrigin = List.of(x, y, z);
            if (children.containsKey(childOrigin)) {
                childMask[i] = true;
                childOrigins.add(childOrigin);
            } else {
                values[i] = grid.tiles.value(x, y, z, childLog2);
                valueMask[i] = grid.tiles.active(x, y, z, childLog2);
            }
        }
        out.mask(childMask);
        out.mask(valueMask);
        writeCompressedValues(out, grid, valueSize, values, valueMask, childMask);
        for (List<Integer> childOrigin : childOrigins) {
            childWriter.write(childOrigin, children.get(childOrigin));
        }
    }

    /** See writeCompressedValues() in OpenVDB's io/Compression.h */
    private static void writeCompressedValues(Out out, Grid grid, int valueSize, float[] values, boolean[] valueMask, boolean[] childMask) {
        if ((grid.compression & COMPRESS_ACTIVE_MASK) == 0) {
            out.int8(6); // NO_MASK_AND_ALL_VALS
            writeData(out, grid, valueSize, values);
            return;
        }
        float bg = grid.background;
        List<Float> inactiveValues = new ArrayList<>();
        for (int i = 0; i < values.length; i++) {
            if (!valueMask[i] && !childMask[i] && !inactiveValues.contains(values[i])) {
                inactiveValues.add(values[i]);
            }
        }
        int metadata;
        float inactive0 = 0, inactive1 = 0;
        if (inactiveValues.isEmpty()) {
            metadata = 0;
        } else if (inactiveValues.size() == 1) {
            inactive0 = inactiveValues.get(0);
            metadata = inactive0 == bg ? 0 : inactive0 == -bg ? 1 : 2;
        } else if (inactiveValues.size() == 2) {
            if (inactiveValues.contains(bg)) {
                inactive1 = bg;
                inactive0 = inactiveValues.get(0) == bg ? inactiveValues.get(1) : inactiveValues.get(0);
                metadata = inactive0 == -bg ? 3 : 4;
            } else {
                inactive0 = inactiveValues.get(0);
                inactive1 = inactiveValues.get(1);
                metadata = 5;
            }
        } else {
            metadata = 6;
        }
        out.int8(metadata);
        if (metadata == 6) {
            writeData(out, grid, valueSize, values);
            return;
        }
        if (metadata == 2 || metadata == 4 || metadata == 5) {
            out.value(inactive0, valueSize);
        }
        if (metadata == 5) {
            out.value(inactive1, valueSize);
        }
        if (metadata >= 3) {
            boolean[] selection = new boolean[values.length];
            for (int i = 0; i < values.length; i++) {
                selection[i] = !valueMask[i] && !childMask[i] && values[i] == inactive1;
            }
            out.mask(selection);
        }
        int count = 0;
        for (boolean b : valueMask) {
            if (b) count++;
        }
        float[] activeValues = new float[count];
        count = 0;
        for (int i = 0; i < values.length; i++) {
            if (valueMask[i]) activeValues[count++] = values[i];
        }
        writeData(out, grid, valueSize, activeValues);
    }

    private static void writeData(Out out, Grid grid, int valueSize, float[] values) {
        if (grid.half && values.length == 0) {
            // OpenVDB writes nothing at all for empty half-float arrays.
            return;
        }
        int elementSize = grid.half ? 2 : valueSize;
        ByteBuffer buffer = ByteBuffer.allocate(values.length * elementSize).order(ByteOrder.LITTLE_ENDIAN);
        for (float v : values) {
            if (grid.half) {
                buffer.putShort(floatToHalf(v));
            } else if (valueSize == 8) {
                buffer.putDouble(v);
            } else {
                buffer.putFloat(v);
            }
        }
        byte[] data = buffer.array();
        if ((grid.compression & COMPRESS_BLOSC) != 0) {
            byte[] compressed = data.length == 0 ? null : bloscCompress(data, 4, grid.bloscSplit);
            writeSizedData(out, data, compressed);
        } else if ((grid.compression & COMPRESS_ZIP) != 0) {
            Deflater deflater = new Deflater();
            deflater.setInput(data);
            deflater.finish();
            byte[] temp = new byte[data.length * 2 + 64];
            int n = deflater.deflate(temp);
            deflater.end();
            writeSizedData(out, data, n < data.length ? java.util.Arrays.copyOf(temp, n) : null);
        } else {
            out.bytes(data);
        }
    }

    /** A positive size means compressed data follows, and a negative size means uncompressed data. */
    private static void writeSizedData(Out out, byte[] data, byte[] compressed) {
        if (compressed != null) {
            out.int64(compressed.length);
            out.bytes(compressed);
        } else {
            out.int64(-data.length);
            out.bytes(data);
        }
    }

    /** Compresses in the Blosc version 1 format, with byte shuffling and the LZ4 codec, like OpenVDB does. */
    static byte[] bloscCompress(byte[] data, int typeSize, boolean split) {
        int numBytes = data.length;
        Out out = new Out();
        if (numBytes < 128) {
            // Small buffers are stored without compression.
            out.int8(2);
            out.int8(1);
            out.int8(0x1 | 0x2 | (1 << 5));
            out.int8(typeSize);
            out.int32(numBytes);
            out.int32(numBytes);
            out.int32(16 + numBytes);
            out.bytes(data);
            return out.toByteArray();
        }
        // Blosc rounds the block size down to a multiple of the type size, which leaves a
        // leftover block when the data size isn't a multiple of it.
        int blockSize = numBytes / typeSize * typeSize;
        int leftover = numBytes % blockSize;
        int numBlocks = numBytes / blockSize + (leftover > 0 ? 1 : 0);

        Out blocks = new Out();
        int[] blockStarts = new int[numBlocks];
        int headerSize = 16 + 4 * numBlocks;
        for (int b = 0; b < numBlocks; b++) {
            boolean isLeftover = leftover > 0 && b == numBlocks - 1;
            int size = isLeftover ? leftover : blockSize;
            byte[] block = new byte[size];
            System.arraycopy(data, b * blockSize, block, 0, size);
            // shuffle
            byte[] shuffled = new byte[size];
            int numElements = size / typeSize;
            for (int i = 0; i < numElements; i++) {
                for (int j = 0; j < typeSize; j++) {
                    shuffled[j * numElements + i] = block[i * typeSize + j];
                }
            }
            System.arraycopy(block, numElements * typeSize, shuffled, numElements * typeSize, size - numElements * typeSize);

            blockStarts[b] = headerSize + blocks.size();
            int numSplits = split && typeSize <= 16 && size / typeSize >= 128 && !isLeftover ? typeSize : 1;
            int splitSize = size / numSplits;
            for (int s = 0; s < numSplits; s++) {
                byte[] compressed = lz4Compress(shuffled, s * splitSize, splitSize);
                if (compressed.length >= splitSize) {
                    blocks.int32(splitSize);
                    blocks.bytes(java.util.Arrays.copyOfRange(shuffled, s * splitSize, (s + 1) * splitSize));
                } else {
                    blocks.int32(compressed.length);
                    blocks.bytes(compressed);
                }
            }
        }
        out.int8(2);
        out.int8(1);
        out.int8(0x1 | (split ? 0 : 0x10) | (1 << 5));
        out.int8(typeSize);
        out.int32(numBytes);
        out.int32(blockSize);
        out.int32(headerSize + blocks.size());
        for (int start : blockStarts) {
            out.int32(start);
        }
        out.bytes(blocks.toByteArray());
        return out.toByteArray();
    }

    /** A simple greedy LZ4 block compressor. */
    static byte[] lz4Compress(byte[] src, int offset, int length) {
        Out out = new Out();
        int[] table = new int[4096];
        java.util.Arrays.fill(table, -1);
        int end = offset + length;
        int anchor = offset;
        int i = offset;
        // The format requires the last 5 bytes to be literals, and the last match to start at least 12 bytes before the end.
        while (i + 12 <= end) {
            int seq = readInt(src, i);
            int hash = (seq * -1640531535) >>> 20;
            int ref = table[hash];
            table[hash] = i;
            if (ref >= 0 && i - ref <= 65535 && readInt(src, ref) == seq) {
                int matchLength = 4;
                while (i + matchLength < end - 5 && src[ref + matchLength] == src[i + matchLength]) {
                    matchLength++;
                }
                writeSequence(out, src, anchor, i - anchor, i - ref, matchLength);
                i += matchLength;
                anchor = i;
            } else {
                i++;
            }
        }
        writeSequence(out, src, anchor, end - anchor, 0, 0);
        return out.toByteArray();
    }

    private static void writeSequence(Out out, byte[] src, int literalStart, int literalLength, int offset, int matchLength) {
        int ml = matchLength > 0 ? matchLength - 4 : 0;
        out.int8((Math.min(literalLength, 15) << 4) | Math.min(ml, 15));
        writeLength(out, literalLength);
        out.bytes(java.util.Arrays.copyOfRange(src, literalStart, literalStart + literalLength));
        if (matchLength > 0) {
            out.int8(offset & 0xff);
            out.int8(offset >> 8);
            writeLength(out, ml);
        }
    }

    private static void writeLength(Out out, int length) {
        if (length < 15) {
            return;
        }
        length -= 15;
        while (length >= 255) {
            out.int8(255);
            length -= 255;
        }
        out.int8(length);
    }

    private static int readInt(byte[] b, int i) {
        return (b[i] & 0xff) | (b[i + 1] & 0xff) << 8 | (b[i + 2] & 0xff) << 16 | (b[i + 3] & 0xff) << 24;
    }

    static short floatToHalf(float f) {
        int bits = Float.floatToIntBits(f);
        int sign = (bits >>> 16) & 0x8000;
        int val = (bits & 0x7fffffff) + 0x1000;
        if (val >= 0x47800000) {
            return (short) (sign | 0x7c00);
        }
        if (val >= 0x38800000) {
            return (short) (sign | ((val - 0x38000000) >>> 13));
        }
        if (val < 0x33000000) {
            return (short) sign;
        }
        val = (bits & 0x7fffffff) >>> 23;
        return (short) (sign | ((((bits & 0x7fffff) | 0x800000) + (0x800000 >>> (val - 102))) >>> (126 - val)));
    }

    private static void writeStringMeta(Out out, String name, String value) {
        out.string(name);
        out.string("string");
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        out.int32(bytes.length);
        out.bytes(bytes);
    }

    private static int floor(int v, int log2) {
        return v >> log2 << log2;
    }

    private static int compareCoords(List<Integer> a, List<Integer> b) {
        for (int i = 0; i < 3; i++) {
            int c = Integer.compare(a.get(i), b.get(i));
            if (c != 0) return c;
        }
        return 0;
    }

    private static class Out extends ByteArrayOutputStream {
        void int8(int v) {
            write(v);
        }

        void int32(int v) {
            write(ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(v).array(), 0, 4);
        }

        void int64(long v) {
            write(ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN).putLong(v).array(), 0, 8);
        }

        void value(float v, int size) {
            if (size == 8) {
                int64(Double.doubleToLongBits(v));
            } else {
                int32(Float.floatToIntBits(v));
            }
        }

        void vec3d(double[] v) {
            for (double d : v) {
                int64(Double.doubleToLongBits(d));
            }
        }

        void coord(List<Integer> c) {
            for (int v : c) {
                int32(v);
            }
        }

        void string(String s) {
            byte[] bytes = s.getBytes(StandardCharsets.UTF_8);
            int32(bytes.length);
            bytes(bytes);
        }

        void bytes(byte[] b) {
            write(b, 0, b.length);
        }

        void mask(boolean[] bits) {
            byte[] bytes = new byte[bits.length / 8];
            for (int i = 0; i < bits.length; i++) {
                if (bits[i]) bytes[i >> 3] |= (byte) (1 << (i & 7));
            }
            bytes(bytes);
        }
    }
}
