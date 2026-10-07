package com.dmitrybrant.modelviewer

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.opengl.GLES20
import android.opengl.Matrix
import android.util.Log
import com.dmitrybrant.modelviewer.util.Util.compileProgram
import java.nio.Buffer
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.nio.IntBuffer

/*
* Copyright 2017-2026 Dmitry Brant. All rights reserved.
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

/**
 * A triangle mesh, with optional vertex colors, texture coordinates, and materials, which are
 * populated by subclasses. The mesh is uploaded to the GPU when it's set up, and drawn from there.
 *
 * Meshes that have only their shape (no colors or materials) are drawn with the app's default
 * lighting and colors.
 */
open class MeshModel : Model() {
    var vertexCount = 0
        protected set

    /** Vertex positions (3 floats per vertex). */
    var vertexBuffer: FloatBuffer? = null
        protected set

    /** Vertex normals (3 floats per vertex). */
    var normalBuffer: FloatBuffer? = null
        protected set

    /** Optional vertex colors (RGBA, 4 floats per vertex). */
    var colorBuffer: FloatBuffer? = null
        protected set

    /** Optional texture coordinates (2 floats per vertex), with the origin at the bottom left of the texture. */
    var texCoordBuffer: FloatBuffer? = null
        protected set

    /** Optional triangle indices (3 per triangle). Without them, every three consecutive vertices form a triangle. */
    var indexBuffer: IntBuffer? = null
        protected set
    var indexCount = 0
        protected set

    /** Parts of the mesh that have their own materials. If empty, the whole mesh is drawn with one default material. */
    var parts: List<MeshPart> = emptyList()
        protected set

    /** Whether the model has any surface properties besides its shape. */
    val hasMaterials get() = colorBuffer != null || parts.isNotEmpty()

    // GL objects, which belong to the context in which setup() was last called.
    private val bufferIds = IntArray(BUFFER_COUNT)
    private val textureIds = HashMap<Material, Int>()
    private val transparentTextures = HashSet<Material>()
    private var defaultMaterial: Material? = null

    override fun setup(boundSize: Float) {
        if (GLES20.glIsProgram(glProgram)) {
            GLES20.glDeleteProgram(glProgram)
            glProgram = -1
        }
        glProgram = if (hasMaterials) {
            compileProgram(R.raw.model_vertex_material, R.raw.model_fragment_material, arrayOf("a_Position", "a_Normal", "a_Color", "a_TexCoord"))
        } else {
            compileProgram(R.raw.model_vertex, R.raw.single_light_fragment, arrayOf("a_Position", "a_Normal"))
        }

        // This is called whenever a new GL context is created, so any previous buffers and textures are already gone.
        bufferIds.fill(0)
        uploadBuffer(BUFFER_POSITION, GLES20.GL_ARRAY_BUFFER, vertexBuffer)
        uploadBuffer(BUFFER_NORMAL, GLES20.GL_ARRAY_BUFFER, normalBuffer)
        uploadBuffer(BUFFER_COLOR, GLES20.GL_ARRAY_BUFFER, colorBuffer)
        uploadBuffer(BUFFER_TEXCOORD, GLES20.GL_ARRAY_BUFFER, texCoordBuffer)
        uploadBuffer(BUFFER_INDEX, GLES20.GL_ELEMENT_ARRAY_BUFFER, indexBuffer)

        textureIds.clear()
        transparentTextures.clear()
        if (texCoordBuffer != null) {
            for (material in parts.map { it.material }.distinct()) {
                material.diffuseTexture?.let { uploadTexture(material, it) }
            }
        }
        super.setup(boundSize)
    }

    private fun uploadBuffer(index: Int, target: Int, buffer: Buffer?) {
        if (buffer == null) {
            return
        }
        val ids = IntArray(1)
        GLES20.glGenBuffers(1, ids, 0)
        bufferIds[index] = ids[0]
        GLES20.glBindBuffer(target, ids[0])
        GLES20.glBufferData(target, buffer.capacity() * 4, buffer, GLES20.GL_STATIC_DRAW)
        GLES20.glBindBuffer(target, 0)
    }

    private fun uploadTexture(material: Material, data: ByteArray) {
        val maxSize = IntArray(1)
        GLES20.glGetIntegerv(GLES20.GL_MAX_TEXTURE_SIZE, maxSize, 0)
        val options = BitmapFactory.Options()
        options.inJustDecodeBounds = true
        BitmapFactory.decodeByteArray(data, 0, data.size, options)
        if (options.outWidth <= 0 || options.outHeight <= 0) {
            Log.w(TAG, "Unable to decode texture: ${material.diffuseTextureName}")
            return
        }
        // Reduce textures that are too large for the GPU.
        var sampleSize = 1
        while (options.outWidth / sampleSize > maxSize[0] || options.outHeight / sampleSize > maxSize[0]) {
            sampleSize *= 2
        }
        options.inJustDecodeBounds = false
        options.inSampleSize = sampleSize
        options.inPreferredConfig = Bitmap.Config.ARGB_8888
        // Keep the colors of transparent pixels intact, for proper blending.
        options.inPremultiplied = false
        var bitmap = BitmapFactory.decodeByteArray(data, 0, data.size, options) ?: return

        // Without an extension, OpenGL ES 2.0 can only repeat and mipmap textures whose sizes are powers of two.
        val extensions = GLES20.glGetString(GLES20.GL_EXTENSIONS).orEmpty()
        if (!extensions.contains("GL_OES_texture_npot")) {
            val width = nextPowerOfTwo(bitmap.width).coerceAtMost(maxSize[0])
            val height = nextPowerOfTwo(bitmap.height).coerceAtMost(maxSize[0])
            if (width != bitmap.width || height != bitmap.height) {
                val scaled = Bitmap.createScaledBitmap(bitmap, width, height, true)
                bitmap.recycle()
                bitmap = scaled
            }
        }
        if (bitmap.config != Bitmap.Config.ARGB_8888) {
            val converted = bitmap.copy(Bitmap.Config.ARGB_8888, false)
            bitmap.recycle()
            bitmap = converted
        }
        val pixels = ByteBuffer.allocateDirect(bitmap.byteCount).order(ByteOrder.nativeOrder())
        bitmap.copyPixelsToBuffer(pixels)
        val width = bitmap.width
        val height = bitmap.height
        bitmap.recycle()

        // ARGB_8888 pixels are stored as RGBA bytes.
        var hasTransparency = false
        for (i in 3 until pixels.capacity() step 4) {
            if (pixels.get(i) != 0xff.toByte()) {
                hasTransparency = true
                break
            }
        }
        pixels.position(0)

        val ids = IntArray(1)
        GLES20.glGenTextures(1, ids, 0)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, ids[0])
        GLES20.glPixelStorei(GLES20.GL_UNPACK_ALIGNMENT, 1)
        GLES20.glTexImage2D(GLES20.GL_TEXTURE_2D, 0, GLES20.GL_RGBA, width, height, 0, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, pixels)
        GLES20.glGenerateMipmap(GLES20.GL_TEXTURE_2D)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR_MIPMAP_LINEAR)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_REPEAT)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_REPEAT)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, 0)
        textureIds[material] = ids[0]
        if (hasTransparency) {
            transparentTextures.add(material)
        }
    }

    override fun draw(viewMatrix: FloatArray, projectionMatrix: FloatArray, light: Light) {
        if (bufferIds[BUFFER_POSITION] == 0 || bufferIds[BUFFER_NORMAL] == 0) {
            return
        }
        GLES20.glUseProgram(glProgram)

        val positionHandle = GLES20.glGetAttribLocation(glProgram, "a_Position")
        val normalHandle = GLES20.glGetAttribLocation(glProgram, "a_Normal")
        bindAttribute(positionHandle, BUFFER_POSITION, 3)
        bindAttribute(normalHandle, BUFFER_NORMAL, 3)

        Matrix.multiplyMM(mvMatrix, 0, viewMatrix, 0, modelMatrix, 0)
        Matrix.multiplyMM(mvpMatrix, 0, projectionMatrix, 0, mvMatrix, 0)
        GLES20.glUniformMatrix4fv(GLES20.glGetUniformLocation(glProgram, "u_MVP"), 1, false, mvpMatrix, 0)
        GLES20.glUniform3fv(GLES20.glGetUniformLocation(glProgram, "u_LightPos"), 1, light.positionInEyeSpace, 0)

        if (!hasMaterials) {
            GLES20.glUniform4fv(GLES20.glGetUniformLocation(glProgram, "u_ambientColor"), 1, light.ambientColor, 0)
            GLES20.glUniform4fv(GLES20.glGetUniformLocation(glProgram, "u_diffuseColor"), 1, light.diffuseColor, 0)
            GLES20.glUniform4fv(GLES20.glGetUniformLocation(glProgram, "u_specularColor"), 1, light.specularColor, 0)
            drawRange(0, if (indexBuffer != null) indexCount else vertexCount)
        } else {
            val colorHandle = GLES20.glGetAttribLocation(glProgram, "a_Color")
            val texCoordHandle = GLES20.glGetAttribLocation(glProgram, "a_TexCoord")
            // Attributes that the mesh doesn't have get constant values instead.
            if (!bindAttribute(colorHandle, BUFFER_COLOR, 4) && colorHandle >= 0) {
                GLES20.glVertexAttrib4f(colorHandle, 1f, 1f, 1f, 1f)
            }
            if (!bindAttribute(texCoordHandle, BUFFER_TEXCOORD, 2) && texCoordHandle >= 0) {
                GLES20.glVertexAttrib2f(texCoordHandle, 0f, 0f)
            }
            GLES20.glUniform1i(GLES20.glGetUniformLocation(glProgram, "u_Texture"), 0)

            val parts = this.parts.ifEmpty {
                val material = defaultMaterial ?: Material.forVertexColors().also { defaultMaterial = it }
                listOf(MeshPart(material, 0, if (indexBuffer != null) indexCount else vertexCount))
            }
            // Draw opaque parts first, and then blend transparent parts over them.
            for (part in parts) {
                if (!isTransparent(part.material)) {
                    drawPart(part)
                }
            }
            if (parts.any { isTransparent(it.material) }) {
                GLES20.glEnable(GLES20.GL_BLEND)
                GLES20.glBlendFunc(GLES20.GL_SRC_ALPHA, GLES20.GL_ONE_MINUS_SRC_ALPHA)
                GLES20.glDepthMask(false)
                for (part in parts) {
                    if (isTransparent(part.material)) {
                        drawPart(part)
                    }
                }
                GLES20.glDepthMask(true)
                GLES20.glDisable(GLES20.GL_BLEND)
            }
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, 0)
            if (colorHandle >= 0) GLES20.glDisableVertexAttribArray(colorHandle)
            if (texCoordHandle >= 0) GLES20.glDisableVertexAttribArray(texCoordHandle)
        }
        GLES20.glDisableVertexAttribArray(normalHandle)
        GLES20.glDisableVertexAttribArray(positionHandle)
    }

    private fun isTransparent(material: Material) = material.opacity < 1f || transparentTextures.contains(material)

    private fun drawPart(part: MeshPart) {
        val material = part.material
        GLES20.glUniform4f(GLES20.glGetUniformLocation(glProgram, "u_DiffuseColor"),
            material.diffuseColor[0], material.diffuseColor[1], material.diffuseColor[2], material.opacity)
        GLES20.glUniform3fv(GLES20.glGetUniformLocation(glProgram, "u_SpecularColor"), 1, material.specularColor, 0)
        GLES20.glUniform3fv(GLES20.glGetUniformLocation(glProgram, "u_EmissiveColor"), 1, material.emissiveColor, 0)
        GLES20.glUniform1f(GLES20.glGetUniformLocation(glProgram, "u_Shininess"), material.shininess.coerceIn(1f, 1000f))
        GLES20.glUniform1f(GLES20.glGetUniformLocation(glProgram, "u_Lighting"), material.lighting.toFloat())
        GLES20.glUniform2fv(GLES20.glGetUniformLocation(glProgram, "u_TexScale"), 1, material.textureScale, 0)
        GLES20.glUniform2fv(GLES20.glGetUniformLocation(glProgram, "u_TexOffset"), 1, material.textureOffset, 0)
        val textureId = textureIds[material]
        GLES20.glUniform1f(GLES20.glGetUniformLocation(glProgram, "u_UseTexture"), if (textureId != null) 1f else 0f)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, textureId ?: 0)
        drawRange(part.first, part.count)
    }

    private fun drawRange(first: Int, count: Int) {
        if (count <= 0) {
            return
        }
        if (bufferIds[BUFFER_INDEX] != 0) {
            GLES20.glBindBuffer(GLES20.GL_ELEMENT_ARRAY_BUFFER, bufferIds[BUFFER_INDEX])
            GLES20.glDrawElements(GLES20.GL_TRIANGLES, count, GLES20.GL_UNSIGNED_INT, first * BYTES_PER_INT)
            GLES20.glBindBuffer(GLES20.GL_ELEMENT_ARRAY_BUFFER, 0)
        } else {
            GLES20.glDrawArrays(GLES20.GL_TRIANGLES, first, count)
        }
    }

    /** Binds a vertex attribute to one of the uploaded buffers, and returns false if there's no such buffer. */
    private fun bindAttribute(handle: Int, bufferIndex: Int, size: Int): Boolean {
        if (handle < 0 || bufferIds[bufferIndex] == 0) {
            if (handle >= 0) GLES20.glDisableVertexAttribArray(handle)
            return false
        }
        GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, bufferIds[bufferIndex])
        GLES20.glEnableVertexAttribArray(handle)
        GLES20.glVertexAttribPointer(handle, size, GLES20.GL_FLOAT, false, size * BYTES_PER_FLOAT, 0)
        GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, 0)
        return true
    }

    companion object {
        private const val TAG = "MeshModel"
        const val BYTES_PER_FLOAT = 4
        const val BYTES_PER_INT = 4
        const val COORDS_PER_VERTEX = 3
        const val VERTEX_STRIDE = COORDS_PER_VERTEX * BYTES_PER_FLOAT
        const val INPUT_BUFFER_SIZE = 0x10000

        private const val BUFFER_POSITION = 0
        private const val BUFFER_NORMAL = 1
        private const val BUFFER_COLOR = 2
        private const val BUFFER_TEXCOORD = 3
        private const val BUFFER_INDEX = 4
        private const val BUFFER_COUNT = 5

        private fun nextPowerOfTwo(n: Int) = if (n <= 1) 1 else Integer.highestOneBit(n - 1) shl 1

        fun allocateFloats(values: FloatArray, count: Int = values.size): FloatBuffer {
            return ByteBuffer.allocateDirect(count * BYTES_PER_FLOAT).order(ByteOrder.nativeOrder()).asFloatBuffer().apply {
                put(values, 0, count)
                position(0)
            }
        }

        fun allocateInts(values: IntArray, count: Int = values.size): IntBuffer {
            return ByteBuffer.allocateDirect(count * BYTES_PER_INT).order(ByteOrder.nativeOrder()).asIntBuffer().apply {
                put(values, 0, count)
                position(0)
            }
        }
    }
}
