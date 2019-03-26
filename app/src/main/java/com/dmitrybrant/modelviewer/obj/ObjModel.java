package com.dmitrybrant.modelviewer.obj;

import androidx.annotation.NonNull;

import com.dmitrybrant.modelviewer.IndexedModel;
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

/*
 *
 * Info on the Wavefront OBJ format: https://en.wikipedia.org/wiki/Wavefront_.obj_file
 * This is NOT a complete implementation of the OBJ format. It does not support textures,
 * only supports faces of 3 or 4 vertices, and combines all sub-objects into a single model.
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
public class ObjModel extends IndexedModel {

    public ObjModel(@NonNull InputStream inputStream) throws IOException {
        super();
        BufferedInputStream stream = new BufferedInputStream(inputStream, INPUT_BUFFER_SIZE);
        readText(stream);
        if (vertexCount <= 0 || vertexBuffer == null || normalBuffer == null
                || indexCount <= 0 || indexBuffer == null) {
            throw new IOException("Invalid model.");
        }
    }

    @Override
    public void initModelMatrix(float boundSize) {
        final float yRotation = 180f;
        initModelMatrix(boundSize, 0.0f, yRotation, 0.0f);
        float scale = getBoundScale(boundSize);
        if (scale == 0.0f) { scale = 1.0f; }
        floorOffset = (minY - centerMassY) / scale;
    }

    private void readText(@NonNull InputStream stream) throws IOException {
        List<Float> normalBucket = new ArrayList<>();
        List<Float> vertices = new ArrayList<>();
        List<Integer> indices = new ArrayList<>();
        List<Integer> normalIndices = new ArrayList<>();
        BufferedReader reader = new BufferedReader(new InputStreamReader(stream), INPUT_BUFFER_SIZE);
        String line;
        String[] lineArr;
        int[][] intArr = new int[4][];
        int index1, index2, index3, index4;
        float[] customNormal = new float[3];
        float x, y, z;
        double centerMassX = 0.0;
        double centerMassY = 0.0;
        double centerMassZ = 0.0;
        for (int i = 0; i < intArr.length; i++) {
            intArr[i] = new int[8];
        }

        while ((line = reader.readLine()) != null) {
            line = line.trim();
            lineArr = line.split("\\s+");

            if (lineArr.length > 3 && lineArr[0].equals("v")) {
                x = Float.parseFloat(lineArr[1]);
                y = Float.parseFloat(lineArr[2]);
                z = Float.parseFloat(lineArr[3]);
                adjustMaxMin(x, y, z);
                vertices.add(x);
                vertices.add(y);
                vertices.add(z);
                centerMassX += x;
                centerMassY += y;
                centerMassZ += z;

            } else if (lineArr.length > 3 && lineArr[0].equals("vn")) {
                x = Float.parseFloat(lineArr[1]);
                y = Float.parseFloat(lineArr[2]);
                z = Float.parseFloat(lineArr[3]);
                normalBucket.add(x);
                normalBucket.add(y);
                normalBucket.add(z);

            } else if (lineArr.length > 3 && lineArr[0].equals("f")) {
                if (lineArr.length == 4) {
                    // it's a triangle
                    parseInts(lineArr[1], intArr[0]);
                    parseInts(lineArr[2], intArr[1]);
                    parseInts(lineArr[3], intArr[2]);

                    index1 = intArr[0][0] - 1;
                    index2 = intArr[1][0] - 1;
                    index3 = intArr[2][0] - 1;
                    indices.add(index1);
                    indices.add(index2);
                    indices.add(index3);

                    if (intArr[0][2] != -1) {
                        normalIndices.add(intArr[0][2] - 1);
                        normalIndices.add(intArr[1][2] - 1);
                        normalIndices.add(intArr[2][2] - 1);
                    } else {
                        Util.calculateNormal(vertices.get(index1 * 3), vertices.get(index1 * 3 + 1), vertices.get(index1 * 3 + 2),
                                vertices.get(index2 * 3), vertices.get(index2 * 3 + 1), vertices.get(index2 * 3 + 2),
                                vertices.get(index3 * 3), vertices.get(index3 * 3 + 1), vertices.get(index3 * 3 + 2),
                                customNormal);
                        normalBucket.add(customNormal[0]);
                        normalBucket.add(customNormal[1]);
                        normalBucket.add(customNormal[2]);
                        normalIndices.add((normalBucket.size() - 1) / 3);
                        normalIndices.add((normalBucket.size() - 1) / 3);
                        normalIndices.add((normalBucket.size() - 1) / 3);
                    }

                } else if (lineArr.length == 5) {
                    // it's a quad
                    parseInts(lineArr[1], intArr[0]);
                    parseInts(lineArr[2], intArr[1]);
                    parseInts(lineArr[3], intArr[2]);
                    parseInts(lineArr[4], intArr[3]);

                    index1 = intArr[0][0] - 1;
                    index2 = intArr[1][0] - 1;
                    index3 = intArr[2][0] - 1;
                    index4 = intArr[3][0] - 1;
                    indices.add(index1);
                    indices.add(index2);
                    indices.add(index3);
                    indices.add(index1);
                    indices.add(index3);
                    indices.add(index4);

                    if (intArr[0][2] != -1) {
                        normalIndices.add(intArr[0][2] - 1);
                        normalIndices.add(intArr[1][2] - 1);
                        normalIndices.add(intArr[2][2] - 1);
                        normalIndices.add(intArr[0][2] - 1);
                        normalIndices.add(intArr[2][2] - 1);
                        normalIndices.add(intArr[3][2] - 1);
                    } else {
                        Util.calculateNormal(vertices.get(index1 * 3), vertices.get(index1 * 3 + 1), vertices.get(index1 * 3 + 2),
                                vertices.get(index2 * 3), vertices.get(index2 * 3 + 1), vertices.get(index2 * 3 + 2),
                                vertices.get(index3 * 3), vertices.get(index3 * 3 + 1), vertices.get(index3 * 3 + 2),
                                customNormal);
                        normalBucket.add(customNormal[0]);
                        normalBucket.add(customNormal[1]);
                        normalBucket.add(customNormal[2]);
                        normalIndices.add((normalBucket.size() - 1) / 3);
                        normalIndices.add((normalBucket.size() - 1) / 3);
                        normalIndices.add((normalBucket.size() - 1) / 3);
                        Util.calculateNormal(vertices.get(index1 * 3), vertices.get(index1 * 3 + 1), vertices.get(index1 * 3 + 2),
                                vertices.get(index3 * 3), vertices.get(index3 * 3 + 1), vertices.get(index3 * 3 + 2),
                                vertices.get(index4 * 3), vertices.get(index4 * 3 + 1), vertices.get(index4 * 3 + 2),
                                customNormal);
                        normalBucket.add(customNormal[0]);
                        normalBucket.add(customNormal[1]);
                        normalBucket.add(customNormal[2]);
                        normalIndices.add((normalBucket.size() - 1) / 3);
                        normalIndices.add((normalBucket.size() - 1) / 3);
                        normalIndices.add((normalBucket.size() - 1) / 3);
                    }
                }
            }
            // TODO: Support texture coordinates ("vt")

        }

        vertexCount = vertices.size() / 3;

        this.centerMassX = (float)(centerMassX / vertexCount);
        this.centerMassY = (float)(centerMassY / vertexCount);
        this.centerMassZ = (float)(centerMassZ / vertexCount);

        float[] floatArray = new float[vertices.size()];
        for (int i = 0; i < vertices.size(); i++) {
            floatArray[i] = vertices.get(i);
        }
        ByteBuffer vbb = ByteBuffer.allocateDirect(floatArray.length * BYTES_PER_FLOAT);
        vbb.order(ByteOrder.nativeOrder());
        vertexBuffer = vbb.asFloatBuffer();
        vertexBuffer.put(floatArray);
        vertexBuffer.position(0);

        indexCount = indices.size();
        int[] intArray = new int[indexCount];
        for (int i = 0; i < indexCount; i++) {
            intArray[i] = indices.get(i);
        }
        vbb = ByteBuffer.allocateDirect(indexCount * BYTES_PER_INT);
        vbb.order(ByteOrder.nativeOrder());
        indexBuffer = vbb.asIntBuffer();
        indexBuffer.put(intArray);
        indexBuffer.position(0);

        float[] normalArray = new float[vertices.size()];
        int vi, ni;
        for (int i = 0; i < indexCount; i++) {
            vi = indices.get(i);
            ni = normalIndices.get(i);
            normalArray[vi * 3] = normalBucket.get(ni * 3);
            normalArray[vi * 3 + 1] = normalBucket.get(ni * 3 + 1);
            normalArray[vi * 3 + 2] = normalBucket.get(ni * 3 + 2);
        }

        vbb = ByteBuffer.allocateDirect(normalArray.length * BYTES_PER_FLOAT);
        vbb.order(ByteOrder.nativeOrder());
        normalBuffer = vbb.asFloatBuffer();
        normalBuffer.put(normalArray);
        normalBuffer.position(0);
    }

    // This is a method that takes a string and parses any integers out of it (in place, without
    // using any additional string splitting, regexes, or int parsing), which provides a pretty
    // significant speed gain.
    // - The first three output integers are pre-initialized to -1.
    // - The integers in the string are expected to be delimited by a single non-numeric character.
    //   If a non-numeric character follows another non-numeric character, then an integer value
    //   of -1 will be added to the output array.
    protected static void parseInts(String str, int[] ints) {
        int len = str.length();
        int intIndex = 0;
        int currentInt = -1;
        ints[0] = -1;
        ints[1] = -1;
        ints[2] = -1;

        for (int i = 0; i < len; i++) {
            char c = str.charAt(i);
            if (c >= '0' && c <= '9') {
                if (currentInt == -1) {
                    currentInt = (c - '0');
                } else {
                    currentInt *= 10;
                    currentInt += (c - '0');
                }
            } else {
                if (currentInt >= 0) {
                    ints[intIndex++] = currentInt;
                    currentInt = -1;
                } else {
                    ints[intIndex++] = -1;
                }
            }
        }
        if (currentInt >= 0) {
            ints[intIndex] = currentInt;
        }
    }
}
