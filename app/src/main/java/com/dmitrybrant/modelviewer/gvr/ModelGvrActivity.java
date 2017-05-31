package com.dmitrybrant.modelviewer.gvr;

import android.content.Context;
import android.opengl.GLES20;
import android.opengl.Matrix;
import android.os.Vibrator;
import android.os.Bundle;
import android.support.annotation.Nullable;
import android.util.Log;

import com.dmitrybrant.modelviewer.Light;
import com.dmitrybrant.modelviewer.Model;
import com.dmitrybrant.modelviewer.ModelViewerApplication;
import com.dmitrybrant.modelviewer.R;
import com.dmitrybrant.modelviewer.util.Util;
import com.google.vr.sdk.base.AndroidCompat;
import com.google.vr.sdk.base.Eye;
import com.google.vr.sdk.base.GvrActivity;
import com.google.vr.sdk.base.GvrView;
import com.google.vr.sdk.base.HeadTransform;
import com.google.vr.sdk.base.Viewport;

import javax.microedition.khronos.egl.EGLConfig;

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
public class ModelGvrActivity extends GvrActivity implements GvrView.StereoRenderer {
    private static final String TAG = "ModelGvrActivity";

    private float rotateAngleX;
    private float rotateAngleY;
    private float translateX;
    private float translateY;
    private float translateZ;

    @Nullable private Model model;
    private Light light = new Light(new float[] {0.0f, 0.0f, MODEL_BOUND_SIZE * 10, 1.0f});

    private final float[] viewMatrix = new float[16];

    private static final float MODEL_BOUND_SIZE = 5f;
    private static final float Z_NEAR = 0.1f;
    private static final float Z_FAR = MODEL_BOUND_SIZE * 4;

    private static final float YAW_LIMIT = 0.12f;
    private static final float PITCH_LIMIT = 0.12f;

    // Convenience vector for extracting the position from a matrix via multiplication.
    private static final float[] POS_MATRIX_MULTIPLY_VEC = {0, 0, 0, 1.0f};
    private static final float RAD2DEG = 57.29577951f;

    private float[] finalViewMatrix = new float[16];
    private float[] tempMatrix = new float[16];
    private float[] tempPosition = new float[4];
    private float[] headView = new float[16];
    private float[] inverseHeadView = new float[16];
    private float[] headRotation = new float[4];
    private float[] headEulerAngles = new float[3];

    private Vibrator vibrator;

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        initializeGvrView();
        vibrator = (Vibrator) getSystemService(Context.VIBRATOR_SERVICE);
    }

    public void initializeGvrView() {
        setContentView(R.layout.activity_gvr);

        GvrView gvrView = (GvrView) findViewById(R.id.gvr_view);
        gvrView.setEGLConfigChooser(8, 8, 8, 8, 16, 8);

        gvrView.setRenderer(this);
        gvrView.setTransitionViewEnabled(true);

        // Enable Cardboard-trigger feedback with Daydream headsets. This is a simple way of supporting
        // Daydream controller input for basic interactions using the existing Cardboard trigger API.
        gvrView.enableCardboardTriggerEmulation();

        gvrView.setOnCloseButtonListener(new Runnable() {
            @Override
            public void run() {
                finish();
            }
        });

        if (gvrView.setAsyncReprojectionEnabled(true)) {
            // Async reprojection decouples the app framerate from the display framerate,
            // allowing immersive interaction even at the throttled clockrates set by
            // sustained performance mode.
            AndroidCompat.setSustainedPerformanceMode(this, true);
        }

        model = ModelViewerApplication.getInstance().getCurrentModel();

        setGvrView(gvrView);
    }

    @Override
    public void onPause() {
        super.onPause();
    }

    @Override
    public void onResume() {
        super.onResume();
    }

    @Override
    public void onRendererShutdown() {
        Log.i(TAG, "onRendererShutdown");
    }

    @Override
    public void onSurfaceChanged(int width, int height) {
        Log.i(TAG, "onSurfaceChanged");

        // initialize the view matrix
        rotateAngleX = 0;
        rotateAngleY = 0;
        translateX = 0f;
        translateY = 0f;
        translateZ = -MODEL_BOUND_SIZE;
        updateViewMatrix();

        // Set light matrix before doing any other transforms on the view matrix
        light.applyViewMatrix(viewMatrix);
    }

    @Override
    public void onSurfaceCreated(EGLConfig config) {
        Log.i(TAG, "onSurfaceCreated");

        GLES20.glClearColor(0.2f, 0.2f, 0.2f, 1f);
        GLES20.glEnable(GLES20.GL_CULL_FACE);
        GLES20.glEnable(GLES20.GL_DEPTH_TEST);
        if (model != null) {
            model.init(MODEL_BOUND_SIZE);
        }
        Util.checkGLError("onSurfaceCreated");
    }

    private void updateViewMatrix() {
        Matrix.setLookAtM(viewMatrix, 0, 0, 0, translateZ, 0f, 0f, 0f, 0f, 1.0f, 0.0f);
        Matrix.translateM(viewMatrix, 0, -translateX, -translateY, 0f);
        Matrix.rotateM(viewMatrix, 0, rotateAngleX, 1f, 0f, 0f);
        Matrix.rotateM(viewMatrix, 0, rotateAngleY, 0f, 1f, 0f);
    }

    @Override
    public void onNewFrame(HeadTransform headTransform) {
        headTransform.getHeadView(headView, 0);
        Matrix.invertM(inverseHeadView, 0, headView, 0);

        headTransform.getQuaternion(headRotation, 0);
        headTransform.getEulerAngles(headEulerAngles, 0);
        headEulerAngles[0] *= RAD2DEG;
        headEulerAngles[1] *= RAD2DEG;
        headEulerAngles[2] *= RAD2DEG;

        updateViewMatrix();
        Util.checkGLError("onNewFrame");
    }

    @Override
    public void onDrawEye(Eye eye) {
        GLES20.glEnable(GLES20.GL_DEPTH_TEST);
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT | GLES20.GL_DEPTH_BUFFER_BIT);

        // Keep the model in front of the camera by applying the inverse of the head matrix
        Matrix.multiplyMM(finalViewMatrix, 0, inverseHeadView, 0, viewMatrix, 0);

        // Apply the eye transformation to the final view
        Matrix.multiplyMM(finalViewMatrix, 0, eye.getEyeView(), 0, finalViewMatrix, 0);

        // Rotate based on Euler angles, so the user can look around the model.
        Matrix.rotateM(finalViewMatrix, 0, headEulerAngles[0], 1.0f, 0.0f, 0.0f);
        Matrix.rotateM(finalViewMatrix, 0, -headEulerAngles[1], 0.0f, 1.0f, 0.0f);
        Matrix.rotateM(finalViewMatrix, 0, headEulerAngles[2], 0.0f, 0.0f, 1.0f);

        float[] perspective = eye.getPerspective(Z_NEAR, Z_FAR);

        if (model != null) {
            model.draw(finalViewMatrix, perspective, light);
        }
        Util.checkGLError("onDrawEye");
    }

    @Override
    public void onFinishFrame(Viewport viewport) {}

    @Override
    public void onCardboardTrigger() {
        Log.i(TAG, "onCardboardTrigger");
        // TODO: use for something

        vibrator.vibrate(50);
    }

    // TODO: use for something.
    private boolean isLookingAtObject() {
        if (model == null) {
            return false;
        }
        // Convert object space to camera space. Use the headView from onNewFrame.
        Matrix.multiplyMM(tempMatrix, 0, headView, 0, model.getModelMatrix(), 0);
        Matrix.multiplyMV(tempPosition, 0, tempMatrix, 0, POS_MATRIX_MULTIPLY_VEC, 0);

        float pitch = (float) Math.atan2(tempPosition[1], -tempPosition[2]);
        float yaw = (float) Math.atan2(tempPosition[0], -tempPosition[2]);
        return Math.abs(pitch) < PITCH_LIMIT && Math.abs(yaw) < YAW_LIMIT;
    }
}
