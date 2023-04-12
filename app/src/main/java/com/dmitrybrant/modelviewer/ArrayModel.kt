package com.dmitrybrant.modelviewer

import android.opengl.GLES20
import android.opengl.Matrix
import com.dmitrybrant.modelviewer.util.Util.compileProgram
import java.nio.FloatBuffer

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
open class ArrayModel : Model() {
    // Vertices, normals will be populated by subclasses
    var vertexCount = 0
        protected set

    protected var vertexBuffer: FloatBuffer? = null
    protected var normalBuffer: FloatBuffer? = null
    protected var colorBuffer: FloatBuffer? = null
    protected var useColorBuffer = false

    override fun init(boundSize: Float) {
        if (GLES20.glIsProgram(glProgram)) {
            GLES20.glDeleteProgram(glProgram)
            glProgram = -1
        }
        glProgram = if (useColorBuffer) {
            compileProgram(R.raw.model_vertex_color, R.raw.model_fragment_color, arrayOf("a_Position", "a_Normal", "a_Color"))
        } else {
            compileProgram(R.raw.model_vertex, R.raw.single_light_fragment, arrayOf("a_Position", "a_Normal"))
        }
        super.init(boundSize)
    }

    override fun draw(viewMatrix: FloatArray, projectionMatrix: FloatArray, light: Light) {
        if (vertexBuffer == null || normalBuffer == null) {
            return
        }
        GLES20.glUseProgram(glProgram)

        val mvpMatrixHandle = GLES20.glGetUniformLocation(glProgram, "u_MVP")
        val positionHandle = GLES20.glGetAttribLocation(glProgram, "a_Position")
        val normalHandle = GLES20.glGetAttribLocation(glProgram, "a_Normal")
        val lightPosHandle = GLES20.glGetUniformLocation(glProgram, "u_LightPos")
        val ambientColorHandle = GLES20.glGetUniformLocation(glProgram, "u_ambientColor")
        val diffuseColorHandle = GLES20.glGetUniformLocation(glProgram, "u_diffuseColor")
        val specularColorHandle = GLES20.glGetUniformLocation(glProgram, "u_specularColor")
        var colorHandle = -1

        GLES20.glEnableVertexAttribArray(positionHandle)
        GLES20.glVertexAttribPointer(positionHandle, COORDS_PER_VERTEX, GLES20.GL_FLOAT, false,
                VERTEX_STRIDE, vertexBuffer)
        GLES20.glEnableVertexAttribArray(normalHandle)
        GLES20.glVertexAttribPointer(normalHandle, COORDS_PER_VERTEX, GLES20.GL_FLOAT, false,
                VERTEX_STRIDE, normalBuffer)

        if (colorBuffer != null) {
            colorHandle = GLES20.glGetAttribLocation(glProgram, "a_Color")
            GLES20.glEnableVertexAttribArray(colorHandle)
            GLES20.glVertexAttribPointer(colorHandle, 4, GLES20.GL_FLOAT, false, 4 * BYTES_PER_FLOAT, colorBuffer)
        }

        Matrix.multiplyMM(mvMatrix, 0, viewMatrix, 0, modelMatrix, 0)
        Matrix.multiplyMM(mvpMatrix, 0, projectionMatrix, 0, mvMatrix, 0)

        GLES20.glUniformMatrix4fv(mvpMatrixHandle, 1, false, mvpMatrix, 0)
        GLES20.glUniform3fv(lightPosHandle, 1, light.positionInEyeSpace, 0)
        if (ambientColorHandle >= 0) {
            GLES20.glUniform4fv(ambientColorHandle, 1, light.ambientColor, 0)
        }
        if (diffuseColorHandle >= 0) {
            GLES20.glUniform4fv(diffuseColorHandle, 1, light.diffuseColor, 0)
        }
        GLES20.glUniform4fv(specularColorHandle, 1, light.specularColor, 0)

        drawFunc()

        if (colorHandle >= 0) {
            GLES20.glDisableVertexAttribArray(colorHandle)
        }
        GLES20.glDisableVertexAttribArray(normalHandle)
        GLES20.glDisableVertexAttribArray(positionHandle)
    }

    protected open fun drawFunc() {
        GLES20.glDrawArrays(GLES20.GL_TRIANGLES, 0, vertexCount)
    }

    companion object {
        const val BYTES_PER_FLOAT = 4
        const val COORDS_PER_VERTEX = 3
        const val VERTEX_STRIDE = COORDS_PER_VERTEX * BYTES_PER_FLOAT
        const val INPUT_BUFFER_SIZE = 0x10000
    }
}
