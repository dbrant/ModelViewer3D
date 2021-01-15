package com.dmitrybrant.modelviewer.gvr

import android.opengl.GLES20
import android.opengl.Matrix
import android.os.Bundle
import android.util.Log
import com.dmitrybrant.modelviewer.Light
import com.dmitrybrant.modelviewer.Model
import com.dmitrybrant.modelviewer.ModelViewerApplication
import com.dmitrybrant.modelviewer.R
import com.dmitrybrant.modelviewer.util.Util.checkGLError
import com.google.vr.sdk.base.*
import com.google.vr.sdk.base.GvrView.StereoRenderer
import javax.microedition.khronos.egl.EGLConfig

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
class ModelGvrActivity : GvrActivity(), StereoRenderer {
    private var rotateAngleX = 0f
    private var rotateAngleY = 0f
    private var translateX = 0f
    private var translateY = 0f
    private var translateZ = 0f

    private var model: Model? = null

    private val light = Light(floatArrayOf(0.0f, 0.0f, MODEL_BOUND_SIZE * 10, 1.0f))
    private val viewMatrix = FloatArray(16)
    private val finalViewMatrix = FloatArray(16)
    private val tempMatrix = FloatArray(16)
    private val tempPosition = FloatArray(4)
    private val headView = FloatArray(16)
    private val inverseHeadView = FloatArray(16)
    private val headRotation = FloatArray(4)
    private val headEulerAngles = FloatArray(3)

    public override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        initializeGvrView()
    }

    private fun initializeGvrView() {
        setContentView(R.layout.activity_gvr)
        val gvrView: GvrView = findViewById(R.id.gvr_view)
        gvrView.setEGLConfigChooser(8, 8, 8, 8, 16, 8)
        gvrView.setRenderer(this)
        gvrView.setTransitionViewEnabled(true)

        // Enable Cardboard-trigger feedback with Daydream headsets. This is a simple way of supporting
        // Daydream controller input for basic interactions using the existing Cardboard trigger API.
        gvrView.enableCardboardTriggerEmulation()
        gvrView.setOnCloseButtonListener { finish() }
        if (gvrView.setAsyncReprojectionEnabled(true)) {
            // Async reprojection decouples the app framerate from the display framerate,
            // allowing immersive interaction even at the throttled clockrates set by
            // sustained performance mode.
            AndroidCompat.setSustainedPerformanceMode(this, true)
        }
        model = ModelViewerApplication.instance!!.currentModel
        setGvrView(gvrView)
    }

    public override fun onPause() {
        super.onPause()
    }

    public override fun onResume() {
        super.onResume()
    }

    override fun onRendererShutdown() {
        Log.i(TAG, "onRendererShutdown")
    }

    override fun onSurfaceChanged(width: Int, height: Int) {
        Log.i(TAG, "onSurfaceChanged")

        // initialize the view matrix
        rotateAngleX = 0f
        rotateAngleY = 0f
        translateX = 0f
        translateY = 0f
        translateZ = -MODEL_BOUND_SIZE
        updateViewMatrix()

        // Set light matrix before doing any other transforms on the view matrix
        light.applyViewMatrix(viewMatrix)
    }

    override fun onSurfaceCreated(config: EGLConfig) {
        Log.i(TAG, "onSurfaceCreated")
        GLES20.glClearColor(0.2f, 0.2f, 0.2f, 1f)
        GLES20.glEnable(GLES20.GL_CULL_FACE)
        GLES20.glEnable(GLES20.GL_DEPTH_TEST)
        if (model != null) {
            model!!.init(MODEL_BOUND_SIZE)
        }
        checkGLError("onSurfaceCreated")
    }

    private fun updateViewMatrix() {
        Matrix.setLookAtM(viewMatrix, 0, 0f, 0f, translateZ, 0f, 0f, 0f, 0f, 1.0f, 0.0f)
        Matrix.translateM(viewMatrix, 0, -translateX, -translateY, 0f)
        Matrix.rotateM(viewMatrix, 0, rotateAngleX, 1f, 0f, 0f)
        Matrix.rotateM(viewMatrix, 0, rotateAngleY, 0f, 1f, 0f)
    }

    override fun onNewFrame(headTransform: HeadTransform) {
        headTransform.getHeadView(headView, 0)
        Matrix.invertM(inverseHeadView, 0, headView, 0)
        headTransform.getQuaternion(headRotation, 0)
        headTransform.getEulerAngles(headEulerAngles, 0)
        headEulerAngles[0] *= RAD2DEG
        headEulerAngles[1] *= RAD2DEG
        headEulerAngles[2] *= RAD2DEG
        updateViewMatrix()
        checkGLError("onNewFrame")
    }

    override fun onDrawEye(eye: Eye) {
        GLES20.glEnable(GLES20.GL_DEPTH_TEST)
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT or GLES20.GL_DEPTH_BUFFER_BIT)

        // Keep the model in front of the camera by applying the inverse of the head matrix
        Matrix.multiplyMM(finalViewMatrix, 0, inverseHeadView, 0, viewMatrix, 0)

        // Apply the eye transformation to the final view
        Matrix.multiplyMM(finalViewMatrix, 0, eye.eyeView, 0, finalViewMatrix, 0)

        // Rotate based on Euler angles, so the user can look around the model.
        Matrix.rotateM(finalViewMatrix, 0, headEulerAngles[0], 1.0f, 0.0f, 0.0f)
        Matrix.rotateM(finalViewMatrix, 0, -headEulerAngles[1], 0.0f, 1.0f, 0.0f)
        Matrix.rotateM(finalViewMatrix, 0, headEulerAngles[2], 0.0f, 0.0f, 1.0f)
        val perspective = eye.getPerspective(Z_NEAR, Z_FAR)
        if (model != null) {
            model!!.draw(finalViewMatrix, perspective, light)
        }
        checkGLError("onDrawEye")
    }

    override fun onFinishFrame(viewport: Viewport) {}
    override fun onCardboardTrigger() {
        Log.i(TAG, "onCardboardTrigger")
        // TODO: use for something
    }// Convert object space to camera space. Use the headView from onNewFrame.

    // TODO: use for something.
    private val isLookingAtObject: Boolean
        get() {
            if (model == null) {
                return false
            }
            // Convert object space to camera space. Use the headView from onNewFrame.
            Matrix.multiplyMM(tempMatrix, 0, headView, 0, model!!.modelMatrix, 0)
            Matrix.multiplyMV(tempPosition, 0, tempMatrix, 0, POS_MATRIX_MULTIPLY_VEC, 0)
            val pitch = Math.atan2(tempPosition[1].toDouble(), (-tempPosition[2]).toDouble()).toFloat()
            val yaw = Math.atan2(tempPosition[0].toDouble(), (-tempPosition[2]).toDouble()).toFloat()
            return Math.abs(pitch) < PITCH_LIMIT && Math.abs(yaw) < YAW_LIMIT
        }

    companion object {
        private const val TAG = "ModelGvrActivity"
        private const val MODEL_BOUND_SIZE = 5f
        private const val Z_NEAR = 0.1f
        private const val Z_FAR = MODEL_BOUND_SIZE * 4
        private const val YAW_LIMIT = 0.12f
        private const val PITCH_LIMIT = 0.12f

        // Convenience vector for extracting the position from a matrix via multiplication.
        private val POS_MATRIX_MULTIPLY_VEC = floatArrayOf(0f, 0f, 0f, 1.0f)
        private const val RAD2DEG = 57.29577951f
    }
}