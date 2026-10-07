package com.dmitrybrant.modelviewer.stl;

import com.dmitrybrant.modelviewer.MeshModel;

import org.junit.Test;

import java.io.ByteArrayInputStream;
import java.io.FileInputStream;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import java.nio.charset.StandardCharsets;

import static org.junit.Assert.*;

public class StlModelTest {
    private static final String RAW_DIR = "src/test/res/raw/";

    @Test
    public void testLoadBinaryModel() throws Exception {
        InputStream stream = new FileInputStream(RAW_DIR + "cube1.stl");
        MeshModel model = new StlModel(stream);
        assertEquals(model.getVertexCount(), 153846);
        stream.close();

        stream = new FileInputStream(RAW_DIR + "bunny.stl");
        model = new StlModel(stream);
        assertEquals(model.getVertexCount(), 20898);
        assertNull(model.getColorBuffer());
        assertFalse(model.getHasMaterials());
        stream.close();
    }

    @Test
    public void testLoadAsciiModel() throws Exception {
        InputStream stream = new FileInputStream(RAW_DIR + "sample.stl");
        MeshModel model = new StlModel(stream);
        assertEquals(model.getVertexCount(), 24048);
        stream.close();

        stream = new FileInputStream(RAW_DIR + "bottle.stl");
        model = new StlModel(stream);
        assertEquals(model.getVertexCount(), 3720);
        stream.close();
    }

    @Test
    public void testVisCamColors() throws Exception {
        // The top bit marks a facet with a color, with 5 bits each of red, green, and blue (from the top).
        MeshModel model = new StlModel(new ByteArrayInputStream(binaryStl("solid exported",
                0x8000 | (31 << 10), 0, 0x8000 | (31 << 5) | 15)));
        assertTrue(model.getHasMaterials());
        assertFacetColor(model, 0, 1, 0, 0);
        // A facet without a color gets the default color.
        assertFacetColor(model, 1, 0.8f, 0.8f, 0.8f);
        assertFacetColor(model, 2, 0, 1, 15 / 31f);
    }

    @Test
    public void testMagicsColors() throws Exception {
        // A header with the object's color, and facets with their own colors (red in the lowest bits),
        // or with the top bit set to use the object's color.
        byte[] header = header("COLOR=", new byte[] {(byte) 255, (byte) 128, 0, (byte) 255});
        MeshModel model = new StlModel(new ByteArrayInputStream(binaryStl(header, 31, 0x8000, 31 << 10)));
        assertFacetColor(model, 0, 1, 0, 0);
        assertFacetColor(model, 1, 1, 128 / 255f, 0);
        assertFacetColor(model, 2, 0, 0, 1);

        // A material's diffuse color takes precedence over the object's color.
        header = header("COLOR=", new byte[] {(byte) 255, 0, 0, (byte) 255, ',', 'M', 'A', 'T', 'E', 'R', 'I', 'A', 'L', '=',
                0, 0, (byte) 255, (byte) 255, 1, 1, 1, 1, 2, 2, 2, 2});
        model = new StlModel(new ByteArrayInputStream(binaryStl(header, 0x8000)));
        assertFacetColor(model, 0, 0, 0, 1);
    }

    @Test
    public void testNoColors() throws Exception {
        MeshModel model = new StlModel(new ByteArrayInputStream(binaryStl("solid exported", 0, 0)));
        assertNull(model.getColorBuffer());
        assertFalse(model.getHasMaterials());
    }

    private static void assertFacetColor(MeshModel model, int facet, float r, float g, float b) {
        FloatBuffer colors = model.getColorBuffer();
        for (int v = facet * 3; v < facet * 3 + 3; v++) {
            assertEquals(r, colors.get(v * 4), 1e-6f);
            assertEquals(g, colors.get(v * 4 + 1), 1e-6f);
            assertEquals(b, colors.get(v * 4 + 2), 1e-6f);
            assertEquals(1f, colors.get(v * 4 + 3), 0f);
        }
    }

    private static byte[] header(String text, byte[] extra) {
        byte[] header = new byte[80];
        byte[] textBytes = text.getBytes(StandardCharsets.US_ASCII);
        System.arraycopy(textBytes, 0, header, 0, textBytes.length);
        System.arraycopy(extra, 0, header, textBytes.length, extra.length);
        return header;
    }

    private static byte[] binaryStl(String headerText, int... attributes) {
        return binaryStl(header(headerText, new byte[0]), attributes);
    }

    /** Builds a binary STL with a triangle for each of the given facet attributes. */
    private static byte[] binaryStl(byte[] header, int... attributes) {
        ByteBuffer buffer = ByteBuffer.allocate(84 + 50 * attributes.length).order(ByteOrder.LITTLE_ENDIAN);
        buffer.put(header);
        buffer.putInt(attributes.length);
        for (int i = 0; i < attributes.length; i++) {
            float[] values = {0, 0, 1, i, 0, 0, i + 1, 0, 0, i, 1, 0};
            for (float v : values) {
                buffer.putFloat(v);
            }
            buffer.putShort((short) attributes[i]);
        }
        return buffer.array();
    }
}
