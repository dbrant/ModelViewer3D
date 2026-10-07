package com.dmitrybrant.modelviewer.vdb

import android.opengl.GLES30
import android.opengl.Matrix
import android.util.Log
import com.dmitrybrant.modelviewer.Light
import com.dmitrybrant.modelviewer.MeshModel
import com.dmitrybrant.modelviewer.Model
import com.dmitrybrant.modelviewer.R
import com.dmitrybrant.modelviewer.util.Util.compileProgram
import java.nio.ByteBuffer
import java.nio.FloatBuffer

/*
* Displays an OpenVDB fog volume (such as smoke or a cloud) by ray marching through its density
* on the GPU, which shows all of its soft, semi-transparent detail. The volume is lit from above,
* with shadows that it casts on itself, and from the direction of the camera. If the volume has a
* temperature or flames, its hot parts glow with the colors of fire.
*
* Copyright 2026 Dmitry Brant. All rights reserved.
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
class VdbVolumeModel(private val volume: VolumeTexture, val gridName: String, val colorGridName: String?,
                     val glowGridName: String?) : Model() {
    // GL objects, which belong to the context in which setup() was last called.
    private var volumeTexture = 0
    private var colorTexture = 0
    private var fireRampTexture = 0
    private var cubeBuffer: FloatBuffer? = null

    // Properties of the texture that was uploaded, which may have a lower resolution than the volume.
    private val textureSize = FloatArray(3)
    /** Transform from texture coordinates to world coordinates (column-major). */
    private val textureMatrix = FloatArray(16)
    /** The linear part of [textureMatrix]. */
    private val textureToWorld = FloatArray(9)
    private var isMirrored = false

    private val modelTextureMatrix = FloatArray(16)
    private val inverseMatrix = FloatArray(16)

    init {
        centerMassX = volume.centroid[0].toFloat()
        centerMassY = volume.centroid[1].toFloat()
        centerMassZ = volume.centroid[2].toFloat()
    }

    override fun setup(boundSize: Float) {
        if (GLES30.glIsProgram(glProgram)) {
            GLES30.glDeleteProgram(glProgram)
            glProgram = -1
        }
        glProgram = compileProgram(R.raw.volume_vertex, R.raw.volume_fragment, arrayOf("a_Position"))

        // Reduce the resolution of a volume that's too large for the GPU.
        val maxSize = IntArray(1)
        GLES30.glGetIntegerv(GLES30.GL_MAX_3D_TEXTURE_SIZE, maxSize, 0)
        val limit = if (maxSize[0] > 0) maxSize[0] else Int.MAX_VALUE
        var texture = volume
        while (maxOf(texture.sizeX, texture.sizeY, texture.sizeZ) > limit) {
            texture = texture.downsampled()
        }
        // This is called whenever a new GL context is created, so any previous textures are already gone.
        volumeTexture = if (texture.channels == 3) {
            uploadTexture(texture, texture.texels, GLES30.GL_RGB8, GLES30.GL_RGB)
        } else {
            uploadTexture(texture, texture.texels, GLES30.GL_RG8, GLES30.GL_RG)
        }
        colorTexture = texture.color?.let { uploadTexture(texture, it, GLES30.GL_SRGB8, GLES30.GL_RGB) } ?: 0
        fireRampTexture = if (texture.glow != VolumeTexture.Glow.NONE) uploadFireRamp() else 0
        setTextureTransform(texture)
        if (cubeBuffer == null) {
            cubeBuffer = MeshModel.allocateFloats(CUBE_VERTICES)
        }
        super.setup(boundSize)
    }

    private fun uploadTexture(texture: VolumeTexture, data: ByteBuffer, internalFormat: Int, format: Int): Int {
        val ids = IntArray(1)
        GLES30.glGenTextures(1, ids, 0)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_3D, ids[0])
        GLES30.glPixelStorei(GLES30.GL_UNPACK_ALIGNMENT, 1)
        data.position(0)
        GLES30.glTexImage3D(GLES30.GL_TEXTURE_3D, 0, internalFormat, texture.sizeX, texture.sizeY, texture.sizeZ, 0,
            format, GLES30.GL_UNSIGNED_BYTE, data)
        val error = GLES30.glGetError()
        if (error != GLES30.GL_NO_ERROR) {
            Log.e(TAG, "Unable to upload ${texture.sizeX}x${texture.sizeY}x${texture.sizeZ} texture: error $error")
        }
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_3D, GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_LINEAR)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_3D, GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_LINEAR)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_3D, GLES30.GL_TEXTURE_WRAP_S, GLES30.GL_CLAMP_TO_EDGE)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_3D, GLES30.GL_TEXTURE_WRAP_T, GLES30.GL_CLAMP_TO_EDGE)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_3D, GLES30.GL_TEXTURE_WRAP_R, GLES30.GL_CLAMP_TO_EDGE)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_3D, 0)
        return ids[0]
    }

    private fun uploadFireRamp(): Int {
        val ids = IntArray(1)
        GLES30.glGenTextures(1, ids, 0)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, ids[0])
        GLES30.glPixelStorei(GLES30.GL_UNPACK_ALIGNMENT, 1)
        val ramp = VolumeTexture.FIRE_RAMP
        val pixels = ByteBuffer.allocateDirect(ramp.size).put(ramp)
        pixels.position(0)
        GLES30.glTexImage2D(GLES30.GL_TEXTURE_2D, 0, GLES30.GL_SRGB8, ramp.size / 3, 1, 0,
            GLES30.GL_RGB, GLES30.GL_UNSIGNED_BYTE, pixels)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_LINEAR)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_LINEAR)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_S, GLES30.GL_CLAMP_TO_EDGE)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_T, GLES30.GL_CLAMP_TO_EDGE)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, 0)
        return ids[0]
    }

    /**
     * Sets up the transform from texture coordinates to world coordinates, and the bounds of the
     * volume's box, for the given texture.
     */
    private fun setTextureTransform(texture: VolumeTexture) {
        textureSize[0] = texture.sizeX.toFloat()
        textureSize[1] = texture.sizeY.toFloat()
        textureSize[2] = texture.sizeZ.toFloat()
        val m = texture.textureToWorld
        Matrix.setIdentityM(textureMatrix, 0)
        for (col in 0 until 3) {
            for (row in 0 until 3) {
                textureMatrix[col * 4 + row] = m[col * 3 + row].toFloat()
                textureToWorld[col * 3 + row] = m[col * 3 + row].toFloat()
            }
            textureMatrix[12 + col] = m[9 + col].toFloat()
        }
        isMirrored = VdbGrid.determinant3x3(m) < 0

        minX = Float.MAX_VALUE
        minY = Float.MAX_VALUE
        minZ = Float.MAX_VALUE
        maxX = -Float.MAX_VALUE
        maxY = -Float.MAX_VALUE
        maxZ = -Float.MAX_VALUE
        for (n in 0 until 8) {
            val corner = VolumeTexture.transform(m, (n and 1).toDouble(), ((n shr 1) and 1).toDouble(), ((n shr 2) and 1).toDouble())
            adjustMaxMin(corner[0].toFloat(), corner[1].toFloat(), corner[2].toFloat())
        }
    }

    public override fun initModelMatrix(boundSize: Float) {
        initModelMatrix(boundSize, 0.0f, 0.0f, 0.0f)
        var scale = getBoundScale(boundSize)
        if (scale == 0.0f) {
            scale = 1.0f
        }
        floorOffset = (minY - centerMassY) / scale
    }

    override fun draw(viewMatrix: FloatArray, projectionMatrix: FloatArray, light: Light) {
        if (volumeTexture == 0) {
            return
        }
        GLES30.glUseProgram(glProgram)

        Matrix.multiplyMM(modelTextureMatrix, 0, modelMatrix, 0, textureMatrix, 0)
        Matrix.multiplyMM(mvMatrix, 0, viewMatrix, 0, modelTextureMatrix, 0)
        Matrix.multiplyMM(mvpMatrix, 0, projectionMatrix, 0, mvMatrix, 0)
        // The camera is at the origin of eye space, so its position in texture coordinates is the
        // translation of the inverse transform.
        Matrix.invertM(inverseMatrix, 0, mvMatrix, 0)
        GLES30.glUniform3f(GLES30.glGetUniformLocation(glProgram, "u_CameraPos"),
            inverseMatrix[12] / inverseMatrix[15], inverseMatrix[13] / inverseMatrix[15], inverseMatrix[14] / inverseMatrix[15])
        GLES30.glUniformMatrix4fv(GLES30.glGetUniformLocation(glProgram, "u_MVP"), 1, false, mvpMatrix, 0)
        GLES30.glUniform3fv(GLES30.glGetUniformLocation(glProgram, "u_TextureSize"), 1, textureSize, 0)
        GLES30.glUniformMatrix3fv(GLES30.glGetUniformLocation(glProgram, "u_TextureToWorld"), 1, false, textureToWorld, 0)
        GLES30.glUniform1f(GLES30.glGetUniformLocation(glProgram, "u_DensityScale"), volume.maxDensity * VolumeTexture.EXTINCTION_SCALE)
        GLES30.glUniform1i(GLES30.glGetUniformLocation(glProgram, "u_HasColor"), if (colorTexture != 0) 1 else 0)
        val glow = volume.glow
        GLES30.glUniform1f(GLES30.glGetUniformLocation(glProgram, "u_Albedo"), if (glow != VolumeTexture.Glow.NONE) SMOKE_ALBEDO else 1f)
        GLES30.glUniform1f(GLES30.glGetUniformLocation(glProgram, "u_ThermalGlow"),
            if (glow == VolumeTexture.Glow.THERMAL) GLOW_BRIGHTNESS else 0f)
        // A ray through all of the flames, packed into a cube at full intensity, would glow at full
        // brightness, whatever the units of the volume.
        GLES30.glUniform1f(GLES30.glGetUniformLocation(glProgram, "u_FlameGlow"),
            if (glow == VolumeTexture.Glow.FLAME && volume.flameLength > 0) (GLOW_BRIGHTNESS / volume.flameLength).toFloat() else 0f)
        GLES30.glUniform1i(GLES30.glGetUniformLocation(glProgram, "u_Volume"), 0)
        GLES30.glUniform1i(GLES30.glGetUniformLocation(glProgram, "u_Color"), 1)
        GLES30.glUniform1i(GLES30.glGetUniformLocation(glProgram, "u_FireRamp"), 2)
        GLES30.glActiveTexture(GLES30.GL_TEXTURE2)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, fireRampTexture)
        GLES30.glActiveTexture(GLES30.GL_TEXTURE1)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_3D, colorTexture)
        GLES30.glActiveTexture(GLES30.GL_TEXTURE0)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_3D, volumeTexture)

        // Draw the back faces of the box, so that the rays through the volume are drawn even when
        // the camera is inside it. A transform that mirrors the box turns its faces inside out.
        GLES30.glCullFace(if (isMirrored) GLES30.GL_BACK else GLES30.GL_FRONT)
        // Blend the volume over what's behind it, which has premultiplied alpha.
        GLES30.glEnable(GLES30.GL_BLEND)
        GLES30.glBlendFunc(GLES30.GL_ONE, GLES30.GL_ONE_MINUS_SRC_ALPHA)
        GLES30.glDepthMask(false)
        // The bottom of the box lies on the floor, so it's moved slightly toward the camera to keep
        // the floor from hiding it.
        GLES30.glEnable(GLES30.GL_POLYGON_OFFSET_FILL)
        GLES30.glPolygonOffset(-1f, -2f)

        val positionHandle = GLES30.glGetAttribLocation(glProgram, "a_Position")
        GLES30.glEnableVertexAttribArray(positionHandle)
        GLES30.glVertexAttribPointer(positionHandle, 3, GLES30.GL_FLOAT, false, 3 * MeshModel.BYTES_PER_FLOAT, cubeBuffer)
        GLES30.glDrawArrays(GLES30.GL_TRIANGLES, 0, CUBE_VERTICES.size / 3)
        GLES30.glDisableVertexAttribArray(positionHandle)

        GLES30.glDisable(GLES30.GL_POLYGON_OFFSET_FILL)
        GLES30.glDepthMask(true)
        GLES30.glDisable(GLES30.GL_BLEND)
        GLES30.glCullFace(GLES30.GL_BACK)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_3D, 0)
        GLES30.glActiveTexture(GLES30.GL_TEXTURE1)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_3D, 0)
        GLES30.glActiveTexture(GLES30.GL_TEXTURE2)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, 0)
        GLES30.glActiveTexture(GLES30.GL_TEXTURE0)
    }

    companion object {
        private const val TAG = "VdbVolumeModel"

        /** The smoke of fire is sooty, which makes it much darker than a cloud. */
        private const val SMOKE_ALBEDO = 0.5f

        /** Brightness of the glow of the hottest parts of a volume, relative to the light that it reflects. */
        private const val GLOW_BRIGHTNESS = 4f

        /**
         * The faces of the unit cube, as quads whose corners are counterclockwise when seen from
         * outside. The bits of each corner are its x, y, and z coordinates, from highest to lowest.
         */
        private val CUBE_FACES = arrayOf(
            intArrayOf(0b000, 0b001, 0b011, 0b010), // -x
            intArrayOf(0b101, 0b100, 0b110, 0b111), // +x
            intArrayOf(0b000, 0b100, 0b101, 0b001), // -y
            intArrayOf(0b011, 0b111, 0b110, 0b010), // +y
            intArrayOf(0b000, 0b010, 0b110, 0b100), // -z
            intArrayOf(0b001, 0b101, 0b111, 0b011)) // +z

        /**
         * Triangles of the unit cube (which spans the volume's texture coordinates), with three
         * coordinates per vertex, and counterclockwise winding when seen from outside.
         */
        val CUBE_VERTICES = CUBE_FACES.flatMap { quad ->
            listOf(quad[0], quad[1], quad[2], quad[0], quad[2], quad[3]).flatMap { corner ->
                listOf((corner shr 2 and 1).toFloat(), (corner shr 1 and 1).toFloat(), (corner and 1).toFloat())
            }
        }.toFloatArray()
    }
}
