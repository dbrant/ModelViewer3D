package com.dmitrybrant.modelviewer.obj;

import com.dmitrybrant.modelviewer.Material;
import com.dmitrybrant.modelviewer.MeshPart;
import com.dmitrybrant.modelviewer.ModelResources;

import org.junit.Test;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.FloatBuffer;
import java.nio.IntBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.*;

public class ObjModelTest {
    // A unit cube, with each face as a quad.
    private static final String CUBE_POSITIONS =
            "v 0 0 0\nv 1 0 0\nv 1 1 0\nv 0 1 0\nv 0 0 1\nv 1 0 1\nv 1 1 1\nv 0 1 1\n";
    private static final String CUBE_FACES =
            "f 1 4 3 2\nf 5 6 7 8\nf 1 2 6 5\nf 2 3 7 6\nf 3 4 8 7\nf 4 1 5 8\n";

    @Test
    public void testPlainModel() throws Exception {
        ObjModel model = load(CUBE_POSITIONS + CUBE_FACES, null);
        assertEquals(8, model.getVertexCount());
        assertEquals(36, model.getIndexCount());
        assertFalse(model.getHasMaterials());
        assertNull(model.getColorBuffer());
        assertNull(model.getTexCoordBuffer());

        // Without normals in the file, each corner gets a smooth (unit) normal pointing away from the cube.
        FloatBuffer positions = model.getVertexBuffer();
        FloatBuffer normals = model.getNormalBuffer();
        for (int v = 0; v < 8; v++) {
            float length = 0;
            for (int i = 0; i < 3; i++) {
                float n = normals.get(v * 3 + i);
                float outward = positions.get(v * 3 + i) == 0 ? -1 : 1;
                assertTrue("Vertex " + v + " normal " + i, n * outward > 0.3f);
                length += n * n;
            }
            assertEquals(1f, length, 1e-5f);
        }
    }

    @Test
    public void testFaceFormats() throws Exception {
        // A pentagon (split into 3 triangles) with negative (relative) indices, and a triangle with
        // texture coordinates and normals, which share a position with the pentagon.
        String obj = "v 0 0 0\nv 1 0 0\nv 1 1 0\nv 0.5 1.5 0\nv 0 1 0\n" +
                "vt 0 0\nvt 1 1\nvn 0 0 1\nvn 0 0 -1\n" +
                "f -5 -4 -3 -2 -1\n" +
                "f 1/1/2 3/2/2 2//1\n";
        ObjModel model = load(obj, null);
        assertEquals(12, model.getIndexCount());
        // The triangle's corners differ from the pentagon's in texture coordinates or normals,
        // so they're separate vertices.
        assertEquals(8, model.getVertexCount());
        IntBuffer indices = model.getIndexBuffer();
        assertArrayEquals(new int[] {0, 1, 2, 0, 2, 3, 0, 3, 4}, slice(indices, 0, 9));
        assertArrayEquals(new int[] {5, 6, 7}, slice(indices, 9, 3));
        // The given normal is used for the triangle's corner.
        assertEquals(-1f, model.getNormalBuffer().get(5 * 3 + 2), 0f);
    }

    @Test
    public void testSharedVertices() throws Exception {
        // Corners with the same position, texture coordinate, and normal are the same vertex.
        String obj = CUBE_POSITIONS + "vt 0 0\nvn 0 0 1\nf 1/1/1 2/1/1 3/1/1\nf 1/1/1 3/1/1 4/1/1\n";
        ObjModel model = load(obj, null);
        assertEquals(4, model.getVertexCount());
        assertArrayEquals(new int[] {0, 1, 2, 0, 2, 3}, slice(model.getIndexBuffer(), 0, 6));
    }

    @Test
    public void testInvalidFace() {
        assertThrows(IOException.class, () -> load("v 0 0 0\nv 1 0 0\nf 1 2 3\n", null));
        assertThrows(IOException.class, () -> load("v 0 0 0\nv 1 0 0\nv 0 1 0\nf 1 2 -4\n", null));
    }

    @Test
    public void testVertexColors() throws Exception {
        // Colors from 0 to 1, and vertices without colors are white.
        ObjModel model = load("v 0 0 0 1 0 0\nv 1 0 0\nv 0 1 0 0 0.5 1\nf 1 2 3\n", null);
        assertTrue(model.getHasMaterials());
        assertArrayEquals(new float[] {1, 0, 0, 1, 1, 1, 1, 1, 0, 0.5f, 1, 1}, slice(model.getColorBuffer(), 0, 12), 0f);

        // Colors from 0 to 255
        model = load("v 0 0 0 255 0 0\nv 1 0 0 0 51 0\nv 0 1 0 0 0 255\nf 1 2 3\n", null);
        assertArrayEquals(new float[] {1, 0, 0, 1, 0, 0.2f, 0, 1, 0, 0, 1, 1}, slice(model.getColorBuffer(), 0, 12), 1e-6f);
    }

    @Test
    public void testMaterials() throws Exception {
        String obj = "mtllib materials.mtl\n" + CUBE_POSITIONS +
                "vt 0 0\nvt 1 0\nvt 1 1\nvt 0 1\n" +
                "usemtl wood\n" +
                "f 1/1 4/2 3/3 2/4\nf 5/1 6/2 7/3 8/4\n" +
                "usemtl glass pane\n" +
                "f 1 2 6 5\n" +
                "usemtl undefined\n" +
                "f 2 3 7 6\n" +
                "usemtl wood\n" +
                "f 3/1 4/2 8/3 7/4\n";
        String mtl = "# materials\n" +
                "newmtl wood\n" +
                "Ka 1 1 1\nKs 0.5 0.4 0.3\nNs 250\nillum 2\n" +
                "map_Kd -s 2 3 -o 0.5 0.25 -clamp on textures\\wood grain.png\n" +
                "newmtl glass pane\n" +
                "Kd 0.1 0.2 0.3\nd 0.25\nTr 0.9\nillum 1\nKe 0.1 0.1 0.1\n";
        byte[] texture = {1, 2, 3, 4};
        Map<String, byte[]> files = new HashMap<>();
        files.put("materials.mtl", mtl.getBytes(StandardCharsets.UTF_8));
        files.put("textures/wood grain.png", texture);
        List<String> requested = new ArrayList<>();
        ObjModel model = load(obj, path -> {
            requested.add(path);
            String key = ModelResources.Companion.normalizePath(path);
            return files.containsKey(key) ? new ByteArrayInputStream(files.get(key)) : null;
        });
        assertEquals(List.of("materials.mtl", "textures\\wood grain.png"), requested);

        // Faces are grouped by material, in the order that the materials are first used.
        List<MeshPart> parts = model.getParts();
        assertEquals(3, parts.size());
        assertEquals("wood", parts.get(0).getMaterial().getName());
        assertEquals(0, parts.get(0).getFirst());
        assertEquals(18, parts.get(0).getCount());
        assertEquals("glass pane", parts.get(1).getMaterial().getName());
        assertEquals(18, parts.get(1).getFirst());
        assertEquals(6, parts.get(1).getCount());
        assertEquals("undefined", parts.get(2).getMaterial().getName());
        assertEquals(24, parts.get(2).getFirst());
        assertEquals(6, parts.get(2).getCount());

        Material wood = parts.get(0).getMaterial();
        assertArrayEquals(texture, wood.getDiffuseTexture());
        // A textured material without a diffuse color shows its texture as is.
        assertArrayEquals(new float[] {1, 1, 1}, wood.getDiffuseColor(), 0f);
        assertArrayEquals(new float[] {0.5f, 0.4f, 0.3f}, wood.getSpecularColor(), 0f);
        assertEquals(250f, wood.getShininess(), 0f);
        assertEquals(Material.LIGHTING_SPECULAR, wood.getLighting());
        assertArrayEquals(new float[] {2, 3}, wood.getTextureScale(), 0f);
        assertArrayEquals(new float[] {0.5f, 0.25f}, wood.getTextureOffset(), 0f);

        Material glass = parts.get(1).getMaterial();
        assertNull(glass.getDiffuseTexture());
        assertArrayEquals(new float[] {0.1f, 0.2f, 0.3f}, glass.getDiffuseColor(), 0f);
        assertArrayEquals(new float[] {0.1f, 0.1f, 0.1f}, glass.getEmissiveColor(), 0f);
        // "d" takes precedence over "Tr".
        assertEquals(0.25f, glass.getOpacity(), 0f);
        assertEquals(Material.LIGHTING_DIFFUSE, glass.getLighting());

        // A material that isn't defined gets the default gray.
        assertArrayEquals(new float[] {0.8f, 0.8f, 0.8f}, parts.get(2).getMaterial().getDiffuseColor(), 0f);

        // Since there's a texture, texture coordinates are included.
        FloatBuffer texCoords = model.getTexCoordBuffer();
        assertNotNull(texCoords);
        int vertex = model.getIndexBuffer().get(1);
        assertArrayEquals(new float[] {1, 0}, slice(texCoords, vertex * 2, 2), 0f);
    }

    @Test
    public void testMaterialLibraryNames() throws Exception {
        Map<String, String> files = new HashMap<>();
        files.put("a.mtl", "newmtl red\nKd 1 0 0\n");
        files.put("b.mtl", "newmtl green\nKd 0 1 0\nTr 0.25\n");
        files.put("my materials.mtl", "newmtl blue\nKd 0 0 1\nTr 1\n");
        ModelResources resources = path -> files.containsKey(path)
                ? new ByteArrayInputStream(files.get(path).getBytes(StandardCharsets.UTF_8)) : null;
        // Several libraries on one line, and a library whose name has a space.
        String obj = "mtllib a.mtl b.mtl\nmtllib my materials.mtl\n" + CUBE_POSITIONS +
                "usemtl red\nf 1 2 3\nusemtl green\nf 1 3 4\nusemtl blue\nf 5 6 7\n";
        List<MeshPart> parts = load(obj, resources).getParts();
        assertEquals(3, parts.size());
        assertArrayEquals(new float[] {1, 0, 0}, parts.get(0).getMaterial().getDiffuseColor(), 0f);
        assertArrayEquals(new float[] {0, 1, 0}, parts.get(1).getMaterial().getDiffuseColor(), 0f);
        assertEquals(0.75f, parts.get(1).getMaterial().getOpacity(), 0f);
        assertArrayEquals(new float[] {0, 0, 1}, parts.get(2).getMaterial().getDiffuseColor(), 0f);
        // Some exporters write "Tr 1" for opaque materials.
        assertEquals(1f, parts.get(2).getMaterial().getOpacity(), 0f);
    }

    @Test
    public void testMissingMaterialLibrary() throws Exception {
        // Without its materials, the model is drawn like a model without materials.
        List<String> requested = new ArrayList<>();
        ObjModel model = load("mtllib missing.mtl\n" + CUBE_POSITIONS + "usemtl red\n" + CUBE_FACES, path -> {
            requested.add(path);
            return null;
        });
        assertEquals(List.of("missing.mtl"), requested);
        assertTrue(model.getParts().isEmpty());
        assertFalse(model.getHasMaterials());
        assertEquals(36, model.getIndexCount());
    }

    private static ObjModel load(String obj, ModelResources resources) throws IOException {
        return new ObjModel(new ByteArrayInputStream(obj.getBytes(StandardCharsets.UTF_8)), resources);
    }

    private static int[] slice(IntBuffer buffer, int start, int count) {
        int[] result = new int[count];
        for (int i = 0; i < count; i++) {
            result[i] = buffer.get(start + i);
        }
        return result;
    }

    private static float[] slice(FloatBuffer buffer, int start, int count) {
        float[] result = new float[count];
        for (int i = 0; i < count; i++) {
            result[i] = buffer.get(start + i);
        }
        return result;
    }
}
