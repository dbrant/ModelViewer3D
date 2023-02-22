package com.dmitrybrant.modelviewer.stl

import com.dmitrybrant.modelviewer.ArrayModel
import com.dmitrybrant.modelviewer.util.Util
import com.dmitrybrant.modelviewer.util.Util.calculateNormal
import java.io.*
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.regex.Pattern

/*
* Info on the STL format: https://en.wikipedia.org/wiki/STL_(file_format)
*
* Copyright 2017-2018 Dmitry Brant. All rights reserved.
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
class StlModel(inputStream: InputStream) : ArrayModel() {
    init {
        val stream = BufferedInputStream(inputStream, INPUT_BUFFER_SIZE)
        stream.mark(ASCII_TEST_SIZE)
        val isText = isTextFormat(stream)
        stream.reset()
        if (isText) {
            readText(stream)
        } else {
            readBinary(stream)
        }

        if (vertexCount <= 0 || vertexBuffer == null || normalBuffer == null) {
            throw IOException("Invalid model.")
        }
    }

    public override fun initModelMatrix(boundSize: Float) {
        val zRotation = 180f
        val xRotation = -90.0f
        initModelMatrix(boundSize, xRotation, 0.0f, zRotation)
        var scale = getBoundScale(boundSize)
        if (scale == 0.0f) {
            scale = 1.0f
        }
        floorOffset = (minZ - centerMassZ) / scale
    }

    private fun isTextFormat(stream: InputStream): Boolean {
        val testBytes = ByteArray(ASCII_TEST_SIZE)
        val bytesRead = stream.read(testBytes, 0, testBytes.size)
        val string = String(testBytes, 0, bytesRead)
        return string.contains("solid") && string.contains("facet") && string.contains("vertex")
    }

    private fun readText(stream: InputStream) {
        val normals = mutableListOf<Float>()
        val vertices = mutableListOf<Float>()
        val reader = BufferedReader(InputStreamReader(stream), INPUT_BUFFER_SIZE)
        var line: String
        var lineArr: Array<String>
        val pattern = Pattern.compile("\\s+")
        var centerMassX = 0.0
        var centerMassY = 0.0
        var centerMassZ = 0.0
        val facetNormalRegex = "facet normal ".toRegex()
        val vertexRegex = "vertex ".toRegex()

        while (reader.readLine().also { line = it.orEmpty() } != null) {
            line = line.trim()
            if (line.startsWith("facet")) {
                line = line.replaceFirst(facetNormalRegex, "").trim()
                lineArr = pattern.split(line, 0)
                val x = lineArr[0].toFloat()
                val y = lineArr[1].toFloat()
                val z = lineArr[2].toFloat()
                normals.add(x)
                normals.add(y)
                normals.add(z)
                normals.add(x)
                normals.add(y)
                normals.add(z)
                normals.add(x)
                normals.add(y)
                normals.add(z)
            } else if (line.startsWith("vertex")) {
                line = line.replaceFirst(vertexRegex, "").trim()
                lineArr = pattern.split(line, 0)
                val x = lineArr[0].toFloat()
                val y = lineArr[1].toFloat()
                val z = lineArr[2].toFloat()
                adjustMaxMin(x, y, z)
                vertices.add(x)
                vertices.add(y)
                vertices.add(z)
                centerMassX += x.toDouble()
                centerMassY += y.toDouble()
                centerMassZ += z.toDouble()
            }
        }

        vertexCount = vertices.size / 3
        this.centerMassX = (centerMassX / vertexCount).toFloat()
        this.centerMassY = (centerMassY / vertexCount).toFloat()
        this.centerMassZ = (centerMassZ / vertexCount).toFloat()

        var vbb = ByteBuffer.allocateDirect(vertices.size * BYTES_PER_FLOAT)
        vbb.order(ByteOrder.nativeOrder())
        vertexBuffer = vbb.asFloatBuffer()
        for (i in vertices.indices) {
            vertexBuffer!!.put(vertices[i])
        }
        vertexBuffer!!.position(0)

        vbb = ByteBuffer.allocateDirect(normals.size * BYTES_PER_FLOAT)
        vbb.order(ByteOrder.nativeOrder())
        normalBuffer = vbb.asFloatBuffer()
        for (i in normals.indices) {
            normalBuffer!!.put(normals[i])
        }
        normalBuffer!!.position(0)
    }

    private fun readBinary(inputStream: BufferedInputStream) {
        val chunkSize = 50
        val tempBytes = ByteArray(chunkSize)
        inputStream.skip(HEADER_SIZE.toLong())
        inputStream.read(tempBytes, 0, BYTES_PER_FLOAT)

        val vectorSize: Int = Util.readIntLe(tempBytes, 0)
        vertexCount = vectorSize * 3
        if (vertexCount < 0 || vertexCount > 10000000) {
            throw IOException("Invalid model.")
        }

        var centerMassX = 0.0
        var centerMassY = 0.0
        var centerMassZ = 0.0
        val vertexArray = FloatArray(vertexCount * COORDS_PER_VERTEX)
        val normalArray = FloatArray(vertexCount * COORDS_PER_VERTEX)
        var x: Float
        var y: Float
        var z: Float
        var vertexPtr = 0
        var normalPtr = 0
        var haveNormals = false
        for (i in 0 until vectorSize) {
            inputStream.read(tempBytes, 0, tempBytes.size)
            x = java.lang.Float.intBitsToFloat(Util.readIntLe(tempBytes, 0))
            y = java.lang.Float.intBitsToFloat(Util.readIntLe(tempBytes, 4))
            z = java.lang.Float.intBitsToFloat(Util.readIntLe(tempBytes, 8))
            normalArray[normalPtr++] = x
            normalArray[normalPtr++] = y
            normalArray[normalPtr++] = z
            normalArray[normalPtr++] = x
            normalArray[normalPtr++] = y
            normalArray[normalPtr++] = z
            normalArray[normalPtr++] = x
            normalArray[normalPtr++] = y
            normalArray[normalPtr++] = z
            if (!haveNormals) {
                if (x != 0.0f || y != 0.0f || z != 0.0f) {
                    haveNormals = true
                }
            }
            x = java.lang.Float.intBitsToFloat(Util.readIntLe(tempBytes, 12))
            y = java.lang.Float.intBitsToFloat(Util.readIntLe(tempBytes, 16))
            z = java.lang.Float.intBitsToFloat(Util.readIntLe(tempBytes, 20))
            adjustMaxMin(x, y, z)
            centerMassX += x.toDouble()
            centerMassY += y.toDouble()
            centerMassZ += z.toDouble()
            vertexArray[vertexPtr++] = x
            vertexArray[vertexPtr++] = y
            vertexArray[vertexPtr++] = z
            x = java.lang.Float.intBitsToFloat(Util.readIntLe(tempBytes, 24))
            y = java.lang.Float.intBitsToFloat(Util.readIntLe(tempBytes, 28))
            z = java.lang.Float.intBitsToFloat(Util.readIntLe(tempBytes, 32))
            adjustMaxMin(x, y, z)
            centerMassX += x.toDouble()
            centerMassY += y.toDouble()
            centerMassZ += z.toDouble()
            vertexArray[vertexPtr++] = x
            vertexArray[vertexPtr++] = y
            vertexArray[vertexPtr++] = z
            x = java.lang.Float.intBitsToFloat(Util.readIntLe(tempBytes, 36))
            y = java.lang.Float.intBitsToFloat(Util.readIntLe(tempBytes, 40))
            z = java.lang.Float.intBitsToFloat(Util.readIntLe(tempBytes, 44))
            adjustMaxMin(x, y, z)
            centerMassX += x.toDouble()
            centerMassY += y.toDouble()
            centerMassZ += z.toDouble()
            vertexArray[vertexPtr++] = x
            vertexArray[vertexPtr++] = y
            vertexArray[vertexPtr++] = z
        }
        this.centerMassX = (centerMassX / vertexCount).toFloat()
        this.centerMassY = (centerMassY / vertexCount).toFloat()
        this.centerMassZ = (centerMassZ / vertexCount).toFloat()

        if (!haveNormals) {
            val customNormal = FloatArray(3)
            var i = 0
            while (i < vertexCount) {
                calculateNormal(vertexArray[i * 3], vertexArray[i * 3 + 1], vertexArray[i * 3 + 2],
                        vertexArray[(i + 1) * 3], vertexArray[(i + 1) * 3 + 1], vertexArray[(i + 1) * 3 + 2],
                        vertexArray[(i + 2) * 3], vertexArray[(i + 2) * 3 + 1], vertexArray[(i + 2) * 3 + 2],
                        customNormal)
                normalArray[i * 3] = customNormal[0]
                normalArray[i * 3 + 1] = customNormal[1]
                normalArray[i * 3 + 2] = customNormal[2]
                normalArray[(i + 1) * 3] = customNormal[0]
                normalArray[(i + 1) * 3 + 1] = customNormal[1]
                normalArray[(i + 1) * 3 + 2] = customNormal[2]
                normalArray[(i + 2) * 3] = customNormal[0]
                normalArray[(i + 2) * 3 + 1] = customNormal[1]
                normalArray[(i + 2) * 3 + 2] = customNormal[2]
                i += 3
            }
        }

        var vbb = ByteBuffer.allocateDirect(vertexArray.size * BYTES_PER_FLOAT)
        vbb.order(ByteOrder.nativeOrder())
        vertexBuffer = vbb.asFloatBuffer()
        vertexBuffer!!.put(vertexArray)
        vertexBuffer!!.position(0)

        vbb = ByteBuffer.allocateDirect(normalArray.size * BYTES_PER_FLOAT)
        vbb.order(ByteOrder.nativeOrder())
        normalBuffer = vbb.asFloatBuffer()
        normalBuffer!!.put(normalArray)
        normalBuffer!!.position(0)
    }

    companion object {
        private const val HEADER_SIZE = 80
        private const val ASCII_TEST_SIZE = 256
    }
}
