package com.dmitrybrant.modelviewer.ply;

import com.dmitrybrant.modelviewer.MeshModel;

import org.junit.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import java.nio.IntBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;

import static org.junit.Assert.*;

public class PlyModelTest {
    private static final String RAW_DIR = "src/test/res/raw/";

    private static PlyModel load(String text) throws IOException {
        return new PlyModel(new ByteArrayInputStream(text.getBytes(StandardCharsets.US_ASCII)));
    }

    private static float[] floats(FloatBuffer buffer) {
        float[] values = new float[buffer.capacity()];
        buffer.position(0);
        buffer.get(values);
        buffer.position(0);
        return values;
    }

    private static int[] ints(IntBuffer buffer) {
        int[] values = new int[buffer.capacity()];
        buffer.position(0);
        buffer.get(values);
        buffer.position(0);
        return values;
    }

    @Test
    public void testLoadAsciiModel() throws Exception {
        InputStream stream = Files.newInputStream(Paths.get(RAW_DIR + "dolphins.ply"));
        MeshModel model = new PlyModel(stream);
        assertEquals(855, model.getVertexCount());
        stream.close();

        stream = Files.newInputStream(Paths.get(RAW_DIR + "ellell.ply"));
        model = new PlyModel(stream);
        assertEquals(20, model.getVertexCount());
        // Meshes without colors are drawn with the default colors.
        assertNull(model.getColorBuffer());
        assertFalse(model.getHasMaterials());
        // 18 quads, as two triangles each.
        assertEquals(18 * 6, model.getIndexCount());
        stream.close();
    }

    @Test
    public void testVertexColors() throws Exception {
        String ply = "ply\nformat ascii 1.0\nelement vertex 3\nproperty float x\nproperty float y\nproperty float z\n" +
                "property uchar red\nproperty uchar green\nproperty uchar blue\nelement face 1\n" +
                "property list uchar int vertex_indices\nend_header\n" +
                "0 0 0 255 0 0\n1 0 0 0 255 0\n0 1 0 0 0 255\n3 0 1 2\n";
        MeshModel model = load(ply);
        assertTrue(model.getHasMaterials());
        assertArrayEquals(new float[] {1, 0, 0, 1, 0, 1, 0, 1, 0, 0, 1, 1}, floats(model.getColorBuffer()), 0f);
        assertArrayEquals(new int[] {0, 1, 2}, ints(model.getIndexBuffer()));
    }

    @Test
    public void testColorTypes() throws Exception {
        // Float colors are from 0 to 1, and ushort colors are from 0 to 65535.
        String ply = "ply\nformat ascii 1.0\nelement vertex 3\nproperty float x\nproperty float y\nproperty float z\n" +
                "property float red\nproperty float green\nproperty float blue\nproperty ushort alpha\nelement face 1\n" +
                "property list uchar int vertex_indices\nend_header\n" +
                "0 0 0 1 0 0.5 65535\n1 0 0 0 1 0 0\n0 1 0 0 0 1 65535\n3 0 1 2\n";
        MeshModel model = load(ply);
        assertArrayEquals(new float[] {1, 0, 0.5f, 1, 0, 1, 0, 0, 0, 0, 1, 1}, floats(model.getColorBuffer()), 1e-6f);
    }

    @Test
    public void testPolygonsAndNormals() throws Exception {
        // A pentagon, which is split into three triangles, with normals from the file, and an element that's skipped.
        String ply = "ply\nformat ascii 1.0\ncomment a pentagon\nelement vertex 5\nproperty float x\nproperty float y\nproperty float z\n" +
                "property float nx\nproperty float ny\nproperty float nz\nelement face 1\n" +
                "property list uchar int vertex_indices\nproperty uchar flags\nelement edge 1\nproperty int vertex1\nproperty int vertex2\nend_header\n" +
                "0 0 0 0 0 1\n1 0 0 0 0 1\n1 1 0 0 1 0\n0.5 2 0 0 0 1\n0 1 0 0 0 1\n" +
                "5 0 1 2 3 4 9\n0 1\n";
        MeshModel model = load(ply);
        assertEquals(5, model.getVertexCount());
        assertArrayEquals(new int[] {0, 1, 2, 0, 2, 3, 0, 3, 4}, ints(model.getIndexBuffer()));
        float[] normals = floats(model.getNormalBuffer());
        assertEquals(15, normals.length);
        assertArrayEquals(new float[] {0, 1, 0}, new float[] {normals[6], normals[7], normals[8]}, 0f);
    }

    @Test
    public void testPointCloud() throws Exception {
        String ply = "ply\nformat ascii 1.0\nelement vertex 2\nproperty float x\nproperty float y\nproperty float z\n" +
                "end_header\n0 0 0\n1 2 3\n";
        MeshModel model = load(ply);
        assertEquals(2, model.getVertexCount());
        assertNull(model.getIndexBuffer());
        // Points without colors are white.
        assertArrayEquals(new float[] {1, 1, 1, 1, 1, 1, 1, 1}, floats(model.getColorBuffer()), 0f);
    }

    @Test
    public void testLoadBinaryModel() throws Exception {
        // A quad in both byte orders, with an extra property in each face, and an element before the vertices.
        for (boolean bigEndian : new boolean[] {false, true}) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            out.write(("ply\nformat binary_" + (bigEndian ? "big" : "little") + "_endian 1.0\n" +
                    "element material 2\nproperty uchar red\nproperty double shininess\n" +
                    "element vertex 4\nproperty float x\nproperty float y\nproperty float z\n" +
                    "property uchar red\nproperty uchar green\nproperty uchar blue\n" +
                    "element face 1\nproperty list uchar int vertex_indices\nproperty uchar flags\nend_header\n")
                    .getBytes(StandardCharsets.US_ASCII));
            ByteBuffer buffer = ByteBuffer.allocate(1024).order(bigEndian ? ByteOrder.BIG_ENDIAN : ByteOrder.LITTLE_ENDIAN);
            for (int i = 0; i < 2; i++) {
                buffer.put((byte) 1).putDouble(2.0);
            }
            float[][] positions = {{0, 0, 0}, {1, 0, 0}, {1, 1, 0}, {0, 1, 0}};
            for (float[] p : positions) {
                buffer.putFloat(p[0]).putFloat(p[1]).putFloat(p[2]);
                buffer.put((byte) 255).put((byte) 128).put((byte) 0);
            }
            buffer.put((byte) 4).putInt(0).putInt(1).putInt(2).putInt(3).put((byte) 7);
            out.write(buffer.array(), 0, buffer.position());

            MeshModel model = new PlyModel(new ByteArrayInputStream(out.toByteArray()));
            assertEquals(4, model.getVertexCount());
            assertArrayEquals(new float[] {0, 0, 0, 1, 0, 0, 1, 1, 0, 0, 1, 0}, floats(model.getVertexBuffer()), 0f);
            assertArrayEquals(new int[] {0, 1, 2, 0, 2, 3}, ints(model.getIndexBuffer()));
            float[] colors = floats(model.getColorBuffer());
            assertArrayEquals(new float[] {1, 128 / 255f, 0, 1}, new float[] {colors[0], colors[1], colors[2], colors[3]}, 1e-6f);
            // Faces face +Z.
            assertTrue(floats(model.getNormalBuffer())[2] > 0);
        }
    }

    @Test
    public void testInvalidFace() {
        String ply = "ply\nformat ascii 1.0\nelement vertex 3\nproperty float x\nproperty float y\nproperty float z\n" +
                "element face 1\nproperty list uchar int vertex_indices\nend_header\n0 0 0\n1 0 0\n0 1 0\n3 0 1 5\n";
        assertThrows(IOException.class, () -> load(ply));
    }

    @Test
    public void testTruncatedBinaryModel() {
        String ply = "ply\nformat binary_little_endian 1.0\nelement vertex 3\nproperty float x\nproperty float y\nproperty float z\n" +
                "end_header\n\0\0\0\0";
        assertThrows(IOException.class, () -> load(ply));
    }
}
