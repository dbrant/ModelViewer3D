package com.dmitrybrant.modelviewer

import android.content.Context
import android.graphics.PointF
import android.opengl.GLSurfaceView
import android.view.MotionEvent
import com.dmitrybrant.modelviewer.util.Util.pxToDp
import kotlin.math.sqrt

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
class ModelSurfaceView(context: Context, model: MutableList<Model>?) : GLSurfaceView(context) {
    private val renderer: ModelRenderer
    private var previousX = 0f
    private var previousY = 0f
    private val pinchStartPoint = PointF()
    private var pinchStartDistance = 0.0f
    private var touchMode = TOUCH_NONE

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.action and MotionEvent.ACTION_MASK) {
            MotionEvent.ACTION_DOWN -> {
                previousX = event.x
                previousY = event.y
            }
            MotionEvent.ACTION_MOVE -> {
                if (event.pointerCount == 1) {
                    if (touchMode != TOUCH_ROTATE) {
                        previousX = event.x
                        previousY = event.y
                    }
                    touchMode = TOUCH_ROTATE
                    val x = event.x
                    val y = event.y
                    val dx = x - previousX
                    val dy = y - previousY
                    previousX = x
                    previousY = y
                    renderer.rotate(pxToDp(dy), pxToDp(dx))
                } else if (event.pointerCount == 2) {
                    if (touchMode != TOUCH_ZOOM) {
                        pinchStartDistance = getPinchDistance(event)
                        getPinchCenterPoint(event, pinchStartPoint)
                        previousX = pinchStartPoint.x
                        previousY = pinchStartPoint.y
                        touchMode = TOUCH_ZOOM
                    } else {
                        val pt = PointF()
                        getPinchCenterPoint(event, pt)
                        val dx = pt.x - previousX
                        val dy = pt.y - previousY
                        previousX = pt.x
                        previousY = pt.y
                        val pinchScale = getPinchDistance(event) / pinchStartDistance
                        pinchStartDistance = getPinchDistance(event)
                        renderer.translate(pxToDp(dx), pxToDp(dy), pinchScale)
                    }
                }
                requestRender()
            }
            MotionEvent.ACTION_UP -> {
                pinchStartPoint.x = 0.0f
                pinchStartPoint.y = 0.0f
                touchMode = TOUCH_NONE
            }
        }
        return true
    }

    private fun getPinchDistance(event: MotionEvent): Float {
        val x = event.getX(0) - event.getX(1)
        val y = event.getY(0) - event.getY(1)
        return sqrt((x * x + y * y).toDouble()).toFloat()
    }

    private fun getPinchCenterPoint(event: MotionEvent, pt: PointF) {
        pt.x = (event.getX(0) + event.getX(1)) * 0.5f
        pt.y = (event.getY(0) + event.getY(1)) * 0.5f
    }

    companion object {
        private const val TOUCH_NONE = 0
        private const val TOUCH_ROTATE = 1
        private const val TOUCH_ZOOM = 2
    }

    init {
        setEGLContextClientVersion(2)
        renderer = ModelRenderer(model)
        setRenderer(renderer)
        renderMode = RENDERMODE_WHEN_DIRTY
    }
}