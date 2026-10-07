package com.dmitrybrant.modelviewer.vdb

import com.dmitrybrant.modelviewer.MeshModel
import java.io.IOException
import java.io.InputStream
import kotlin.math.pow

/*
* Displays an OpenVDB volume as a surface mesh. Level sets are shown as their zero isosurface,
* and fog volumes (e.g. smoke or clouds) are shown as the isosurface at a fixed fraction of their
* maximum density (when they can't be rendered as volumes; see VdbLoader). If the file also has
* a color grid, the surface is colored with it.
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
class VdbModel(surface: Surface) : MeshModel() {
    /**
     * The surface of a volume and its colors, which are extracted before creating the model, so
     * that the voxel data can be released before the vertex buffers are created.
     */
    class Surface(val mesh: SurfaceNets.Mesh, val colors: FloatArray?, val gridName: String, val colorGridName: String?)

    val gridName = surface.gridName
    val colorGridName = surface.colorGridName

    constructor(inputStream: InputStream) : this(extractSurface(VdbReader(inputStream).read()))

    init {
        val mesh = surface.mesh
        if (mesh.indexCount == 0) {
            throw IOException("No surface found in volume.")
        }
        setMesh(mesh)
        surface.colors?.let { colorBuffer = allocateFloats(it) }
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

        vertexBuffer = allocateFloats(vertices, vertexCount * 3)
        normalBuffer = allocateFloats(mesh.normals, vertexCount * 3)
        indexBuffer = allocateInts(mesh.indices, indexCount)
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

        /**
         * Extracts the surface of the volume at full resolution (and its colors, if any).
         */
        fun extractSurface(grids: VdbReader.Grids): Surface {
            val grid = grids.surface
            val surfaceNets = if (grid.isLevelSet) {
                SurfaceNets(grid, 0f, false)
            } else {
                SurfaceNets(grid, grid.maxValue * FOG_ISO_FRACTION, true)
            }
            val mesh = surfaceNets.extract()
            val colors = grids.color?.let { sampleColors(it, mesh) }
            return Surface(mesh, colors, grid.name, grids.color?.name)
        }

        private fun sampleColors(colorGrid: VdbGrid, mesh: SurfaceNets.Mesh): FloatArray {
            val colors = FloatArray(mesh.vertexCount * 4)
            val color = FloatArray(3)
            val vertices = mesh.vertices
            for (i in 0 until mesh.vertexCount) {
                colorGrid.sample(vertices[i * 3].toDouble(), vertices[i * 3 + 1].toDouble(), vertices[i * 3 + 2].toDouble(), color)
                for (c in 0 until 3) {
                    colors[i * 4 + c] = linearToSrgb(color[c])
                }
                colors[i * 4 + 3] = 1f
            }
            return colors
        }

        /**
         * Volume colors are in linear space (as used for rendering), so they're converted to sRGB for display.
         */
        fun linearToSrgb(value: Float): Float {
            val c = value.coerceIn(0f, 1f)
            return if (c <= 0.0031308f) c * 12.92f else 1.055f * c.pow(1f / 2.4f) - 0.055f
        }
    }
}
