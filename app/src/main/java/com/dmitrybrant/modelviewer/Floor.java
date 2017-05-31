package com.dmitrybrant.modelviewer;

import android.opengl.GLES20;
import android.opengl.Matrix;
import android.support.annotation.NonNull;

import com.dmitrybrant.modelviewer.util.Util;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/*
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
public class Floor extends ArrayModel {

    private float[] floorColor = new float[] {0.2f, 0.2f, 0.2f, 0.5f};
    private float[] lineColor = new float[] {0.6f, 0.6f, 0.6f, 0.5f};
    private float extent;

    @Override
    public void init(float boundSize) {
        extent = boundSize * 5.0f;

        // The grid lines on the floor are rendered procedurally and large polygons cause floating point
        // precision problems on some architectures. So we split the floor into 4 quadrants.
        final float[] coords = new float[] {
                // +X, +Z quadrant
                extent, 0, 0,
                0, 0, 0,
                0, 0, extent,
                extent, 0, 0,
                0, 0, extent,
                extent, 0, extent,

                // -X, +Z quadrant
                0, 0, 0,
                -extent, 0, 0,
                -extent, 0, extent,
                0, 0, 0,
                -extent, 0, extent,
                0, 0, extent,

                // +X, -Z quadrant
                extent, 0, -extent,
                0, 0, -extent,
                0, 0, 0,
                extent, 0, -extent,
                0, 0, 0,
                extent, 0, 0,

                // -X, -Z quadrant
                0, 0, -extent,
                -extent, 0, -extent,
                -extent, 0, 0,
                0, 0, -extent,
                -extent, 0, 0,
                0, 0, 0,
        };

        final float normals[] = new float[coords.length];
        for (int i = 0; i < normals.length; i += 3) {
            normals[i] = 0.0f;
            normals[i + 1] = 1.0f;
            normals[i + 2] = 0.0f;
        }

        minX = -extent;
        maxX = extent;
        minY = -extent;
        maxY = extent;
        minZ = -extent;
        maxZ = extent;

        vertexCount = coords.length / COORDS_PER_VERTEX;

        ByteBuffer vbb = ByteBuffer.allocateDirect(coords.length * BYTES_PER_FLOAT);
        vbb.order(ByteOrder.nativeOrder());
        vertexBuffer = vbb.asFloatBuffer();
        vertexBuffer.put(coords);
        vertexBuffer.position(0);

        vbb = ByteBuffer.allocateDirect(normals.length * BYTES_PER_FLOAT);
        vbb.order(ByteOrder.nativeOrder());
        normalBuffer = vbb.asFloatBuffer();
        normalBuffer.put(normals);
        normalBuffer.position(0);

        if (GLES20.glIsProgram(glProgram)) {
            GLES20.glDeleteProgram(glProgram);
            glProgram = -1;
        }
        glProgram = Util.compileProgram(R.raw.floor_vertex, R.raw.floor_fragment,
                new String[] {"a_Position", "a_Normal"});

        Matrix.setIdentityM(modelMatrix, 0);
    }

    public void setOffsetY(float y) {
        Matrix.translateM(modelMatrix, 0, 0.0f, y, 0.0f);
    }

    @Override
    public void draw(float[] viewMatrix, float[] projectionMatrix, @NonNull Light light) {
        if (vertexBuffer == null || normalBuffer == null) {
            return;
        }
        GLES20.glUseProgram(glProgram);

        int modelMatrixHandle = GLES20.glGetUniformLocation(glProgram, "u_Model");
        int mvpMatrixHandle = GLES20.glGetUniformLocation(glProgram, "u_MVP");
        int positionHandle = GLES20.glGetAttribLocation(glProgram, "a_Position");
        int normalHandle = GLES20.glGetAttribLocation(glProgram, "a_Normal");
        int floorColorHandle = GLES20.glGetUniformLocation(glProgram, "u_FloorColor");
        int lineColorHandle = GLES20.glGetUniformLocation(glProgram, "u_LineColor");
        int maxDepthHandle = GLES20.glGetUniformLocation(glProgram, "u_MaxDepth");
        int gridUnitHandle = GLES20.glGetUniformLocation(glProgram, "u_GridUnit");

        GLES20.glEnableVertexAttribArray(positionHandle);
        GLES20.glVertexAttribPointer(positionHandle, COORDS_PER_VERTEX, GLES20.GL_FLOAT, false,
                VERTEX_STRIDE, vertexBuffer);

        GLES20.glEnableVertexAttribArray(normalHandle);
        GLES20.glVertexAttribPointer(normalHandle, COORDS_PER_VERTEX, GLES20.GL_FLOAT, false,
                VERTEX_STRIDE, normalBuffer);

        GLES20.glUniformMatrix4fv(modelMatrixHandle, 1, false, modelMatrix, 0);

        Matrix.multiplyMM(mvMatrix, 0, viewMatrix, 0, modelMatrix, 0);
        Matrix.multiplyMM(mvpMatrix, 0, projectionMatrix, 0, mvMatrix, 0);
        GLES20.glUniformMatrix4fv(mvpMatrixHandle, 1, false, mvpMatrix, 0);

        GLES20.glUniform4fv(floorColorHandle, 1, floorColor, 0);
        GLES20.glUniform4fv(lineColorHandle, 1, lineColor, 0);
        GLES20.glUniform1f(maxDepthHandle, extent);
        GLES20.glUniform1f(gridUnitHandle, extent / 75.0f);

        GLES20.glDrawArrays(GLES20.GL_TRIANGLES, 0, vertexCount);

        GLES20.glDisableVertexAttribArray(normalHandle);
        GLES20.glDisableVertexAttribArray(positionHandle);
    }
}
