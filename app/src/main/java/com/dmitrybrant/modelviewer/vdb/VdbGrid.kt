package com.dmitrybrant.modelviewer.vdb

import kotlin.math.floor

/*
* An OpenVDB grid of scalar or vector values, flattened into the parts needed for extracting
* a surface, resampling a volume, and sampling colors: the voxel values of the leaf nodes, plus
* the tile values of the internal and root nodes, which provide values for regions that have no
* leaf nodes.
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
class VdbGrid(
    val name: String,
    val gridClass: String,
    /** Number of floats in each value: 1 for scalar grids, or 3 for vector grids. */
    val components: Int,
    val backgroundValue: FloatArray,
    /** Index-to-world transform as a 4x3 affine matrix, applied to row vectors: [x y z 1] * M. */
    val transform: DoubleArray,
    /** Log2 of the leaf node dimension (3 for standard 8x8x8 leaves). */
    val leafLog2: Int,
    /** Tile values of internal nodes, ordered from the level just above the leaves to the top. */
    internal val internalLevels: List<InternalLevel>,
    /** Tile values of the root node, keyed by tile origin shifted right by [rootChildLog2]. */
    internal val rootTiles: Map<Long, FloatArray>,
    internal val rootChildLog2: Int,
    /** Voxel values of each leaf node (with [components] floats per voxel), keyed by leaf origin shifted right by [leafLog2]. */
    val leaves: Map<Long, FloatArray>,
    val minValue: Float,
    val maxValue: Float
) {
    class InternalLevel(val log2: Int, val totalLog2: Int, val nodes: Map<Long, FloatArray>)

    val leafDim get() = 1 shl leafLog2

    /** The background value of a scalar grid. */
    val background get() = backgroundValue[0]

    private val inverseTransform by lazy { invert3x3(transform) }
    private var cachedLeafKey = Long.MIN_VALUE
    private var cachedLeaf: FloatArray? = null
    private val sampleScratch = FloatArray(components)

    val isLevelSet get() = when (gridClass) {
        GRID_CLASS_LEVEL_SET -> true
        GRID_CLASS_FOG_VOLUME -> false
        else -> minValue < 0f && maxValue > 0f
    }

    /**
     * Returns the (first component of the) value at the given voxel coordinates, for a location
     * that is not inside any leaf node.
     */
    fun tileValue(x: Int, y: Int, z: Int): Float {
        val value = FloatArray(components)
        tileValue(x, y, z, value)
        return value[0]
    }

    /**
     * Gets the value at the given voxel coordinates, for a location that is not inside any leaf node.
     */
    fun tileValue(x: Int, y: Int, z: Int, value: FloatArray) {
        for (level in internalLevels) {
            val node = level.nodes[packKey(x shr level.totalLog2, y shr level.totalLog2, z shr level.totalLog2)] ?: continue
            val childLog2 = level.totalLog2 - level.log2
            val mask = (1 shl level.log2) - 1
            val index = (((x shr childLog2) and mask) shl (2 * level.log2)) or
                    (((y shr childLog2) and mask) shl level.log2) or ((z shr childLog2) and mask)
            node.copyInto(value, 0, index * components, index * components + components)
            return
        }
        val tile = rootTiles[packKey(x shr rootChildLog2, y shr rootChildLog2, z shr rootChildLog2)] ?: backgroundValue
        tile.copyInto(value)
    }

    /**
     * Calls the given function for each tile of the internal nodes (a region with a single value,
     * where there's no child node) whose value (in its first component) differs from the background,
     * with the tile's origin, log2 size, and value. Tiles of the root node, which are enormous, aren't included.
     */
    fun forEachTile(action: (x: Int, y: Int, z: Int, log2Size: Int, value: Float) -> Unit) {
        for ((levelIndex, level) in internalLevels.withIndex()) {
            val childLog2 = level.totalLog2 - level.log2
            val children = if (levelIndex == 0) leaves else internalLevels[levelIndex - 1].nodes
            val mask = (1 shl level.log2) - 1
            for ((key, values) in level.nodes) {
                val originX = unpackKeyX(key) shl level.totalLog2
                val originY = unpackKeyY(key) shl level.totalLog2
                val originZ = unpackKeyZ(key) shl level.totalLog2
                for (i in 0 until (1 shl (3 * level.log2))) {
                    val value = values[i * components]
                    if (value == background) {
                        continue
                    }
                    val x = originX + (((i shr (2 * level.log2)) and mask) shl childLog2)
                    val y = originY + (((i shr level.log2) and mask) shl childLog2)
                    val z = originZ + ((i and mask) shl childLog2)
                    // The values of child nodes' positions aren't meaningful.
                    if (!children.containsKey(packKey(x shr childLog2, y shr childLog2, z shr childLog2))) {
                        action(x, y, z, childLog2, value)
                    }
                }
            }
        }
    }

    /**
     * Gets the value at the given voxel coordinates.
     */
    fun voxelValue(x: Int, y: Int, z: Int, value: FloatArray) {
        val key = packKey(x shr leafLog2, y shr leafLog2, z shr leafLog2)
        // Consecutive lookups are usually in the same leaf.
        if (key != cachedLeafKey) {
            cachedLeafKey = key
            cachedLeaf = leaves[key]
        }
        val leaf = cachedLeaf
        if (leaf == null) {
            tileValue(x, y, z, value)
            return
        }
        val mask = leafDim - 1
        val index = (((x and mask) shl (2 * leafLog2)) or ((y and mask) shl leafLog2) or (z and mask)) * components
        leaf.copyInto(value, 0, index, index + components)
    }

    /**
     * Gets the value at the given world-space position, by trilinear interpolation of the
     * surrounding voxels.
     */
    fun sample(x: Double, y: Double, z: Double, value: FloatArray) {
        // Transform to index space: subtract the translation, and multiply by the inverse of the linear part.
        val m = inverseTransform
        val dx = x - transform[9]
        val dy = y - transform[10]
        val dz = z - transform[11]
        val ix = dx * m[0] + dy * m[3] + dz * m[6]
        val iy = dx * m[1] + dy * m[4] + dz * m[7]
        val iz = dx * m[2] + dy * m[5] + dz * m[8]
        val x0 = floor(ix).toInt()
        val y0 = floor(iy).toInt()
        val z0 = floor(iz).toInt()
        val fx = (ix - x0).toFloat()
        val fy = (iy - y0).toFloat()
        val fz = (iz - z0).toFloat()
        value.fill(0f)
        for (n in 0 until 8) {
            val wx = if (n and 1 != 0) fx else 1f - fx
            val wy = if (n and 2 != 0) fy else 1f - fy
            val wz = if (n and 4 != 0) fz else 1f - fz
            val weight = wx * wy * wz
            if (weight == 0f) {
                continue
            }
            voxelValue(x0 + (n and 1), y0 + ((n shr 1) and 1), z0 + ((n shr 2) and 1), sampleScratch)
            for (c in 0 until components) {
                value[c] += weight * sampleScratch[c]
            }
        }
    }

    companion object {
        const val GRID_CLASS_LEVEL_SET = "level set"
        const val GRID_CLASS_FOG_VOLUME = "fog volume"

        private const val KEY_BITS = 21
        private const val KEY_MASK = (1L shl KEY_BITS) - 1

        fun packKey(x: Int, y: Int, z: Int): Long {
            return ((x.toLong() and KEY_MASK) shl (2 * KEY_BITS)) or
                    ((y.toLong() and KEY_MASK) shl KEY_BITS) or (z.toLong() and KEY_MASK)
        }

        fun unpackKeyX(key: Long) = signExtend(key ushr (2 * KEY_BITS))
        fun unpackKeyY(key: Long) = signExtend(key ushr KEY_BITS)
        fun unpackKeyZ(key: Long) = signExtend(key)

        private fun signExtend(value: Long): Int {
            return ((value and KEY_MASK) shl (64 - KEY_BITS) shr (64 - KEY_BITS)).toInt()
        }

        /** Determinant of the 3x3 linear part of a transform (row-major). */
        fun determinant3x3(m: DoubleArray): Double {
            return m[0] * (m[4] * m[8] - m[5] * m[7]) - m[1] * (m[3] * m[8] - m[5] * m[6]) + m[2] * (m[3] * m[7] - m[4] * m[6])
        }

        /** Inverse of the 3x3 linear part of a transform (row-major), or the identity if it's not invertible. */
        fun invert3x3(m: DoubleArray): DoubleArray {
            val det = determinant3x3(m)
            if (det == 0.0) {
                return doubleArrayOf(1.0, 0.0, 0.0, 0.0, 1.0, 0.0, 0.0, 0.0, 1.0)
            }
            return doubleArrayOf(
                (m[4] * m[8] - m[5] * m[7]) / det, (m[2] * m[7] - m[1] * m[8]) / det, (m[1] * m[5] - m[2] * m[4]) / det,
                (m[5] * m[6] - m[3] * m[8]) / det, (m[0] * m[8] - m[2] * m[6]) / det, (m[2] * m[3] - m[0] * m[5]) / det,
                (m[3] * m[7] - m[4] * m[6]) / det, (m[1] * m[6] - m[0] * m[7]) / det, (m[0] * m[4] - m[1] * m[3]) / det)
        }
    }
}
