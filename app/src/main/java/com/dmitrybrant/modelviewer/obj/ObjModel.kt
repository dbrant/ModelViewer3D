package com.dmitrybrant.modelviewer.obj

import com.dmitrybrant.modelviewer.IndexedModel
import com.dmitrybrant.modelviewer.util.Util.calculateNormal
import java.io.*
import java.nio.ByteBuffer
import java.nio.ByteOrder

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
class ObjModel(inputStream: InputStream) : IndexedModel() {
    init {
        val stream = BufferedInputStream(inputStream, INPUT_BUFFER_SIZE)
        readText(stream)
        if (vertexCount <= 0 || vertexBuffer == null || normalBuffer == null || indexCount <= 0 || indexBuffer == null) {
            throw IOException("Invalid model.")
        }
    }

    public override fun initModelMatrix(boundSize: Float) {
        val yRotation = 180f
        initModelMatrix(boundSize, 0.0f, yRotation, 0.0f)
        var scale = getBoundScale(boundSize)
        if (scale == 0.0f) {
            scale = 1.0f
        }
        floorOffset = (minY - centerMassY) / scale
    }

    private fun readText(stream: InputStream) {
        val normalBucket = mutableListOf<Float>()
        val vertices = mutableListOf<Float>()
        val indices = mutableListOf<Int>()
        val normalIndices = mutableListOf<Int>()

        val reader = BufferedReader(InputStreamReader(stream), INPUT_BUFFER_SIZE)
        var line: String
        var lineArr: Array<String>
        val intArr = Array(4) { IntArray(8) }
        var index1: Int
        var index2: Int
        var index3: Int
        var index4: Int
        val customNormal = FloatArray(3)

        var x: Float
        var y: Float
        var z: Float
        var centerMassX = 0.0
        var centerMassY = 0.0
        var centerMassZ = 0.0

        while (reader.readLine().also { line = it.orEmpty() } != null) {
            lineArr = line.trim().split("\\s+".toRegex()).toTypedArray()

            if (lineArr.size > 3 && lineArr[0] == "v") {
                x = lineArr[1].toFloat()
                y = lineArr[2].toFloat()
                z = lineArr[3].toFloat()
                adjustMaxMin(x, y, z)
                vertices.add(x)
                vertices.add(y)
                vertices.add(z)
                centerMassX += x.toDouble()
                centerMassY += y.toDouble()
                centerMassZ += z.toDouble()
            } else if (lineArr.size > 3 && lineArr[0] == "vn") {
                x = lineArr[1].toFloat()
                y = lineArr[2].toFloat()
                z = lineArr[3].toFloat()
                normalBucket.add(x)
                normalBucket.add(y)
                normalBucket.add(z)
            } else if (lineArr.size > 3 && lineArr[0] == "f") {
                if (lineArr.size == 4) {
                    // it's a triangle
                    parseInts(lineArr[1], intArr[0])
                    parseInts(lineArr[2], intArr[1])
                    parseInts(lineArr[3], intArr[2])
                    index1 = intArr[0][0] - 1
                    index2 = intArr[1][0] - 1
                    index3 = intArr[2][0] - 1
                    indices.add(index1)
                    indices.add(index2)
                    indices.add(index3)
                    if (intArr[0][2] != -1) {
                        normalIndices.add(intArr[0][2] - 1)
                        normalIndices.add(intArr[1][2] - 1)
                        normalIndices.add(intArr[2][2] - 1)
                    } else {
                        calculateNormal(vertices[index1 * 3], vertices[index1 * 3 + 1], vertices[index1 * 3 + 2],
                                vertices[index2 * 3], vertices[index2 * 3 + 1], vertices[index2 * 3 + 2],
                                vertices[index3 * 3], vertices[index3 * 3 + 1], vertices[index3 * 3 + 2],
                                customNormal)
                        normalBucket.add(customNormal[0])
                        normalBucket.add(customNormal[1])
                        normalBucket.add(customNormal[2])
                        normalIndices.add((normalBucket.size - 1) / 3)
                        normalIndices.add((normalBucket.size - 1) / 3)
                        normalIndices.add((normalBucket.size - 1) / 3)
                    }
                } else if (lineArr.size == 5) {
                    // it's a quad
                    parseInts(lineArr[1], intArr[0])
                    parseInts(lineArr[2], intArr[1])
                    parseInts(lineArr[3], intArr[2])
                    parseInts(lineArr[4], intArr[3])
                    index1 = intArr[0][0] - 1
                    index2 = intArr[1][0] - 1
                    index3 = intArr[2][0] - 1
                    index4 = intArr[3][0] - 1
                    indices.add(index1)
                    indices.add(index2)
                    indices.add(index3)
                    indices.add(index1)
                    indices.add(index3)
                    indices.add(index4)
                    if (intArr[0][2] != -1) {
                        normalIndices.add(intArr[0][2] - 1)
                        normalIndices.add(intArr[1][2] - 1)
                        normalIndices.add(intArr[2][2] - 1)
                        normalIndices.add(intArr[0][2] - 1)
                        normalIndices.add(intArr[2][2] - 1)
                        normalIndices.add(intArr[3][2] - 1)
                    } else {
                        calculateNormal(vertices[index1 * 3], vertices[index1 * 3 + 1], vertices[index1 * 3 + 2],
                                vertices[index2 * 3], vertices[index2 * 3 + 1], vertices[index2 * 3 + 2],
                                vertices[index3 * 3], vertices[index3 * 3 + 1], vertices[index3 * 3 + 2],
                                customNormal)
                        normalBucket.add(customNormal[0])
                        normalBucket.add(customNormal[1])
                        normalBucket.add(customNormal[2])
                        normalIndices.add((normalBucket.size - 1) / 3)
                        normalIndices.add((normalBucket.size - 1) / 3)
                        normalIndices.add((normalBucket.size - 1) / 3)
                        calculateNormal(vertices[index1 * 3], vertices[index1 * 3 + 1], vertices[index1 * 3 + 2],
                                vertices[index3 * 3], vertices[index3 * 3 + 1], vertices[index3 * 3 + 2],
                                vertices[index4 * 3], vertices[index4 * 3 + 1], vertices[index4 * 3 + 2],
                                customNormal)
                        normalBucket.add(customNormal[0])
                        normalBucket.add(customNormal[1])
                        normalBucket.add(customNormal[2])
                        normalIndices.add((normalBucket.size - 1) / 3)
                        normalIndices.add((normalBucket.size - 1) / 3)
                        normalIndices.add((normalBucket.size - 1) / 3)
                    }
                }
            }
            // TODO: Support texture coordinates ("vt")
        }

        vertexCount = vertices.size / 3
        this.centerMassX = (centerMassX / vertexCount).toFloat()
        this.centerMassY = (centerMassY / vertexCount).toFloat()
        this.centerMassZ = (centerMassZ / vertexCount).toFloat()

        val floatArray = FloatArray(vertices.size)
        for (i in vertices.indices) {
            floatArray[i] = vertices[i]
        }
        var vbb = ByteBuffer.allocateDirect(floatArray.size * BYTES_PER_FLOAT)
        vbb.order(ByteOrder.nativeOrder())
        vertexBuffer = vbb.asFloatBuffer()
        vertexBuffer!!.put(floatArray)
        vertexBuffer!!.position(0)
        indexCount = indices.size

        val intArray = IntArray(indexCount)
        for (i in 0 until indexCount) {
            intArray[i] = indices[i]
        }
        vbb = ByteBuffer.allocateDirect(indexCount * BYTES_PER_INT)
        vbb.order(ByteOrder.nativeOrder())
        indexBuffer = vbb.asIntBuffer()
        indexBuffer!!.put(intArray)
        indexBuffer!!.position(0)

        val normalArray = FloatArray(vertices.size)
        var vi: Int
        var ni: Int
        for (i in 0 until indexCount) {
            vi = indices[i]
            ni = normalIndices[i]
            normalArray[vi * 3] = normalBucket[ni * 3]
            normalArray[vi * 3 + 1] = normalBucket[ni * 3 + 1]
            normalArray[vi * 3 + 2] = normalBucket[ni * 3 + 2]
        }
        vbb = ByteBuffer.allocateDirect(normalArray.size * BYTES_PER_FLOAT)
        vbb.order(ByteOrder.nativeOrder())
        normalBuffer = vbb.asFloatBuffer()
        normalBuffer!!.put(normalArray)
        normalBuffer!!.position(0)
    }

    companion object {
        // This is a method that takes a string and parses any integers out of it (in place, without
        // using any additional string splitting, regexes, or int parsing), which provides a pretty
        // significant speed gain.
        // - The first three output integers are pre-initialized to -1.
        // - The integers in the string are expected to be delimited by a single non-numeric character.
        //   If a non-numeric character follows another non-numeric character, then an integer value
        //   of -1 will be added to the output array.
        fun parseInts(str: String, ints: IntArray) {
            val len = str.length
            var intIndex = 0
            var currentInt = -1
            ints[0] = -1
            ints[1] = -1
            ints[2] = -1
            for (i in 0 until len) {
                val c = str[i]
                if (c in '0'..'9') {
                    if (currentInt == -1) {
                        currentInt = c - '0'
                    } else {
                        currentInt *= 10
                        currentInt += c - '0'
                    }
                } else {
                    if (currentInt >= 0) {
                        ints[intIndex++] = currentInt
                        currentInt = -1
                    } else {
                        ints[intIndex++] = -1
                    }
                }
            }
            if (currentInt >= 0) {
                ints[intIndex] = currentInt
            }
        }
    }
}
