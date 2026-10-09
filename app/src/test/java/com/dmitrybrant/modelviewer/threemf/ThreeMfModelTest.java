package com.dmitrybrant.modelviewer.threemf;

import com.dmitrybrant.modelviewer.MeshPart;

import org.junit.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.zip.CRC32;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.Assert.*;

public class ThreeMfModelTest {
    private static final String MODEL_START = """
            <?xml version="1.0" encoding="UTF-8"?>
            <model unit="millimeter" xmlns="http://schemas.microsoft.com/3dmanufacturing/core/2015/02"
                xmlns:m="http://schemas.microsoft.com/3dmanufacturing/material/2015/02"
                xmlns:p="http://schemas.microsoft.com/3dmanufacturing/production/2015/06">
            """;
    private static final String MODEL_END = "</model>";
    // A triangle in the XY plane, facing +Z.
    private static final String TRIANGLE_MESH = """
            <mesh>
              <vertices>
                <vertex x="0" y="0" z="0"/>
                <vertex x="1" y="0" z="0"/>
                <vertex x="0" y="1" z="0"/>
              </vertices>
              <triangles>
                <triangle v1="0" v2="1" v3="2"/>
              </triangles>
            </mesh>
            """;
    private static final float TOLERANCE = 1e-5f;

    @Test
    public void testBuildItemsAndComponents() throws Exception {
        Map<String, String> files = new LinkedHashMap<>();
        // The part with the build is named in the package relationships, which come last.
        files.put("3D/main.model", MODEL_START + """
                <resources>
                  <object id="2" type="model">
                    <components>
                      <component p:path="/3D/Objects/part.model" objectid="1" transform="1 0 0 0 1 0 0 0 1 10 0 0"/>
                    </components>
                  </object>
                  <object id="3" type="model">""" + TRIANGLE_MESH + """
                  </object>
                </resources>
                <build>
                  <item objectid="2" transform="2 0 0 0 2 0 0 0 2 0 0 5"/>
                  <item objectid="3"/>
                </build>
                """ + MODEL_END);
        files.put("3D/Objects/part.model", MODEL_START + "<resources><object id=\"1\">" + TRIANGLE_MESH + "</object></resources>" + MODEL_END);
        files.put("_rels/.rels", """
                <?xml version="1.0" encoding="UTF-8"?>
                <Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">
                  <Relationship Target="/Metadata/thumbnail.png" Id="rel-2" Type="http://schemas.openxmlformats.org/package/2006/relationships/metadata/thumbnail"/>
                  <Relationship Target="/3D/main.model" Id="rel-1" Type="http://schemas.microsoft.com/3dmanufacturing/2013/01/3dmodel"/>
                </Relationships>
                """);

        ThreeMfModel model = load(files);
        assertEquals(6, model.getVertexCount());
        // The component is translated, and then the build item scales and translates it.
        assertPositions(model, 0, new float[] {20, 0, 5, 22, 0, 5, 20, 2, 5});
        assertPositions(model, 3, new float[] {0, 0, 0, 1, 0, 0, 0, 1, 0});
        for (int v = 0; v < 6; v++) {
            assertNormal(model, v, 0, 0, 1);
        }
        assertNull(model.getColorBuffer());
        assertNull(model.getTexCoordBuffer());
        assertFalse(model.getHasMaterials());
    }

    @Test
    public void testMirroredTransform() throws Exception {
        ThreeMfModel model = load(Map.of("3D/3dmodel.model", MODEL_START
                + "<resources><object id=\"1\">" + TRIANGLE_MESH + "</object></resources>"
                + "<build><item objectid=\"1\" transform=\"-1 0 0 0 1 0 0 0 1 0 0 0\"/></build>" + MODEL_END));
        // The corners are reversed, so that the mirrored triangle still faces outward.
        assertPositions(model, 0, new float[] {0, 0, 0, 0, 1, 0, -1, 0, 0});
        assertNormal(model, 0, 0, 0, 1);
    }

    @Test
    public void testColors() throws Exception {
        ThreeMfModel model = load(Map.of("3D/3dmodel.model", MODEL_START + """
                <resources>
                  <basematerials id="5">
                    <base name="red" displaycolor="#FF0000"/>
                    <base name="green" displaycolor="#00FF0080"/>
                  </basematerials>
                  <m:colorgroup id="6">
                    <m:color color="#0000FFFF"/>
                    <m:color color="#FFFFFF"/>
                  </m:colorgroup>
                  <object id="1" pid="5" pindex="1">
                    <mesh>
                      <vertices>
                        <vertex x="0" y="0" z="0"/>
                        <vertex x="1" y="0" z="0"/>
                        <vertex x="0" y="1" z="0"/>
                      </vertices>
                      <triangles>
                        <triangle v1="0" v2="1" v3="2"/>
                        <triangle v1="0" v2="1" v3="2" pid="5" p1="0"/>
                        <triangle v1="0" v2="1" v3="2" pid="6" p1="0" p2="1" p3="0"/>
                      </triangles>
                    </mesh>
                  </object>
                  <object id="2">""" + TRIANGLE_MESH + """
                  </object>
                </resources>
                <build>
                  <item objectid="1"/>
                  <item objectid="2"/>
                </build>
                """ + MODEL_END));
        assertEquals(12, model.getVertexCount());
        assertTrue(model.getHasMaterials());
        float[] green = {0, 1, 0, 128 / 255f};
        float[] red = {1, 0, 0, 1};
        float[] blue = {0, 0, 1, 1};
        float[] white = {1, 1, 1, 1};
        float[] gray = {0.8f, 0.8f, 0.8f, 1};
        // A triangle without properties has the object's, and a group's color may differ at each corner.
        float[][] expected = {green, green, green, red, red, red, blue, white, blue, gray, gray, gray};
        FloatBuffer colors = model.getColorBuffer();
        for (int v = 0; v < expected.length; v++) {
            for (int c = 0; c < 4; c++) {
                assertEquals("Vertex " + v + " component " + c, expected[v][c], colors.get(v * 4 + c), TOLERANCE);
            }
        }
    }

    @Test
    public void testTextures() throws Exception {
        byte[] texture = {1, 2, 3, 4};
        Map<String, Object> files = new LinkedHashMap<>();
        files.put("3D/3dmodel.model", MODEL_START + """
                <resources>
                  <m:texture2d id="7" path="/3D/Textures/Tex.png" contenttype="image/png"/>
                  <m:texture2dgroup id="8" texid="7">
                    <m:tex2coord u="0.25" v="0.5"/>
                    <m:tex2coord u="1" v="0"/>
                    <m:tex2coord u="0" v="1"/>
                  </m:texture2dgroup>
                  <object id="1">
                    <mesh>
                      <vertices>
                        <vertex x="0" y="0" z="0"/>
                        <vertex x="1" y="0" z="0"/>
                        <vertex x="0" y="1" z="0"/>
                      </vertices>
                      <triangles>
                        <triangle v1="0" v2="1" v3="2" pid="8" p1="0" p2="1" p3="2"/>
                        <triangle v1="0" v2="1" v3="2"/>
                      </triangles>
                    </mesh>
                  </object>
                </resources>
                <build><item objectid="1"/></build>
                """ + MODEL_END);
        // Part names are case-insensitive, and the texture may come after the model.
        files.put("3D/Textures/tex.png", texture);
        ThreeMfModel model = load(files);

        // The triangle without a texture comes first.
        assertEquals(2, model.getParts().size());
        MeshPart untextured = model.getParts().get(0);
        MeshPart textured = model.getParts().get(1);
        assertNull(untextured.getMaterial().getDiffuseTexture());
        assertEquals(0, untextured.getFirst());
        assertEquals(3, untextured.getCount());
        assertArrayEquals(texture, textured.getMaterial().getDiffuseTexture());
        assertEquals(3, textured.getFirst());
        assertEquals(3, textured.getCount());

        float[] expected = {0, 0, 0, 0, 0, 0, 0.25f, 0.5f, 1, 0, 0, 1};
        FloatBuffer texCoords = model.getTexCoordBuffer();
        for (int i = 0; i < expected.length; i++) {
            assertEquals("Coordinate " + i, expected[i], texCoords.get(i), TOLERANCE);
        }
        // Without any colors, there are no vertex colors.
        assertNull(model.getColorBuffer());
    }

    @Test
    public void testStoredEntriesWithDataDescriptors() throws Exception {
        // Like the archives from Bambu Studio, whose uncompressed entries give their sizes only after their data.
        Map<String, byte[]> files = new LinkedHashMap<>();
        files.put("Metadata/thumbnail.png", new byte[300]);
        files.put("3D/3dmodel.model", (MODEL_START + "<resources><object id=\"1\">" + TRIANGLE_MESH + "</object></resources>"
                + "<build><item objectid=\"1\"/></build>" + MODEL_END).getBytes(StandardCharsets.UTF_8));
        ThreeMfModel model = new ThreeMfModel(new ByteArrayInputStream(storedZipWithDataDescriptors(files)));
        assertEquals(3, model.getVertexCount());
        assertPositions(model, 0, new float[] {0, 0, 0, 1, 0, 0, 0, 1, 0});
    }

    @Test
    public void testInvalidFiles() {
        assertThrows(IOException.class, () -> new ThreeMfModel(new ByteArrayInputStream("solid cube\nendsolid".getBytes())));
        assertThrows(IOException.class, () -> load(Map.of("Metadata/thumbnail.png", new byte[10])));
        assertThrows(IOException.class, () -> load(Map.of("3D/3dmodel.model", MODEL_START + "<resources>"
                + "<object id=\"1\"><mesh><vertices><vertex x=\"0\" y=\"0\" z=\"0\"/></vertices>"
                + "<triangles><triangle v1=\"0\" v2=\"1\" v3=\"2\"/></triangles></mesh></object>"
                + "</resources><build><item objectid=\"1\"/></build>" + MODEL_END)));
        assertThrows(IOException.class, () -> load(Map.of("3D/3dmodel.model", MODEL_START
                + "<resources/><build><item objectid=\"9\"/></build>" + MODEL_END)));
        assertThrows(IOException.class, () -> load(Map.of("3D/3dmodel.model", MODEL_START
                + "<resources><object id=\"1\"><mesh><vertices><vertex x=\"zero\" y=\"0\" z=\"0\"/></vertices>"
                + "</mesh></object></resources>" + MODEL_END)));
    }

    private static ThreeMfModel load(Map<String, ?> files) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(bytes)) {
            for (Map.Entry<String, ?> file : files.entrySet()) {
                zip.putNextEntry(new ZipEntry(file.getKey()));
                Object content = file.getValue();
                zip.write(content instanceof byte[] ? (byte[]) content : ((String) content).getBytes(StandardCharsets.UTF_8));
                zip.closeEntry();
            }
        }
        return new ThreeMfModel(new ByteArrayInputStream(bytes.toByteArray()));
    }

    /**
     * Writes a ZIP archive with uncompressed entries whose local headers have no sizes or CRCs,
     * which are given in data descriptors after the data instead (and in the central directory).
     */
    private static byte[] storedZipWithDataDescriptors(Map<String, byte[]> files) {
        ByteBuffer out = ByteBuffer.allocate(0x100000).order(ByteOrder.LITTLE_ENDIAN);
        ByteBuffer directory = ByteBuffer.allocate(0x10000).order(ByteOrder.LITTLE_ENDIAN);
        for (Map.Entry<String, byte[]> file : files.entrySet()) {
            byte[] name = file.getKey().getBytes(StandardCharsets.UTF_8);
            byte[] data = file.getValue();
            CRC32 crc = new CRC32();
            crc.update(data);
            int offset = out.position();
            // Local header: version, flags (data descriptor, UTF-8), stored, time, date, and no CRC or sizes.
            out.putInt(0x04034b50).putShort((short) 20).putShort((short) 0x0808).putShort((short) 0).putInt(0)
                    .putInt(0).putInt(0).putInt(0).putShort((short) name.length).putShort((short) 0).put(name).put(data);
            out.putInt(0x08074b50).putInt((int) crc.getValue()).putInt(data.length).putInt(data.length);
            directory.putInt(0x02014b50).putShort((short) 20).putShort((short) 20).putShort((short) 0x0808).putShort((short) 0)
                    .putInt(0).putInt((int) crc.getValue()).putInt(data.length).putInt(data.length).putShort((short) name.length)
                    .putShort((short) 0).putShort((short) 0).putShort((short) 0).putShort((short) 0).putInt(0).putInt(offset).put(name);
        }
        int directoryOffset = out.position();
        out.put(directory.array(), 0, directory.position());
        out.putInt(0x06054b50).putShort((short) 0).putShort((short) 0).putShort((short) files.size()).putShort((short) files.size())
                .putInt(directory.position()).putInt(directoryOffset).putShort((short) 0);
        return java.util.Arrays.copyOf(out.array(), out.position());
    }

    private static void assertPositions(ThreeMfModel model, int firstVertex, float[] expected) {
        FloatBuffer vertices = model.getVertexBuffer();
        for (int i = 0; i < expected.length; i++) {
            assertEquals("Coordinate " + i, expected[i], vertices.get(firstVertex * 3 + i), TOLERANCE);
        }
    }

    private static void assertNormal(ThreeMfModel model, int vertex, float x, float y, float z) {
        FloatBuffer normals = model.getNormalBuffer();
        assertEquals(x, normals.get(vertex * 3), TOLERANCE);
        assertEquals(y, normals.get(vertex * 3 + 1), TOLERANCE);
        assertEquals(z, normals.get(vertex * 3 + 2), TOLERANCE);
    }
}
