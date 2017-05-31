package com.dmitrybrant.modelviewer;

import android.opengl.Matrix;
import android.support.annotation.NonNull;

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
public class Light {

    @NonNull private float[] lightPosInWorldSpace;
    private final float[] lightPosInEyeSpace = new float[4];
    private float[] ambientColor = new float[] {0.1f, 0.1f, 0.4f};
    private float[] diffuseColor = new float[] {1.0f, 1.0f, 1.0f};
    private float[] specularColor = new float[] {1.0f, 1.0f, 1.0f};

    public Light(@NonNull float[] position) {
        this.lightPosInWorldSpace = position;
    }

    public void setPosition(@NonNull float[] position) {
        this.lightPosInWorldSpace = position;
    }

    public void setAmbientColor(@NonNull float[] color) {
        ambientColor = color;
    }

    public float[] getAmbientColor() {
        return ambientColor;
    }

    public void setDiffuseColor(@NonNull float[] color) {
        diffuseColor = color;
    }

    public float[] getDiffuseColor() {
        return diffuseColor;
    }

    public void setSpecularColor(@NonNull float[] color) {
        specularColor = color;
    }

    public float[] getSpecularColor() {
        return specularColor;
    }

    public void applyViewMatrix(@NonNull float[] viewMatrix) {
        Matrix.multiplyMV(lightPosInEyeSpace, 0, viewMatrix, 0, lightPosInWorldSpace, 0);
    }

    public float[] getPositionInEyeSpace() {
        return lightPosInEyeSpace;
    }

}
