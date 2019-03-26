package com.dmitrybrant.modelviewer;

import android.opengl.GLES20;
import android.opengl.Matrix;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.dmitrybrant.modelviewer.util.Util;

import java.nio.FloatBuffer;

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
public class ArrayModel extends Model {
    protected static final int BYTES_PER_FLOAT = 4;
    protected static final int COORDS_PER_VERTEX = 3;
    protected static final int VERTEX_STRIDE = COORDS_PER_VERTEX * BYTES_PER_FLOAT;
    protected static final int INPUT_BUFFER_SIZE = 0x10000;

    // Vertices, normals will be populated by subclasses
    protected int vertexCount;
    @Nullable protected FloatBuffer vertexBuffer;
    @Nullable protected FloatBuffer normalBuffer;

    @Override
    public void init(float boundSize) {
        if (GLES20.glIsProgram(glProgram)) {
            GLES20.glDeleteProgram(glProgram);
            glProgram = -1;
        }
        glProgram = Util.compileProgram(R.raw.model_vertex, R.raw.single_light_fragment,
                new String[] {"a_Position", "a_Normal"});
        super.init(boundSize);
    }

    public int getVertexCount() {
        return vertexCount;
    }

    @Override
    public void draw(float[] viewMatrix, float[] projectionMatrix, @NonNull Light light) {
        if (vertexBuffer == null || normalBuffer == null) {
            return;
        }
        GLES20.glUseProgram(glProgram);

        int mvpMatrixHandle = GLES20.glGetUniformLocation(glProgram, "u_MVP");
        int positionHandle = GLES20.glGetAttribLocation(glProgram, "a_Position");
        int normalHandle = GLES20.glGetAttribLocation(glProgram, "a_Normal");
        int lightPosHandle = GLES20.glGetUniformLocation(glProgram, "u_LightPos");
        int ambientColorHandle = GLES20.glGetUniformLocation(glProgram, "u_ambientColor");
        int diffuseColorHandle = GLES20.glGetUniformLocation(glProgram, "u_diffuseColor");
        int specularColorHandle = GLES20.glGetUniformLocation(glProgram, "u_specularColor");

        GLES20.glEnableVertexAttribArray(positionHandle);
        GLES20.glVertexAttribPointer(positionHandle, COORDS_PER_VERTEX, GLES20.GL_FLOAT, false,
                VERTEX_STRIDE, vertexBuffer);

        GLES20.glEnableVertexAttribArray(normalHandle);
        GLES20.glVertexAttribPointer(normalHandle, COORDS_PER_VERTEX, GLES20.GL_FLOAT, false,
                VERTEX_STRIDE, normalBuffer);

        Matrix.multiplyMM(mvMatrix, 0, viewMatrix, 0, modelMatrix, 0);
        Matrix.multiplyMM(mvpMatrix, 0, projectionMatrix, 0, mvMatrix, 0);
        GLES20.glUniformMatrix4fv(mvpMatrixHandle, 1, false, mvpMatrix, 0);

        GLES20.glUniform3fv(lightPosHandle, 1, light.getPositionInEyeSpace(), 0);
        GLES20.glUniform3fv(ambientColorHandle, 1, light.getAmbientColor(), 0);
        GLES20.glUniform3fv(diffuseColorHandle, 1, light.getDiffuseColor(), 0);
        GLES20.glUniform3fv(specularColorHandle, 1, light.getSpecularColor(), 0);

        drawFunc();

        GLES20.glDisableVertexAttribArray(normalHandle);
        GLES20.glDisableVertexAttribArray(positionHandle);
    }

    protected void drawFunc() {
        GLES20.glDrawArrays(GLES20.GL_TRIANGLES, 0, vertexCount);
    }
}
