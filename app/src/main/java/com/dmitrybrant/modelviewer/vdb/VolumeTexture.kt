package com.dmitrybrant.modelviewer.vdb

import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.pow
import kotlin.math.sqrt

/*
* The density of a fog volume, resampled into a dense 3D texture that can be rendered by ray
* marching on the GPU. Each texel also holds the fraction of the light from above that reaches it
* through the volume, which gives the volume its self-shadowing, and optionally its color.
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
class VolumeTexture(
    val sizeX: Int,
    val sizeY: Int,
    val sizeZ: Int,
    /** Index coordinates of the first voxel that the first texel covers. */
    val origin: IntArray,
    /** Number of voxels along each side of a texel. */
    val factor: Int,
    /** Index-to-world transform of the grid, as in [VdbGrid.transform]. */
    val gridTransform: DoubleArray,
    /** The density of a texel whose stored density is at its maximum (255). */
    val maxDensity: Float,
    /**
     * Two bytes per texel, ordered by x, then y, then z (x varies fastest): the density, as a
     * fraction of [maxDensity], and the fraction of the light from above that reaches the texel.
     */
    val densityLight: ByteBuffer,
    /** Optional sRGB color of each texel (three bytes), premultiplied by its density fraction. */
    val color: ByteBuffer?,
    /** Density-weighted center of the volume, in world coordinates. */
    val centroid: DoubleArray
) {
    /**
     * Transform from texture coordinates (0 to 1 across the whole texture) to world coordinates,
     * as a 4x3 affine matrix applied to row vectors, like [VdbGrid.transform].
     */
    val textureToWorld = computeTextureToWorld(intArrayOf(sizeX, sizeY, sizeZ), origin, factor, gridTransform)

    /**
     * Returns this texture at half its resolution.
     */
    fun downsampled(): VolumeTexture {
        val newSizeX = (sizeX + 1) / 2
        val newSizeY = (sizeY + 1) / 2
        val newSizeZ = (sizeZ + 1) / 2
        val newDensityLight = allocate(newSizeX * newSizeY * newSizeZ * 2)
        val newColor = color?.let { allocate(newSizeX * newSizeY * newSizeZ * 3) }
        val colorSum = FloatArray(3)
        for (z in 0 until newSizeZ) {
            for (y in 0 until newSizeY) {
                for (x in 0 until newSizeX) {
                    // Texels beyond the edges are empty, and fully lit.
                    var density = 0
                    var light = 0
                    colorSum.fill(0f)
                    for (n in 0 until 8) {
                        val sx = x * 2 + (n and 1)
                        val sy = y * 2 + ((n shr 1) and 1)
                        val sz = z * 2 + ((n shr 2) and 1)
                        if (sx >= sizeX || sy >= sizeY || sz >= sizeZ) {
                            light += 255
                            continue
                        }
                        val i = (sz * sizeY + sy) * sizeX + sx
                        density += densityLight.get(i * 2).toInt() and 0xff
                        light += densityLight.get(i * 2 + 1).toInt() and 0xff
                        color?.let {
                            for (c in 0 until 3) {
                                colorSum[c] += SRGB_TO_LINEAR[it.get(i * 3 + c).toInt() and 0xff]
                            }
                        }
                    }
                    val i = (z * newSizeY + y) * newSizeX + x
                    newDensityLight.put(i * 2, toByte(density / 8f))
                    newDensityLight.put(i * 2 + 1, toByte(light / 8f))
                    newColor?.let {
                        for (c in 0 until 3) {
                            it.put(i * 3 + c, toByte(VdbModel.linearToSrgb(colorSum[c] / 8f) * 255f))
                        }
                    }
                }
            }
        }
        return VolumeTexture(newSizeX, newSizeY, newSizeZ, origin, factor * 2, gridTransform, maxDensity,
            newDensityLight, newColor, centroid)
    }

    companion object {
        /**
         * Extinction coefficient (per world unit) of a density of 1, which is the usual convention
         * for fog volumes (as in Houdini and Blender).
         */
        const val EXTINCTION_SCALE = 1f

        /** Maximum size of the texture data, including the colors. */
        const val MAX_BYTES = 64L shl 20

        /** Maximum number of texels along each side, which nearly all GPUs support for 3D textures. */
        const val MAX_DIMENSION = 2048

        /** Empty texels around the volume, so that its density fades to nothing at the edges of the texture. */
        private const val PADDING = 1

        private val SRGB_TO_LINEAR = FloatArray(256) {
            val c = it / 255f
            if (c <= 0.04045f) c / 12.92f else ((c + 0.055f) / 1.055f).pow(2.4f)
        }

        /**
         * Resamples the density of a fog volume (and its color grid, if any) at the highest
         * resolution that fits within the given number of bytes. Returns null if the volume has no
         * density at all, or if it's too large even at the lowest resolution.
         */
        fun build(grid: VdbGrid, colorGrid: VdbGrid?, maxBytes: Long): VolumeTexture? {
            // Find the extent of the voxels that have any density.
            val min = IntArray(3) { Int.MAX_VALUE }
            val max = IntArray(3) { Int.MIN_VALUE }
            val leafLog2 = grid.leafLog2
            val leafMask = grid.leafDim - 1
            for ((key, values) in grid.leaves) {
                val originX = VdbGrid.unpackKeyX(key) shl leafLog2
                val originY = VdbGrid.unpackKeyY(key) shl leafLog2
                val originZ = VdbGrid.unpackKeyZ(key) shl leafLog2
                for (i in 0 until (1 shl (3 * leafLog2))) {
                    if (values[i * grid.components] > 0f) {
                        include(min, max, originX + (i shr (2 * leafLog2)), originY + ((i shr leafLog2) and leafMask),
                            originZ + (i and leafMask))
                    }
                }
            }
            grid.forEachTile { x, y, z, log2Size, value ->
                if (value > 0f) {
                    val last = (1 shl log2Size) - 1
                    include(min, max, x, y, z)
                    include(min, max, x + last, y + last, z + last)
                }
            }
            if (min[0] > max[0]) {
                return null
            }

            // Reduce the resolution by powers of two until the texture fits. Texels then never
            // straddle leaves, so the density of each one can be averaged from a single leaf.
            val bytesPerTexel = if (colorGrid != null) 5 else 2
            var log2Factor = 0
            val lo = IntArray(3)
            val size = IntArray(3)
            while (true) {
                for (a in 0 until 3) {
                    lo[a] = (min[a] shr log2Factor) - PADDING
                    size[a] = (max[a] shr log2Factor) + PADDING - lo[a] + 1
                }
                if (size.all { it <= MAX_DIMENSION } && size[0].toLong() * size[1] * size[2] * bytesPerTexel <= maxBytes) {
                    break
                }
                if (log2Factor == leafLog2) {
                    return null
                }
                log2Factor++
            }
            val factor = 1 shl log2Factor
            val (sizeX, sizeY, sizeZ) = size

            // Store densities as fractions of the largest one.
            var maxDensity = 0f
            forEachBlock(grid, log2Factor) { _, _, _, density ->
                if (density > maxDensity) maxDensity = density
            }
            grid.forEachTile { _, _, _, _, value ->
                if (value > maxDensity) maxDensity = value
            }
            val densityScale = 255f / maxDensity

            val densityLight = allocate(sizeX * sizeY * sizeZ * 2)
            grid.forEachTile { x, y, z, log2Size, value ->
                if (value > 0f) {
                    val texels = (1 shl log2Size) shr log2Factor
                    val tx = (x shr log2Factor) - lo[0]
                    val ty = (y shr log2Factor) - lo[1]
                    val tz = (z shr log2Factor) - lo[2]
                    val density = toByte(value * densityScale)
                    for (k in tz until tz + texels) {
                        for (j in ty until ty + texels) {
                            for (i in tx until tx + texels) {
                                densityLight.put(((k * sizeY + j) * sizeX + i) * 2, density)
                            }
                        }
                    }
                }
            }
            forEachBlock(grid, log2Factor) { x, y, z, density ->
                if (density > 0f) {
                    densityLight.put((((z - lo[2]) * sizeY + y - lo[1]) * sizeX + x - lo[0]) * 2, toByte(density * densityScale))
                }
            }

            val texelOrigin = IntArray(3) { lo[it] shl log2Factor }
            val textureToWorld = computeTextureToWorld(size, texelOrigin, factor, grid.transform)
            val centroid = computeLight(size, densityLight, maxDensity, grid.transform, factor, textureToWorld)
            val color = colorGrid?.let { sampleColors(size, densityLight, it, textureToWorld) }
            return VolumeTexture(sizeX, sizeY, sizeZ, texelOrigin, factor, grid.transform, maxDensity, densityLight, color, centroid)
        }

        /**
         * Calls the given function with the average density of each block of voxels in the leaves
         * (whose size is the given resolution factor), and the block's texel coordinates.
         */
        private inline fun forEachBlock(grid: VdbGrid, log2Factor: Int, action: (x: Int, y: Int, z: Int, density: Float) -> Unit) {
            val leafLog2 = grid.leafLog2
            val factor = 1 shl log2Factor
            val blocks = grid.leafDim shr log2Factor
            val scale = 1f / (factor * factor * factor)
            for ((key, values) in grid.leaves) {
                val originX = VdbGrid.unpackKeyX(key) shl (leafLog2 - log2Factor)
                val originY = VdbGrid.unpackKeyY(key) shl (leafLog2 - log2Factor)
                val originZ = VdbGrid.unpackKeyZ(key) shl (leafLog2 - log2Factor)
                for (bx in 0 until blocks) {
                    for (by in 0 until blocks) {
                        for (bz in 0 until blocks) {
                            var sum = 0f
                            for (x in bx * factor until (bx + 1) * factor) {
                                for (y in by * factor until (by + 1) * factor) {
                                    for (z in bz * factor until (bz + 1) * factor) {
                                        sum += values[((x shl (2 * leafLog2)) or (y shl leafLog2) or z) * grid.components]
                                    }
                                }
                            }
                            action(originX + bx, originY + by, originZ + bz, sum * scale)
                        }
                    }
                }
            }
        }

        /**
         * Computes how much of the light from straight above (along the world's y axis) reaches each
         * texel, by attenuating it through the volume along the texture axis that's closest to vertical.
         * Returns the density-weighted center of the volume, in world coordinates.
         */
        private fun computeLight(size: IntArray, densityLight: ByteBuffer, maxDensity: Float, gridTransform: DoubleArray,
                                 factor: Int, textureToWorld: DoubleArray): DoubleArray {
            val up = (0 until 3).maxBy { abs(gridTransform[it * 3 + 1]) }
            val upIsPositive = gridTransform[up * 3 + 1] >= 0
            val axis1 = (up + 1) % 3
            val axis2 = (up + 2) % 3
            val stride = intArrayOf(1, size[0], size[0] * size[1])
            val stepLength = factor * sqrt((0 until 3).sumOf { gridTransform[up * 3 + it] * gridTransform[up * 3 + it] })

            // Fractions of light that pass through half of a texel, and through all of it, for each density.
            val halfTransmittance = FloatArray(256)
            val fullTransmittance = FloatArray(256)
            for (d in 0 until 256) {
                val opticalDepth = d / 255.0 * maxDensity * EXTINCTION_SCALE * stepLength
                halfTransmittance[d] = exp(-opticalDepth / 2).toFloat()
                fullTransmittance[d] = exp(-opticalDepth).toFloat()
            }

            var weight = 0.0
            val weightedSum = DoubleArray(3)
            for (i1 in 0 until size[axis1]) {
                for (i2 in 0 until size[axis2]) {
                    var transmittance = 1f
                    for (n in 0 until size[up]) {
                        val k = if (upIsPositive) size[up] - 1 - n else n
                        val i = i1 * stride[axis1] + i2 * stride[axis2] + k * stride[up]
                        val density = densityLight.get(i * 2).toInt() and 0xff
                        densityLight.put(i * 2 + 1, toByte(transmittance * halfTransmittance[density] * 255f))
                        transmittance *= fullTransmittance[density]
                        if (density > 0) {
                            weight += density
                            weightedSum[axis1] += density * (i1 + 0.5) / size[axis1]
                            weightedSum[axis2] += density * (i2 + 0.5) / size[axis2]
                            weightedSum[up] += density * (k + 0.5) / size[up]
                        }
                    }
                }
            }
            return transform(textureToWorld, weightedSum[0] / weight, weightedSum[1] / weight, weightedSum[2] / weight)
        }

        /**
         * Samples the color grid at the center of each texel that has any density, and returns the
         * colors in sRGB, premultiplied by the density, so that filtering the texture doesn't blend
         * in the colors of empty texels.
         */
        private fun sampleColors(size: IntArray, densityLight: ByteBuffer, colorGrid: VdbGrid, textureToWorld: DoubleArray): ByteBuffer {
            val colors = allocate(size[0] * size[1] * size[2] * 3)
            val color = FloatArray(3)
            for (z in 0 until size[2]) {
                for (y in 0 until size[1]) {
                    for (x in 0 until size[0]) {
                        val i = (z * size[1] + y) * size[0] + x
                        val density = (densityLight.get(i * 2).toInt() and 0xff) / 255f
                        if (density == 0f) {
                            continue
                        }
                        val position = transform(textureToWorld, (x + 0.5) / size[0], (y + 0.5) / size[1], (z + 0.5) / size[2])
                        colorGrid.sample(position[0], position[1], position[2], color)
                        for (c in 0 until 3) {
                            colors.put(i * 3 + c, toByte(VdbModel.linearToSrgb(color[c].coerceIn(0f, 1f) * density) * 255f))
                        }
                    }
                }
            }
            return colors
        }

        private fun computeTextureToWorld(size: IntArray, origin: IntArray, factor: Int, gridTransform: DoubleArray): DoubleArray {
            val matrix = DoubleArray(12)
            for (j in 0 until 3) {
                var translation = gridTransform[9 + j]
                for (a in 0 until 3) {
                    matrix[a * 3 + j] = gridTransform[a * 3 + j] * size[a] * factor
                    // Voxel values lie at integer coordinates, so the voxels' extent begins half a voxel before the origin.
                    translation += (origin[a] - 0.5) * gridTransform[a * 3 + j]
                }
                matrix[9 + j] = translation
            }
            return matrix
        }

        /** Applies a 4x3 affine matrix to a point. */
        fun transform(matrix: DoubleArray, x: Double, y: Double, z: Double): DoubleArray {
            return DoubleArray(3) { x * matrix[it] + y * matrix[3 + it] + z * matrix[6 + it] + matrix[9 + it] }
        }

        private fun include(min: IntArray, max: IntArray, x: Int, y: Int, z: Int) {
            if (x < min[0]) min[0] = x
            if (y < min[1]) min[1] = y
            if (z < min[2]) min[2] = z
            if (x > max[0]) max[0] = x
            if (y > max[1]) max[1] = y
            if (z > max[2]) max[2] = z
        }

        private fun toByte(value: Float) = (value + 0.5f).toInt().coerceIn(0, 255).toByte()

        private fun allocate(size: Int) = ByteBuffer.allocateDirect(size).order(ByteOrder.nativeOrder())
    }
}
