package com.dmitrybrant.modelviewer;

import android.opengl.GLES20;
import android.opengl.GLSurfaceView;
import android.opengl.Matrix;
import android.support.annotation.Nullable;

import javax.microedition.khronos.egl.EGLConfig;
import javax.microedition.khronos.opengles.GL10;

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
public class ModelRenderer implements GLSurfaceView.Renderer {
    private static final float MODEL_BOUND_SIZE = 50f;
    private static final float Z_NEAR = 2f;
    private static final float Z_FAR = MODEL_BOUND_SIZE * 10;

    @Nullable private Model model;
    private final Light light = new Light(new float[] {0.0f, 0.0f, MODEL_BOUND_SIZE * 10, 1.0f});
    private final Floor floor = new Floor();

    private final float[] projectionMatrix = new float[16];
    private final float[] viewMatrix = new float[16];

    private float rotateAngleX;
    private float rotateAngleY;
    private float translateX;
    private float translateY;
    private float translateZ;

    public ModelRenderer(@Nullable Model model) {
        this.model = model;
    }

    public void translate(float dx, float dy, float dz) {
        final float translateScaleFactor = MODEL_BOUND_SIZE / 200f;
        translateX += dx * translateScaleFactor;
        translateY += dy * translateScaleFactor;
        if (dz != 0f) {
            translateZ /= dz;
        }
        updateViewMatrix();
    }

    public void rotate(float aX, float aY) {
        final float rotateScaleFactor = 0.5f;
        rotateAngleX -= aX * rotateScaleFactor;
        rotateAngleY += aY * rotateScaleFactor;
        updateViewMatrix();
    }

    private void updateViewMatrix() {
        Matrix.setLookAtM(viewMatrix, 0, 0, 0, translateZ, 0f, 0f, 0f, 0f, 1.0f, 0.0f);
        Matrix.translateM(viewMatrix, 0, -translateX, -translateY, 0f);
        Matrix.rotateM(viewMatrix, 0, rotateAngleX, 1f, 0f, 0f);
        Matrix.rotateM(viewMatrix, 0, rotateAngleY, 0f, 1f, 0f);
    }

    @Override
    public void onDrawFrame(GL10 unused) {
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT | GLES20.GL_DEPTH_BUFFER_BIT);

        floor.draw(viewMatrix, projectionMatrix, light);
        if (model != null) {
            model.draw(viewMatrix, projectionMatrix, light);
        }
    }

    @Override
    public void onSurfaceChanged(GL10 unused, int width, int height) {
        GLES20.glViewport(0, 0, width, height);
        float ratio = (float) width / height;
        Matrix.frustumM(projectionMatrix, 0, -ratio, ratio, -1, 1, Z_NEAR, Z_FAR);

        // initialize the view matrix
        rotateAngleX = 0;
        rotateAngleY = 0;
        translateX = 0f;
        translateY = 0f;
        translateZ = -MODEL_BOUND_SIZE * 1.5f;
        updateViewMatrix();

        // Set light matrix before doing any other transforms on the view matrix
        light.applyViewMatrix(viewMatrix);

        // By default, rotate the model towards the user a bit
        rotateAngleX = -15.0f;
        rotateAngleY = 15.0f;
        updateViewMatrix();
    }

    @Override
    public void onSurfaceCreated(GL10 unused, EGLConfig config) {
        GLES20.glClearColor(0.2f, 0.2f, 0.2f, 1f);
        GLES20.glEnable(GLES20.GL_CULL_FACE);
        GLES20.glEnable(GLES20.GL_DEPTH_TEST);
        //GLES20.glEnable(GLES20.GL_BLEND);
        //GLES20.glBlendFunc(GLES20.GL_SRC_ALPHA, GLES20.GL_ONE_MINUS_SRC_ALPHA);

        floor.init(MODEL_BOUND_SIZE);
        if (model != null) {
            model.init(MODEL_BOUND_SIZE);
            floor.setOffsetY(model.getFloorOffset());
        }
    }
}

