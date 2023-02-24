package com.dmitrybrant.modelviewer.ply;

import com.dmitrybrant.modelviewer.ArrayModel;

import org.junit.Test;

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
        ArrayModel model = new PlyModel(stream);
        assertEquals(model.getVertexCount(), 855);
        stream.close();

        stream = Files.newInputStream(Paths.get(RAW_DIR + "ellell.ply"));
        model = new PlyModel(stream);
        assertEquals(model.getVertexCount(), 20);
        stream.close();
    }
}