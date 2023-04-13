package com.dmitrybrant.modelviewer.ply

import android.opengl.GLES20
import android.opengl.Matrix
import com.dmitrybrant.modelviewer.IndexedModel
import com.dmitrybrant.modelviewer.Light
import com.dmitrybrant.modelviewer.R
import com.dmitrybrant.modelviewer.util.Util
import java.io.*
import java.nio.ByteBuffer
import java.nio.ByteOrder

/*
*
* Info on the PLY format: https://en.wikipedia.org/wiki/PLY_(file_format)
* Please see limitations in inline comments.
*
* Copyright 2017- Dmitry Brant. All rights reserved.
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
class PlyModel(inputStream: InputStream) : IndexedModel() {
    private val pointColor = floatArrayOf(1.0f, 1.0f, 1.0f, 1.0f)
    private var isPointCloud = false

    init {
        val stream = BufferedInputStream(inputStream, INPUT_BUFFER_SIZE)
        readText(stream)
        if (vertexCount <= 0 || vertexBuffer == null) {
            throw IOException("Invalid model.")
        }
    }

    override fun setup(boundSize: Float) {
        if (isPointCloud) {
            if (GLES20.glIsProgram(glProgram)) {
                GLES20.glDeleteProgram(glProgram)
                glProgram = -1
            }
            glProgram = Util.compileProgram(R.raw.point_cloud_vertex, R.raw.point_cloud_fragment, arrayOf("a_Position", "a_Color"))
        } else {
            super.setup(boundSize)
        }
        initModelMatrix(boundSize)
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

    private fun readText(stream: BufferedInputStream) {
        val elements = mutableMapOf<String, List<Pair<String, String>>>()
        val elementCounts = mutableMapOf<String, Int>()
        val indices = mutableListOf<Int>()
        val normalBucket = mutableListOf<Float>()
        val normalIndices = mutableListOf<Int>()

        val vertices = mutableListOf<Float>()
        val colors = mutableListOf<Float>()
        val reader = BufferedReader(InputStreamReader(stream), INPUT_BUFFER_SIZE)
        var line: String
        var lineArr: List<String>

        stream.mark(0x100000)
        var isBinary = false
        var isBigEndian = false

        var currentElement: MutableList<Pair<String, String>>? = null

        while (reader.readLine().also { line = it.orEmpty() } != null) {
            line = line.trim()
            if (line.isEmpty())
                continue

            lineArr = line.split(spaceRegex)
            if (lineArr.isEmpty()) {
                continue
            }
            if (lineArr[0] == "format") {
                if (line.contains("binary")) {
                    isBinary = true
                    isBigEndian = line.contains("big")
                }
            } else if (lineArr[0] == "element") {
                val elementName = lineArr[1]
                currentElement = mutableListOf()
                elements[elementName] = currentElement
                elementCounts[elementName] = lineArr[2].toInt()
            } else if (lineArr[0] == "property") {
                val propType = lineArr.subList(1, (lineArr.size - 1)).joinToString(" ")
                val propName = lineArr[lineArr.size - 1]
                currentElement?.add(Pair(propType, propName))
            } else if (lineArr[0] == "end_header") {
                break
            }
        }

        vertexCount = elementCounts["vertex"] ?: 0
        if (vertexCount <= 0) {
            throw IOException("No vertices found in model.")
        }

        if (isBinary) {
            stream.reset()
            readVerticesBinary(isBigEndian, vertices, colors, elements["vertex"]!!, stream)
        } else {
            readVerticesText(vertices, colors, elements["vertex"]!!, reader)
        }

        val faceCount = elementCounts["face"] ?: 0
        isPointCloud = faceCount == 0

        if (faceCount > 0) {
            if (isBinary) {
                val faceVertexIndexType = (elements["face"]!!.firstOrNull { it.second == "vertex_index" } ?: elements["face"]!!.first { it.second == "vertex_indices" }).first
                lineArr = faceVertexIndexType.split(" ")
                val indexLength = typeToSize(lineArr[lineArr.size - 2])
                val itemLength = typeToSize(lineArr[lineArr.size - 1])
                readFacesBinary(isBigEndian, indexLength, itemLength, faceCount, indices, vertices,
                    normalBucket, normalIndices, stream)
            } else {
                readFacesText(faceCount, indices, vertices, normalBucket, normalIndices, reader)
            }
        }

        var vbb = ByteBuffer.allocateDirect(vertices.size * BYTES_PER_FLOAT)
        vbb.order(ByteOrder.nativeOrder())
        vertexBuffer = vbb.asFloatBuffer()
        for (i in vertices.indices) {
            vertexBuffer!!.put(vertices[i])
        }
        vertexBuffer!!.position(0)

        if (faceCount > 0) {
            indexCount = indices.size
            vbb = ByteBuffer.allocateDirect(indexCount * BYTES_PER_INT)
            vbb.order(ByteOrder.nativeOrder())
            indexBuffer = vbb.asIntBuffer()
            for (i in 0 until indexCount) {
                indexBuffer!!.put(indices[i])
            }
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

        if (colors.isNotEmpty()) {
            vbb = ByteBuffer.allocateDirect(colors.size * BYTES_PER_FLOAT)
            vbb.order(ByteOrder.nativeOrder())
            colorBuffer = vbb.asFloatBuffer()
            for (i in colors.indices) {
                colorBuffer!!.put(colors[i])
            }
            colorBuffer!!.position(0)
        }
    }

    private fun readFacesText(faceCount: Int, indices: MutableList<Int>, vertices: List<Float>,
                              normalBucket: MutableList<Float>, normalIndices: MutableList<Int>,
                              reader: BufferedReader) {
        var line: String
        var index1: Int
        var index2: Int
        var index3: Int
        var index4: Int
        val intArr = IntArray(8)
        val customNormal = FloatArray(3)
        var i = 0

        while (i < faceCount) {
            line = reader.readLine().trim()
            if (line.isEmpty())
                continue
            i++

            parseInts(line, intArr)

            if (intArr[0] == 3) {
                // triangle
                index1 = intArr[1]
                index2 = intArr[2]
                index3 = intArr[3]
                indices.add(index1)
                indices.add(index2)
                indices.add(index3)

                Util.calculateNormal(
                    vertices[index1 * 3], vertices[index1 * 3 + 1], vertices[index1 * 3 + 2],
                    vertices[index2 * 3], vertices[index2 * 3 + 1], vertices[index2 * 3 + 2],
                    vertices[index3 * 3], vertices[index3 * 3 + 1], vertices[index3 * 3 + 2],
                    customNormal
                )
                normalBucket.add(customNormal[0])
                normalBucket.add(customNormal[1])
                normalBucket.add(customNormal[2])
                normalIndices.add((normalBucket.size - 1) / 3)
                normalIndices.add((normalBucket.size - 1) / 3)
                normalIndices.add((normalBucket.size - 1) / 3)
            } else if (intArr[0] == 4) {
                // quad
                index1 = intArr[1]
                index2 = intArr[2]
                index3 = intArr[3]
                index4 = intArr[4]
                indices.add(index1)
                indices.add(index2)
                indices.add(index3)
                indices.add(index1)
                indices.add(index3)
                indices.add(index4)

                Util.calculateNormal(
                    vertices[index1 * 3], vertices[index1 * 3 + 1], vertices[index1 * 3 + 2],
                    vertices[index2 * 3], vertices[index2 * 3 + 1], vertices[index2 * 3 + 2],
                    vertices[index3 * 3], vertices[index3 * 3 + 1], vertices[index3 * 3 + 2],
                    customNormal
                )
                normalBucket.add(customNormal[0])
                normalBucket.add(customNormal[1])
                normalBucket.add(customNormal[2])
                normalIndices.add((normalBucket.size - 1) / 3)
                normalIndices.add((normalBucket.size - 1) / 3)
                normalIndices.add((normalBucket.size - 1) / 3)
                Util.calculateNormal(
                    vertices[index1 * 3], vertices[index1 * 3 + 1], vertices[index1 * 3 + 2],
                    vertices[index3 * 3], vertices[index3 * 3 + 1], vertices[index3 * 3 + 2],
                    vertices[index4 * 3], vertices[index4 * 3 + 1], vertices[index4 * 3 + 2],
                    customNormal
                )
                normalBucket.add(customNormal[0])
                normalBucket.add(customNormal[1])
                normalBucket.add(customNormal[2])
                normalIndices.add((normalBucket.size - 1) / 3)
                normalIndices.add((normalBucket.size - 1) / 3)
                normalIndices.add((normalBucket.size - 1) / 3)
            } else {
                // unsupported, so fall back to point cloud.
                isPointCloud = true
                return
            }
        }
    }

    // TODO: The binary format of faces is currently assumed to be "uchar int"
    // Are there other types?
    private fun readFacesBinary(bigEndian: Boolean, indexLen: Int, itemLen: Int,
                                faceCount: Int, indices: MutableList<Int>,
                                vertices: List<Float>, normalBucket: MutableList<Float>,
                                normalIndices: MutableList<Int>, stream: BufferedInputStream) {
        val tempBytes = ByteArray(0x1000)
        var elementCount: Int
        var index1: Int
        var index2: Int
        var index3: Int
        var index4: Int
        val customNormal = FloatArray(3)

        for (i in 0 until faceCount) {
            stream.read(tempBytes, 0, indexLen)
            elementCount = if (indexLen == 1) {
                tempBytes[0].toInt()
            } else if (indexLen == 2) {
                if (bigEndian) Util.readShortBe(tempBytes, 0) else Util.readShortLe(tempBytes, 0)
            } else {
                if (bigEndian) Util.readIntBe(tempBytes, 0) else Util.readIntLe(tempBytes, 0)
            }
            stream.read(tempBytes, 0, elementCount * itemLen)
            if (elementCount == 3) {
                // triangle
                if (itemLen == 1) {
                    index1 = tempBytes[0].toInt()
                    index2 = tempBytes[1].toInt()
                    index3 = tempBytes[2].toInt()
                } else if (itemLen == 2) {
                    if (bigEndian) {
                        index1 = Util.readShortBe(tempBytes, 0)
                        index2 = Util.readShortBe(tempBytes, 2)
                        index3 = Util.readShortBe(tempBytes, 4)
                    } else {
                        index1 = Util.readShortLe(tempBytes, 0)
                        index2 = Util.readShortLe(tempBytes, 2)
                        index3 = Util.readShortLe(tempBytes, 4)
                    }
                } else {
                    if (bigEndian) {
                        index1 = Util.readIntBe(tempBytes, 0)
                        index2 = Util.readIntBe(tempBytes, 4)
                        index3 = Util.readIntBe(tempBytes, 8)
                    } else {
                        index1 = Util.readIntLe(tempBytes, 0)
                        index2 = Util.readIntLe(tempBytes, 4)
                        index3 = Util.readIntLe(tempBytes, 8)
                    }
                }
                indices.add(index1)
                indices.add(index2)
                indices.add(index3)

                Util.calculateNormal(
                    vertices[index1 * 3], vertices[index1 * 3 + 1], vertices[index1 * 3 + 2],
                    vertices[index2 * 3], vertices[index2 * 3 + 1], vertices[index2 * 3 + 2],
                    vertices[index3 * 3], vertices[index3 * 3 + 1], vertices[index3 * 3 + 2],
                    customNormal
                )
                normalBucket.add(customNormal[0])
                normalBucket.add(customNormal[1])
                normalBucket.add(customNormal[2])
                normalIndices.add((normalBucket.size - 1) / 3)
                normalIndices.add((normalBucket.size - 1) / 3)
                normalIndices.add((normalBucket.size - 1) / 3)
            } else if (elementCount == 4) {
                if (itemLen == 1) {
                    index1 = tempBytes[0].toInt()
                    index2 = tempBytes[1].toInt()
                    index3 = tempBytes[2].toInt()
                    index4 = tempBytes[3].toInt()
                } else if (itemLen == 2) {
                    if (bigEndian) {
                        index1 = Util.readShortBe(tempBytes, 0)
                        index2 = Util.readShortBe(tempBytes, 2)
                        index3 = Util.readShortBe(tempBytes, 4)
                        index4 = Util.readShortBe(tempBytes, 6)
                    } else {
                        index1 = Util.readShortLe(tempBytes, 0)
                        index2 = Util.readShortLe(tempBytes, 2)
                        index3 = Util.readShortLe(tempBytes, 4)
                        index4 = Util.readShortLe(tempBytes, 6)
                    }
                } else {
                    if (bigEndian) {
                        index1 = Util.readIntBe(tempBytes, 0)
                        index2 = Util.readIntBe(tempBytes, 4)
                        index3 = Util.readIntBe(tempBytes, 8)
                        index4 = Util.readIntBe(tempBytes, 12)
                    } else {
                        index1 = Util.readIntLe(tempBytes, 0)
                        index2 = Util.readIntLe(tempBytes, 4)
                        index3 = Util.readIntLe(tempBytes, 8)
                        index4 = Util.readIntLe(tempBytes, 12)
                    }
                }
                indices.add(index1)
                indices.add(index2)
                indices.add(index3)
                indices.add(index1)
                indices.add(index3)
                indices.add(index4)

                Util.calculateNormal(
                    vertices[index1 * 3], vertices[index1 * 3 + 1], vertices[index1 * 3 + 2],
                    vertices[index2 * 3], vertices[index2 * 3 + 1], vertices[index2 * 3 + 2],
                    vertices[index3 * 3], vertices[index3 * 3 + 1], vertices[index3 * 3 + 2],
                    customNormal
                )
                normalBucket.add(customNormal[0])
                normalBucket.add(customNormal[1])
                normalBucket.add(customNormal[2])
                normalIndices.add((normalBucket.size - 1) / 3)
                normalIndices.add((normalBucket.size - 1) / 3)
                normalIndices.add((normalBucket.size - 1) / 3)
                Util.calculateNormal(
                    vertices[index1 * 3], vertices[index1 * 3 + 1], vertices[index1 * 3 + 2],
                    vertices[index3 * 3], vertices[index3 * 3 + 1], vertices[index3 * 3 + 2],
                    vertices[index4 * 3], vertices[index4 * 3 + 1], vertices[index4 * 3 + 2],
                    customNormal
                )
                normalBucket.add(customNormal[0])
                normalBucket.add(customNormal[1])
                normalBucket.add(customNormal[2])
                normalIndices.add((normalBucket.size - 1) / 3)
                normalIndices.add((normalBucket.size - 1) / 3)
                normalIndices.add((normalBucket.size - 1) / 3)
            } else {
                // unsupported, so fall back to point cloud.
                isPointCloud = true
                return
            }
        }
    }

    private fun readVerticesBinary(bigEndian: Boolean, vertices: MutableList<Float>, colors: MutableList<Float>,
                                   vertexElement: List<Pair<String, String>>, stream: BufferedInputStream) {
        val tempBytes = ByteArray(0x1000)
        stream.mark(1)
        stream.read(tempBytes)
        val tempStr = String(tempBytes)
        val contentsPos = tempStr.indexOf("end_header") + 11
        stream.reset()
        stream.skip(contentsPos.toLong())

        var x: Float
        var y: Float
        var z: Float
        var centerMassX = 0.0
        var centerMassY = 0.0
        var centerMassZ = 0.0

        var elementByteLength = 0

        var xOffset = -1
        var yOffset = -1
        var zOffset = -1
        var rOffset = -1
        var gOffset = -1
        var bOffset = -1
        var alphaOffset = -1

        // TODO: This just assumes that all three components x,y,z are the same type (float or double)
        // Can there be cases when each component is a different type?
        var areVerticesDouble = false

        for (i in vertexElement.indices) {
            val length = typeToSize(vertexElement[i].first)
            if (vertexElement[i].second == "x" && xOffset < 0) {
                xOffset = elementByteLength
                if (vertexElement[i].first == "double") { areVerticesDouble = true }
            }
            else if (vertexElement[i].second == "y" && yOffset < 0) { yOffset = elementByteLength }
            else if (vertexElement[i].second == "z" && zOffset < 0) { zOffset = elementByteLength }
            else if (vertexElement[i].second == "red" && rOffset < 0) { rOffset = elementByteLength }
            else if (vertexElement[i].second == "green" && gOffset < 0) { gOffset = elementByteLength }
            else if (vertexElement[i].second == "blue" && bOffset < 0) { bOffset = elementByteLength }
            else if (vertexElement[i].second == "alpha" && alphaOffset < 0) { alphaOffset = elementByteLength }
            elementByteLength += length
        }

        if (rOffset >= 0 && gOffset >= 0 && bOffset >= 0) {
            useColorBuffer = true
        }

        for (i in 0 until vertexCount) {
            stream.read(tempBytes, 0, elementByteLength)

            if (bigEndian) {
                if (areVerticesDouble) {
                    x = java.lang.Double.longBitsToDouble(Util.readLongBe(tempBytes, xOffset)).toFloat()
                    y = java.lang.Double.longBitsToDouble(Util.readLongBe(tempBytes, yOffset)).toFloat()
                    z = java.lang.Double.longBitsToDouble(Util.readLongBe(tempBytes, zOffset)).toFloat()
                } else {
                    x = java.lang.Float.intBitsToFloat(Util.readIntBe(tempBytes, xOffset))
                    y = java.lang.Float.intBitsToFloat(Util.readIntBe(tempBytes, yOffset))
                    z = java.lang.Float.intBitsToFloat(Util.readIntBe(tempBytes, zOffset))
                }
            } else {
                if (areVerticesDouble) {
                    x = java.lang.Double.longBitsToDouble(Util.readLongLe(tempBytes, xOffset)).toFloat()
                    y = java.lang.Double.longBitsToDouble(Util.readLongLe(tempBytes, yOffset)).toFloat()
                    z = java.lang.Double.longBitsToDouble(Util.readLongLe(tempBytes, zOffset)).toFloat()
                } else {
                    x = java.lang.Float.intBitsToFloat(Util.readIntLe(tempBytes, xOffset))
                    y = java.lang.Float.intBitsToFloat(Util.readIntLe(tempBytes, yOffset))
                    z = java.lang.Float.intBitsToFloat(Util.readIntLe(tempBytes, zOffset))
                }
            }

            vertices.add(x)
            vertices.add(y)
            vertices.add(z)
            adjustMaxMin(x, y, z)
            centerMassX += x.toDouble()
            centerMassY += y.toDouble()
            centerMassZ += z.toDouble()

            if (useColorBuffer) {
                colors.add((tempBytes[rOffset].toInt() and 0xff).toFloat() / 255f)
                colors.add((tempBytes[gOffset].toInt() and 0xff).toFloat() / 255f)
                colors.add((tempBytes[bOffset].toInt() and 0xff).toFloat() / 255f)
                if (alphaOffset >= 0) {
                    colors.add((tempBytes[alphaOffset].toInt()and 0xff).toFloat() / 255f)
                } else {
                    colors.add(1f)
                }
            } else {
                colors.add(pointColor[0])
                colors.add(pointColor[1])
                colors.add(pointColor[2])
                colors.add(pointColor[3])
            }
        }
        this.centerMassX = (centerMassX / vertexCount).toFloat()
        this.centerMassY = (centerMassY / vertexCount).toFloat()
        this.centerMassZ = (centerMassZ / vertexCount).toFloat()
    }

    private fun readVerticesText(vertices: MutableList<Float>, colors: MutableList<Float>,
                                 vertexElement: List<Pair<String, String>>, reader: BufferedReader) {
        var line: String
        var lineArr: List<String>
        var x: Float
        var y: Float
        var z: Float
        var centerMassX = 0.0
        var centerMassY = 0.0
        var centerMassZ = 0.0

        var xIndex = -1
        var yIndex = -1
        var zIndex = -1
        var rIndex = -1
        var gIndex = -1
        var bIndex = -1
        var alphaIndex = -1

        for (i in vertexElement.indices) {
            if (vertexElement[i].second == "x" && xIndex < 0) { xIndex = i }
            else if (vertexElement[i].second == "y" && yIndex < 0) { yIndex = i }
            else if (vertexElement[i].second == "z" && zIndex < 0) { zIndex = i }
            else if (vertexElement[i].second == "red" && rIndex < 0) { rIndex = i }
            else if (vertexElement[i].second == "green" && gIndex < 0) { gIndex = i }
            else if (vertexElement[i].second == "blue" && bIndex < 0) { bIndex = i }
            else if (vertexElement[i].second == "alpha" && alphaIndex < 0) { alphaIndex = i }
        }

        if (rIndex >= 0 && gIndex >= 0 && bIndex >= 0) {
            useColorBuffer = true
        }

        var i = 0
        while (i < vertexCount) {
            line = reader.readLine().trim()
            if (line.isEmpty())
                continue
            i++

            lineArr = line.split(spaceRegex)
            x = lineArr[xIndex].toFloat()
            y = lineArr[yIndex].toFloat()
            z = lineArr[zIndex].toFloat()
            vertices.add(x)
            vertices.add(y)
            vertices.add(z)
            adjustMaxMin(x, y, z)
            centerMassX += x.toDouble()
            centerMassY += y.toDouble()
            centerMassZ += z.toDouble()
            if (useColorBuffer) {
                colors.add(lineArr[rIndex].toFloat() / 255f)
                colors.add(lineArr[gIndex].toFloat() / 255f)
                colors.add(lineArr[bIndex].toFloat() / 255f)
                colors.add(if (alphaIndex >= 0) lineArr[alphaIndex].toFloat() / 255f else 1f)
            } else {
                colors.add(pointColor[0])
                colors.add(pointColor[1])
                colors.add(pointColor[2])
                colors.add(pointColor[3])
            }
        }
        this.centerMassX = (centerMassX / vertexCount).toFloat()
        this.centerMassY = (centerMassY / vertexCount).toFloat()
        this.centerMassZ = (centerMassZ / vertexCount).toFloat()
    }

    override fun draw(viewMatrix: FloatArray, projectionMatrix: FloatArray, light: Light) {
        if (!isPointCloud) {
            super.draw(viewMatrix, projectionMatrix, light)
            return
        }
        if (vertexBuffer == null) {
            return
        }
        GLES20.glUseProgram(glProgram)
        val mvpMatrixHandle = GLES20.glGetUniformLocation(glProgram, "u_MVP")
        val positionHandle = GLES20.glGetAttribLocation(glProgram, "a_Position")
        val colorHandle = GLES20.glGetAttribLocation(glProgram, "a_Color")
        val pointThicknessHandle = GLES20.glGetUniformLocation(glProgram, "u_PointThickness")
        val ambientColorHandle = GLES20.glGetUniformLocation(glProgram, "u_ambientColor")
        GLES20.glEnableVertexAttribArray(positionHandle)
        GLES20.glVertexAttribPointer(positionHandle, COORDS_PER_VERTEX, GLES20.GL_FLOAT, false, VERTEX_STRIDE, vertexBuffer)
        GLES20.glEnableVertexAttribArray(colorHandle)
        GLES20.glVertexAttribPointer(colorHandle, 4, GLES20.GL_FLOAT, false, 4 * BYTES_PER_FLOAT, colorBuffer)
        Matrix.multiplyMM(mvMatrix, 0, viewMatrix, 0, modelMatrix, 0)
        Matrix.multiplyMM(mvpMatrix, 0, projectionMatrix, 0, mvMatrix, 0)
        GLES20.glUniformMatrix4fv(mvpMatrixHandle, 1, false, mvpMatrix, 0)
        GLES20.glUniform1f(pointThicknessHandle, 3.0f)
        if (ambientColorHandle >= 0) {
            GLES20.glUniform4fv(ambientColorHandle, 1, pointColor, 0)
        }
        GLES20.glDrawArrays(GLES20.GL_POINTS, 0, vertexCount)
        GLES20.glDisableVertexAttribArray(colorHandle)
        GLES20.glDisableVertexAttribArray(positionHandle)
    }

    private fun typeToSize(typeStr: String): Int {
        return when (typeStr) {
            "char" -> 1
            "uchar" -> 1
            "int8" -> 1
            "uint8" -> 1
            "short" -> 2
            "ushort" -> 2
            "int16" -> 2
            "uint16" -> 2
            "int" -> 4
            "int32" -> 4
            "uint" -> 4
            "uint32" -> 4
            "int64" -> 8
            "uint64" -> 8
            "long" -> 8
            "ulong" -> 8
            "float" -> 4
            "float32" -> 4
            "double" -> 8
            else -> 0
        }
    }

    companion object {
        private val spaceRegex = "\\s+".toRegex()

        // This is a method that takes a string and parses any integers out of it (in place, without
        // using any additional string splitting, regexes, or int parsing), which provides a pretty
        // significant speed gain.
        fun parseInts(str: String, ints: IntArray) {
            val len = str.length
            var intIndex = 0
            var currentInt = -1
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
                    }
                }
            }
            if (currentInt >= 0) {
                ints[intIndex] = currentInt
            }
        }
    }
}
