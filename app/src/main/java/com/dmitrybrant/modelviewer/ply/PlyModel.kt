package com.dmitrybrant.modelviewer.ply

import android.opengl.GLES20
import android.opengl.Matrix
import com.dmitrybrant.modelviewer.IndexedModel
import com.dmitrybrant.modelviewer.Light
import com.dmitrybrant.modelviewer.R
import com.dmitrybrant.modelviewer.util.Util.compileProgram
import com.dmitrybrant.modelviewer.util.Util.readIntLe
import com.dmitrybrant.modelviewer.util.Util.readLongLe
import java.io.*
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer

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
    private val pointColor = floatArrayOf(1.0f, 1.0f, 1.0f)
    private var colorBuffer: FloatBuffer? = null

    init {
        val stream = BufferedInputStream(inputStream, INPUT_BUFFER_SIZE)
        readText(stream)
        if (vertexCount <= 0 || vertexBuffer == null) {
            throw IOException("Invalid model.")
        }
    }

    override fun init(boundSize: Float) {
        if (GLES20.glIsProgram(glProgram)) {
            GLES20.glDeleteProgram(glProgram)
            glProgram = -1
        }
        glProgram = compileProgram(R.raw.point_cloud_vertex, R.raw.point_cloud_fragment, arrayOf("a_Position", "a_Color"))
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

        val vertices = mutableListOf<Float>()
        val colors = mutableListOf<Float>()
        val reader = BufferedReader(InputStreamReader(stream), INPUT_BUFFER_SIZE)
        var line: String
        var lineArr: Array<String>

        /*
        TODO:
        At the moment we only parse "vertex" elements, and render them as points.
        (we do not parse "face" elements that tie together vertices)
        */
        stream.mark(0x100000)
        var isBinary = false

        var currentElement: MutableList<Pair<String, String>>? = null

        while (reader.readLine().also { line = it.orEmpty() } != null) {
            line = line.trim()
            lineArr = line.split(" ").toTypedArray()
            if (line.startsWith("format ")) {
                if (line.contains("binary")) {
                    isBinary = true
                }
            } else if (line.startsWith("element ")) {
                val elementName = lineArr[1]
                currentElement = mutableListOf()
                elements[elementName] = currentElement
                elementCounts[elementName] = lineArr[2].toInt()
            } else if (line.startsWith("property ")) {
                val propType = lineArr[1] // TODO: should be all words until n-1
                val propName = lineArr[lineArr.size - 1]
                currentElement?.add(Pair(propType, propName))
            } else if (line.startsWith("end_header")) {
                break
            }
        }

        vertexCount = elementCounts["vertex"] ?: 0

        if (vertexCount <= 0) {
            throw IOException("No vertices found in model.")
        }

        if (isBinary) {
            stream.reset()
            readVerticesBinary(vertices, colors, elements["vertex"]!!, stream)
        } else {
            readVerticesText(vertices, colors, elements["vertex"]!!, reader)
        }

        var floatArray = FloatArray(vertices.size)
        for (i in vertices.indices) {
            floatArray[i] = vertices[i]
        }
        var vbb = ByteBuffer.allocateDirect(floatArray.size * BYTES_PER_FLOAT)
        vbb.order(ByteOrder.nativeOrder())
        vertexBuffer = vbb.asFloatBuffer()
        vertexBuffer!!.put(floatArray)
        vertexBuffer!!.position(0)

        floatArray = FloatArray(colors.size)
        for (i in colors.indices) {
            floatArray[i] = colors[i]
        }
        vbb = ByteBuffer.allocateDirect(floatArray.size * BYTES_PER_FLOAT)
        vbb.order(ByteOrder.nativeOrder())
        colorBuffer = vbb.asFloatBuffer()
        colorBuffer!!.put(floatArray)
        colorBuffer!!.position(0)
    }

    private fun readVerticesText(vertices: MutableList<Float>, colors: MutableList<Float>,
                                 vertexElement: List<Pair<String, String>>, reader: BufferedReader) {
        var lineArr: Array<String>
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
        var haveColor = false

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
            haveColor = true
        }

        for (i in 0 until vertexCount) {
            lineArr = reader.readLine().trim().split(" ").toTypedArray()
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
            if (haveColor) {
                colors.add(lineArr[rIndex].toFloat() / 255f)
                colors.add(lineArr[gIndex].toFloat() / 255f)
                colors.add(lineArr[bIndex].toFloat() / 255f)
                colors.add(if (alphaIndex >= 0) lineArr[alphaIndex].toFloat() / 255f else 1f)
            } else {
                colors.add(pointColor[0])
                colors.add(pointColor[1])
                colors.add(pointColor[2])
                colors.add(255f)
            }
        }
        this.centerMassX = (centerMassX / vertexCount).toFloat()
        this.centerMassY = (centerMassY / vertexCount).toFloat()
        this.centerMassZ = (centerMassZ / vertexCount).toFloat()
    }

    private fun readVerticesBinary(vertices: MutableList<Float>, colors: MutableList<Float>,
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
        var haveColor = false

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
            haveColor = true
        }

        for (i in 0 until vertexCount) {
            stream.read(tempBytes, 0, elementByteLength)

            if (areVerticesDouble) {
                x = java.lang.Double.longBitsToDouble(readLongLe(tempBytes, xOffset)).toFloat()
                y = java.lang.Double.longBitsToDouble(readLongLe(tempBytes, yOffset)).toFloat()
                z = java.lang.Double.longBitsToDouble(readLongLe(tempBytes, zOffset)).toFloat()
            } else {
                x = java.lang.Float.intBitsToFloat(readIntLe(tempBytes, xOffset))
                y = java.lang.Float.intBitsToFloat(readIntLe(tempBytes, yOffset))
                z = java.lang.Float.intBitsToFloat(readIntLe(tempBytes, zOffset))
            }

            vertices.add(x)
            vertices.add(y)
            vertices.add(z)
            adjustMaxMin(x, y, z)
            centerMassX += x.toDouble()
            centerMassY += y.toDouble()
            centerMassZ += z.toDouble()

            if (haveColor) {
                colors.add(tempBytes[rOffset].toInt().toFloat() / 255f)
                colors.add(tempBytes[rOffset].toInt().toFloat() / 255f)
                colors.add(tempBytes[rOffset].toInt().toFloat() / 255f)
                if (alphaOffset >= 0) {
                    colors.add(tempBytes[alphaOffset].toInt().toFloat() / 255f)
                } else {
                    colors.add(255f)
                }
            } else {
                colors.add(pointColor[0])
                colors.add(pointColor[1])
                colors.add(pointColor[2])
                colors.add(255f)
            }
        }
        this.centerMassX = (centerMassX / vertexCount).toFloat()
        this.centerMassY = (centerMassY / vertexCount).toFloat()
        this.centerMassZ = (centerMassZ / vertexCount).toFloat()
    }

    override fun draw(viewMatrix: FloatArray, projectionMatrix: FloatArray, light: Light) {
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
        GLES20.glUniform3fv(ambientColorHandle, 1, pointColor, 0)
        GLES20.glDrawArrays(GLES20.GL_POINTS, 0, vertexCount)
        GLES20.glDisableVertexAttribArray(positionHandle)
    }

    private fun typeToSize(typeStr: String): Int {
        return when (typeStr) {
            "char" -> 1
            "uchar" -> 1
            "short" -> 2
            "ushort" -> 2
            "int" -> 4
            "uint" -> 4
            "long" -> 8
            "ulong" -> 8
            "float" -> 4
            "double" -> 8
            else -> 0
        }
    }
}
