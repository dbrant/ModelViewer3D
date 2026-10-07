package com.dmitrybrant.modelviewer.vdb

import com.dmitrybrant.modelviewer.util.FloatList
import com.dmitrybrant.modelviewer.util.IntList
import kotlin.math.sqrt

/*
* Extracts an isosurface from a sparse VDB grid, using the "naive surface nets" algorithm:
* one vertex is placed inside every cell (cube of 8 neighboring voxels) that the surface passes
* through, and every cell edge that the surface crosses produces a quad that joins the vertices
* of the four cells around that edge.
*
* Info on the algorithm: https://0fps.net/2012/07/12/smooth-voxel-terrain-part-2/
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
class SurfaceNets(private val grid: VdbGrid, private val isoValue: Float, private val insideIsAbove: Boolean) {
    /**
     * The arrays may be larger than needed, so only the first [vertexCount] * 3 values of the
     * vertex and normal arrays, and the first [indexCount] values of the index array are valid.
     */
    class Mesh(val vertices: FloatArray, val normals: FloatArray, val vertexCount: Int,
               val indices: IntArray, val indexCount: Int)

    /**
     * Extracts the surface, in world coordinates.
     */
    fun extract(): Mesh {
        val dim = grid.leafDim
        val paddedDim = dim + 1
        val cellCount = dim * dim * dim

        // The cells whose lowest corner lies in a leaf, or just below a leaf (along any axis),
        // are the ones that can contain the surface.
        val blockKeys = HashSet<Long>(grid.leaves.size * 4)
        for (key in grid.leaves.keys) {
            val x = VdbGrid.unpackKeyX(key)
            val y = VdbGrid.unpackKeyY(key)
            val z = VdbGrid.unpackKeyZ(key)
            for (n in 0 until 8) {
                blockKeys.add(VdbGrid.packKey(x - (n and 1), y - ((n shr 1) and 1), z - ((n shr 2) and 1)))
            }
        }

        val positions = FloatList(0x10000)
        val normals = FloatList(0x10000)
        val cellVertices = HashMap<Long, IntArray>()
        val cellMasks = HashMap<Long, ByteArray>()
        val padded = FloatArray(paddedDim * paddedDim * paddedDim)
        val masks = ByteArray(cellCount)
        val corners = FloatArray(8)
        val point = FloatArray(3)
        var vertexCount = 0

        // Pass 1: create a vertex in each cell that the surface passes through.
        for (key in blockKeys) {
            val bx = VdbGrid.unpackKeyX(key)
            val by = VdbGrid.unpackKeyY(key)
            val bz = VdbGrid.unpackKeyZ(key)
            fillPaddedBlock(bx, by, bz, padded)

            var vertices: IntArray? = null
            for (x in 0 until dim) {
                for (y in 0 until dim) {
                    for (z in 0 until dim) {
                        var mask = 0
                        for (n in 0 until 8) {
                            val v = padded[((x + (n and 1)) * paddedDim + y + ((n shr 1) and 1)) * paddedDim + z + ((n shr 2) and 1)]
                            corners[n] = v
                            if (v < 0f) mask = mask or (1 shl n)
                        }
                        val cell = (x * dim + y) * dim + z
                        masks[cell] = mask.toByte()
                        if (mask == 0 || mask == 0xff) {
                            continue
                        }
                        if (vertices == null) {
                            vertices = IntArray(cellCount) { -1 }
                        }
                        vertices[cell] = vertexCount++

                        // Place the vertex at the average of the points where the surface crosses the cell edges.
                        point[0] = 0f; point[1] = 0f; point[2] = 0f
                        var crossings = 0
                        for (e in 0 until 12) {
                            val c0 = EDGES[e * 2]
                            val c1 = EDGES[e * 2 + 1]
                            val f0 = corners[c0]
                            val f1 = corners[c1]
                            if ((f0 < 0f) == (f1 < 0f)) {
                                continue
                            }
                            val t = f0 / (f0 - f1)
                            for (axis in 0 until 3) {
                                val p0 = (c0 shr axis) and 1
                                val p1 = (c1 shr axis) and 1
                                point[axis] += p0 + t * (p1 - p0)
                            }
                            crossings++
                        }
                        // Voxel (index space) coordinates, then transformed to world space.
                        val sx = (bx * dim + x) + point[0] / crossings
                        val sy = (by * dim + y) + point[1] / crossings
                        val sz = (bz * dim + z) + point[2] / crossings
                        val m = grid.transform
                        positions.add((sx * m[0] + sy * m[3] + sz * m[6] + m[9]).toFloat())
                        positions.add((sx * m[1] + sy * m[4] + sz * m[7] + m[10]).toFloat())
                        positions.add((sx * m[2] + sy * m[5] + sz * m[8] + m[11]).toFloat())

                        // The normal is the gradient of the field, which points outward since the field is
                        // negative inside the surface.
                        normals.add((corners[1] - corners[0]) + (corners[3] - corners[2]) + (corners[5] - corners[4]) + (corners[7] - corners[6]))
                        normals.add((corners[2] - corners[0]) + (corners[3] - corners[1]) + (corners[6] - corners[4]) + (corners[7] - corners[5]))
                        normals.add((corners[4] - corners[0]) + (corners[5] - corners[1]) + (corners[6] - corners[2]) + (corners[7] - corners[3]))
                    }
                }
            }
            if (vertices != null) {
                cellVertices[key] = vertices
                cellMasks[key] = masks.copyOf()
            }
        }

        // Pass 2: connect the vertices of the four cells around each edge that the surface crosses.
        val indices = IntList(0x10000)
        val neighbors = arrayOfNulls<IntArray>(8)
        val quad = IntArray(4)
        for ((key, vertices) in cellVertices) {
            val bx = VdbGrid.unpackKeyX(key)
            val by = VdbGrid.unpackKeyY(key)
            val bz = VdbGrid.unpackKeyZ(key)
            val masks = cellMasks[key]!!
            // Cells along an edge may be in the neighboring blocks below this one.
            for (n in 0 until 8) {
                neighbors[n] = if (n == 0) vertices else
                    cellVertices[VdbGrid.packKey(bx - (n and 1), by - ((n shr 1) and 1), bz - ((n shr 2) and 1))]
            }
            for (x in 0 until dim) {
                for (y in 0 until dim) {
                    for (z in 0 until dim) {
                        val cell = (x * dim + y) * dim + z
                        if (vertices[cell] < 0) {
                            continue
                        }
                        val mask = masks[cell].toInt()
                        val inside0 = mask and 1
                        for (axis in 0 until 3) {
                            val inside1 = (mask shr (1 shl axis)) and 1
                            if (inside0 == inside1) {
                                continue
                            }
                            // The other two axes, in cyclic order, so that the quad winds counterclockwise
                            // when viewed from the direction that the edge points.
                            val axisU = (axis + 1) % 3
                            val axisV = (axis + 2) % 3
                            var valid = true
                            for (q in 0 until 4) {
                                // Quad corners: (-1,-1), (0,-1), (0,0), (-1,0) in (u,v)
                                val du = if (q == 0 || q == 3) -1 else 0
                                val dv = if (q < 2) -1 else 0
                                val cx = x + (if (axisU == 0) du else 0) + (if (axisV == 0) dv else 0)
                                val cy = y + (if (axisU == 1) du else 0) + (if (axisV == 1) dv else 0)
                                val cz = z + (if (axisU == 2) du else 0) + (if (axisV == 2) dv else 0)
                                val neighbor = (if (cx < 0) 1 else 0) or (if (cy < 0) 2 else 0) or (if (cz < 0) 4 else 0)
                                val array = neighbors[neighbor]
                                val v = array?.get((((cx + dim) % dim) * dim + (cy + dim) % dim) * dim + (cz + dim) % dim) ?: -1
                                if (v < 0) {
                                    valid = false
                                    break
                                }
                                quad[q] = v
                            }
                            if (!valid) {
                                continue
                            }
                            // If the inside is at the lower end of the edge, the surface faces toward
                            // the positive direction of the edge.
                            if (inside0 == 1) {
                                indices.add(quad[0]); indices.add(quad[1]); indices.add(quad[2])
                                indices.add(quad[0]); indices.add(quad[2]); indices.add(quad[3])
                            } else {
                                indices.add(quad[0]); indices.add(quad[2]); indices.add(quad[1])
                                indices.add(quad[0]); indices.add(quad[3]); indices.add(quad[2])
                            }
                        }
                    }
                }
            }
        }

        val normalArray = normals.array
        transformNormals(normalArray, vertexCount * 3)
        val indexArray = indices.array
        if (VdbGrid.determinant3x3(grid.transform) < 0.0) {
            // A mirroring transform reverses the winding of the triangles.
            for (i in 0 until indices.size step 3) {
                val temp = indexArray[i + 1]
                indexArray[i + 1] = indexArray[i + 2]
                indexArray[i + 2] = temp
            }
        }
        return Mesh(positions.array, normalArray, vertexCount, indexArray, indices.size)
    }

    /**
     * Fills an array of (dim+1)^3 values of the implicit function (negative inside the surface),
     * covering the given leaf-sized block and one voxel past its upper boundary along each axis.
     */
    private fun fillPaddedBlock(bx: Int, by: Int, bz: Int, padded: FloatArray) {
        val dim = grid.leafDim
        val paddedDim = dim + 1
        for (n in 0 until 8) {
            val nx = n and 1
            val ny = (n shr 1) and 1
            val nz = (n shr 2) and 1
            val block = grid.leaves[VdbGrid.packKey(bx + nx, by + ny, bz + nz)]
            val tileValue = if (block == null) grid.tileValue((bx + nx) * dim, (by + ny) * dim, (bz + nz) * dim) else 0f
            // The region of the padded array that comes from this neighbor
            val x0 = nx * dim
            val x1 = if (nx == 0) dim else paddedDim
            val y0 = ny * dim
            val y1 = if (ny == 0) dim else paddedDim
            val z0 = nz * dim
            val z1 = if (nz == 0) dim else paddedDim
            for (x in x0 until x1) {
                for (y in y0 until y1) {
                    for (z in z0 until z1) {
                        val value = block?.get(((x - x0) * dim + (y - y0)) * dim + (z - z0)) ?: tileValue
                        padded[(x * paddedDim + y) * paddedDim + z] = if (insideIsAbove) isoValue - value else value - isoValue
                    }
                }
            }
        }
    }

    /**
     * Transforms normals from index space to world space, which requires the inverse transpose of
     * the transform's linear part. Since the matrix is applied to row vectors, that works out to
     * multiplying the normal (as a column vector) by the plain inverse.
     */
    private fun transformNormals(normals: FloatArray, count: Int) {
        val inv = VdbGrid.invert3x3(grid.transform)
        for (i in 0 until count step 3) {
            val x = normals[i]
            val y = normals[i + 1]
            val z = normals[i + 2]
            val nx = (inv[0] * x + inv[1] * y + inv[2] * z).toFloat()
            val ny = (inv[3] * x + inv[4] * y + inv[5] * z).toFloat()
            val nz = (inv[6] * x + inv[7] * y + inv[8] * z).toFloat()
            val length = sqrt(nx * nx + ny * ny + nz * nz)
            if (length > 0f) {
                normals[i] = nx / length
                normals[i + 1] = ny / length
                normals[i + 2] = nz / length
            }
        }
    }

    companion object {
        // Pairs of cell corners that make up the 12 edges of a cell. Corner n is at offset
        // (n & 1, (n >> 1) & 1, (n >> 2) & 1).
        private val EDGES = intArrayOf(
            0, 1, 2, 3, 4, 5, 6, 7,
            0, 2, 1, 3, 4, 6, 5, 7,
            0, 4, 1, 5, 2, 6, 3, 7)
    }
}
