package com.dmitrybrant.modelviewer

import android.opengl.GLES20
import android.opengl.GLSurfaceView
import android.opengl.Matrix
import kotlin.math.atan
import kotlin.math.max
import kotlin.math.min
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10

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
class ModelRenderer(private val model: Model?) : GLSurfaceView.Renderer {
    private val light = Light(floatArrayOf(0.0f, 0.0f, MODEL_BOUND_SIZE * 10, 1.0f))
    private val floor = Floor()

    private val projectionMatrix = FloatArray(16)
    private val viewMatrix = FloatArray(16)

    private var rotateAngleX = 0f
    private var rotateAngleY = 0f
    private var translateX = 0f
    private var translateY = 0f
    private var translateZ = 0f
    private var aspectRatio = 1f

    fun translate(dx: Float, dy: Float, dz: Float) {
        val translateScaleFactor = MODEL_BOUND_SIZE / 200f
        translateX += dx * translateScaleFactor
        translateY += dy * translateScaleFactor
        if (dz != 0f) {
            translateZ /= dz
        }
        updateViewMatrix()
    }

    fun rotate(aX: Float, aY: Float) {
        val rotateScaleFactor = 0.5f
        rotateAngleX -= aX * rotateScaleFactor
        rotateAngleY += aY * rotateScaleFactor
        updateViewMatrix()
    }

    private fun updateViewMatrix() {
        Matrix.setLookAtM(viewMatrix, 0, 0f, 0f, translateZ, 0f, 0f, 0f, 0f, 1.0f, 0.0f)
        Matrix.translateM(viewMatrix, 0, -translateX, -translateY, 0f)
        Matrix.rotateM(viewMatrix, 0, rotateAngleX, 1f, 0f, 0f)
        Matrix.rotateM(viewMatrix, 0, rotateAngleY, 0f, 1f, 0f)
    }

    /**
     * Sets the projection, with its near plane as far from the camera as it can be: just in front of
     * the model, and of the floor where it's visible. The precision of the depth buffer is spread
     * mostly near the near plane, so if it's much closer than the model, surfaces of the model that
     * are very close together can't be told apart in depth, and they flicker through each other.
     */
    private fun updateProjection() {
        // The view only moves the model sideways, so its center is at this depth.
        val modelDepth = -translateZ
        // Without its own bounds, a model might extend as far as the corners of its bounding box.
        val radius = model?.boundingRadius?.takeIf { it > 0f } ?: (MODEL_BOUND_SIZE * 1.75f)
        val near = max(min(modelDepth - radius, floorDepth()) * NEAR_MARGIN, Z_NEAR)
        Matrix.perspectiveM(projectionMatrix, 0, FIELD_OF_VIEW, aspectRatio, near, Z_FAR)
    }

    /** The smallest depth of the floor in the view (where it meets a corner of the view), if it's seen from above. */
    private fun floorDepth(): Float {
        // The floor's plane (y = floor height), in the camera's coordinates.
        val normal = FloatArray(4)
        val point = FloatArray(4)
        Matrix.multiplyMV(normal, 0, viewMatrix, 0, floatArrayOf(0f, 1f, 0f, 0f), 0)
        Matrix.multiplyMV(point, 0, viewMatrix, 0, floatArrayOf(0f, model?.floorOffset ?: 0f, 0f, 1f), 0)
        val distance = normal[0] * point[0] + normal[1] * point[1] + normal[2] * point[2]
        // From below, the floor isn't drawn.
        if (distance >= 0f) {
            return Float.MAX_VALUE
        }
        var depth = Float.MAX_VALUE
        val tanY = 0.5f
        for (corner in CORNERS) {
            // A ray toward the corner, with a depth of 1.
            val dx = corner[0] * tanY * aspectRatio
            val dy = corner[1] * tanY
            val dot = normal[0] * dx + normal[1] * dy - normal[2]
            if (dot < 0f) {
                depth = min(depth, distance / dot)
            }
        }
        return depth
    }

    override fun onDrawFrame(unused: GL10) {
        updateProjection()
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT or GLES20.GL_DEPTH_BUFFER_BIT)
        floor.draw(viewMatrix, projectionMatrix, light)
        model?.draw(viewMatrix, projectionMatrix, light)
    }

    override fun onSurfaceChanged(unused: GL10, width: Int, height: Int) {
        GLES20.glViewport(0, 0, width, height)
        aspectRatio = width.toFloat() / height

        // initialize the view matrix
        rotateAngleX = 0f
        rotateAngleY = 0f
        translateX = 0f
        translateY = 0f
        translateZ = -MODEL_BOUND_SIZE * 1.5f
        updateViewMatrix()

        // Set light matrix before doing any other transforms on the view matrix
        light.applyViewMatrix(viewMatrix)

        // By default, rotate the model towards the user a bit
        rotateAngleX = -15.0f
        rotateAngleY = 15.0f
        updateViewMatrix()
    }

    override fun onSurfaceCreated(unused: GL10, config: EGLConfig) {
        GLES20.glClearColor(0.2f, 0.2f, 0.2f, 1f)
        GLES20.glEnable(GLES20.GL_CULL_FACE)
        GLES20.glEnable(GLES20.GL_DEPTH_TEST)
        //GLES20.glEnable(GLES20.GL_BLEND);
        //GLES20.glBlendFunc(GLES20.GL_SRC_ALPHA, GLES20.GL_ONE_MINUS_SRC_ALPHA);

        floor.setup(MODEL_BOUND_SIZE)
        model?.let {
            it.setup(MODEL_BOUND_SIZE)
            floor.setOffsetY(it.floorOffset)
        }
    }

    companion object {
        private const val MODEL_BOUND_SIZE = 50f
        private const val Z_NEAR = 2f
        private const val Z_FAR = MODEL_BOUND_SIZE * 10
        /** Vertical field of view, in degrees: the height of the view is half of its distance. */
        private val FIELD_OF_VIEW = Math.toDegrees(2 * atan(0.5)).toFloat()
        /** How much closer the near plane is than the closest part of the model or floor, to be safe. */
        private const val NEAR_MARGIN = 0.8f
        private val CORNERS = arrayOf(floatArrayOf(-1f, -1f), floatArrayOf(1f, -1f), floatArrayOf(-1f, 1f), floatArrayOf(1f, 1f))
    }
}
