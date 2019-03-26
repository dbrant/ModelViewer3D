package com.dmitrybrant.modelviewer.ply;

import android.opengl.GLES20;
import android.opengl.Matrix;
import androidx.annotation.NonNull;

import com.dmitrybrant.modelviewer.IndexedModel;
import com.dmitrybrant.modelviewer.Light;
import com.dmitrybrant.modelviewer.R;
import com.dmitrybrant.modelviewer.util.Util;

import java.io.BufferedInputStream;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.List;

import static com.dmitrybrant.modelviewer.util.Util.readIntLe;

/*
 *
 * Info on the PLY format: https://en.wikipedia.org/wiki/PLY_(file_format)
 * Please see limitations in inline comments.
 *
 * Copyright 2017 Dmitry Brant. All rights reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
public class PlyModel extends IndexedModel {

    private final float[] pointColor = new float[] { 1.0f, 1.0f, 1.0f };

    public PlyModel(@NonNull InputStream inputStream) throws IOException {
        super();
        BufferedInputStream stream = new BufferedInputStream(inputStream, INPUT_BUFFER_SIZE);
        readText(stream);
        if (vertexCount <= 0 || vertexBuffer == null) {
            throw new IOException("Invalid model.");
        }
    }

    @Override
    public void init(float boundSize) {
        if (GLES20.glIsProgram(glProgram)) {
            GLES20.glDeleteProgram(glProgram);
            glProgram = -1;
        }
        glProgram = Util.compileProgram(R.raw.point_cloud_vertex, R.raw.single_color_fragment,
                new String[] {"a_Position"});
        initModelMatrix(boundSize);
    }

    @Override
    public void initModelMatrix(float boundSize) {
        final float yRotation = 180f;
        initModelMatrix(boundSize, 0.0f, yRotation, 0.0f);
        float scale = getBoundScale(boundSize);
        if (scale == 0.0f) { scale = 1.0f; }
        floorOffset = (minY - centerMassY) / scale;
    }

    private void readText(@NonNull BufferedInputStream stream) throws IOException {
        List<Float> vertices = new ArrayList<>();

        BufferedReader reader = new BufferedReader(new InputStreamReader(stream), INPUT_BUFFER_SIZE);
        String line;
        String[] lineArr;

        /*
        TODO:
        This is currently pretty limited. We expect the header to contain a line of
        "element vertex nnn", and the list of vertices to follow immediately after the
        header, and each vertex to have the format "x, y, z, ...".
        */

        stream.mark(0x100000);
        boolean isBinary = false;
        while ((line = reader.readLine()) != null) {
            line = line.trim();
            if (line.startsWith("format ")) {
                if (line.contains("binary")) {
                    isBinary = true;
                }
            } else if (line.startsWith("element vertex")) {
                lineArr = line.split(" ");
                vertexCount = Integer.parseInt(lineArr[2]);
            } else if (line.startsWith("end_header")) {
                break;
            }
        }

        if (vertexCount <= 0) {
            return;
        }

        if (isBinary) {
            stream.reset();
            readVerticesBinary(vertices, stream);
        } else {
            readVerticesText(vertices, reader);
        }

        float[] floatArray = new float[vertices.size()];
        for (int i = 0; i < vertices.size(); i++) {
            floatArray[i] = vertices.get(i);
        }
        ByteBuffer vbb = ByteBuffer.allocateDirect(floatArray.length * BYTES_PER_FLOAT);
        vbb.order(ByteOrder.nativeOrder());
        vertexBuffer = vbb.asFloatBuffer();
        vertexBuffer.put(floatArray);
        vertexBuffer.position(0);
    }

    private void readVerticesText(List<Float> vertices, BufferedReader reader) throws IOException {
        String[] lineArr;
        float x, y, z;

        double centerMassX = 0.0;
        double centerMassY = 0.0;
        double centerMassZ = 0.0;

        for (int i = 0; i < vertexCount; i++) {
            lineArr = reader.readLine().trim().split(" ");
            x = Float.parseFloat(lineArr[0]);
            y = Float.parseFloat(lineArr[1]);
            z = Float.parseFloat(lineArr[2]);
            vertices.add(x);
            vertices.add(y);
            vertices.add(z);

            adjustMaxMin(x, y, z);
            centerMassX += x;
            centerMassY += y;
            centerMassZ += z;
        }

        this.centerMassX = (float)(centerMassX / vertexCount);
        this.centerMassY = (float)(centerMassY / vertexCount);
        this.centerMassZ = (float)(centerMassZ / vertexCount);
    }

    private void readVerticesBinary(List<Float> vertices, BufferedInputStream stream) throws IOException {
        byte[] tempBytes = new byte[0x1000];
        stream.mark(1);
        stream.read(tempBytes);
        String tempStr = new String(tempBytes);
        int contentsPos = tempStr.indexOf("end_header") + 11;
        stream.reset();
        stream.skip(contentsPos);

        float x, y, z;

        double centerMassX = 0.0;
        double centerMassY = 0.0;
        double centerMassZ = 0.0;

        for (int i = 0; i < vertexCount; i++) {
            stream.read(tempBytes, 0, BYTES_PER_FLOAT * 3);
            x = Float.intBitsToFloat(readIntLe(tempBytes, 0));
            y = Float.intBitsToFloat(readIntLe(tempBytes, BYTES_PER_FLOAT));
            z = Float.intBitsToFloat(readIntLe(tempBytes, BYTES_PER_FLOAT * 2));
            vertices.add(x);
            vertices.add(y);
            vertices.add(z);

            adjustMaxMin(x, y, z);
            centerMassX += x;
            centerMassY += y;
            centerMassZ += z;
        }

        this.centerMassX = (float)(centerMassX / vertexCount);
        this.centerMassY = (float)(centerMassY / vertexCount);
        this.centerMassZ = (float)(centerMassZ / vertexCount);
    }

    @Override
    public void draw(float[] viewMatrix, float[] projectionMatrix, @NonNull Light light) {
        if (vertexBuffer == null) {
            return;
        }
        GLES20.glUseProgram(glProgram);

        int mvpMatrixHandle = GLES20.glGetUniformLocation(glProgram, "u_MVP");
        int positionHandle = GLES20.glGetAttribLocation(glProgram, "a_Position");
        int pointThicknessHandle = GLES20.glGetUniformLocation(glProgram, "u_PointThickness");
        int ambientColorHandle = GLES20.glGetUniformLocation(glProgram, "u_ambientColor");

        GLES20.glEnableVertexAttribArray(positionHandle);
        GLES20.glVertexAttribPointer(positionHandle, COORDS_PER_VERTEX, GLES20.GL_FLOAT, false,
                VERTEX_STRIDE, vertexBuffer);

        Matrix.multiplyMM(mvMatrix, 0, viewMatrix, 0, modelMatrix, 0);
        Matrix.multiplyMM(mvpMatrix, 0, projectionMatrix, 0, mvMatrix, 0);
        GLES20.glUniformMatrix4fv(mvpMatrixHandle, 1, false, mvpMatrix, 0);

        GLES20.glUniform1f(pointThicknessHandle, 3.0f);
        GLES20.glUniform3fv(ambientColorHandle, 1, pointColor, 0);

        GLES20.glDrawArrays(GLES20.GL_POINTS, 0, vertexCount);

        GLES20.glDisableVertexAttribArray(positionHandle);
    }
}
