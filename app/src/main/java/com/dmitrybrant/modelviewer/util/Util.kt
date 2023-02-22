package com.dmitrybrant.modelviewer.util

import android.opengl.GLES20
import android.util.Log
import androidx.annotation.RawRes
import com.dmitrybrant.modelviewer.ModelViewerApplication
import java.io.Closeable
import java.io.IOException

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
object Util {
    fun compileProgram(@RawRes vertexShader: Int, @RawRes fragmentShader: Int, attributes: Array<String>): Int {
        val program = GLES20.glCreateProgram()
        GLES20.glAttachShader(program, loadShader(GLES20.GL_VERTEX_SHADER, readTextFileFromRawRes(vertexShader)))
        GLES20.glAttachShader(program, loadShader(GLES20.GL_FRAGMENT_SHADER, readTextFileFromRawRes(fragmentShader)))
        for (i in attributes.indices) {
            GLES20.glBindAttribLocation(program, i, attributes[i])
        }
        GLES20.glLinkProgram(program)
        return program
    }

    fun checkGLError(label: String) {
        var error: Int
        if (GLES20.glGetError().also { error = it } != GLES20.GL_NO_ERROR) {
            throw RuntimeException("$label: glError $error")
        }
    }

    fun closeSilently(c: Closeable?) {
        try {
            c?.close()
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    fun readIntLe(bytes: ByteArray, offset: Int): Int {
        return (bytes[offset].toInt() and 0xff) or
                (bytes[offset + 1].toInt() and 0xff shl 8) or
                (bytes[offset + 2].toInt() and 0xff shl 16) or
                (bytes[offset + 3].toInt() and 0xff shl 24)
    }

    fun readLongLe(bytes: ByteArray, offset: Int): Long {
        return (bytes[offset].toLong() and 0xff) or
                (bytes[offset + 1].toLong() and 0xff shl 8) or
                (bytes[offset + 2].toLong() and 0xff shl 16) or
                (bytes[offset + 3].toLong() and 0xff shl 24) or
                (bytes[offset + 4].toLong() and 0xff shl 32) or
                (bytes[offset + 5].toLong() and 0xff shl 40) or
                (bytes[offset + 6].toLong() and 0xff shl 48) or
                (bytes[offset + 7].toLong() and 0xff shl 56)
    }

    fun calculateNormal(x1: Float, y1: Float, z1: Float,
                        x2: Float, y2: Float, z2: Float,
                        x3: Float, y3: Float, z3: Float,
                        normal: FloatArray) {
        normal[0] = (y2 - y1) * (z3 - z1) - (y3 - y1) * (z2 - z1)
        normal[1] = (z2 - z1) * (x3 - x1) - (x2 - x1) * (z3 - z1)
        normal[2] = (x2 - x1) * (y3 - y1) - (x3 - x1) * (y2 - y1)
    }

    fun pxToDp(px: Float): Float {
        return px / densityScalar
    }

    private val densityScalar: Float
        get() = ModelViewerApplication.instance.resources.displayMetrics.density

    private fun loadShader(type: Int, shaderCode: String): Int {
        val shader = GLES20.glCreateShader(type)
        GLES20.glShaderSource(shader, shaderCode)
        GLES20.glCompileShader(shader)

        // Get the compilation status.
        val compileStatus = IntArray(1)
        GLES20.glGetShaderiv(shader, GLES20.GL_COMPILE_STATUS, compileStatus, 0)
        // If the compilation fails, delete the shader.
        if (compileStatus[0] == 0) {
            Log.e("loadShader", "Error compiling shader: " + GLES20.glGetShaderInfoLog(shader))
            GLES20.glDeleteShader(shader)
            throw RuntimeException("Shader compilation failed.")
        }
        return shader
    }

    private fun readTextFileFromRawRes(@RawRes resourceId: Int): String {
        val inputStream = ModelViewerApplication.instance.resources.openRawResource(resourceId)
        try {
            val bytes = ByteArray(inputStream.available())
            inputStream.read(bytes)
            return String(bytes)
        } catch (e: IOException) {
            e.printStackTrace()
        } finally {
            closeSilently(inputStream)
        }
        throw RuntimeException("Failed to read raw resource id $resourceId")
    }
}
