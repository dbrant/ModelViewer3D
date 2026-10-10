package com.dmitrybrant.modelviewer

import android.opengl.EGLExt
import android.opengl.GLSurfaceView
import javax.microedition.khronos.egl.EGL10
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.egl.EGLDisplay

/*
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

/**
 * Chooses an EGL config for OpenGL ES 3.0, with 8 bits for each color, and a 24-bit depth buffer
 * (or a 16-bit one, if there's no config with a deeper one).
 *
 * GLSurfaceView's own choosers take the first config whose depth buffer has at least the given
 * number of bits, and some GPUs (like the Mali GPU of the Pixel 8) list their configs with 16-bit
 * depth buffers first. With only 16 bits, surfaces that are close together (like the panels of a
 * detailed model) can't be told apart in depth, so they flicker through each other as the model moves.
 */
class EglConfigChooser(private val alphaSize: Int = 0, private val stencilSize: Int = 0) : GLSurfaceView.EGLConfigChooser {
    override fun chooseConfig(egl: EGL10, display: EGLDisplay): EGLConfig {
        for (depthSize in DEPTH_SIZES) {
            chooseConfig(egl, display, depthSize)?.let { return it }
        }
        throw IllegalArgumentException("No suitable EGL config.")
    }

    private fun chooseConfig(egl: EGL10, display: EGLDisplay, depthSize: Int): EGLConfig? {
        val spec = intArrayOf(
            EGL10.EGL_RED_SIZE, 8,
            EGL10.EGL_GREEN_SIZE, 8,
            EGL10.EGL_BLUE_SIZE, 8,
            EGL10.EGL_ALPHA_SIZE, alphaSize,
            EGL10.EGL_DEPTH_SIZE, depthSize,
            EGL10.EGL_STENCIL_SIZE, stencilSize,
            EGL10.EGL_RENDERABLE_TYPE, EGLExt.EGL_OPENGL_ES3_BIT_KHR,
            EGL10.EGL_NONE)
        val count = IntArray(1)
        if (!egl.eglChooseConfig(display, spec, null, 0, count) || count[0] <= 0) {
            return null
        }
        val configs = arrayOfNulls<EGLConfig>(count[0])
        if (!egl.eglChooseConfig(display, spec, configs, configs.size, count)) {
            return null
        }
        // The configs have at least the given sizes; ones with exactly the given color sizes are preferred.
        val candidates = configs.take(count[0]).filterNotNull()
        return candidates.firstOrNull { config ->
            attribute(egl, display, config, EGL10.EGL_RED_SIZE) == 8 && attribute(egl, display, config, EGL10.EGL_GREEN_SIZE) == 8 &&
                    attribute(egl, display, config, EGL10.EGL_BLUE_SIZE) == 8 && attribute(egl, display, config, EGL10.EGL_ALPHA_SIZE) == alphaSize
        } ?: candidates.firstOrNull()
    }

    private fun attribute(egl: EGL10, display: EGLDisplay, config: EGLConfig, attribute: Int): Int {
        val value = IntArray(1)
        return if (egl.eglGetConfigAttrib(display, config, attribute, value)) value[0] else 0
    }

    companion object {
        private val DEPTH_SIZES = intArrayOf(24, 16)
    }
}
