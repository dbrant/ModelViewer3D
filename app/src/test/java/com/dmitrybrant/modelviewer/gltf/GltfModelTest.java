package com.dmitrybrant.modelviewer.gltf;

import com.dmitrybrant.modelviewer.Material;
import com.dmitrybrant.modelviewer.MeshPart;
import com.dmitrybrant.modelviewer.ModelResources;

import org.junit.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import java.nio.IntBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.List;

import static org.junit.Assert.*;

public class GltfModelTest {
    private static final float TOLERANCE = 1e-4f;
    private static final byte[] IMAGE = {(byte) 0x89, 'P', 'N', 'G', 13, 10, 26, 10, 1, 2, 3, 4, 5};
    private static final float[] TRIANGLE = {0, 0, 0, 1, 0, 0, 0, 1, 0};
    private static final float[] TRIANGLE_NORMALS = {0, 0, 1, 0, 0, 1, 0, 0, 1};
    // sin(45 degrees), for quaternions of rotations by 90 degrees.
    private static final double S = Math.sqrt(0.5);

    private static final int UNSIGNED_BYTE = 5121;
    private static final int UNSIGNED_SHORT = 5123;

    @Test
    public void testNodesAndMaterials() throws Exception {
        Glb glb = new Glb();
        int positions = glb.floats("VEC3", TRIANGLE);
        int normals = glb.floats("VEC3", TRIANGLE_NORMALS);
        int indices = glb.ints(UNSIGNED_SHORT, "SCALAR", false, 0, 1, 2);
        // Node 0 has a child, which is rotated (90 degrees about Y) and scaled, and the next node
        // has a matrix (a translation); the last node isn't in the scene.
        byte[] data = glb.build("""
                "scene": 0,
                "scenes": [{"nodes": [0, 2]}],
                "nodes": [
                  {"translation": [10, 0, 0], "children": [1]},
                  {"rotation": [0, %s, 0, %s], "scale": [2, 2, 2], "mesh": 0},
                  {"matrix": [1, 0, 0, 0, 0, 1, 0, 0, 0, 0, 1, 0, 0, 5, 0, 1], "mesh": 0},
                  {"mesh": 0}
                ],
                "meshes": [{"primitives": [{"attributes": {"POSITION": %d, "NORMAL": %d}, "indices": %d, "material": 0}]}],
                "materials": [{
                  "name": "Gray \\"1\\" \\u00e9",
                  "pbrMetallicRoughness": {"baseColorFactor": [0.21404114, 0.21404114, 0.21404114, 1], "metallicFactor": 0, "roughnessFactor": 0.5},
                  "emissiveFactor": [1, 0, 0]
                }]
                """.formatted(S, S, positions, normals, indices));
        GltfModel model = load(data, null);

        assertEquals(6, model.getVertexCount());
        assertPositions(model, 0, new float[] {10, 0, 0, 10, 0, -2, 10, 2, 0});
        assertPositions(model, 3, new float[] {0, 5, 0, 1, 5, 0, 0, 6, 0});
        for (int v = 0; v < 3; v++) {
            assertNormal(model, v, 1, 0, 0);
            assertNormal(model, v + 3, 0, 0, 1);
        }
        assertIndices(model, new int[] {0, 1, 2, 3, 4, 5});
        assertNull(model.getTexCoordBuffer());
        assertNull(model.getColorBuffer());

        assertEquals(1, model.getParts().size());
        Material material = model.getParts().get(0).getMaterial();
        assertEquals("Gray \"1\" \u00e9", material.getName());
        // Linear colors are converted to sRGB.
        assertArrayEquals(new float[] {0.5f, 0.5f, 0.5f}, material.getDiffuseColor(), 1e-3f);
        assertArrayEquals(new float[] {1, 0, 0}, material.getEmissiveColor(), TOLERANCE);
        // The highlight of a dielectric with a roughness of 0.5.
        assertEquals(30, material.getShininess(), TOLERANCE);
        float specular = 0.04f * (30 + 8) / 8 * 0.75f;
        assertArrayEquals(new float[] {specular, specular, specular}, material.getSpecularColor(), TOLERANCE);
        assertEquals(Material.AlphaMode.OPAQUE, material.getAlphaMode());
    }

    @Test
    public void testTexturesAndAlpha() throws Exception {
        Glb glb = new Glb();
        int positions = glb.floats("VEC3", 0, 0, 0, 1, 0, 0, 1, 1, 0, 0, 1, 0);
        int normals = glb.floats("VEC3", 0, 0, 1, 0, 0, 1, 0, 0, 1, 0, 0, 1);
        int texCoords = glb.floats("VEC2", 0, 0, 1, 0, 1, 1, 0, 1);
        int colors = glb.ints(UNSIGNED_BYTE, "VEC4", true, 255, 0, 0, 255, 0, 255, 0, 255, 0, 0, 255, 51, 255, 255, 255, 255);
        int indices = glb.ints(UNSIGNED_SHORT, "SCALAR", false, 0, 1, 2, 0, 2, 3);
        int image = glb.view(IMAGE);
        byte[] data = glb.build("""
                "extensionsUsed": ["KHR_texture_transform", "KHR_materials_pbrSpecularGlossiness"],
                "extensionsRequired": ["KHR_texture_transform"],
                "scenes": [{"nodes": [0]}],
                "nodes": [{"mesh": 0}],
                "meshes": [{"primitives": [
                  {"attributes": {"POSITION": %d, "NORMAL": %d, "TEXCOORD_0": %d}, "indices": %d, "material": 0},
                  {"attributes": {"POSITION": %d, "NORMAL": %d, "COLOR_0": %d}, "indices": %d, "material": 1}
                ]}],
                "materials": [
                  {
                    "name": "Leaves",
                    "pbrMetallicRoughness": {"baseColorTexture": {"index": 0, "extensions": {"KHR_texture_transform": {"offset": [0.5, 0], "scale": [2, 1]}}}},
                    "alphaMode": "MASK",
                    "alphaCutoff": 0.25
                  },
                  {
                    "extensions": {"KHR_materials_pbrSpecularGlossiness": {"diffuseFactor": [1, 1, 1, 0.5], "specularFactor": [1, 1, 1], "glossinessFactor": 0.5}},
                    "alphaMode": "BLEND"
                  }
                ],
                "textures": [{"source": 0, "sampler": 0}],
                "samplers": [{}],
                "images": [{"bufferView": %d, "mimeType": "image/png", "name": "leaves.png"}]
                """.formatted(positions, normals, texCoords, indices, positions, normals, colors, indices, image));
        GltfModel model = load(data, null);

        assertEquals(8, model.getVertexCount());
        assertIndices(model, new int[] {0, 1, 2, 0, 2, 3, 4, 5, 6, 4, 6, 7});
        List<MeshPart> parts = model.getParts();
        assertEquals(2, parts.size());
        Material leaves = parts.get(0).getMaterial();
        assertArrayEquals(IMAGE, leaves.getDiffuseTexture());
        assertEquals("leaves.png", leaves.getDiffuseTextureName());
        assertEquals(Material.AlphaMode.MASK, leaves.getAlphaMode());
        assertEquals(0.25f, leaves.getAlphaCutoff(), TOLERANCE);
        // The texture coordinates are scaled and offset, and then flipped, since glTF's origin is at the top left.
        assertTexCoords(model, 0, new float[] {0.5f, 1, 2.5f, 1, 2.5f, 0, 0.5f, 0});

        // The specular-glossiness extension is used without other properties.
        Material blended = parts.get(1).getMaterial();
        assertEquals(Material.AlphaMode.BLEND, blended.getAlphaMode());
        assertEquals(0.5f, blended.getOpacity(), TOLERANCE);
        assertEquals(30, blended.getShininess(), TOLERANCE);
        assertArrayEquals(new float[] {1, 1, 1}, blended.getSpecularColor(), TOLERANCE);
        assertNull(blended.getDiffuseTexture());
        // Vertex colors are converted to sRGB (except for alpha), and vertices without colors are white.
        assertColor(model, 0, 1, 1, 1, 1);
        assertColor(model, 4, 1, 0, 0, 1);
        assertColor(model, 6, 0, 0, 1, 0.2f);
    }

    @Test
    public void testStripsFansAndFlatNormals() throws Exception {
        Glb glb = new Glb();
        int stripPositions = glb.floats("VEC3", 0, 0, 0, 1, 0, 0, 0, 1, 0, 1, 1, 0);
        int fanPositions = glb.floats("VEC3", 0, 0, 0, 1, 0, 0, 1, 1, 0, 0, 1, 0);
        int fanNormals = glb.floats("VEC3", 0, 0, 1, 0, 0, 1, 0, 0, 1, 0, 0, 1);
        int fanIndices = glb.ints(UNSIGNED_BYTE, "SCALAR", false, 0, 1, 2, 3);
        // A strip without normals, in a mirrored node, and a fan (with points, which aren't shown).
        byte[] data = glb.build("""
                "scenes": [{"nodes": [0, 1]}],
                "nodes": [{"mesh": 0, "scale": [-1, 1, 1]}, {"mesh": 1}],
                "meshes": [
                  {"primitives": [{"attributes": {"POSITION": %d}, "mode": 5}]},
                  {"primitives": [
                    {"attributes": {"POSITION": %d, "NORMAL": %d}, "indices": %d, "mode": 6},
                    {"attributes": {"POSITION": %d}, "mode": 0}
                  ]}
                ]
                """.formatted(stripPositions, fanPositions, fanNormals, fanIndices, fanPositions));
        GltfModel model = load(data, null);

        // The triangles of the strip have their own vertices, and their corners are reversed, since they're mirrored.
        assertEquals(10, model.getVertexCount());
        assertPositions(model, 0, new float[] {0, 0, 0, 0, 1, 0, -1, 0, 0, 0, 1, 0, -1, 1, 0, -1, 0, 0});
        for (int v = 0; v < 10; v++) {
            assertNormal(model, v, 0, 0, 1);
        }
        assertIndices(model, new int[] {0, 1, 2, 3, 4, 5, 6, 7, 8, 6, 8, 9});
        assertTrue(model.getParts().isEmpty());
    }

    @Test
    public void testSkinning() throws Exception {
        Glb glb = new Glb();
        int positions = glb.floats("VEC3", 0, 0, 0, 6, 0, 0, 5, 1, 0);
        int normals = glb.floats("VEC3", TRIANGLE_NORMALS);
        int joints = glb.ints(UNSIGNED_BYTE, "VEC4", false, 0, 0, 0, 0, 1, 0, 0, 0, 0, 1, 0, 0);
        int weights = glb.floats("VEC4", 1, 0, 0, 0, 1, 0, 0, 0, 0.5f, 0.5f, 0, 0);
        // The first joint was bound at the origin, and the second at X = 5.
        int inverseBindMatrices = glb.floats("MAT4", 1, 0, 0, 0, 0, 1, 0, 0, 0, 0, 1, 0, 0, 0, 0, 1, 1, 0, 0, 0, 0, 1, 0, 0, 0, 0, 1, 0, -5, 0, 0, 1);
        // The joints have moved up, and the second one is turned 90 degrees about Z. The transform
        // of the skinned mesh's node doesn't matter.
        byte[] data = glb.build("""
                "scenes": [{"nodes": [0, 1]}],
                "nodes": [
                  {"mesh": 0, "skin": 0, "translation": [100, 0, 0]},
                  {"translation": [0, 10, 0], "children": [2]},
                  {"translation": [5, 0, 0], "rotation": [0, 0, %s, %s]}
                ],
                "skins": [{"joints": [1, 2], "inverseBindMatrices": %d}],
                "meshes": [{"primitives": [{"attributes": {"POSITION": %d, "NORMAL": %d, "JOINTS_0": %d, "WEIGHTS_0": %d}}]}]
                """.formatted(S, S, inverseBindMatrices, positions, normals, joints, weights));
        GltfModel model = load(data, null);

        // The last vertex is halfway between where the two joints put it.
        assertPositions(model, 0, new float[] {0, 10, 0, 5, 11, 0, 4.5f, 10.5f, 0});
        for (int v = 0; v < 3; v++) {
            assertNormal(model, v, 0, 0, 1);
        }
    }

    @Test
    public void testMorphTargetsAndSparseAccessors() throws Exception {
        Glb glb = new Glb();
        int positions = glb.floats("VEC3", TRIANGLE);
        int sparseIndices = glb.view(new byte[] {2});
        int sparseValues = glb.view(floatBytes(0, 0, 1));
        // The first target moves the last vertex (with a sparse accessor, without a buffer view), and the second isn't used.
        int target = glb.accessor("""
                {"componentType": 5126, "count": 3, "type": "VEC3",
                 "sparse": {"count": 1, "indices": {"bufferView": %d, "componentType": 5121}, "values": {"bufferView": %d}}}
                """.formatted(sparseIndices, sparseValues));
        int unusedTarget = glb.floats("VEC3", 10, 10, 10, 10, 10, 10, 10, 10, 10);
        byte[] data = glb.build("""
                "scenes": [{"nodes": [0]}],
                "nodes": [{"mesh": 0}],
                "meshes": [{
                  "primitives": [{"attributes": {"POSITION": %d}, "targets": [{"POSITION": %d}, {"POSITION": %d}]}],
                  "weights": [0.5, 0]
                }]
                """.formatted(positions, target, unusedTarget));
        GltfModel model = load(data, null);
        assertPositions(model, 0, new float[] {0, 0, 0, 1, 0, 0, 0, 1, 0.5f});
    }

    @Test
    public void testGltfWithExternalFiles() throws Exception {
        // The geometry is in a data URI, with quantized (normalized) texture coordinates, and the
        // image is in a separate file.
        ByteArrayOutputStream bin = new ByteArrayOutputStream();
        bin.writeBytes(floatBytes(TRIANGLE));
        ByteBuffer texCoords = ByteBuffer.allocate(12).order(ByteOrder.LITTLE_ENDIAN);
        texCoords.putShort((short) 0).putShort((short) 0).putShort((short) 65535).putShort((short) 0).putShort((short) 0).putShort((short) 65535);
        bin.writeBytes(texCoords.array());
        String uri = "data:application/octet-stream;base64," + Base64.getEncoder().encodeToString(bin.toByteArray());
        String gltf = """
                {
                  "asset": {"version": "2.0", "generator": "test"},
                  "extensionsRequired": ["KHR_mesh_quantization"],
                  "scenes": [{"nodes": [0]}],
                  "nodes": [{"mesh": 0}],
                  "meshes": [{"primitives": [{"attributes": {"POSITION": 0, "TEXCOORD_0": 1}, "material": 0}]}],
                  "materials": [{"pbrMetallicRoughness": {"baseColorTexture": {"index": 0}}}],
                  "textures": [{"source": 0}],
                  "images": [{"uri": "textures/My%%20Image.png"}],
                  "buffers": [{"byteLength": 48, "uri": "%s"}],
                  "bufferViews": [{"buffer": 0, "byteOffset": 0, "byteLength": 36}, {"buffer": 0, "byteOffset": 36, "byteLength": 12}],
                  "accessors": [
                    {"bufferView": 0, "componentType": 5126, "count": 3, "type": "VEC3"},
                    {"bufferView": 1, "componentType": 5123, "normalized": true, "count": 3, "type": "VEC2"}
                  ]
                }
                """.formatted(uri);
        List<String> requested = new ArrayList<>();
        GltfModel model = load(gltf.getBytes(StandardCharsets.UTF_8), path -> {
            requested.add(path);
            return path.equals("textures/My Image.png") ? new ByteArrayInputStream(IMAGE) : null;
        });
        assertEquals(List.of("textures/My Image.png"), requested);
        assertPositions(model, 0, TRIANGLE);
        // Flat normals, since the mesh has none.
        for (int v = 0; v < 3; v++) {
            assertNormal(model, v, 0, 0, 1);
        }
        assertArrayEquals(IMAGE, model.getParts().get(0).getMaterial().getDiffuseTexture());
        assertTexCoords(model, 0, new float[] {0, 1, 1, 1, 0, 0});
    }

    @Test
    public void testInvalidFiles() {
        assertThrows(IOException.class, () -> load("solid cube\nendsolid\n".getBytes(StandardCharsets.UTF_8), null));
        assertThrows(IOException.class, () -> load("{\"asset\": ".getBytes(StandardCharsets.UTF_8), null));
        assertThrows(IOException.class, () -> load("{\"asset\": {\"version\": \"1.0\"}}".getBytes(StandardCharsets.UTF_8), null));

        // Compressed meshes.
        IOException e = assertThrows(IOException.class, () -> load(new Glb().build("""
                "extensionsRequired": ["KHR_draco_mesh_compression"], "scenes": [{"nodes": []}]
                """), null));
        assertTrue(e.getMessage().contains("KHR_draco_mesh_compression"));

        // Only points.
        Glb points = new Glb();
        int positions = points.floats("VEC3", TRIANGLE);
        assertThrows(IOException.class, () -> load(points.build("""
                "scenes": [{"nodes": [0]}], "nodes": [{"mesh": 0}],
                "meshes": [{"primitives": [{"attributes": {"POSITION": %d}, "mode": 0}]}]
                """.formatted(positions)), null));

        // An index that's out of range.
        Glb badIndex = new Glb();
        int badPositions = badIndex.floats("VEC3", TRIANGLE);
        int indices = badIndex.ints(UNSIGNED_SHORT, "SCALAR", false, 0, 1, 3);
        assertThrows(IOException.class, () -> load(badIndex.build("""
                "scenes": [{"nodes": [0]}], "nodes": [{"mesh": 0}],
                "meshes": [{"primitives": [{"attributes": {"POSITION": %d}, "indices": %d}]}]
                """.formatted(badPositions, indices)), null));

        // An accessor with more elements than its buffer view.
        Glb badAccessor = new Glb();
        int view = badAccessor.view(floatBytes(TRIANGLE));
        int accessor = badAccessor.accessor("{\"bufferView\": %d, \"componentType\": 5126, \"count\": 4, \"type\": \"VEC3\"}".formatted(view));
        byte[] data = badAccessor.build("""
                "scenes": [{"nodes": [0]}], "nodes": [{"mesh": 0}],
                "meshes": [{"primitives": [{"attributes": {"POSITION": %d}}]}]
                """.formatted(accessor));
        assertThrows(IOException.class, () -> load(data, null));

        // A file that's cut off, and one of glTF 1.0.
        for (int length : new int[] {12, 30, data.length / 2, data.length - 8}) {
            assertThrows(IOException.class, () -> load(Arrays.copyOf(data, length), null));
        }
        byte[] version1 = data.clone();
        version1[4] = 1;
        assertThrows(IOException.class, () -> load(version1, null));
    }

    private static GltfModel load(byte[] data, ModelResources resources) throws IOException {
        return new GltfModel(new ByteArrayInputStream(data), resources);
    }

    private static byte[] floatBytes(float... values) {
        ByteBuffer buffer = ByteBuffer.allocate(values.length * 4).order(ByteOrder.LITTLE_ENDIAN);
        for (float value : values) {
            buffer.putFloat(value);
        }
        return buffer.array();
    }

    private static void assertPositions(GltfModel model, int firstVertex, float[] expected) {
        FloatBuffer vertices = model.getVertexBuffer();
        for (int i = 0; i < expected.length; i++) {
            assertEquals("Coordinate " + i, expected[i], vertices.get(firstVertex * 3 + i), TOLERANCE);
        }
    }

    private static void assertNormal(GltfModel model, int vertex, float x, float y, float z) {
        FloatBuffer normals = model.getNormalBuffer();
        assertEquals(x, normals.get(vertex * 3), TOLERANCE);
        assertEquals(y, normals.get(vertex * 3 + 1), TOLERANCE);
        assertEquals(z, normals.get(vertex * 3 + 2), TOLERANCE);
    }

    private static void assertTexCoords(GltfModel model, int firstVertex, float[] expected) {
        FloatBuffer texCoords = model.getTexCoordBuffer();
        assertNotNull(texCoords);
        for (int i = 0; i < expected.length; i++) {
            assertEquals("Coordinate " + i, expected[i], texCoords.get(firstVertex * 2 + i), TOLERANCE);
        }
    }

    private static void assertColor(GltfModel model, int vertex, float r, float g, float b, float a) {
        FloatBuffer colors = model.getColorBuffer();
        assertNotNull(colors);
        assertArrayEquals(new float[] {r, g, b, a},
                new float[] {colors.get(vertex * 4), colors.get(vertex * 4 + 1), colors.get(vertex * 4 + 2), colors.get(vertex * 4 + 3)}, TOLERANCE);
    }

    private static void assertIndices(GltfModel model, int[] expected) {
        IntBuffer indices = model.getIndexBuffer();
        assertNotNull(indices);
        assertEquals(expected.length, model.getIndexCount());
        for (int i = 0; i < expected.length; i++) {
            assertEquals("Index " + i, expected[i], indices.get(i));
        }
    }

    /** Builds GLB files, whose binary chunk holds the data of the buffer views. */
    private static final class Glb {
        private final ByteArrayOutputStream bin = new ByteArrayOutputStream();
        private final List<String> bufferViews = new ArrayList<>();
        private final List<String> accessors = new ArrayList<>();

        /** Adds a buffer view with the given bytes, and returns its index. */
        int view(byte[] bytes) {
            while (bin.size() % 4 != 0) {
                bin.write(0);
            }
            bufferViews.add("{\"buffer\": 0, \"byteOffset\": " + bin.size() + ", \"byteLength\": " + bytes.length + "}");
            bin.writeBytes(bytes);
            return bufferViews.size() - 1;
        }

        int floats(String type, float... values) {
            return accessor(view(floatBytes(values)), 5126, values.length / components(type), type, false);
        }

        int ints(int componentType, String type, boolean normalized, int... values) {
            int size = componentType == UNSIGNED_BYTE ? 1 : componentType == UNSIGNED_SHORT ? 2 : 4;
            ByteBuffer buffer = ByteBuffer.allocate(values.length * size).order(ByteOrder.LITTLE_ENDIAN);
            for (int value : values) {
                if (size == 1) {
                    buffer.put((byte) value);
                } else if (size == 2) {
                    buffer.putShort((short) value);
                } else {
                    buffer.putInt(value);
                }
            }
            return accessor(view(buffer.array()), componentType, values.length / components(type), type, normalized);
        }

        int accessor(int view, int componentType, int count, String type, boolean normalized) {
            return accessor("{\"bufferView\": " + view + ", \"componentType\": " + componentType + ", \"count\": " + count
                    + ", \"type\": \"" + type + "\"" + (normalized ? ", \"normalized\": true" : "") + "}");
        }

        /** Adds an accessor that's given as JSON, and returns its index. */
        int accessor(String json) {
            accessors.add(json);
            return accessors.size() - 1;
        }

        /** Builds the file, whose JSON has the given members, besides the asset, buffers, buffer views, and accessors. */
        byte[] build(String members) {
            while (bin.size() % 4 != 0) {
                bin.write(0);
            }
            String json = "{\"asset\": {\"version\": \"2.0\"}, " + members + ", \"buffers\": [{\"byteLength\": " + bin.size() + "}], "
                    + "\"bufferViews\": [" + String.join(", ", bufferViews) + "], \"accessors\": [" + String.join(", ", accessors) + "]}";
            byte[] jsonBytes = json.getBytes(StandardCharsets.UTF_8);
            // Chunks are padded to multiples of 4 bytes (with spaces, for the JSON).
            int jsonLength = (jsonBytes.length + 3) / 4 * 4;
            int length = 12 + 8 + jsonLength + 8 + bin.size();
            ByteBuffer out = ByteBuffer.allocate(length).order(ByteOrder.LITTLE_ENDIAN);
            out.put("glTF".getBytes(StandardCharsets.ISO_8859_1)).putInt(2).putInt(length);
            out.putInt(jsonLength).putInt(0x4E4F534A).put(jsonBytes);
            for (int i = jsonBytes.length; i < jsonLength; i++) {
                out.put((byte) ' ');
            }
            out.putInt(bin.size()).putInt(0x004E4942).put(bin.toByteArray());
            return out.array();
        }

        private static int components(String type) {
            return switch (type) {
                case "SCALAR" -> 1;
                case "VEC2" -> 2;
                case "VEC3" -> 3;
                case "MAT4" -> 16;
                default -> 4;
            };
        }
    }
}
