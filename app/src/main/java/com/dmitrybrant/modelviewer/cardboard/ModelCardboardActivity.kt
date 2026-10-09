package com.dmitrybrant.modelviewer.cardboard

import android.opengl.GLES20
import android.opengl.GLSurfaceView
import android.opengl.Matrix
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.WindowManager
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.core.view.children
import com.dmitrybrant.modelviewer.Light
import com.dmitrybrant.modelviewer.ModelViewerApplication
import com.dmitrybrant.modelviewer.databinding.ActivityCardboardBinding
import com.dmitrybrant.modelviewer.util.Util.checkGLError
import com.google.cardboard.sdk.CardboardView
import com.google.cardboard.sdk.HeadTransform
import com.google.cardboard.sdk.Viewport
import javax.microedition.khronos.egl.EGLConfig
import kotlin.math.abs
import kotlin.math.atan2

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
class ModelCardboardActivity : AppCompatActivity(), CardboardView.Renderer {
    private lateinit var binding: ActivityCardboardBinding

    private var rotateAngleX = 0f
    private var rotateAngleY = 0f
    private var translateX = 0f
    private var translateY = 0f
    private var translateZ = 0f

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
        initializeCardboardView()
    }

    override fun onResume() {
        super.onResume()
        binding.cardboardView.onResume()
    }

    override fun onPause() {
        super.onPause()
        binding.cardboardView.onPause()
    }

    override fun onDestroy() {
        super.onDestroy()
        binding.cardboardView.onDestroy()
    }

    private fun initializeCardboardView()
    {
        binding = ActivityCardboardBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // Use the whole screen, without the system bars, and keep it on.
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            window.attributes.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
        }
        WindowCompat.getInsetsController(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }

        // CardboardView sets up its GLSurfaceView for OpenGL ES 2.0, but the models need 3.0.
        // The context version needs to be set first, since the config chooser picks configs that support it.
        binding.cardboardView.children.filterIsInstance<GLSurfaceView>().first().setEGLContextClientVersion(3)
        binding.cardboardView.setEGLConfigChooser(8, 8, 8, 8, 16, 8)
        binding.cardboardView.setRenderer(this)
        binding.cardboardView.setStereoRenderMode(true)

        binding.cardboardView.setOnBackButtonClick { finish() }
        binding.cardboardView.setOnSettingsButtonClick { binding.cardboardView.scanViewerQrCode() }
        binding.cardboardView.setOnTriggerEvent { onCardboardTrigger() }
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
        ModelViewerApplication.currentModel?.setup(MODEL_BOUND_SIZE)
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

        // CardboardView clears the frame (for both eyes) after this, and its distortion pass at the
        // end of the previous frame changes some of the GL state, so the state is set for each frame.
        GLES20.glClearColor(0.2f, 0.2f, 0.2f, 1f)
        GLES20.glEnable(GLES20.GL_CULL_FACE)
        GLES20.glEnable(GLES20.GL_DEPTH_TEST)
        checkGLError("onNewFrame")
    }

    override fun onDrawEye(eye: CardboardView.Eye) {
        // Keep the model in front of the camera by applying the inverse of the head matrix
        Matrix.multiplyMM(finalViewMatrix, 0, inverseHeadView, 0, viewMatrix, 0)

        // Apply the eye transformation to the final view
        eye.applyHeadView(headView)
        Matrix.multiplyMM(finalViewMatrix, 0, eye.eyeView, 0, finalViewMatrix, 0)

        // Rotate based on Euler angles, so the user can look around the model.
        Matrix.rotateM(finalViewMatrix, 0, headEulerAngles[0], 1.0f, 0.0f, 0.0f)
        Matrix.rotateM(finalViewMatrix, 0, -headEulerAngles[1], 0.0f, 1.0f, 0.0f)
        Matrix.rotateM(finalViewMatrix, 0, headEulerAngles[2], 0.0f, 0.0f, 1.0f)
        val perspective = eye.getPerspective(Z_NEAR, Z_FAR)

        ModelViewerApplication.currentModel?.draw(finalViewMatrix, perspective, light)
        checkGLError("onDrawEye")
    }

    override fun onFinishFrame(viewport: Viewport) { }

    private fun onCardboardTrigger() {
        Log.i(TAG, "onCardboardTrigger")
        // TODO: use for something
    }

    // TODO: use for something.
    private val isLookingAtObject: Boolean
        get() {
            if (ModelViewerApplication.currentModel == null) {
                return false
            }
            // Convert object space to camera space. Use the headView from onNewFrame.
            Matrix.multiplyMM(tempMatrix, 0, headView, 0, ModelViewerApplication.currentModel!!.modelMatrix, 0)
            Matrix.multiplyMV(tempPosition, 0, tempMatrix, 0, POS_MATRIX_MULTIPLY_VEC, 0)
            val pitch = atan2(tempPosition[1].toDouble(), (-tempPosition[2]).toDouble()).toFloat()
            val yaw = atan2(tempPosition[0].toDouble(), (-tempPosition[2]).toDouble()).toFloat()
            return abs(pitch) < PITCH_LIMIT && abs(yaw) < YAW_LIMIT
        }

    companion object {
        private const val TAG = "ModelCardboardActivity"
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