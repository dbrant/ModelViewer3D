package com.dmitrybrant.modelviewer.threemf

import com.dmitrybrant.modelviewer.Material
import com.dmitrybrant.modelviewer.MeshModel
import com.dmitrybrant.modelviewer.MeshPart
import java.io.IOException
import java.io.InputStream
import kotlin.math.sqrt

/*
* Displays a 3MF model: the objects in its build, with their colors and textures. Like STL files,
* 3MF files are mostly made for 3D printing, so the models are flat shaded, with the Z axis up.
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
class ThreeMfModel(inputStream: InputStream) : MeshModel() {
    init {
        val mesh = ThreeMfReader(inputStream).read()
        if (mesh.triangleCount == 0) {
            throw IOException("No triangles found in model.")
        }
        setMesh(mesh)
    }

    private fun setMesh(mesh: ThreeMfReader.Mesh) {
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
        normalBuffer = allocateFloats(faceNormals(positions, mesh.triangleCount))
        mesh.colors?.let { colorBuffer = allocateFloats(it) }
        if (mesh.texCoords != null) {
            texCoordBuffer = allocateFloats(mesh.texCoords)
            parts = mesh.parts.map { part ->
                val material = if (part.texture != null || mesh.colors != null) Material.forVertexColors() else Material()
                material.diffuseTexture = part.texture
                material.diffuseTextureName = part.textureName
                MeshPart(material, part.firstTriangle * 3, part.triangleCount * 3)
            }
        }
    }

    /** The normal of each triangle, at each of its corners. */
    private fun faceNormals(positions: FloatArray, triangleCount: Int): FloatArray {
        val normals = FloatArray(triangleCount * 9)
        for (t in 0 until triangleCount) {
            val i = t * 9
            val e1x = positions[i + 3] - positions[i]
            val e1y = positions[i + 4] - positions[i + 1]
            val e1z = positions[i + 5] - positions[i + 2]
            val e2x = positions[i + 6] - positions[i]
            val e2y = positions[i + 7] - positions[i + 1]
            val e2z = positions[i + 8] - positions[i + 2]
            var nx = e1y * e2z - e1z * e2y
            var ny = e1z * e2x - e1x * e2z
            var nz = e1x * e2y - e1y * e2x
            val length = sqrt(nx * nx + ny * ny + nz * nz)
            if (length > 0f) {
                nx /= length
                ny /= length
                nz /= length
            }
            for (c in 0 until 3) {
                normals[i + c * 3] = nx
                normals[i + c * 3 + 1] = ny
                normals[i + c * 3 + 2] = nz
            }
        }
        return normals
    }

    public override fun initModelMatrix(boundSize: Float) {
        // The Z axis is up, as in STL files.
        initModelMatrix(boundSize, -90.0f, 0.0f, 180.0f)
        var scale = getBoundScale(boundSize)
        if (scale == 0.0f) {
            scale = 1.0f
        }
        floorOffset = (minZ - centerMassZ) / scale
    }
}
