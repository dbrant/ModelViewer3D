package com.dmitrybrant.modelviewer

import android.opengl.GLES20
import android.opengl.Matrix
import com.dmitrybrant.modelviewer.util.Util.compileProgram
import java.nio.ByteBuffer
import java.nio.ByteOrder

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
class Floor : ArrayModel() {
    private val floorColor = floatArrayOf(0.2f, 0.2f, 0.2f, 0.5f)
    private val lineColor = floatArrayOf(0.6f, 0.6f, 0.6f, 0.5f)
    private var extent = 0f
    override fun init(boundSize: Float) {
        extent = boundSize * 5.0f

        // The grid lines on the floor are rendered procedurally and large polygons cause floating point
        // precision problems on some architectures. So we split the floor into 4 quadrants.
        val coords = floatArrayOf( // +X, +Z quadrant
                extent, 0f, 0f, 0f, 0f, 0f, 0f, 0f, extent,
                extent, 0f, 0f, 0f, 0f, extent,
                extent, 0f, extent, 0f, 0f, 0f,
                -extent, 0f, 0f,
                -extent, 0f, extent, 0f, 0f, 0f,
                -extent, 0f, extent, 0f, 0f, extent,  // +X, -Z quadrant
                extent, 0f, -extent, 0f, 0f, -extent, 0f, 0f, 0f,
                extent, 0f, -extent, 0f, 0f, 0f,
                extent, 0f, 0f, 0f, 0f, -extent,
                -extent, 0f, -extent,
                -extent, 0f, 0f, 0f, 0f, -extent,
                -extent, 0f, 0f, 0f, 0f, 0f)
        val normals = FloatArray(coords.size)
        var i = 0
        while (i < normals.size) {
            normals[i] = 0.0f
            normals[i + 1] = 1.0f
            normals[i + 2] = 0.0f
            i += 3
        }
        minX = -extent
        maxX = extent
        minY = -extent
        maxY = extent
        minZ = -extent
        maxZ = extent
        vertexCount = coords.size / COORDS_PER_VERTEX
        var vbb = ByteBuffer.allocateDirect(coords.size * BYTES_PER_FLOAT)
        vbb.order(ByteOrder.nativeOrder())
        vertexBuffer = vbb.asFloatBuffer()
        vertexBuffer.put(coords)
        vertexBuffer.position(0)
        vbb = ByteBuffer.allocateDirect(normals.size * BYTES_PER_FLOAT)
        vbb.order(ByteOrder.nativeOrder())
        normalBuffer = vbb.asFloatBuffer()
        normalBuffer.put(normals)
        normalBuffer.position(0)
        if (GLES20.glIsProgram(glProgram)) {
            GLES20.glDeleteProgram(glProgram)
            glProgram = -1
        }
        glProgram = compileProgram(R.raw.floor_vertex, R.raw.floor_fragment, arrayOf("a_Position", "a_Normal"))
        Matrix.setIdentityM(modelMatrix, 0)
    }

    fun setOffsetY(y: Float) {
        Matrix.translateM(modelMatrix, 0, 0.0f, y, 0.0f)
    }

    override fun draw(viewMatrix: FloatArray?, projectionMatrix: FloatArray?, light: Light) {
        if (vertexBuffer == null || normalBuffer == null) {
            return
        }
        GLES20.glUseProgram(glProgram)
        val modelMatrixHandle = GLES20.glGetUniformLocation(glProgram, "u_Model")
        val mvpMatrixHandle = GLES20.glGetUniformLocation(glProgram, "u_MVP")
        val positionHandle = GLES20.glGetAttribLocation(glProgram, "a_Position")
        val normalHandle = GLES20.glGetAttribLocation(glProgram, "a_Normal")
        val floorColorHandle = GLES20.glGetUniformLocation(glProgram, "u_FloorColor")
        val lineColorHandle = GLES20.glGetUniformLocation(glProgram, "u_LineColor")
        val maxDepthHandle = GLES20.glGetUniformLocation(glProgram, "u_MaxDepth")
        val gridUnitHandle = GLES20.glGetUniformLocation(glProgram, "u_GridUnit")
        GLES20.glEnableVertexAttribArray(positionHandle)
        GLES20.glVertexAttribPointer(positionHandle, COORDS_PER_VERTEX, GLES20.GL_FLOAT, false,
                VERTEX_STRIDE, vertexBuffer)
        GLES20.glEnableVertexAttribArray(normalHandle)
        GLES20.glVertexAttribPointer(normalHandle, COORDS_PER_VERTEX, GLES20.GL_FLOAT, false,
                VERTEX_STRIDE, normalBuffer)
        GLES20.glUniformMatrix4fv(modelMatrixHandle, 1, false, modelMatrix, 0)
        Matrix.multiplyMM(mvMatrix, 0, viewMatrix, 0, modelMatrix, 0)
        Matrix.multiplyMM(mvpMatrix, 0, projectionMatrix, 0, mvMatrix, 0)
        GLES20.glUniformMatrix4fv(mvpMatrixHandle, 1, false, mvpMatrix, 0)
        GLES20.glUniform4fv(floorColorHandle, 1, floorColor, 0)
        GLES20.glUniform4fv(lineColorHandle, 1, lineColor, 0)
        GLES20.glUniform1f(maxDepthHandle, extent)
        GLES20.glUniform1f(gridUnitHandle, extent / 75.0f)
        GLES20.glDrawArrays(GLES20.GL_TRIANGLES, 0, vertexCount)
        GLES20.glDisableVertexAttribArray(normalHandle)
        GLES20.glDisableVertexAttribArray(positionHandle)
    }
}