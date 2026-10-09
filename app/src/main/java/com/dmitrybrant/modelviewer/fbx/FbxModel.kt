package com.dmitrybrant.modelviewer.fbx

import com.dmitrybrant.modelviewer.MeshModel
import com.dmitrybrant.modelviewer.MeshPart
import com.dmitrybrant.modelviewer.ModelResources
import java.io.InputStream

/*
* Displays an FBX model (binary, or ASCII): the meshes of its scene, with their materials and
* textures. Textures may be embedded in the file, or in separate files, which are opened from the
* given resources. Animations are ignored, so the models are shown in the pose that's in the file.
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
class FbxModel(inputStream: InputStream, resources: ModelResources? = null) : MeshModel() {
    init {
        val document = FbxParser.parse(inputStream.readBytes())
        setMesh(FbxScene(document, resources).build())
    }

    private fun setMesh(mesh: FbxScene.Mesh) {
        vertexCount = mesh.triangleCount * 3
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
        parts = mesh.parts.map { MeshPart(it.material, it.firstTriangle * 3, it.triangleCount * 3) }
    }

    public override fun initModelMatrix(boundSize: Float) {
        // The scene is converted to have the Y axis up, as in OBJ files.
        val yRotation = 180f
        initModelMatrix(boundSize, 0.0f, yRotation, 0.0f)
        var scale = getBoundScale(boundSize)
        if (scale == 0.0f) {
            scale = 1.0f
        }
        floorOffset = (minY - centerMassY) / scale
    }
}
