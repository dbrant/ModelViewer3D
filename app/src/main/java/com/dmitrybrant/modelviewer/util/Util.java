package com.dmitrybrant.modelviewer.util;

import android.opengl.GLES20;
import android.support.annotation.NonNull;
import android.support.annotation.Nullable;
import android.support.annotation.RawRes;
import android.util.Log;

import com.dmitrybrant.modelviewer.ModelViewerApplication;

import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;

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
public final class Util {

    public static int compileProgram(@RawRes int vertexShader, @RawRes int fragmentShader,
                                     @NonNull String[] attributes) {
        int program = GLES20.glCreateProgram();
        GLES20.glAttachShader(program, loadShader(GLES20.GL_VERTEX_SHADER,
                readTextFileFromRawRes(vertexShader)));
        GLES20.glAttachShader(program, loadShader(GLES20.GL_FRAGMENT_SHADER,
                readTextFileFromRawRes(fragmentShader)));
        for (int i = 0; i < attributes.length; i++) {
            GLES20.glBindAttribLocation(program, i, attributes[i]);
        }
        GLES20.glLinkProgram(program);
        return program;
    }

    public static void checkGLError(@NonNull String label) {
        int error;
        if ((error = GLES20.glGetError()) != GLES20.GL_NO_ERROR) {
            throw new RuntimeException(label + ": glError " + error);
        }
    }

    public static void closeSilently(@Nullable Closeable c) {
        try {
            if (c != null) {
                c.close();
            }
        } catch(Exception e) {
            e.printStackTrace();
        }
    }

    public static int readIntLe(byte[] bytes, int offset) {
        return (bytes[offset] & 0xff) | (bytes[offset + 1] & 0xff) << 8
                | (bytes[offset + 2] & 0xff) << 16 | (bytes[offset + 3] & 0xff) << 24;
    }

    public static void calculateNormal(float x1, float y1, float z1,
                                 float x2, float y2, float z2,
                                 float x3, float y3, float z3,
                                 float[] normal) {
        normal[0] = (y2 - y1) * (z3 - z1) - (y3 - y1) * (z2 - z1);
        normal[1] = (z2 - z1) * (x3 - x1) - (x2 - x1) * (z3 - z1);
        normal[2] = (x2 - x1) * (y3 - y1) - (x3 - x1) * (y2 - y1);
    }

    public static float pxToDp(float px) {
        return px / getDensityScalar();
    }

    private static float getDensityScalar() {
        return ModelViewerApplication.getInstance().getResources().getDisplayMetrics().density;
    }

    private static int loadShader(int type, @NonNull String shaderCode){
        int shader = GLES20.glCreateShader(type);
        GLES20.glShaderSource(shader, shaderCode);
        GLES20.glCompileShader(shader);

        // Get the compilation status.
        final int[] compileStatus = new int[1];
        GLES20.glGetShaderiv(shader, GLES20.GL_COMPILE_STATUS, compileStatus, 0);
        // If the compilation fails, delete the shader.
        if (compileStatus[0] == 0) {
            Log.e("loadShader", "Error compiling shader: " + GLES20.glGetShaderInfoLog(shader));
            GLES20.glDeleteShader(shader);
            throw new RuntimeException("Shader compilation failed.");
        }
        return shader;
    }

    @NonNull
    private static String readTextFileFromRawRes(@RawRes int resourceId) {
        InputStream inputStream = ModelViewerApplication.getInstance().getResources()
                .openRawResource(resourceId);
        try {
            byte[] bytes = new byte[inputStream.available()];
            inputStream.read(bytes);
            return new String(bytes);
        } catch (IOException e) {
            e.printStackTrace();
        } finally {
            closeSilently(inputStream);
        }
        throw new RuntimeException("Failed to read raw resource id " + resourceId);
    }
}
