package com.dmitrybrant.modelviewer.ply;

import com.dmitrybrant.modelviewer.MeshModel;

import org.junit.Test;

import java.io.ByteArrayInputStream;
import java.io.FileInputStream;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Paths;

import static org.junit.Assert.*;

public class PlyModelTest {
    private static final String RAW_DIR = "src/test/res/raw/";

    @Test
    public void testLoadBinaryModel() throws Exception {

    }

    @Test
    public void testLoadAsciiModel() throws Exception {
        InputStream stream = Files.newInputStream(Paths.get(RAW_DIR + "dolphins.ply"));
        MeshModel model = new PlyModel(stream);
        assertEquals(model.getVertexCount(), 855);
        stream.close();

        stream = Files.newInputStream(Paths.get(RAW_DIR + "ellell.ply"));
        model = new PlyModel(stream);
        assertEquals(model.getVertexCount(), 20);
        // Meshes without colors are drawn with the default colors.
        assertNull(model.getColorBuffer());
        assertFalse(model.getHasMaterials());
        stream.close();
    }

    @Test
    public void testVertexColors() throws Exception {
        String ply = "ply\nformat ascii 1.0\nelement vertex 3\nproperty float x\nproperty float y\nproperty float z\n" +
                "property uchar red\nproperty uchar green\nproperty uchar blue\nelement face 1\n" +
                "property list uchar int vertex_indices\nend_header\n" +
                "0 0 0 255 0 0\n1 0 0 0 255 0\n0 1 0 0 0 255\n3 0 1 2\n";
        MeshModel model = new PlyModel(new ByteArrayInputStream(ply.getBytes()));
        assertTrue(model.getHasMaterials());
        float[] colors = new float[12];
        model.getColorBuffer().get(colors);
        assertArrayEquals(new float[] {1, 0, 0, 1, 0, 1, 0, 1, 0, 0, 1, 1}, colors, 0f);
    }
}
