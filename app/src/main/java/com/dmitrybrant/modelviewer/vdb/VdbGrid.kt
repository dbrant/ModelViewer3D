package com.dmitrybrant.modelviewer.vdb

/*
* A scalar OpenVDB grid, flattened into the parts needed for extracting a surface:
* the voxel values of the leaf nodes, plus the tile values of the internal and root nodes,
* which provide values for regions that have no leaf nodes.
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
    val background: Float,
    /** Index-to-world transform as a 4x3 affine matrix, applied to row vectors: [x y z 1] * M. */
    val transform: DoubleArray,
    /** Log2 of the leaf node dimension (3 for standard 8x8x8 leaves). */
    val leafLog2: Int,
    /** Tile values of internal nodes, ordered from the level just above the leaves to the top. */
    internal val internalLevels: List<InternalLevel>,
    /** Tile values of the root node, keyed by tile origin shifted right by [rootChildLog2]. */
    internal val rootTiles: Map<Long, Float>,
    internal val rootChildLog2: Int,
    /** Voxel values of each leaf node, keyed by leaf origin shifted right by [leafLog2]. */
    val leaves: Map<Long, FloatArray>,
    val minValue: Float,
    val maxValue: Float
) {
    class InternalLevel(val log2: Int, val totalLog2: Int, val nodes: Map<Long, FloatArray>)

    val leafDim get() = 1 shl leafLog2

    val isLevelSet get() = when (gridClass) {
        GRID_CLASS_LEVEL_SET -> true
        GRID_CLASS_FOG_VOLUME -> false
        else -> minValue < 0f && maxValue > 0f
    }

    /**
     * Returns the value at the given voxel coordinates, for a location that is not inside any leaf node.
     */
    fun tileValue(x: Int, y: Int, z: Int): Float {
        for (level in internalLevels) {
            val node = level.nodes[packKey(x shr level.totalLog2, y shr level.totalLog2, z shr level.totalLog2)] ?: continue
            val childLog2 = level.totalLog2 - level.log2
            val mask = (1 shl level.log2) - 1
            val index = (((x shr childLog2) and mask) shl (2 * level.log2)) or
                    (((y shr childLog2) and mask) shl level.log2) or ((z shr childLog2) and mask)
            return node[index]
        }
        return rootTiles[packKey(x shr rootChildLog2, y shr rootChildLog2, z shr rootChildLog2)] ?: background
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
    }
}
