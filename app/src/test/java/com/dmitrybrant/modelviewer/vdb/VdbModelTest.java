package com.dmitrybrant.modelviewer.vdb;

import org.junit.Test;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.FloatBuffer;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

import static org.junit.Assert.*;

public class VdbModelTest {
    // A sphere centered in the middle of the leaf at the origin, so that the leaf is an interior tile.
    static final double CENTER = 3.5;
    static final double RADIUS = 12.0;
    static final double VOXEL_SIZE = 0.5;
    static final double[] TRANSLATION = {1, 2, 3};
    static final float BACKGROUND = (float) (3 * VOXEL_SIZE);
    private static final List<Integer> ROOT_TILE = List.of(8192, 0, 0);
    // The color grid is offset by half a voxel from the surface grid, so that its values are interpolated.
    static final double[] COLOR_TRANSLATION = {TRANSLATION[0] + VOXEL_SIZE / 2, TRANSLATION[1], TRANSLATION[2]};
    // The temperature grid is offset by half a voxel along y, so that its values are interpolated.
    static final double[] TEMPERATURE_TRANSLATION = {TRANSLATION[0], TRANSLATION[1] + VOXEL_SIZE / 2, TRANSLATION[2]};
    static final float TEMPERATURE_COLD = 5;
    // Flames fill the leaf at this origin, just above the sphere, with this intensity.
    static final List<Integer> FLAME_ORIGIN = List.of(0, 16, 0);
    static final float FLAME_VALUE = 0.5f;

    @Test
    public void testLevelSetBlosc() throws Exception {
        VdbTestWriter.Grid grid = levelSetSphere();
        VdbGrid result = read(grid);
        assertEquals("sphere", result.getName());
        assertEquals("level set", result.getGridClass());
        assertTrue(result.isLevelSet());
        assertEquals(BACKGROUND, result.getBackground(), 0f);
        assertLeavesEqual(grid, result, false);

        // Values of regions without leaves come from the tiles of the tree.
        assertEquals(-BACKGROUND, result.tileValue(3, 4, 5), 0f);
        assertEquals(BACKGROUND, result.tileValue(40, 40, 40), 0f);
        assertEquals(BACKGROUND, result.tileValue(-1000, 0, 0), 0f);
        assertEquals(-BACKGROUND, result.tileValue(ROOT_TILE.get(0) + 100, 5, 5), 0f);

        checkSphereSurface(new SurfaceNets(result, 0f, false).extract(), RADIUS * VOXEL_SIZE, 0.1, 0.03, 0.99);
    }

    @Test
    public void testLevelSetBloscWithoutSplitting() throws Exception {
        VdbTestWriter.Grid grid = levelSetSphere();
        grid.bloscSplit = false;
        assertLeavesEqual(grid, read(grid), false);
    }

    @Test
    public void testLevelSetZipHalfFloat() throws Exception {
        VdbTestWriter.Grid grid = levelSetSphere();
        grid.half = true;
        grid.compression = VdbTestWriter.COMPRESS_ZIP | VdbTestWriter.COMPRESS_ACTIVE_MASK;
        VdbGrid result = read(grid);
        assertLeavesEqual(grid, result, true);
        checkSphereSurface(new SurfaceNets(result, 0f, false).extract(), RADIUS * VOXEL_SIZE, 0.1, 0.03, 0.99);
    }

    @Test
    public void testLevelSetBloscHalfFloat() throws Exception {
        VdbTestWriter.Grid grid = levelSetSphere();
        grid.half = true;
        assertLeavesEqual(grid, read(grid), true);
    }

    @Test
    public void testLevelSetUncompressedDouble() throws Exception {
        VdbTestWriter.Grid grid = levelSetSphere();
        grid.valueType = "double";
        grid.compression = VdbTestWriter.COMPRESS_NONE;
        VdbGrid result = read(grid);
        assertLeavesEqual(grid, result, false);
        assertEquals(BACKGROUND, result.tileValue(40, 40, 40), 0f);
        checkSphereSurface(new SurfaceNets(result, 0f, false).extract(), RADIUS * VOXEL_SIZE, 0.1, 0.03, 0.99);
    }

    @Test
    public void testFogVolume() throws Exception {
        VdbTestWriter.Grid grid = fogSphere();
        VdbGrid result = read(grid);
        assertFalse(result.isLevelSet());
        assertEquals(1f, result.getMaxValue(), 0f);
        assertLeavesEqual(grid, result, false);
        // Interior tiles of a fog volume are active, with full density.
        assertEquals(1f, result.tileValue(3, 4, 5), 0f);

        // The surface is drawn at 10% of the maximum density, which for this volume is
        // a tenth of the falloff width inside the sphere. The density is clamped to zero outside
        // the sphere, which makes interpolating the surface position and normals less accurate.
        double expectedRadius = RADIUS * VOXEL_SIZE - VdbModel.FOG_ISO_FRACTION * BACKGROUND;
        checkSphereSurface(new SurfaceNets(result, VdbModel.FOG_ISO_FRACTION, true).extract(), expectedRadius, 0.3, 0.06, 0.8);

        VdbModel model = new VdbModel(new ByteArrayInputStream(VdbTestWriter.write(List.of(grid))));
        assertEquals("fog", model.getGridName());
        assertTrue(model.getVertexCount() > 0);
    }

    @Test
    public void testInactiveValueEncodings() throws Exception {
        // Leaves whose inactive values exercise every way that OpenVDB can encode them.
        float[][] inactiveValues = {
                {BACKGROUND}, {-BACKGROUND}, {0.25f}, {-BACKGROUND, BACKGROUND},
                {0.25f, BACKGROUND}, {0.25f, 0.5f}, {0.125f, 0.25f, 0.375f}};
        Random random = new Random(1);
        for (boolean half : new boolean[] {false, true}) {
            VdbTestWriter.Grid grid = new VdbTestWriter.Grid();
            grid.background = BACKGROUND;
            grid.half = half;
            grid.tiles = constantTiles(BACKGROUND);
            for (int n = 0; n < inactiveValues.length; n++) {
                VdbTestWriter.Leaf leaf = new VdbTestWriter.Leaf();
                for (int i = 0; i < 512; i++) {
                    leaf.active[i] = random.nextInt(3) == 0;
                    leaf.values[i] = leaf.active[i] ? random.nextFloat() - 0.5f
                            : inactiveValues[n][random.nextInt(inactiveValues[n].length)];
                }
                grid.leaves.put(List.of(n * 8, 0, 0), leaf);
            }
            assertLeavesEqual(grid, read(grid), half);
        }
    }

    @Test
    public void testSkipsUnsupportedGrids() throws Exception {
        VdbTestWriter.Grid vectorGrid = new VdbTestWriter.Grid();
        vectorGrid.name = "velocity";
        vectorGrid.rawType = "Tree_vec3s_5_4_3";
        vectorGrid.rawPayload = new byte[1000];
        VdbTestWriter.Grid sphere = levelSetSphere();

        VdbModel model = new VdbModel(new ByteArrayInputStream(VdbTestWriter.write(List.of(vectorGrid, sphere))));
        assertEquals("sphere", model.getGridName());
        assertTrue(model.getVertexCount() > 0);
        // A vector grid that isn't named like a color grid isn't used for colors.
        assertNull(model.getColorGridName());
        assertNull(model.getColorBuffer());
    }

    @Test
    public void testColorGrid() throws Exception {
        for (boolean half : new boolean[] {false, true}) {
            VdbTestWriter.Grid velocity = colorGrid("vel", half);
            VdbTestWriter.Grid color = colorGrid("Cd", half);
            // The color grid may come before or after the surface grid.
            for (List<VdbTestWriter.Grid> grids : List.of(List.of(velocity, color, levelSetSphere()), List.of(levelSetSphere(), velocity, color))) {
                VdbModel model = new VdbModel(new ByteArrayInputStream(VdbTestWriter.write(grids)));
                assertEquals("sphere", model.getGridName());
                assertEquals("Cd", model.getColorGridName());
                assertTrue(model.getHasMaterials());

                // Colors are interpolated from the color grid at each vertex, and converted from linear to sRGB.
                FloatBuffer vertices = model.getVertexBuffer();
                FloatBuffer colors = model.getColorBuffer();
                for (int v = 0; v < model.getVertexCount(); v++) {
                    for (int c = 0; c < 3; c++) {
                        double index = (vertices.get(v * 3 + c) - COLOR_TRANSLATION[c]) / VOXEL_SIZE;
                        float expected = VdbModel.Companion.linearToSrgb(colorValue(index, c));
                        assertEquals("Vertex " + v + " component " + c, expected, colors.get(v * 4 + c), half ? 3e-3f : 1e-4f);
                    }
                    assertEquals(1f, colors.get(v * 4 + 3), 0f);
                }
            }
        }
    }

    @Test
    public void testTemperatureGrid() throws Exception {
        // The temperature grid may come before or after the density grid, and isn't mistaken for it.
        for (List<VdbTestWriter.Grid> grids : List.of(List.of(temperatureGrid("temperature"), fogSphere()),
                List.of(fogSphere(), colorGrid("Cd", false), temperatureGrid("Temperature")))) {
            VdbReader.Grids result = new VdbReader(new ByteArrayInputStream(VdbTestWriter.write(grids))).read();
            assertEquals("fog", result.getSurface().getName());
            assertNotNull(result.getTemperature());
            assertEquals(TEMPERATURE_COLD, result.getTemperature().getBackground(), 0f);
        }

        // Without any other grid, the temperature is shown as the volume.
        VdbReader.Grids result = new VdbReader(new ByteArrayInputStream(VdbTestWriter.write(List.of(temperatureGrid("temperature"))))).read();
        assertEquals("temperature", result.getSurface().getName());
        assertNull(result.getTemperature());
    }

    @Test
    public void testFlameGrid() throws Exception {
        // Grids of flames are named "flames" (EmberGen) or "flame" (Blender).
        for (String name : new String[] {"flames", "Flame"}) {
            VdbReader.Grids result = new VdbReader(new ByteArrayInputStream(VdbTestWriter.write(
                    List.of(flameGrid(name), temperatureGrid("temperature"), fogSphere())))).read();
            assertEquals("fog", result.getSurface().getName());
            assertEquals(name, result.getFlames().getName());
            assertEquals("temperature", result.getTemperature().getName());
        }

        // Without any other grid, the flames are shown as the volume.
        VdbReader.Grids result = new VdbReader(new ByteArrayInputStream(VdbTestWriter.write(List.of(flameGrid("flames"))))).read();
        assertEquals("flames", result.getSurface().getName());
        assertNull(result.getFlames());
    }

    @Test
    public void testNoSupportedGrids() {
        VdbTestWriter.Grid vectorGrid = new VdbTestWriter.Grid();
        vectorGrid.rawType = "Tree_vec3s_5_4_3";
        vectorGrid.rawPayload = new byte[100];
        IOException e = assertThrows(IOException.class, () ->
                new VdbReader(new ByteArrayInputStream(VdbTestWriter.write(List.of(vectorGrid)))).readGrid());
        assertTrue(e.getMessage(), e.getMessage().contains("Tree_vec3s_5_4_3"));
    }

    @Test
    public void testInvalidFiles() {
        assertThrows(IOException.class, () -> new VdbModel(new ByteArrayInputStream("solid cube\nendsolid".getBytes())));
        byte[] valid = VdbTestWriter.write(List.of(levelSetSphere()));
        byte[] truncated = java.util.Arrays.copyOf(valid, valid.length / 2);
        assertThrows(IOException.class, () -> new VdbModel(new ByteArrayInputStream(truncated)));
    }

    @Test
    public void testLz4Decompression() throws Exception {
        // Long runs (which need extended length bytes and overlapping matches), and random data.
        Random random = new Random(2);
        // An odd size also produces a leftover block.
        byte[] data = new byte[5001];
        for (int i = 0; i < data.length; i++) {
            data[i] = i < 2000 ? (byte) (i / 700) : i < 3000 ? (byte) (i % 7) : (byte) random.nextInt(256);
        }
        for (boolean split : new boolean[] {false, true}) {
            byte[] compressed = VdbTestWriter.bloscCompress(data, 4, split);
            assertTrue(compressed.length < data.length);
            byte[] output = new byte[data.length];
            new Blosc().decompress(compressed, compressed.length, output, output.length);
            assertArrayEquals(data, output);
        }
    }

    static VdbGrid read(VdbTestWriter.Grid grid) throws IOException {
        return new VdbReader(new ByteArrayInputStream(VdbTestWriter.write(List.of(grid)))).readGrid();
    }

    private static void assertLeavesEqual(VdbTestWriter.Grid expected, VdbGrid actual, boolean half) {
        assertEquals(expected.leaves.size(), actual.getLeaves().size());
        for (Map.Entry<List<Integer>, VdbTestWriter.Leaf> entry : expected.leaves.entrySet()) {
            List<Integer> o = entry.getKey();
            float[] values = actual.getLeaves().get(VdbGrid.Companion.packKey(o.get(0) >> 3, o.get(1) >> 3, o.get(2) >> 3));
            assertNotNull("Missing leaf " + o, values);
            VdbTestWriter.Leaf leaf = entry.getValue();
            for (int i = 0; i < 512; i++) {
                // Inactive values are usually stored at full precision, even in half-float grids.
                // (The test values are chosen so that they're exact either way.)
                float expectedValue = half && leaf.active[i]
                        ? VdbReader.Companion.halfToFloat(VdbTestWriter.floatToHalf(leaf.values[i]) & 0xffff)
                        : leaf.values[i];
                assertEquals("Leaf " + o + " voxel " + i, expectedValue, values[i], 0f);
            }
        }
    }

    /**
     * Checks that the mesh is a closed, consistently oriented surface that faces outward,
     * and that it matches a sphere of the given radius (in world units), within the given
     * tolerance for vertex positions (in voxels) and for the enclosed volume (as a fraction).
     * Vertex normals must point outward, with at least the given cosine to the radial direction.
     */
    private static void checkSphereSurface(SurfaceNets.Mesh mesh, double radius, double tolerance, double volumeTolerance,
                                           double minNormalCosine) {
        float[] v = mesh.getVertices();
        float[] n = mesh.getNormals();
        int[] indices = mesh.getIndices();
        assertTrue(mesh.getVertexCount() > 1000);
        assertEquals(0, mesh.getIndexCount() % 3);

        double cx = CENTER * VOXEL_SIZE + TRANSLATION[0];
        double cy = CENTER * VOXEL_SIZE + TRANSLATION[1];
        double cz = CENTER * VOXEL_SIZE + TRANSLATION[2];
        for (int i = 0; i < mesh.getVertexCount(); i++) {
            double dx = v[i * 3] - cx, dy = v[i * 3 + 1] - cy, dz = v[i * 3 + 2] - cz;
            double r = Math.sqrt(dx * dx + dy * dy + dz * dz);
            assertEquals("Vertex " + i + " distance from center", radius, r, tolerance * VOXEL_SIZE);
            double cosine = (n[i * 3] * dx + n[i * 3 + 1] * dy + n[i * 3 + 2] * dz) / r;
            assertTrue("Normal " + i + " deviates from the radial direction: " + cosine, cosine > minNormalCosine);
        }

        // Every edge must be shared by exactly two triangles, in opposite directions.
        Map<Long, Integer> edges = new HashMap<>();
        double volume = 0;
        for (int i = 0; i < mesh.getIndexCount(); i += 3) {
            for (int e = 0; e < 3; e++) {
                long key = ((long) indices[i + e] << 32) | indices[i + (e + 1) % 3];
                assertNull("Duplicate edge", edges.put(key, 1));
            }
            int a = indices[i] * 3, b = indices[i + 1] * 3, c = indices[i + 2] * 3;
            volume += (v[a] - cx) * ((v[b + 1] - cy) * (v[c + 2] - cz) - (v[b + 2] - cz) * (v[c + 1] - cy))
                    - (v[a + 1] - cy) * ((v[b] - cx) * (v[c + 2] - cz) - (v[b + 2] - cz) * (v[c] - cx))
                    + (v[a + 2] - cz) * ((v[b] - cx) * (v[c + 1] - cy) - (v[b + 1] - cy) * (v[c] - cx));
        }
        for (long key : edges.keySet()) {
            long reverse = (key << 32) | (key >>> 32);
            assertTrue("Unmatched edge", edges.containsKey(reverse));
        }
        // A positive volume means that the triangles wind counterclockwise when viewed from outside.
        volume /= 6;
        double expectedVolume = 4.0 / 3.0 * Math.PI * radius * radius * radius;
        assertEquals(expectedVolume, volume, expectedVolume * volumeTolerance);
    }

    /** Signed distance from the sphere, in world units. */
    static double distance(int x, int y, int z) {
        double dx = x - CENTER, dy = y - CENTER, dz = z - CENTER;
        return (Math.sqrt(dx * dx + dy * dy + dz * dz) - RADIUS) * VOXEL_SIZE;
    }

    static VdbTestWriter.Grid levelSetSphere() {
        VdbTestWriter.Grid grid = sphereGrid("sphere", "level set", (x, y, z) -> {
            double d = distance(x, y, z);
            return (float) Math.max(-BACKGROUND, Math.min(BACKGROUND, d));
        }, (x, y, z) -> Math.abs(distance(x, y, z)) < BACKGROUND);
        grid.background = BACKGROUND;
        grid.rootTiles.put(ROOT_TILE, -BACKGROUND);
        return grid;
    }

    /** The linear color at the given index-space position of the color grid, which varies along each axis. */
    static float colorValue(double index, int component) {
        return (float) ((index + 16 + component) / 40);
    }

    /** A vector grid that covers the sphere, with all voxels active. */
    static VdbTestWriter.Grid colorGrid(String name, boolean half) {
        VdbTestWriter.Grid grid = new VdbTestWriter.Grid();
        grid.name = name;
        grid.valueType = "vec3s";
        grid.half = half;
        grid.scale = new double[] {VOXEL_SIZE, VOXEL_SIZE, VOXEL_SIZE};
        grid.translation = COLOR_TRANSLATION;
        grid.tiles = constantTiles(0);
        for (int ox = -24; ox < 32; ox += 8) {
            for (int oy = -24; oy < 32; oy += 8) {
                for (int oz = -24; oz < 32; oz += 8) {
                    VdbTestWriter.Leaf leaf = new VdbTestWriter.Leaf(3);
                    for (int i = 0; i < 512; i++) {
                        int[] xyz = {ox + (i >> 6), oy + ((i >> 3) & 7), oz + (i & 7)};
                        leaf.active[i] = true;
                        for (int c = 0; c < 3; c++) {
                            leaf.values[i * 3 + c] = colorValue(xyz[c], c);
                        }
                    }
                    grid.leaves.put(List.of(ox, oy, oz), leaf);
                }
            }
        }
        return grid;
    }

    /** The temperature at the given index-space y coordinate of the temperature grid, which rises along the y axis. */
    static float temperatureValue(double y) {
        return (float) (TEMPERATURE_COLD + (y + 24) / 2);
    }

    /** A scalar grid that covers the sphere, with all voxels active, whose temperature rises along the y axis. */
    static VdbTestWriter.Grid temperatureGrid(String name) {
        VdbTestWriter.Grid grid = new VdbTestWriter.Grid();
        grid.name = name;
        grid.background = TEMPERATURE_COLD;
        grid.scale = new double[] {VOXEL_SIZE, VOXEL_SIZE, VOXEL_SIZE};
        grid.translation = TEMPERATURE_TRANSLATION;
        grid.tiles = constantTiles(TEMPERATURE_COLD);
        for (int ox = -24; ox < 32; ox += 8) {
            for (int oy = -24; oy < 32; oy += 8) {
                for (int oz = -24; oz < 32; oz += 8) {
                    VdbTestWriter.Leaf leaf = new VdbTestWriter.Leaf();
                    for (int i = 0; i < 512; i++) {
                        leaf.active[i] = true;
                        leaf.values[i] = temperatureValue(oy + ((i >> 3) & 7));
                    }
                    grid.leaves.put(List.of(ox, oy, oz), leaf);
                }
            }
        }
        return grid;
    }

    /** A grid of flames, which fill a single leaf at FLAME_ORIGIN. */
    static VdbTestWriter.Grid flameGrid(String name) {
        VdbTestWriter.Grid grid = new VdbTestWriter.Grid();
        grid.name = name;
        grid.scale = new double[] {VOXEL_SIZE, VOXEL_SIZE, VOXEL_SIZE};
        grid.translation = TRANSLATION;
        grid.tiles = constantTiles(0);
        VdbTestWriter.Leaf leaf = new VdbTestWriter.Leaf();
        java.util.Arrays.fill(leaf.values, FLAME_VALUE);
        java.util.Arrays.fill(leaf.active, true);
        grid.leaves.put(FLAME_ORIGIN, leaf);
        return grid;
    }

    static VdbTestWriter.Grid fogSphere() {
        VdbTestWriter.Grid grid = sphereGrid("fog", "fog volume", (x, y, z) ->
                (float) Math.max(0, Math.min(1, -distance(x, y, z) / BACKGROUND)),
                (x, y, z) -> distance(x, y, z) < 0);
        grid.background = 0;
        return grid;
    }

    private interface VoxelValue {
        float value(int x, int y, int z);
    }

    private interface VoxelActive {
        boolean active(int x, int y, int z);
    }

    /** Builds a grid from the given function, with leaves wherever the values aren't uniform. */
    private static VdbTestWriter.Grid sphereGrid(String name, String gridClass, VoxelValue value, VoxelActive active) {
        VdbTestWriter.Grid grid = new VdbTestWriter.Grid();
        grid.name = name;
        grid.gridClass = gridClass;
        grid.scale = new double[] {VOXEL_SIZE, VOXEL_SIZE, VOXEL_SIZE};
        grid.translation = TRANSLATION;
        for (int ox = -24; ox < 32; ox += 8) {
            for (int oy = -24; oy < 32; oy += 8) {
                for (int oz = -24; oz < 32; oz += 8) {
                    VdbTestWriter.Leaf leaf = new VdbTestWriter.Leaf();
                    boolean uniform = true;
                    for (int i = 0; i < 512; i++) {
                        int x = ox + (i >> 6), y = oy + ((i >> 3) & 7), z = oz + (i & 7);
                        leaf.values[i] = value.value(x, y, z);
                        leaf.active[i] = active.active(x, y, z);
                        uniform &= leaf.values[i] == leaf.values[0] && leaf.active[i] == leaf.active[0];
                    }
                    if (!uniform) {
                        grid.leaves.put(List.of(ox, oy, oz), leaf);
                    }
                }
            }
        }
        // Regions without leaves have uniform values, so their value at any point will do.
        grid.tiles = new VdbTestWriter.TileFunction() {
            @Override
            public float value(int x, int y, int z, int log2Size) {
                return value.value(x, y, z);
            }

            @Override
            public boolean active(int x, int y, int z, int log2Size) {
                return log2Size == 3 && active.active(x, y, z);
            }
        };
        return grid;
    }

    static VdbTestWriter.TileFunction constantTiles(float value) {
        return new VdbTestWriter.TileFunction() {
            @Override
            public float value(int x, int y, int z, int log2Size) {
                return value;
            }

            @Override
            public boolean active(int x, int y, int z, int log2Size) {
                return false;
            }
        };
    }
}
