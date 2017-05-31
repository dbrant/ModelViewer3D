package com.dmitrybrant.modelviewer;

import android.content.Context;
import android.graphics.PointF;
import android.opengl.GLSurfaceView;
import android.support.annotation.NonNull;
import android.support.annotation.Nullable;
import android.view.MotionEvent;

import com.dmitrybrant.modelviewer.util.Util;

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
public class ModelSurfaceView extends GLSurfaceView {
    private static final int TOUCH_NONE = 0;
    private static final int TOUCH_ROTATE = 1;
    private static final int TOUCH_ZOOM = 2;

    @NonNull private ModelRenderer renderer;

    private float previousX;
    private float previousY;

    private PointF pinchStartPoint = new PointF();
    private float pinchStartDistance = 0.0f;
    private int touchMode = TOUCH_NONE;

    public ModelSurfaceView(@NonNull Context context, @Nullable Model model) {
        super(context);
        setEGLContextClientVersion(2);

        renderer = new ModelRenderer(model);
        setRenderer(renderer);
        setRenderMode(GLSurfaceView.RENDERMODE_WHEN_DIRTY);
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        switch (event.getAction() & MotionEvent.ACTION_MASK) {
            case MotionEvent.ACTION_DOWN:
                previousX = event.getX();
                previousY = event.getY();
                break;

            case MotionEvent.ACTION_MOVE:
                if (event.getPointerCount() == 1) {
                    if (touchMode != TOUCH_ROTATE) {
                        previousX = event.getX();
                        previousY = event.getY();
                    }
                    touchMode = TOUCH_ROTATE;
                    float x = event.getX();
                    float y = event.getY();
                    float dx = x - previousX;
                    float dy = y - previousY;
                    previousX = x;
                    previousY = y;
                    renderer.rotate(Util.pxToDp(dy), Util.pxToDp(dx));
                } else if (event.getPointerCount() == 2) {
                    if (touchMode != TOUCH_ZOOM) {
                        pinchStartDistance = getPinchDistance(event);
                        getPinchCenterPoint(event, pinchStartPoint);
                        previousX = pinchStartPoint.x;
                        previousY = pinchStartPoint.y;
                        touchMode = TOUCH_ZOOM;
                    } else {
                        PointF pt = new PointF();
                        getPinchCenterPoint(event, pt);
                        float dx = pt.x - previousX;
                        float dy = pt.y - previousY;
                        previousX = pt.x;
                        previousY = pt.y;
                        float pinchScale = getPinchDistance(event) / pinchStartDistance;
                        pinchStartDistance = getPinchDistance(event);
                        renderer.translate(Util.pxToDp(dx), Util.pxToDp(dy), pinchScale);
                    }

                }
                requestRender();
                break;

            case MotionEvent.ACTION_UP:
                pinchStartPoint.x = 0.0f;
                pinchStartPoint.y = 0.0f;
                touchMode = TOUCH_NONE;
                break;
        }
        return true;
    }

    private float getPinchDistance(MotionEvent event) {
        float x = event.getX(0) - event.getX(1);
        float y = event.getY(0) - event.getY(1);
        return (float) Math.sqrt(x * x + y * y);
    }

    private void getPinchCenterPoint(MotionEvent event, PointF pt) {
        pt.x = (event.getX(0) + event.getX(1)) * 0.5f;
        pt.y = (event.getY(0) + event.getY(1)) * 0.5f;
    }
}
