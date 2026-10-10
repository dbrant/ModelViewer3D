package com.dmitrybrant.modelviewer.gltf

import com.dmitrybrant.modelviewer.MeshModel
import com.dmitrybrant.modelviewer.MeshPart
import com.dmitrybrant.modelviewer.ModelResources
import java.io.InputStream

/*
* Displays a glTF model (GLB, or .gltf with its buffers and images), with its materials, textures,
* and vertex colors. Animations are ignored, so the model is shown in its default pose.
*
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
class GltfModel(inputStream: InputStream, resources: ModelResources? = null) : MeshModel() {
    init {
        setMesh(GltfReader(inputStream.readBytes(), resources).read())
    }

    private fun setMesh(mesh: GltfReader.Mesh) {
        vertexCount = mesh.vertexCount
        val positions = mesh.positions
        var sumX = 0.0
        var sumY = 0.0
        var sumZ = 0.0
        for (i in 0 until vertexCount) {
            val x = positions[i * 3]
            val y = positions[i * 3 + 1]
            val z = positions[i * 3 + 2]
            adjustMaxMin(x, y, z)
            sumX += x
            sumY += y
            sumZ += z
        }
        centerMassX = (sumX / vertexCount).toFloat()
        centerMassY = (sumY / vertexCount).toFloat()
        centerMassZ = (sumZ / vertexCount).toFloat()

        vertexBuffer = allocateFloats(positions)
        normalBuffer = allocateFloats(mesh.normals)
        mesh.texCoords?.let { texCoordBuffer = allocateFloats(it) }
        mesh.colors?.let { colorBuffer = allocateFloats(it) }
        indexCount = mesh.indices.size
        indexBuffer = allocateInts(mesh.indices)
        parts = mesh.parts.map { MeshPart(it.material, it.firstIndex, it.indexCount) }
    }

    public override fun initModelMatrix(boundSize: Float) {
        // The Y axis is up, as in OBJ files.
        val yRotation = 180f
        initModelMatrix(boundSize, 0.0f, yRotation, 0.0f)
        var scale = getBoundScale(boundSize)
        if (scale == 0.0f) {
            scale = 1.0f
        }
        floorOffset = (minY - centerMassY) / scale
    }
}
