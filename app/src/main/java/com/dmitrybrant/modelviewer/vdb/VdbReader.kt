package com.dmitrybrant.modelviewer.vdb

import com.dmitrybrant.modelviewer.util.IntList
import com.dmitrybrant.modelviewer.util.Util
import java.io.BufferedInputStream
import java.io.EOFException
import java.io.IOException
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.zip.DataFormatException
import java.util.zip.Inflater

/*
* Reader for OpenVDB (.vdb) files. Reads the first scalar (float or double) grid in the file,
* which is enough for visualizing level sets and fog volumes, and a vector grid with the color
* of the volume, if there is one.
*
* The stream is read strictly sequentially, so it doesn't need to support seeking.
*
* Info on the format: https://www.openvdb.org/documentation/doxygen/ and the OpenVDB source code,
* in particular io/Archive.cc, io/Compression.h, and the readTopology/readBuffers methods of
* tree/RootNode.h, tree/InternalNode.h, and tree/LeafNode.h.
*
* Copyright 2026 Dmitry Brant. All rights reserved.
*
* Licensed under the Apache License, Version 2.0 (the "License");
* you may not use this file except in compliance with the License.
* You may obtain a copy of the License at
*
*   http://www.apache.org/licenses/LICENSE-2.0
*
* Unless required by applicable law or agreed to in writing, software
* distributed under the License is distributed on an "AS IS" BASIS,
* WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
* See the License for the specific language governing permissions and
* limitations under the License.
*/
class VdbReader(inputStream: InputStream) {
    private val stream = LittleEndianStream(inputStream)

    private var compression = 0
    private var valueSize = 4
    private var components = 1
    private var isHalf = false
    private var background = FloatArray(1)
    private var byteBuffer = ByteArray(0x1000)
    private var compressedBuffer = ByteArray(0x1000)
    private var inflater: Inflater? = null
    private val blosc = Blosc()

    /** The grids that were read from a file. */
    class Grids(val surface: VdbGrid, val color: VdbGrid?)

    /**
     * Reads the first scalar grid in the file.
     */
    fun readGrid() = read().surface

    /**
     * Reads the first scalar grid in the file, and a vector grid that holds the colors of the
     * volume (with a name like "Cd" or "color"), if there is one.
     */
    fun read(): Grids {
        try {
            if (stream.readLong() != MAGIC) {
                throw IOException("Not a valid OpenVDB file.")
            }
            val fileVersion = stream.readInt()
            if (fileVersion < MIN_FILE_VERSION) {
                throw IOException("Unsupported OpenVDB file version: $fileVersion")
            }
            // library major and minor version
            stream.readInt()
            stream.readInt()
            val hasGridOffsets = stream.readByte() != 0
            // UUID, stored as an ASCII string.
            stream.skip(36)
            // File-level metadata, which we don't need.
            readMetadata()

            val gridCount = stream.readInt()
            val skippedTypes = mutableListOf<String>()
            var surface: VdbGrid? = null
            var color: VdbGrid? = null
            for (i in 0 until gridCount) {
                // The unique name may have a suffix that disambiguates grids with the same name.
                val name = stream.readString().substringBefore('\u001e')
                var gridType = stream.readString()
                isHalf = gridType.endsWith(HALF_FLOAT_SUFFIX)
                gridType = gridType.removeSuffix(HALF_FLOAT_SUFFIX)
                val instanceParent = stream.readString()
                val gridPos = stream.readLong()
                stream.readLong() // block position
                val endPos = stream.readLong()

                val type = parseGridType(gridType)
                val isSurface = surface == null && type?.components == 1
                val isColor = color == null && type?.components == 3 && name.lowercase() in COLOR_GRID_NAMES
                if (type != null && instanceParent.isEmpty() && (isSurface || isColor)) {
                    if (hasGridOffsets) {
                        stream.seek(gridPos)
                    }
                    valueSize = type.valueSize
                    components = type.components
                    val grid = readGrid(name, type.log2Dims)
                    if (isSurface) {
                        surface = grid
                    } else {
                        color = grid
                    }
                    if (surface != null && color != null) {
                        break
                    }
                    if (hasGridOffsets) {
                        stream.seek(endPos)
                    }
                    continue
                }
                skippedTypes.add(gridType)
                if (!hasGridOffsets) {
                    // Without offsets, there's no way to skip over this grid.
                    break
                }
                stream.seek(endPos)
            }
            return Grids(surface ?: throw IOException("No supported grids found in file. Only float and double grids " +
                    "are supported" + (if (skippedTypes.isNotEmpty()) " (found: ${skippedTypes.joinToString()})." else ".")), color)
        } finally {
            inflater?.end()
        }
    }

    private fun readGrid(name: String, log2Dims: List<Int>): VdbGrid {
        compression = stream.readInt()
        val metadata = readMetadata()
        val gridClass = metadata["class"].orEmpty()
        val transform = readTransform()

        // Number of buffers per leaf, which should always be 1.
        stream.readInt()

        // Root node
        background = readValues()
        val numTiles = stream.readInt()
        val numChildren = stream.readInt()
        if (numTiles < 0 || numChildren < 0) {
            throw IOException("Invalid root node.")
        }

        // Total log2 dimension of the nodes at each level, from the top internal level to the leaves.
        val totalLog2 = IntArray(log2Dims.size)
        for (i in log2Dims.indices.reversed()) {
            totalLog2[i] = log2Dims[i] + if (i < log2Dims.size - 1) totalLog2[i + 1] else 0
        }
        val rootChildLog2 = totalLog2[0]

        val rootTiles = HashMap<Long, FloatArray>()
        for (i in 0 until numTiles) {
            val x = stream.readInt()
            val y = stream.readInt()
            val z = stream.readInt()
            val value = readValues()
            stream.readByte() // active state
            rootTiles[VdbGrid.packKey(x shr rootChildLog2, y shr rootChildLog2, z shr rootChildLog2)] = value
        }

        val internalNodes = List(log2Dims.size - 1) { HashMap<Long, FloatArray>() }
        val leafOrigins = IntList()
        for (i in 0 until numChildren) {
            val x = stream.readInt()
            val y = stream.readInt()
            val z = stream.readInt()
            readInternalTopology(0, x, y, z, log2Dims, totalLog2, internalNodes, leafOrigins)
        }

        // Now read the leaf buffers, which come in the same order as in the topology.
        val leafLog2 = log2Dims.last()
        val leafSize = 1 shl (3 * leafLog2)
        val numLeaves = leafOrigins.size / 3

        val leaves = HashMap<Long, FloatArray>(numLeaves * 2)
        val valueMask = LongArray((leafSize + 63) / 64)
        var minValue = Float.MAX_VALUE
        var maxValue = -Float.MAX_VALUE
        for (leaf in 0 until numLeaves) {
            readMask(valueMask)
            val values = FloatArray(leafSize * components)
            readCompressedValues(values, leafSize, valueMask)
            for (v in values) {
                if (v < minValue) minValue = v
                if (v > maxValue) maxValue = v
            }
            val x = leafOrigins[leaf * 3]
            val y = leafOrigins[leaf * 3 + 1]
            val z = leafOrigins[leaf * 3 + 2]
            leaves[VdbGrid.packKey(x shr leafLog2, y shr leafLog2, z shr leafLog2)] = values
        }
        if (numLeaves == 0) {
            minValue = background.min()
            maxValue = background.max()
        }

        val internalLevels = (log2Dims.size - 2 downTo 0).map {
            VdbGrid.InternalLevel(log2Dims[it], totalLog2[it], internalNodes[it])
        }
        return VdbGrid(name, gridClass, components, background, transform, leafLog2, internalLevels, rootTiles,
            rootChildLog2, leaves, minValue, maxValue)
    }

    private fun readInternalTopology(level: Int, originX: Int, originY: Int, originZ: Int,
                                     log2Dims: List<Int>, totalLog2: IntArray,
                                     internalNodes: List<HashMap<Long, FloatArray>>, leafOrigins: IntList) {
        val log2 = log2Dims[level]
        val numValues = 1 shl (3 * log2)
        val childMask = LongArray(numValues / 64)
        val valueMask = LongArray(numValues / 64)
        readMask(childMask)
        readMask(valueMask)
        val values = FloatArray(numValues * components)
        readCompressedValues(values, numValues, valueMask)
        val nodeLog2 = totalLog2[level]
        checkCoordRange(originX shr nodeLog2, originY shr nodeLog2, originZ shr nodeLog2)
        internalNodes[level][VdbGrid.packKey(originX shr nodeLog2, originY shr nodeLog2, originZ shr nodeLog2)] = values

        val childLog2 = totalLog2[level + 1]
        val isLeafParent = level + 1 == log2Dims.size - 1
        val dimMask = (1 shl log2) - 1
        for (i in 0 until numValues) {
            if ((childMask[i shr 6] ushr (i and 63)) and 1L == 0L) {
                continue
            }
            val x = originX + (((i shr (2 * log2)) and dimMask) shl childLog2)
            val y = originY + (((i shr log2) and dimMask) shl childLog2)
            val z = originZ + ((i and dimMask) shl childLog2)
            if (isLeafParent) {
                // The leaf topology is just its value mask, which is repeated in the leaf buffers.
                stream.skip((1L shl (3 * log2Dims.last())) / 8)
                checkCoordRange(x shr childLog2, y shr childLog2, z shr childLog2)
                leafOrigins.add(x)
                leafOrigins.add(y)
                leafOrigins.add(z)
            } else {
                readInternalTopology(level + 1, x, y, z, log2Dims, totalLog2, internalNodes, leafOrigins)
            }
        }
    }

    private fun checkCoordRange(x: Int, y: Int, z: Int) {
        if (x !in -MAX_NODE_COORD until MAX_NODE_COORD || y !in -MAX_NODE_COORD until MAX_NODE_COORD ||
            z !in -MAX_NODE_COORD until MAX_NODE_COORD) {
            throw IOException("Grid extents are too large.")
        }
    }

    /**
     * Reads a node's values (each with [components] floats), which may be compressed, and may omit
     * inactive values that can be reconstructed from the value mask.
     * See readCompressedValues() in OpenVDB's io/Compression.h
     */
    private fun readCompressedValues(dest: FloatArray, count: Int, valueMask: LongArray) {
        val maskCompressed = compression and COMPRESS_ACTIVE_MASK != 0
        val metadata = stream.readByte()

        var inactiveVal0 = if (metadata == NO_MASK_OR_INACTIVE_VALS) background else FloatArray(components) { -background[it] }
        var inactiveVal1 = background
        if (metadata == NO_MASK_AND_ONE_INACTIVE_VAL || metadata == MASK_AND_ONE_INACTIVE_VAL ||
            metadata == MASK_AND_TWO_INACTIVE_VALS) {
            inactiveVal0 = readValues()
            if (metadata == MASK_AND_TWO_INACTIVE_VALS) {
                inactiveVal1 = readValues()
            }
        }

        var selectionMask: LongArray? = null
        if (metadata == MASK_AND_NO_INACTIVE_VALS || metadata == MASK_AND_ONE_INACTIVE_VAL ||
            metadata == MASK_AND_TWO_INACTIVE_VALS) {
            selectionMask = LongArray(valueMask.size)
            readMask(selectionMask)
        }

        if (maskCompressed && metadata != NO_MASK_AND_ALL_VALS) {
            // Only the active values are stored, so read them into the beginning of the
            // destination array, and then spread them out into their proper positions.
            var activeCount = 0
            for (word in valueMask) {
                activeCount += java.lang.Long.bitCount(word)
            }
            readData(dest, activeCount)
            // Going backward, an active value is never moved over one that hasn't been moved yet.
            val c = components
            var activeIndex = activeCount - 1
            for (i in count - 1 downTo 0) {
                val word = i shr 6
                val bit = 1L shl (i and 63)
                if (valueMask[word] and bit != 0L) {
                    dest.copyInto(dest, i * c, activeIndex * c, activeIndex * c + c)
                    activeIndex--
                } else if (selectionMask != null && selectionMask[word] and bit != 0L) {
                    inactiveVal1.copyInto(dest, i * c)
                } else {
                    inactiveVal0.copyInto(dest, i * c)
                }
            }
        } else {
            readData(dest, count)
        }
    }

    /**
     * Reads [count] values (each with [components] floats) into the destination array.
     */
    private fun readData(dest: FloatArray, count: Int) {
        if (isHalf && count == 0) {
            // Empty arrays of half-float values are not written at all.
            return
        }
        val elementSize = if (isHalf) 2 else valueSize
        val numFloats = count * components
        val numBytes = numFloats * elementSize
        if (byteBuffer.size < numBytes) {
            byteBuffer = ByteArray(numBytes)
        }
        if (compression and (COMPRESS_BLOSC or COMPRESS_ZIP) != 0) {
            // A negative size means that the data is uncompressed.
            val size = stream.readLong()
            if (size <= 0) {
                if (-size != numBytes.toLong()) {
                    throw IOException("Unexpected uncompressed data size: ${-size}, expected $numBytes.")
                }
                stream.readFully(byteBuffer, 0, numBytes)
            } else {
                if (size > MAX_COMPRESSED_SIZE) {
                    throw IOException("Invalid compressed data size: $size")
                }
                if (compressedBuffer.size < size) {
                    compressedBuffer = ByteArray(size.toInt())
                }
                stream.readFully(compressedBuffer, 0, size.toInt())
                if (compression and COMPRESS_BLOSC != 0) {
                    blosc.decompress(compressedBuffer, size.toInt(), byteBuffer, numBytes)
                } else {
                    inflate(compressedBuffer, size.toInt(), byteBuffer, numBytes)
                }
            }
        } else {
            stream.readFully(byteBuffer, 0, numBytes)
        }

        when {
            isHalf -> {
                for (i in 0 until numFloats) {
                    dest[i] = halfToFloat(Util.readShortLe(byteBuffer, i * 2))
                }
            }
            valueSize == 8 -> {
                for (i in 0 until numFloats) {
                    dest[i] = java.lang.Double.longBitsToDouble(Util.readLongLe(byteBuffer, i * 8)).toFloat()
                }
            }
            else -> {
                ByteBuffer.wrap(byteBuffer, 0, numBytes).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer().get(dest, 0, numFloats)
            }
        }
    }

    private fun inflate(src: ByteArray, srcLength: Int, dest: ByteArray, destLength: Int) {
        val inflater = this.inflater ?: Inflater().also { this.inflater = it }
        inflater.reset()
        inflater.setInput(src, 0, srcLength)
        var total = 0
        try {
            while (total < destLength) {
                val n = inflater.inflate(dest, total, destLength - total)
                if (n == 0 && (inflater.finished() || inflater.needsInput() || inflater.needsDictionary())) {
                    break
                }
                total += n
            }
        } catch (e: DataFormatException) {
            throw IOException("Corrupt zlib data.", e)
        }
        if (total != destLength) {
            throw IOException("Unexpected decompressed data size: $total, expected $destLength.")
        }
    }

    /**
     * Reads a single value of the grid's type, which is never stored as half-float.
     */
    private fun readValues(): FloatArray {
        return FloatArray(components) { if (valueSize == 8) stream.readDouble().toFloat() else stream.readFloat() }
    }

    private class GridType(val valueSize: Int, val components: Int, val log2Dims: List<Int>)

    /**
     * Parses a grid type such as "Tree_float_5_4_3", which means a float grid with internal nodes of
     * log2 dimension 5 and 4, and leaf nodes of log2 dimension 3. Returns null if the type isn't supported.
     */
    private fun parseGridType(gridType: String): GridType? {
        val typeParts = gridType.split("_")
        if (typeParts.size < 3 || typeParts[0] != "Tree") {
            return null
        }
        val log2Dims = typeParts.drop(2).mapNotNull { it.toIntOrNull() }
        if (log2Dims.size != typeParts.size - 2 || log2Dims.size < 2 || log2Dims.any { it !in 2..7 }) {
            return null
        }
        return when (typeParts[1]) {
            "float" -> GridType(4, 1, log2Dims)
            "double" -> GridType(8, 1, log2Dims)
            "vec3s" -> GridType(4, 3, log2Dims)
            "vec3d" -> GridType(8, 3, log2Dims)
            else -> null
        }
    }

    private fun readMask(mask: LongArray) {
        for (i in mask.indices) {
            mask[i] = stream.readLong()
        }
    }

    /**
     * Reads a metadata map, and returns the values of any string entries.
     */
    private fun readMetadata(): Map<String, String> {
        val strings = mutableMapOf<String, String>()
        val count = stream.readInt()
        for (i in 0 until count) {
            val name = stream.readString()
            val type = stream.readString()
            val size = stream.readInt()
            if (size < 0) {
                throw IOException("Invalid metadata size.")
            }
            if (type == "string" && size <= MAX_STRING_LENGTH) {
                val bytes = ByteArray(size)
                stream.readFully(bytes, 0, size)
                strings[name] = String(bytes, Charsets.UTF_8)
            } else {
                stream.skip(size.toLong())
            }
        }
        return strings
    }

    /**
     * Reads the grid's index-to-world transform, and returns it as a 4x3 affine matrix (row-major,
     * applied to row vectors), which is how OpenVDB itself stores matrices.
     */
    private fun readTransform(): DoubleArray {
        val matrix = doubleArrayOf(1.0, 0.0, 0.0, 0.0, 1.0, 0.0, 0.0, 0.0, 1.0, 0.0, 0.0, 0.0)
        when (val type = stream.readString()) {
            "UniformScaleMap", "ScaleMap" -> {
                readScale(matrix)
                // Precomputed values that we don't need:
                // voxel size, inverse scale, inverse scale squared, inverse twice scale.
                stream.skip(4 * 24)
            }
            "UniformScaleTranslateMap", "ScaleTranslateMap" -> {
                matrix[9] = stream.readDouble()
                matrix[10] = stream.readDouble()
                matrix[11] = stream.readDouble()
                readScale(matrix)
                stream.skip(4 * 24)
            }
            "TranslationMap" -> {
                matrix[9] = stream.readDouble()
                matrix[10] = stream.readDouble()
                matrix[11] = stream.readDouble()
            }
            "AffineMap", "UnitaryMap" -> {
                for (row in 0 until 4) {
                    for (col in 0 until 4) {
                        val value = stream.readDouble()
                        if (col < 3) {
                            matrix[row * 3 + col] = value
                        }
                    }
                }
            }
            else -> throw IOException("Unsupported grid transform: $type")
        }
        return matrix
    }

    private fun readScale(matrix: DoubleArray) {
        matrix[0] = stream.readDouble()
        matrix[4] = stream.readDouble()
        matrix[8] = stream.readDouble()
    }

    private class LittleEndianStream(inputStream: InputStream) {
        private val input = if (inputStream is BufferedInputStream) inputStream else BufferedInputStream(inputStream, 0x10000)
        private val buffer = ByteArray(8)
        private var position = 0L

        fun readFully(bytes: ByteArray, offset: Int, length: Int) {
            var total = 0
            while (total < length) {
                val n = input.read(bytes, offset + total, length - total)
                if (n < 0) {
                    throw EOFException("Unexpected end of file.")
                }
                total += n
            }
            position += length
        }

        fun readByte(): Int {
            val b = input.read()
            if (b < 0) {
                throw EOFException("Unexpected end of file.")
            }
            position++
            return b
        }

        fun readInt(): Int {
            readFully(buffer, 0, 4)
            return Util.readIntLe(buffer, 0)
        }

        fun readLong(): Long {
            readFully(buffer, 0, 8)
            return Util.readLongLe(buffer, 0)
        }

        fun readFloat() = java.lang.Float.intBitsToFloat(readInt())

        fun readDouble() = java.lang.Double.longBitsToDouble(readLong())

        fun readString(): String {
            val length = readInt()
            if (length < 0 || length > MAX_STRING_LENGTH) {
                throw IOException("Invalid string length: $length")
            }
            val bytes = ByteArray(length)
            readFully(bytes, 0, length)
            return String(bytes, Charsets.UTF_8)
        }

        fun skip(count: Long) {
            var remaining = count
            while (remaining > 0) {
                val n = input.skip(remaining)
                if (n > 0) {
                    remaining -= n
                } else {
                    readByte()
                    position--
                    remaining--
                }
            }
            position += count
        }

        fun seek(newPosition: Long) {
            if (newPosition < position) {
                throw IOException("Invalid grid offset.")
            }
            skip(newPosition - position)
        }
    }

    companion object {
        private const val MAGIC = 0x56444220L
        private const val MIN_FILE_VERSION = 222
        private const val HALF_FLOAT_SUFFIX = "_HalfFloat"
        private const val MAX_STRING_LENGTH = 0x100000
        private const val MAX_COMPRESSED_SIZE = 0x10000000L
        private const val MAX_NODE_COORD = 1 shl 20

        /** Names of vector grids that hold the colors of a volume (lowercase). */
        private val COLOR_GRID_NAMES = setOf("cd", "color", "colour", "albedo")

        private const val COMPRESS_ZIP = 0x1
        private const val COMPRESS_ACTIVE_MASK = 0x2
        private const val COMPRESS_BLOSC = 0x4

        // Flags that describe how inactive values are stored, when using active mask compression.
        private const val NO_MASK_OR_INACTIVE_VALS = 0
        private const val NO_MASK_AND_MINUS_BG = 1
        private const val NO_MASK_AND_ONE_INACTIVE_VAL = 2
        private const val MASK_AND_NO_INACTIVE_VALS = 3
        private const val MASK_AND_ONE_INACTIVE_VAL = 4
        private const val MASK_AND_TWO_INACTIVE_VALS = 5
        private const val NO_MASK_AND_ALL_VALS = 6

        fun halfToFloat(half: Int): Float {
            val sign = (half and 0x8000) shl 16
            val exponent = (half ushr 10) and 0x1f
            val mantissa = half and 0x3ff
            return when (exponent) {
                0 -> {
                    // zero or subnormal
                    val value = mantissa * (1f / (1 shl 24))
                    if (sign != 0) -value else value
                }
                0x1f -> java.lang.Float.intBitsToFloat(sign or 0x7f800000 or (mantissa shl 13))
                else -> java.lang.Float.intBitsToFloat(sign or ((exponent + 112) shl 23) or (mantissa shl 13))
            }
        }
    }
}
