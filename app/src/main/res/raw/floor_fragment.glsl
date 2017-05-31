/*
 * Adapted from Cardboard SDK sample.
 * Copyright 2017 Google Inc. All Rights Reserved.

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

precision mediump float;
uniform vec4 u_FloorColor;
uniform vec4 u_LineColor;
uniform float u_MaxDepth;
uniform float u_GridUnit;
varying vec3 v_Grid;

void main() {
    float depth = gl_FragCoord.z / gl_FragCoord.w; // Calculate world-space distance.

    if ((mod(abs(v_Grid.x), u_GridUnit) < 0.1) || (mod(abs(v_Grid.z), u_GridUnit) < 0.1)) {
        gl_FragColor = max(0.0, (u_MaxDepth - depth) / u_MaxDepth) * u_LineColor
                + min(1.0, depth / u_MaxDepth) * u_FloorColor;
    } else {
        gl_FragColor = u_FloorColor;
    }
}
