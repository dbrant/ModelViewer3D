package com.dmitrybrant.modelviewer.vdb;

import com.dmitrybrant.modelviewer.Model;

import org.junit.Test;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.List;

import static com.dmitrybrant.modelviewer.vdb.VdbModelTest.*;
import static org.junit.Assert.*;

public class VolumeTextureTest {
    // The fog sphere has density wherever it's less than RADIUS voxels from its center, which on
    // each axis is between these index coordinates.
    private static final int MIN_INDEX = -8;
    private static final int MAX_INDEX = 15;
    // Allows for the rounding of values to bytes.
    private static final double BYTE_TOLERANCE = 0.501;

    @Test
    public void testFullResolution() throws Exception {
        VolumeTexture texture = build(VolumeTexture.MAX_BYTES, fogSphere());
        assertEquals(1, texture.getFactor());
        // An empty texel of padding on each side.
        int size = MAX_INDEX - MIN_INDEX + 3;
        assertSize(texture, size);
        assertArrayEquals(new int[] {MIN_INDEX - 1, MIN_INDEX - 1, MIN_INDEX - 1}, texture.getOrigin());
        assertEquals(1f, texture.getMaxDensity(), 0f);
        assertEquals(2, texture.getChannels());
        assertFalse(texture.getHasTemperature());
        assertNull(texture.getColor());

        // This includes the middle of the sphere, which is a tile rather than a leaf.
        for (int k = 0; k < size; k++) {
            for (int j = 0; j < size; j++) {
                for (int i = 0; i < size; i++) {
                    int x = MIN_INDEX - 1 + i, y = MIN_INDEX - 1 + j, z = MIN_INDEX - 1 + k;
                    assertEquals("Texel " + i + "," + j + "," + k, fogDensity(x, y, z) * 255, density(texture, i, j, k), BYTE_TOLERANCE);
                }
            }
        }
        checkLight(texture);
        checkTexelPositions(texture);

        // The sphere is symmetric, so its center of mass is its center.
        for (int c = 0; c < 3; c++) {
            assertEquals(CENTER * VOXEL_SIZE + TRANSLATION[c], texture.getCentroid()[c], 1e-6);
        }
    }

    @Test
    public void testReducedResolution() throws Exception {
        // Too large at full resolution (26^3 texels, with 2 bytes each), but not at half of it.
        VolumeTexture texture = build(30000, fogSphere());
        assertEquals(2, texture.getFactor());
        int lo = (MIN_INDEX >> 1) - 1;
        int size = (MAX_INDEX >> 1) + 1 - lo + 1;
        assertSize(texture, size);
        assertArrayEquals(new int[] {lo * 2, lo * 2, lo * 2}, texture.getOrigin());
        // The middle of the sphere has the full density.
        assertEquals(1f, texture.getMaxDensity(), 0f);

        // Each texel has the average density of the 2x2x2 voxels that it covers.
        for (int k = 0; k < size; k++) {
            for (int j = 0; j < size; j++) {
                for (int i = 0; i < size; i++) {
                    double sum = 0;
                    for (int n = 0; n < 8; n++) {
                        sum += fogDensity((lo + i) * 2 + (n & 1), (lo + j) * 2 + ((n >> 1) & 1), (lo + k) * 2 + ((n >> 2) & 1));
                    }
                    assertEquals("Texel " + i + "," + j + "," + k, sum / 8 * 255, density(texture, i, j, k), BYTE_TOLERANCE);
                }
            }
        }
        checkLight(texture);
        checkTexelPositions(texture);
    }

    @Test
    public void testDownsampled() throws Exception {
        VolumeTexture full = build(VolumeTexture.MAX_BYTES, fogSphere(), colorGrid("Cd", false), temperatureGrid("temperature"));
        VolumeTexture half = full.downsampled();
        assertEquals(2, half.getFactor());
        assertEquals(3, half.getChannels());
        assertSize(half, (full.getSizeX() + 1) / 2);
        assertArrayEquals(full.getOrigin(), half.getOrigin());
        assertArrayEquals(full.getCentroid(), half.getCentroid(), 0);

        // Each texel has the average density, light, temperature, and (linear) color of the 2x2x2
        // texels that it covers.
        for (int k = 0; k < half.getSizeZ(); k++) {
            for (int j = 0; j < half.getSizeY(); j++) {
                for (int i = 0; i < half.getSizeX(); i++) {
                    double[] sum = new double[3];
                    double[] color = new double[3];
                    for (int n = 0; n < 8; n++) {
                        int fi = i * 2 + (n & 1), fj = j * 2 + ((n >> 1) & 1), fk = k * 2 + ((n >> 2) & 1);
                        for (int c = 0; c < 3; c++) {
                            sum[c] += channel(full, fi, fj, fk, c);
                            color[c] += srgbToLinear(color(full, fi, fj, fk, c) / 255.0);
                        }
                    }
                    String texel = "Texel " + i + "," + j + "," + k;
                    for (int c = 0; c < 3; c++) {
                        assertEquals(texel, sum[c] / 8, channel(half, i, j, k, c), BYTE_TOLERANCE);
                        assertEquals(texel, VdbModel.Companion.linearToSrgb((float) (color[c] / 8)) * 255, color(half, i, j, k, c), BYTE_TOLERANCE);
                    }
                }
            }
        }
        checkTexelPositions(half);
    }

    @Test
    public void testColor() throws Exception {
        VolumeTexture texture = build(VolumeTexture.MAX_BYTES, fogSphere(), colorGrid("Cd", false));
        assertEquals(1, texture.getFactor());
        assertNotNull(texture.getColor());
        int size = texture.getSizeX();
        for (int k = 0; k < size; k++) {
            for (int j = 0; j < size; j++) {
                for (int i = 0; i < size; i++) {
                    int[] index = {MIN_INDEX - 1 + i, MIN_INDEX - 1 + j, MIN_INDEX - 1 + k};
                    double density = density(texture, i, j, k) / 255.0;
                    for (int c = 0; c < 3; c++) {
                        // Colors are interpolated from the color grid at each texel, and premultiplied by the density.
                        double colorIndex = (index[c] * VOXEL_SIZE + TRANSLATION[c] - COLOR_TRANSLATION[c]) / VOXEL_SIZE;
                        double expected = density == 0 ? 0 : VdbModel.Companion.linearToSrgb((float) (colorValue(colorIndex, c) * density)) * 255;
                        assertEquals("Texel " + i + "," + j + "," + k + " component " + c, expected, color(texture, i, j, k, c), BYTE_TOLERANCE);
                    }
                }
            }
        }
    }

    @Test
    public void testTemperature() throws Exception {
        VolumeTexture plain = build(VolumeTexture.MAX_BYTES, fogSphere());
        VolumeTexture texture = build(VolumeTexture.MAX_BYTES, fogSphere(), temperatureGrid("temperature"));
        assertEquals(3, texture.getChannels());
        assertTrue(texture.getHasTemperature());
        assertNull(texture.getColor());
        int size = texture.getSizeX();
        assertSize(texture, size);
        assertEquals(plain.getSizeX(), size);
        float hottest = temperatureValue(31);
        for (int k = 0; k < size; k++) {
            for (int j = 0; j < size; j++) {
                for (int i = 0; i < size; i++) {
                    String texel = "Texel " + i + "," + j + "," + k;
                    // The density and light are the same as without a temperature.
                    assertEquals(texel, density(plain, i, j, k), density(texture, i, j, k));
                    assertEquals(texel, light(plain, i, j, k), light(texture, i, j, k));
                    // Temperatures are interpolated from the temperature grid at each texel with any
                    // density, as a fraction of the way from cold to the hottest.
                    double temperatureIndex = MIN_INDEX - 1 + j - 0.5;
                    double expected = density(texture, i, j, k) == 0 ? 0
                            : (temperatureValue(temperatureIndex) - TEMPERATURE_COLD) / (hottest - TEMPERATURE_COLD) * 255;
                    assertEquals(texel, expected, channel(texture, i, j, k, 2), BYTE_TOLERANCE);
                }
            }
        }

        // A temperature grid that's cold everywhere is left out.
        VdbTestWriter.Grid cold = temperatureGrid("temperature");
        for (VdbTestWriter.Leaf leaf : cold.leaves.values()) {
            java.util.Arrays.fill(leaf.values, TEMPERATURE_COLD - 1);
        }
        VolumeTexture coldTexture = build(VolumeTexture.MAX_BYTES, fogSphere(), cold);
        assertEquals(2, coldTexture.getChannels());
        assertFalse(coldTexture.getHasTemperature());
    }

    @Test
    public void testEmptyOrTooLarge() throws Exception {
        VdbTestWriter.Grid empty = new VdbTestWriter.Grid();
        empty.gridClass = "fog volume";
        empty.tiles = constantTiles(0);
        empty.leaves.put(List.of(0, 0, 0), new VdbTestWriter.Leaf());
        assertNull(VolumeTexture.Companion.build(read(empty), null, null, VolumeTexture.MAX_BYTES));

        // Too large even at an eighth of the resolution (5^3 texels).
        assertNull(VolumeTexture.Companion.build(read(fogSphere()), null, null, 100));
    }

    @Test
    public void testLoader() throws Exception {
        Model fog = load(fogSphere());
        assertTrue(fog instanceof VdbVolumeModel);
        assertEquals("fog", ((VdbVolumeModel) fog).getGridName());
        assertNull(((VdbVolumeModel) fog).getColorGridName());
        assertNull(((VdbVolumeModel) fog).getTemperatureGridName());

        Model coloredFire = load(fogSphere(), colorGrid("Cd", false), temperatureGrid("temperature"));
        assertTrue(coloredFire instanceof VdbVolumeModel);
        assertEquals("Cd", ((VdbVolumeModel) coloredFire).getColorGridName());
        assertEquals("temperature", ((VdbVolumeModel) coloredFire).getTemperatureGridName());

        Model levelSet = load(levelSetSphere());
        assertTrue(levelSet instanceof VdbModel);
        assertEquals("sphere", ((VdbModel) levelSet).getGridName());
    }

    @Test
    public void testBlackbodyColor() {
        // Compared to Mitchell Charity's table of blackbody colors (CIE 1931 2-degree observer, sRGB).
        assertSrgbEquals(new int[] {255, 137, 18}, VolumeTexture.Companion.blackbodyColor(2000), 8);
        assertSrgbEquals(new int[] {255, 180, 107}, VolumeTexture.Companion.blackbodyColor(3000), 8);
        // The white point of sRGB is about 6500K.
        assertSrgbEquals(new int[] {255, 255, 255}, VolumeTexture.Companion.blackbodyColor(6500), 8);

        // Hotter is less red.
        float[] previous = null;
        for (int kelvin = 800; kelvin <= 3000; kelvin += 100) {
            float[] color = VolumeTexture.Companion.blackbodyColor(kelvin);
            assertEquals(1f, color[0], 0f);
            if (previous != null) {
                assertTrue("Green at " + kelvin + "K", color[1] > previous[1]);
                assertTrue("Blue at " + kelvin + "K", color[2] >= previous[2]);
            }
            previous = color;
        }
    }

    @Test
    public void testFireRamp() {
        byte[] ramp = VolumeTexture.Companion.getFIRE_RAMP();
        assertEquals(256 * 3, ramp.length);
        // From nothing when cold, getting brighter, to the full color of the hottest blackbody.
        for (int c = 0; c < 3; c++) {
            assertEquals(0, ramp[c]);
        }
        float[] hottest = VolumeTexture.Companion.blackbodyColor(VolumeTexture.FIRE_MAX_KELVIN);
        for (int c = 0; c < 3; c++) {
            assertEquals(VdbModel.Companion.linearToSrgb(hottest[c]) * 255, ramp[255 * 3 + c] & 0xff, BYTE_TOLERANCE);
        }
        for (int i = 1; i < 256; i++) {
            assertTrue("Entry " + i, (ramp[i * 3] & 0xff) >= (ramp[i * 3 - 3] & 0xff));
        }
    }

    @Test
    public void testCube() {
        // Every triangle of the cube must face outward, and together they must cover all six faces.
        float[] v = VdbVolumeModel.Companion.getCUBE_VERTICES();
        assertEquals(36 * 3, v.length);
        double area = 0;
        for (int t = 0; t < v.length; t += 9) {
            double[] e1 = new double[3], e2 = new double[3], center = new double[3];
            for (int c = 0; c < 3; c++) {
                e1[c] = v[t + 3 + c] - v[t + c];
                e2[c] = v[t + 6 + c] - v[t + c];
                center[c] = (v[t + c] + v[t + 3 + c] + v[t + 6 + c]) / 3 - 0.5;
            }
            double[] normal = {e1[1] * e2[2] - e1[2] * e2[1], e1[2] * e2[0] - e1[0] * e2[2], e1[0] * e2[1] - e1[1] * e2[0]};
            assertTrue("Triangle " + t / 9 + " faces inward", normal[0] * center[0] + normal[1] * center[1] + normal[2] * center[2] > 0);
            area += Math.sqrt(normal[0] * normal[0] + normal[1] * normal[1] + normal[2] * normal[2]) / 2;
        }
        assertEquals(6, area, 1e-6);
    }

    /**
     * Checks that the light in each texel is the light from above, attenuated by the density of
     * the texels above it, and by half of its own.
     */
    private static void checkLight(VolumeTexture texture) {
        double step = VolumeTexture.EXTINCTION_SCALE * texture.getMaxDensity() * VOXEL_SIZE * texture.getFactor() / 255.0;
        for (int k = 0; k < texture.getSizeZ(); k++) {
            for (int i = 0; i < texture.getSizeX(); i++) {
                double opticalDepth = 0;
                for (int j = texture.getSizeY() - 1; j >= 0; j--) {
                    double density = density(texture, i, j, k) * step;
                    assertEquals("Texel " + i + "," + j + "," + k, Math.exp(-(opticalDepth + density / 2)) * 255,
                            light(texture, i, j, k), BYTE_TOLERANCE);
                    opticalDepth += density;
                }
            }
        }
    }

    /** Checks that texture coordinates map to the world positions of the voxels that the texels cover. */
    private static void checkTexelPositions(VolumeTexture texture) {
        int[] size = {texture.getSizeX(), texture.getSizeY(), texture.getSizeZ()};
        int[][] texels = {{0, 0, 0}, {size[0] - 1, size[1] - 1, size[2] - 1}, {size[0] / 2, 1, size[2] - 2}};
        for (int[] texel : texels) {
            double[] position = VolumeTexture.Companion.transform(texture.getTextureToWorld(),
                    (texel[0] + 0.5) / size[0], (texel[1] + 0.5) / size[1], (texel[2] + 0.5) / size[2]);
            for (int c = 0; c < 3; c++) {
                // The center of the texel's voxels.
                double index = texture.getOrigin()[c] + texel[c] * texture.getFactor() + (texture.getFactor() - 1) / 2.0;
                assertEquals(index * VOXEL_SIZE + TRANSLATION[c], position[c], 1e-9);
            }
        }
    }

    private static void assertSrgbEquals(int[] expected, float[] linear, int tolerance) {
        for (int c = 0; c < 3; c++) {
            assertEquals("Component " + c, expected[c], VdbModel.Companion.linearToSrgb(linear[c]) * 255, tolerance);
        }
    }

    private static double fogDensity(int x, int y, int z) {
        return Math.max(0, Math.min(1, -distance(x, y, z) / BACKGROUND));
    }

    private static double srgbToLinear(double c) {
        return c <= 0.04045 ? c / 12.92 : Math.pow((c + 0.055) / 1.055, 2.4);
    }

    private static int density(VolumeTexture texture, int i, int j, int k) {
        return channel(texture, i, j, k, 0);
    }

    private static int light(VolumeTexture texture, int i, int j, int k) {
        return channel(texture, i, j, k, 1);
    }

    private static int channel(VolumeTexture texture, int i, int j, int k, int channel) {
        return texture.getTexels().get(index(texture, i, j, k) * texture.getChannels() + channel) & 0xff;
    }

    private static int color(VolumeTexture texture, int i, int j, int k, int component) {
        return texture.getColor().get(index(texture, i, j, k) * 3 + component) & 0xff;
    }

    private static int index(VolumeTexture texture, int i, int j, int k) {
        return (k * texture.getSizeY() + j) * texture.getSizeX() + i;
    }

    private static void assertSize(VolumeTexture texture, int size) {
        assertEquals(size, texture.getSizeX());
        assertEquals(size, texture.getSizeY());
        assertEquals(size, texture.getSizeZ());
    }

    /** Builds the texture of the first grid, along with the color and temperature grids among the others. */
    private static VolumeTexture build(long maxBytes, VdbTestWriter.Grid... grids) throws IOException {
        VdbReader.Grids result = new VdbReader(new ByteArrayInputStream(VdbTestWriter.write(List.of(grids)))).read();
        assertEquals(grids[0].name, result.getSurface().getName());
        VolumeTexture texture = VolumeTexture.Companion.build(result.getSurface(), result.getColor(), result.getTemperature(), maxBytes);
        assertNotNull(texture);
        return texture;
    }

    private static Model load(VdbTestWriter.Grid... grids) throws IOException {
        return VdbLoader.INSTANCE.load(new ByteArrayInputStream(VdbTestWriter.write(List.of(grids))));
    }
}
