package com.dmitrybrant.modelviewer.vdb

import android.opengl.GLES20
import android.opengl.Matrix
import com.dmitrybrant.modelviewer.IndexedModel
import com.dmitrybrant.modelviewer.Light
import java.io.IOException
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/*
* Displays an OpenVDB volume as a surface mesh. Level sets are shown as their zero isosurface,
* and fog volumes (e.g. smoke or clouds) are shown as the isosurface at a fixed fraction of their
* maximum density.
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
class VdbModel(inputStream: InputStream) : IndexedModel() {
    var gridName = ""
        private set

    // Vertex, normal, and index buffer objects. Volumes tend to produce large meshes, so the mesh
    // is uploaded to the GPU once, instead of being copied from client memory on every frame.
    private val bufferIds = IntArray(3)

    init {
        val mesh = extractMesh(inputStream)
        if (mesh.indexCount == 0) {
            throw IOException("No surface found in volume.")
        }
        setMesh(mesh)
    }

    /**
     * Reads the volume and extracts its surface at full resolution. This is kept separate from
     * creating the vertex buffers, so that the voxel data can be released before then.
     */
    private fun extractMesh(inputStream: InputStream): SurfaceNets.Mesh {
        val grid = VdbReader(inputStream).readGrid()
        gridName = grid.name
        val surfaceNets = if (grid.isLevelSet) {
            SurfaceNets(grid, 0f, false)
        } else {
            SurfaceNets(grid, grid.maxValue * FOG_ISO_FRACTION, true)
        }
        return surfaceNets.extract()
    }

    private fun setMesh(mesh: SurfaceNets.Mesh) {
        vertexCount = mesh.vertexCount
        indexCount = mesh.indexCount

        var sumX = 0.0
        var sumY = 0.0
        var sumZ = 0.0
        val vertices = mesh.vertices
        for (i in 0 until vertexCount) {
            val x = vertices[i * 3]
            val y = vertices[i * 3 + 1]
            val z = vertices[i * 3 + 2]
            adjustMaxMin(x, y, z)
            sumX += x
            sumY += y
            sumZ += z
        }
        centerMassX = (sumX / vertexCount).toFloat()
        centerMassY = (sumY / vertexCount).toFloat()
        centerMassZ = (sumZ / vertexCount).toFloat()

        vertexBuffer = ByteBuffer.allocateDirect(vertexCount * 3 * BYTES_PER_FLOAT).order(ByteOrder.nativeOrder()).asFloatBuffer().apply {
            put(vertices, 0, vertexCount * 3)
            position(0)
        }
        normalBuffer = ByteBuffer.allocateDirect(vertexCount * 3 * BYTES_PER_FLOAT).order(ByteOrder.nativeOrder()).asFloatBuffer().apply {
            put(mesh.normals, 0, vertexCount * 3)
            position(0)
        }
        indexBuffer = ByteBuffer.allocateDirect(indexCount * BYTES_PER_INT).order(ByteOrder.nativeOrder()).asIntBuffer().apply {
            put(mesh.indices, 0, indexCount)
            position(0)
        }
    }

    override fun setup(boundSize: Float) {
        super.setup(boundSize)
        // This is called whenever a new GL context is created, so any previous buffers are already gone.
        GLES20.glGenBuffers(bufferIds.size, bufferIds, 0)
        GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, bufferIds[0])
        GLES20.glBufferData(GLES20.GL_ARRAY_BUFFER, vertexCount * VERTEX_STRIDE, vertexBuffer, GLES20.GL_STATIC_DRAW)
        GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, bufferIds[1])
        GLES20.glBufferData(GLES20.GL_ARRAY_BUFFER, vertexCount * VERTEX_STRIDE, normalBuffer, GLES20.GL_STATIC_DRAW)
        GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, 0)
        GLES20.glBindBuffer(GLES20.GL_ELEMENT_ARRAY_BUFFER, bufferIds[2])
        GLES20.glBufferData(GLES20.GL_ELEMENT_ARRAY_BUFFER, indexCount * BYTES_PER_INT, indexBuffer, GLES20.GL_STATIC_DRAW)
        GLES20.glBindBuffer(GLES20.GL_ELEMENT_ARRAY_BUFFER, 0)
    }

    override fun draw(viewMatrix: FloatArray, projectionMatrix: FloatArray, light: Light) {
        GLES20.glUseProgram(glProgram)

        val mvpMatrixHandle = GLES20.glGetUniformLocation(glProgram, "u_MVP")
        val positionHandle = GLES20.glGetAttribLocation(glProgram, "a_Position")
        val normalHandle = GLES20.glGetAttribLocation(glProgram, "a_Normal")
        val lightPosHandle = GLES20.glGetUniformLocation(glProgram, "u_LightPos")
        val ambientColorHandle = GLES20.glGetUniformLocation(glProgram, "u_ambientColor")
        val diffuseColorHandle = GLES20.glGetUniformLocation(glProgram, "u_diffuseColor")
        val specularColorHandle = GLES20.glGetUniformLocation(glProgram, "u_specularColor")

        GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, bufferIds[0])
        GLES20.glEnableVertexAttribArray(positionHandle)
        GLES20.glVertexAttribPointer(positionHandle, COORDS_PER_VERTEX, GLES20.GL_FLOAT, false, VERTEX_STRIDE, 0)
        GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, bufferIds[1])
        GLES20.glEnableVertexAttribArray(normalHandle)
        GLES20.glVertexAttribPointer(normalHandle, COORDS_PER_VERTEX, GLES20.GL_FLOAT, false, VERTEX_STRIDE, 0)
        GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, 0)

        Matrix.multiplyMM(mvMatrix, 0, viewMatrix, 0, modelMatrix, 0)
        Matrix.multiplyMM(mvpMatrix, 0, projectionMatrix, 0, mvMatrix, 0)

        GLES20.glUniformMatrix4fv(mvpMatrixHandle, 1, false, mvpMatrix, 0)
        GLES20.glUniform3fv(lightPosHandle, 1, light.positionInEyeSpace, 0)
        GLES20.glUniform4fv(ambientColorHandle, 1, light.ambientColor, 0)
        GLES20.glUniform4fv(diffuseColorHandle, 1, light.diffuseColor, 0)
        GLES20.glUniform4fv(specularColorHandle, 1, light.specularColor, 0)

        GLES20.glBindBuffer(GLES20.GL_ELEMENT_ARRAY_BUFFER, bufferIds[2])
        GLES20.glDrawElements(GLES20.GL_TRIANGLES, indexCount, GLES20.GL_UNSIGNED_INT, 0)
        GLES20.glBindBuffer(GLES20.GL_ELEMENT_ARRAY_BUFFER, 0)

        GLES20.glDisableVertexAttribArray(normalHandle)
        GLES20.glDisableVertexAttribArray(positionHandle)
    }

    public override fun initModelMatrix(boundSize: Float) {
        initModelMatrix(boundSize, 0.0f, 0.0f, 0.0f)
        var scale = getBoundScale(boundSize)
        if (scale == 0.0f) {
            scale = 1.0f
        }
        floorOffset = (minY - centerMassY) / scale
    }

    companion object {
        // Fraction of the maximum density at which to draw the surface of a fog volume.
        const val FOG_ISO_FRACTION = 0.1f
    }
}
