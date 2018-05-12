package com.dmitrybrant.modelviewer.stl;

import com.dmitrybrant.modelviewer.ArrayModel;

import org.junit.Test;

import java.io.FileInputStream;
import java.io.InputStream;

import static org.junit.Assert.*;

public class StlModelTest {
    private static final String RAW_DIR = "src/test/res/raw/";

    @Test
    public void testLoadBinaryModel() throws Exception {
        InputStream stream = new FileInputStream(RAW_DIR + "cube1.stl");
        ArrayModel model = new StlModel(stream);
        assertEquals(model.getVertexCount(), 153846);
        stream.close();

        stream = new FileInputStream(RAW_DIR + "bunny.stl");
        model = new StlModel(stream);
        assertEquals(model.getVertexCount(), 20898);
        stream.close();
    }

    @Test
    public void testLoadAsciiModel() throws Exception {
        InputStream stream = new FileInputStream(RAW_DIR + "sample.stl");
        ArrayModel model = new StlModel(stream);
        assertEquals(model.getVertexCount(), 24048);
        stream.close();

        stream = new FileInputStream(RAW_DIR + "bottle.stl");
        model = new StlModel(stream);
        assertEquals(model.getVertexCount(), 3720);
        stream.close();
    }
}