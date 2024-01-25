package com.dmitrybrant.modelviewer.obj

import android.graphics.BitmapFactory
import android.opengl.GLES20
import android.opengl.Matrix
import android.renderscript.Matrix4f
import android.text.TextUtils
import com.dmitrybrant.modelviewer.IndexedModel
import com.dmitrybrant.modelviewer.Light
import com.dmitrybrant.modelviewer.ModelViewerApplication
import com.dmitrybrant.modelviewer.R
import com.dmitrybrant.modelviewer.obj.bean.ObjectBean
import com.dmitrybrant.modelviewer.util.LogUtil
import com.dmitrybrant.modelviewer.util.Util
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer

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
class ObjModel(var objectBean: ObjectBean,var fileName : String = "nanosuit",var morethanOne:Boolean = true) : IndexedModel() {
    private var textCoorBuffer : FloatBuffer? = null
    init {
        readObjectBean(objectBean)
        if (vertexCount <= 0 || vertexBuffer == null || normalBuffer == null) {
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

    override fun setup(boundSize: Float) {
        if (GLES20.glIsProgram(glProgram)) {
            GLES20.glDeleteProgram(glProgram)
            glProgram = -1
        }
        glProgram = Util.compileProgram(
            R.raw.model_texture_load_vertex,
            R.raw.model_texture_load_fragment,
            arrayOf("aPosition", "aNormal", "aTexCoords")
        )
        initModelMatrix(boundSize)
    }


    private fun readObjectBean(objectBean: ObjectBean) {
        objectBean.apply {
            this@ObjModel.isMorethenOne = morethanOne
            this@ObjModel.maxX = maxX
            this@ObjModel.maxY = maxY
            this@ObjModel.maxZ = maxZ
            this@ObjModel.minX = minX
            this@ObjModel.minY = minY
            this@ObjModel.minZ = minZ
            vertexCount = aVertices!!.size / 3
            this.centerMassX = (centerMassX / vertexCount)
            this.centerMassY = (centerMassY / vertexCount)
            this.centerMassZ = (centerMassZ / vertexCount)

            var vbb = ByteBuffer.allocateDirect(aVertices!!.size * BYTES_PER_FLOAT)
            vbb.order(ByteOrder.nativeOrder())
            vertexBuffer = vbb.asFloatBuffer()
            vertexBuffer!!.put(aVertices!!)
            vertexBuffer!!.position(0)

            vbb = ByteBuffer.allocateDirect(aNormals!!.size * BYTES_PER_INT)
            vbb.order(ByteOrder.nativeOrder())
            normalBuffer = vbb.asFloatBuffer()
            normalBuffer!!.put(aNormals)
            normalBuffer!!.position(0)


            vbb = ByteBuffer.allocateDirect(aTexCoords!!.size * BYTES_PER_INT)
            vbb.order(ByteOrder.nativeOrder())
            textCoorBuffer = vbb.asFloatBuffer()
            textCoorBuffer!!.put(aTexCoords)
            textCoorBuffer!!.position(0)
        }
    }

    override fun draw(viewMatrix: FloatArray, projectionMatrix: FloatArray, light: Light) {
        GLES20.glUseProgram(glProgram)
        
        val positionHandle = GLES20.glGetAttribLocation(glProgram, "aPosition")
        val normalHandle = GLES20.glGetAttribLocation(glProgram, "aNormal")
        val textHandle = GLES20.glGetAttribLocation(glProgram, "aTexCoords")

        val mMVMatrixHandle = GLES20.glGetUniformLocation(glProgram, "uMVMatrix")
        val mMVPMatrixHandle = GLES20.glGetUniformLocation(glProgram, "uMVPMatrix")
        val mNormalPosHandle = GLES20.glGetUniformLocation(glProgram, "normalMatrix")

        Matrix.multiplyMM(mvMatrix, 0, viewMatrix, 0, modelMatrix, 0)
        GLES20.glUniformMatrix4fv(mMVMatrixHandle, 1, false, mvMatrix, 0)
        Matrix.multiplyMM(mvpMatrix, 0, projectionMatrix, 0, mvMatrix, 0)
        GLES20.glUniformMatrix4fv(mMVPMatrixHandle, 1, false, mvpMatrix, 0)

        val normalMatrix = Matrix4f()
        normalMatrix.loadMultiply(Matrix4f(viewMatrix), Matrix4f(modelMatrix))
        normalMatrix.inverse()
        normalMatrix.transpose()
        GLES20.glUniformMatrix4fv(mNormalPosHandle, 1, false, normalMatrix.array, 0)

        val materialAmbientPosHandle =
            GLES20.glGetUniformLocation(glProgram, "material.ambient")
        val materialDiffusePosHandle =
            GLES20.glGetUniformLocation(glProgram, "material.diffuse")
        val materialSpecularPosHandle =
            GLES20.glGetUniformLocation(glProgram, "material.specular")
        val materialShininessPosHandle =
            GLES20.glGetUniformLocation(glProgram, "material.shininess")
        val materialAlphaPosHandle = GLES20.glGetUniformLocation(glProgram, "material.alpha")

        val lightPosHandle = GLES20.glGetUniformLocation(glProgram, "light.position")
        val lightAmbientPosHandle = GLES20.glGetUniformLocation(glProgram, "light.ambient")
        val lightDiffusePosHandle = GLES20.glGetUniformLocation(glProgram, "light.diffuse")
        val lightSpecularPosHandle = GLES20.glGetUniformLocation(glProgram, "light.specular")
        GLES20.glUniform3f(
            lightPosHandle,
            light.positionInEyeSpace[0],
            light.positionInEyeSpace[0],
            light.positionInEyeSpace[0]
        )

        GLES20.glEnableVertexAttribArray(positionHandle)
        GLES20.glVertexAttribPointer(
            positionHandle, 3, GLES20.GL_FLOAT,
            false, 3 * 4, vertexBuffer
        )
        GLES20.glEnableVertexAttribArray(normalHandle)
        GLES20.glVertexAttribPointer(
            normalHandle, 3, GLES20.GL_FLOAT,
            false, 3 * 4, normalBuffer
        )
        GLES20.glEnableVertexAttribArray(textHandle)
        GLES20.glVertexAttribPointer(
            textHandle, 2, GLES20.GL_FLOAT,
            false, 2 * 4, textCoorBuffer
        )

        if (objectBean.mtl != null) {
            GLES20.glUniform3f(
                lightAmbientPosHandle,
                0.5f * objectBean.mtl!!.Ka_Color[0],
                0.5f * objectBean.mtl!!.Ka_Color[1],
                0.5f * objectBean.mtl!!.Ka_Color[2]
            )
            GLES20.glUniform3f(
                lightDiffusePosHandle,
                0.6f * objectBean.mtl!!.Kd_Color[0],
                0.6f * objectBean.mtl!!.Kd_Color[1],
                0.6f * objectBean.mtl!!.Kd_Color[2]
            )
            GLES20.glUniform3f(lightSpecularPosHandle,
                0.6f * objectBean.mtl!!.Ks_Color[0],
                0.6f * objectBean.mtl!!.Ks_Color[1],
                0.6f * objectBean.mtl!!.Ks_Color[2])

            if (!TextUtils.isEmpty(objectBean.mtl?.Kd_Texture)) {
                if (objectBean.diffuse < 0) {
                    try {
                        val bitmap = BitmapFactory.decodeStream(
                            ModelViewerApplication.instance.assets.open(
                                fileName + "/" + objectBean.mtl!!.Kd_Texture
                            )
                        )
                        objectBean.diffuse = Util.createTextureNormal(bitmap,false)
                        bitmap.recycle()
                    } catch (e: IOException) {
                        LogUtil.e(e)
                    }
                }
            } else {
                if (objectBean.diffuse < 0) {
                    val bitmap = BitmapFactory.decodeResource(
                        ModelViewerApplication.instance.resources,
                        R.drawable.ic_default_texture
                    )
                    objectBean.diffuse = Util.createTextureNormal(bitmap,false)
                    bitmap.recycle()
                }
            }
            if (TextUtils.equals(objectBean.mtl!!.Kd_Texture, objectBean.mtl!!.Ka_Texture)) {
                // 相同
                objectBean.ambient = objectBean.diffuse
            } else {
                if (!TextUtils.isEmpty(objectBean.mtl!!.Ka_Texture)) {
                    if (objectBean.ambient < 0) {
                        try {
                            val bitmap = BitmapFactory.decodeStream(
                                ModelViewerApplication.instance.assets.open(
                                    fileName + "/" + objectBean.mtl!!.Ka_Texture
                                )
                            )
                            objectBean.ambient = Util.createTextureNormal(bitmap,false)
                            bitmap.recycle()
                        } catch (e: IOException) {
                            LogUtil.e(e)
                        }
                    }
                } else {
                    if (objectBean.ambient < 0) {
                        val bitmap = BitmapFactory.decodeResource(
                            ModelViewerApplication.instance.resources,
                            R.drawable.ic_default_texture
                        )
                        objectBean.ambient = Util.createTextureNormal(bitmap,false)
                        bitmap.recycle()
                    }
                }
            }
            Util.bindTexture(materialAmbientPosHandle, objectBean.ambient, 0)
            Util.bindTexture(materialDiffusePosHandle, objectBean.diffuse, 0)
            if (!TextUtils.isEmpty(objectBean.mtl!!.Ks_Texture)) {
                if (objectBean.specular < 0) {
                    try {
                        val bitmap = BitmapFactory.decodeStream(
                            ModelViewerApplication.instance.assets.open(
                                fileName + "/" + objectBean.mtl!!.Ks_Texture
                            )
                        )
                        objectBean.specular = Util.createTextureNormal(bitmap,false)
                        bitmap.recycle()
                    } catch (e: IOException) {
                        LogUtil.e(e)
                    }
                }
            } else {
                if (objectBean.specular < 0) {
                    val bitmap = BitmapFactory.decodeResource(
                        ModelViewerApplication.instance.resources,
                        R.drawable.ic_default_texture
                    )
                    objectBean.specular = Util.createTextureNormal(bitmap,false)
                    bitmap.recycle()
                }
            }
            Util.bindTexture(materialSpecularPosHandle, objectBean.specular, 1)
            GLES20.glUniform1f(materialAlphaPosHandle, objectBean.mtl!!.alpha)
            GLES20.glUniform1f(materialShininessPosHandle, objectBean.mtl!!.ns)
        }
        // draw vertices
        GLES20.glDrawArrays(GLES20.GL_TRIANGLES, 0, objectBean.aVertices!!.size / 3)
    }
}
