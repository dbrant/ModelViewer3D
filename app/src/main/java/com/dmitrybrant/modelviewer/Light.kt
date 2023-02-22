package com.dmitrybrant.modelviewer

import android.opengl.Matrix

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
class Light(private var lightPosInWorldSpace: FloatArray) {
    val positionInEyeSpace = FloatArray(4)
    var ambientColor = floatArrayOf(0.1f, 0.1f, 0.4f, 1.0f)
    var diffuseColor = floatArrayOf(1.0f, 1.0f, 1.0f, 1.0f)
    var specularColor = floatArrayOf(1.0f, 1.0f, 1.0f, 0.5f)

    fun setPosition(position: FloatArray) {
        lightPosInWorldSpace = position
    }

    fun applyViewMatrix(viewMatrix: FloatArray) {
        Matrix.multiplyMV(positionInEyeSpace, 0, viewMatrix, 0, lightPosInWorldSpace, 0)
    }
}
