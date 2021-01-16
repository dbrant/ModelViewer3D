package com.dmitrybrant.modelviewer

import android.opengl.GLES20
import java.nio.IntBuffer

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
open class IndexedModel : ArrayModel() {
    protected var indexBuffer: IntBuffer? = null
    protected var indexCount = 0

    override fun drawFunc() {
        if (indexBuffer == null || indexCount == 0) {
            return
        }
        GLES20.glDrawElements(GLES20.GL_TRIANGLES, indexCount, GLES20.GL_UNSIGNED_INT, indexBuffer)
    }

    companion object {
        const val BYTES_PER_INT = 4
    }
}
