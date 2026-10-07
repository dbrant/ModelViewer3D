package com.dmitrybrant.modelviewer

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
 * Surface properties of a model (or part of a model), such as those defined in OBJ material
 * libraries. Colors are RGB, with components from 0 to 1.
 */
class Material(val name: String = "") {
    var diffuseColor = floatArrayOf(0.8f, 0.8f, 0.8f)
    var specularColor = floatArrayOf(0f, 0f, 0f)
    var emissiveColor = floatArrayOf(0f, 0f, 0f)
    var shininess = 1f
    var opacity = 1f

    /** How the material is lit: 0 = not at all (constant color), 1 = diffuse only, 2 = diffuse and specular. */
    var lighting = LIGHTING_SPECULAR

    /** Encoded image (e.g. PNG or JPEG) to use as the diffuse texture, which is multiplied by the diffuse color. */
    var diffuseTexture: ByteArray? = null
    var diffuseTextureName: String? = null

    /** Scale and offset that are applied to texture coordinates. */
    var textureScale = floatArrayOf(1f, 1f)
    var textureOffset = floatArrayOf(0f, 0f)

    companion object {
        const val LIGHTING_NONE = 0
        const val LIGHTING_DIFFUSE = 1
        const val LIGHTING_SPECULAR = 2

        /** Material for models whose colors come only from their vertex colors. */
        fun forVertexColors() = Material().apply {
            diffuseColor = floatArrayOf(1f, 1f, 1f)
            specularColor = floatArrayOf(0.2f, 0.2f, 0.2f)
            shininess = 20f
        }
    }
}

/**
 * A range of a mesh's triangles that are drawn with the given material. The range is in terms of
 * indices if the mesh has an index buffer, or vertices otherwise.
 */
class MeshPart(val material: Material, val first: Int, val count: Int)
