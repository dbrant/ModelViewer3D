package com.dmitrybrant.modelviewer.vdb

import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs
import kotlin.math.cbrt
import kotlin.math.ceil
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.pow
import kotlin.math.sqrt

/*
* The density of a fog volume, resampled into a dense 3D texture that can be rendered by ray
* marching on the GPU. Each texel also holds the fraction of the light from above that reaches it
* through the volume, which gives the volume its self-shadowing, and optionally how much it glows
* (from its temperature or its flames) and its color.
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
    /** How the volume glows, if it does. */
    val glow: Glow,
    /**
     * For flames, the size of a cube that would hold all of their glow at full intensity, in world
     * units, by which the brightness of flames is scaled, so that it doesn't depend on the units.
     */
    val flameLength: Double,
    /**
     * Texels ordered by x, then y, then z (x varies fastest), with [channels] bytes each: the
     * density, as a fraction of [maxDensity], the fraction of the light from above that reaches
     * the texel, and how much it glows, if the volume does: a fraction of the way from the
     * background of the glow grid (e.g. cold) to its highest value (e.g. the hottest temperature).
     */
    val texels: ByteBuffer,
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

    /** Number of bytes per texel in [texels]. */
    val channels get() = if (glow == Glow.NONE) 2 else 3

    /** How a volume glows. */
    enum class Glow {
        NONE,
        /** By its temperature, as much as it absorbs light (like the soot of fire), by Kirchhoff's law. */
        THERMAL,
        /** With flames, which glow on their own, even where there's no density. */
        FLAME
    }

    /**
     * Returns this texture at half its resolution.
     */
    fun downsampled(): VolumeTexture {
        val newSizeX = (sizeX + 1) / 2
        val newSizeY = (sizeY + 1) / 2
        val newSizeZ = (sizeZ + 1) / 2
        val newTexels = allocate(newSizeX * newSizeY * newSizeZ * channels)
        val newColor = color?.let { allocate(newSizeX * newSizeY * newSizeZ * 3) }
        val sum = IntArray(channels)
        val colorSum = FloatArray(3)
        for (z in 0 until newSizeZ) {
            for (y in 0 until newSizeY) {
                for (x in 0 until newSizeX) {
                    sum.fill(0)
                    colorSum.fill(0f)
                    for (n in 0 until 8) {
                        val sx = x * 2 + (n and 1)
                        val sy = y * 2 + ((n shr 1) and 1)
                        val sz = z * 2 + ((n shr 2) and 1)
                        if (sx >= sizeX || sy >= sizeY || sz >= sizeZ) {
                            // Texels beyond the edges are empty and cold, and fully lit.
                            sum[1] += 255
                            continue
                        }
                        val i = (sz * sizeY + sy) * sizeX + sx
                        for (c in 0 until channels) {
                            sum[c] += texels.get(i * channels + c).toInt() and 0xff
                        }
                        color?.let {
                            for (c in 0 until 3) {
                                colorSum[c] += SRGB_TO_LINEAR[it.get(i * 3 + c).toInt() and 0xff]
                            }
                        }
                    }
                    val i = (z * newSizeY + y) * newSizeX + x
                    for (c in 0 until channels) {
                        newTexels.put(i * channels + c, toByte(sum[c] / 8f))
                    }
                    newColor?.let {
                        for (c in 0 until 3) {
                            it.put(i * 3 + c, toByte(VdbModel.linearToSrgb(colorSum[c] / 8f) * 255f))
                        }
                    }
                }
            }
        }
        return VolumeTexture(newSizeX, newSizeY, newSizeZ, origin, factor * 2, gridTransform, maxDensity,
            glow, flameLength, newTexels, newColor, centroid)
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

        /** Range of blackbody temperatures (in Kelvin) that the temperature of fire is shown with. */
        const val FIRE_MIN_KELVIN = 800.0
        const val FIRE_MAX_KELVIN = 2500.0

        private const val ROUNDING = 1e-6

        /** Second radiation constant of Planck's law (hc/k), in meter-Kelvins. */
        private const val PLANCK_C2 = 1.4388e-2

        /**
         * Emission colors of fire for temperatures from cold to the hottest (256 entries of three
         * sRGB bytes): the color of a blackbody from [FIRE_MIN_KELVIN] to [FIRE_MAX_KELVIN], with
         * a brightness that increases with the square of the temperature, from nothing to full.
         */
        val FIRE_RAMP: ByteArray by lazy {
            val ramp = ByteArray(256 * 3)
            for (i in 0 until 256) {
                val t = i / 255.0
                val color = blackbodyColor(FIRE_MIN_KELVIN + t * (FIRE_MAX_KELVIN - FIRE_MIN_KELVIN))
                for (c in 0 until 3) {
                    ramp[i * 3 + c] = toByte(VdbModel.linearToSrgb((color[c] * t * t).toFloat()) * 255f)
                }
            }
            ramp
        }

        private val SRGB_TO_LINEAR = FloatArray(256) {
            val c = it / 255f
            if (c <= 0.04045f) c / 12.92f else ((c + 0.055f) / 1.055f).pow(2.4f)
        }

        /**
         * Resamples the density of a fog volume (and its color grid and a grid that makes it glow,
         * if any) at the highest resolution that fits within the given number of bytes. Returns
         * null if the volume has no density or flames at all, or if it's too large even at the
         * lowest resolution.
         */
        fun build(grid: VdbGrid, colorGrid: VdbGrid?, glowGrid: VdbGrid?, glowKind: Glow, maxBytes: Long): VolumeTexture? {
            // A glow grid that's cold everywhere doesn't make anything glow.
            val hottest = glowGrid?.let { maxValue(it) } ?: 0f
            val glow = if (glowGrid != null && hottest > glowGrid.background) glowKind else Glow.NONE

            // Find the extent of the voxels that have any density, and of any flames, which glow
            // even where there's no density.
            val min = IntArray(3) { Int.MAX_VALUE }
            val max = IntArray(3) { Int.MIN_VALUE }
            includeValuesAbove(grid, 0f, min, max)
            val flameMin = IntArray(3) { Int.MAX_VALUE }
            val flameMax = IntArray(3) { Int.MIN_VALUE }
            if (glow == Glow.FLAME) {
                includeOtherGrid(glowGrid!!, grid, flameMin, flameMax)
                include(min, max, flameMin[0], flameMin[1], flameMin[2])
                include(min, max, flameMax[0], flameMax[1], flameMax[2])
            }
            if (min[0] > max[0]) {
                return null
            }

            // Reduce the resolution by powers of two until the texture fits. Texels then never
            // straddle leaves, so the density of each one can be averaged from a single leaf.
            val channels = if (glow == Glow.NONE) 2 else 3
            val bytesPerTexel = channels + if (colorGrid != null) 3 else 0
            val leafLog2 = grid.leafLog2
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
            val densityScale = if (maxDensity > 0f) 255f / maxDensity else 0f

            val texels = allocate(sizeX * sizeY * sizeZ * channels)
            grid.forEachTile { x, y, z, log2Size, value ->
                if (value > 0f) {
                    val tileTexels = (1 shl log2Size) shr log2Factor
                    val tx = (x shr log2Factor) - lo[0]
                    val ty = (y shr log2Factor) - lo[1]
                    val tz = (z shr log2Factor) - lo[2]
                    val density = toByte(value * densityScale)
                    for (k in tz until tz + tileTexels) {
                        for (j in ty until ty + tileTexels) {
                            for (i in tx until tx + tileTexels) {
                                texels.put(((k * sizeY + j) * sizeX + i) * channels, density)
                            }
                        }
                    }
                }
            }
            forEachBlock(grid, log2Factor) { x, y, z, density ->
                if (density > 0f) {
                    texels.put((((z - lo[2]) * sizeY + y - lo[1]) * sizeX + x - lo[0]) * channels, toByte(density * densityScale))
                }
            }

            val texelOrigin = IntArray(3) { lo[it] shl log2Factor }
            val textureToWorld = computeTextureToWorld(size, texelOrigin, factor, grid.transform)
            val centroid = computeLight(size, texels, channels, maxDensity, grid.transform, factor, textureToWorld)

            // The temperature and color only matter where there's density, since only a volume
            // with density reflects light, or glows by its temperature. Flames glow anywhere.
            val firstTexel = IntArray(3)
            var flameLength = 0.0
            if (glow != Glow.NONE) {
                val cold = glowGrid!!.background
                val glowScale = 255f / (hottest - cold)
                val glowLo = if (glow == Glow.FLAME) IntArray(3) { (flameMin[it] shr log2Factor) - lo[it] } else firstTexel
                val glowHi = if (glow == Glow.FLAME) IntArray(3) { (flameMax[it] shr log2Factor) - lo[it] + 1 } else size
                var glowSum = 0.0
                sampleAtTexels(size, glowLo, glowHi, glow == Glow.THERMAL, texels, channels, textureToWorld, glowGrid) { i, _, value ->
                    val glowByte = toByte((value[0] - cold) * glowScale)
                    texels.put(i * channels + 2, glowByte)
                    glowSum += (glowByte.toInt() and 0xff) / 255.0
                }
                if (glow == Glow.FLAME) {
                    val texelSize = factor * cbrt(abs(VdbGrid.determinant3x3(grid.transform)))
                    flameLength = cbrt(glowSum) * texelSize
                }
            }
            val color = colorGrid?.let { colorGrid ->
                // Colors are premultiplied by the density, so that filtering the texture doesn't
                // blend in the colors of empty texels.
                val colors = allocate(sizeX * sizeY * sizeZ * 3)
                sampleAtTexels(size, firstTexel, size, true, texels, channels, textureToWorld, colorGrid) { i, density, color ->
                    for (c in 0 until 3) {
                        colors.put(i * 3 + c, toByte(VdbModel.linearToSrgb(color[c].coerceIn(0f, 1f) * density) * 255f))
                    }
                }
                colors
            }
            return VolumeTexture(sizeX, sizeY, sizeZ, texelOrigin, factor, grid.transform, maxDensity, glow, flameLength,
                texels, color, centroid)
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
         * Returns the density-weighted center of the volume (or the center of the texture, if it has
         * no density), in world coordinates.
         */
        private fun computeLight(size: IntArray, texels: ByteBuffer, channels: Int, maxDensity: Float, gridTransform: DoubleArray,
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
                        val density = texels.get(i * channels).toInt() and 0xff
                        texels.put(i * channels + 1, toByte(transmittance * halfTransmittance[density] * 255f))
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
            if (weight == 0.0) {
                return transform(textureToWorld, 0.5, 0.5, 0.5)
            }
            return transform(textureToWorld, weightedSum[0] / weight, weightedSum[1] / weight, weightedSum[2] / weight)
        }

        /**
         * Samples the given grid at the center of each texel from [lo] (inclusive) to [hi]
         * (exclusive), optionally only the texels that have any density, and calls the given
         * function with the texel's index, its density (as a fraction), and the grid's value.
         */
        private inline fun sampleAtTexels(size: IntArray, lo: IntArray, hi: IntArray, requireDensity: Boolean, texels: ByteBuffer,
                                          channels: Int, textureToWorld: DoubleArray, grid: VdbGrid,
                                          action: (index: Int, density: Float, value: FloatArray) -> Unit) {
            val value = FloatArray(grid.components)
            val m = textureToWorld
            for (z in lo[2] until hi[2]) {
                val w = (z + 0.5) / size[2]
                for (y in lo[1] until hi[1]) {
                    val v = (y + 0.5) / size[1]
                    for (x in lo[0] until hi[0]) {
                        val i = (z * size[1] + y) * size[0] + x
                        val density = (texels.get(i * channels).toInt() and 0xff) / 255f
                        if (requireDensity && density == 0f) {
                            continue
                        }
                        val u = (x + 0.5) / size[0]
                        grid.sample(u * m[0] + v * m[3] + w * m[6] + m[9], u * m[1] + v * m[4] + w * m[7] + m[10],
                            u * m[2] + v * m[5] + w * m[8] + m[11], value)
                        action(i, density, value)
                    }
                }
            }
        }

        /**
         * Expands the given extent (in index coordinates) to include the voxels of the grid whose
         * values (in their first component) exceed the given threshold.
         */
        private fun includeValuesAbove(grid: VdbGrid, threshold: Float, min: IntArray, max: IntArray) {
            val leafLog2 = grid.leafLog2
            val leafMask = grid.leafDim - 1
            for ((key, values) in grid.leaves) {
                val originX = VdbGrid.unpackKeyX(key) shl leafLog2
                val originY = VdbGrid.unpackKeyY(key) shl leafLog2
                val originZ = VdbGrid.unpackKeyZ(key) shl leafLog2
                for (i in 0 until (1 shl (3 * leafLog2))) {
                    if (values[i * grid.components] > threshold) {
                        include(min, max, originX + (i shr (2 * leafLog2)), originY + ((i shr leafLog2) and leafMask),
                            originZ + (i and leafMask))
                    }
                }
            }
            grid.forEachTile { x, y, z, log2Size, value ->
                if (value > threshold) {
                    val last = (1 shl log2Size) - 1
                    include(min, max, x, y, z)
                    include(min, max, x + last, y + last, z + last)
                }
            }
        }

        /**
         * Expands the given extent (in the index coordinates of [grid]) to include the voxels of
         * another grid whose values exceed its background, which may have a different transform.
         */
        private fun includeOtherGrid(other: VdbGrid, grid: VdbGrid, min: IntArray, max: IntArray) {
            val otherMin = IntArray(3) { Int.MAX_VALUE }
            val otherMax = IntArray(3) { Int.MIN_VALUE }
            includeValuesAbove(other, other.background, otherMin, otherMax)
            if (otherMin[0] > otherMax[0]) {
                return
            }
            for (n in 0 until 8) {
                val world = other.indexToWorld(
                    (if (n and 1 == 0) otherMin[0] else otherMax[0]).toDouble(),
                    (if (n and 2 == 0) otherMin[1] else otherMax[1]).toDouble(),
                    (if (n and 4 == 0) otherMin[2] else otherMax[2]).toDouble())
                val index = grid.worldToIndex(world[0], world[1], world[2])
                // Round outward, but not past positions that are whole numbers, apart from rounding errors.
                include(min, max, floor(index[0] + ROUNDING).toInt(), floor(index[1] + ROUNDING).toInt(), floor(index[2] + ROUNDING).toInt())
                include(min, max, ceil(index[0] - ROUNDING).toInt(), ceil(index[1] - ROUNDING).toInt(), ceil(index[2] - ROUNDING).toInt())
            }
        }

        /** The highest value of a scalar grid, including its tiles. */
        private fun maxValue(grid: VdbGrid): Float {
            var max = grid.maxValue
            grid.forEachTile { _, _, _, _, value ->
                if (value > max) max = value
            }
            return max
        }

        /**
         * Linear sRGB color of a blackbody at the given temperature (in Kelvin), scaled so that its
         * largest component is 1. This integrates Planck's law over the visible spectrum with the
         * CIE 1931 color matching functions, using the analytic fit of Wyman, Sloan, and Shirley (2013).
         */
        fun blackbodyColor(kelvin: Double): FloatArray {
            var x = 0.0
            var y = 0.0
            var z = 0.0
            for (nm in 380..780 step 5) {
                val lambda = nm * 1e-9
                // Planck's law, without its constant factors, which don't affect the color.
                val radiance = 1 / (lambda.pow(5) * (exp(PLANCK_C2 / (lambda * kelvin)) - 1))
                x += radiance * (1.056 * lobe(nm, 599.8, 37.9, 31.0) + 0.362 * lobe(nm, 442.0, 16.0, 26.7) -
                        0.065 * lobe(nm, 501.1, 20.4, 26.2))
                y += radiance * (0.821 * lobe(nm, 568.8, 46.9, 40.5) + 0.286 * lobe(nm, 530.9, 16.3, 31.1))
                z += radiance * (1.217 * lobe(nm, 437.0, 11.8, 36.0) + 0.681 * lobe(nm, 459.0, 26.0, 13.8))
            }
            val rgb = doubleArrayOf(3.2406 * x - 1.5372 * y - 0.4986 * z, -0.9689 * x + 1.8758 * y + 0.0415 * z,
                0.0557 * x - 0.2040 * y + 1.0570 * z)
            val max = rgb.max()
            return FloatArray(3) { (rgb[it].coerceAtLeast(0.0) / max).toFloat() }
        }

        /** A Gaussian lobe with different widths below and above its mean. */
        private fun lobe(nm: Int, mean: Double, widthBelow: Double, widthAbove: Double): Double {
            val t = (nm - mean) / (if (nm < mean) widthBelow else widthAbove)
            return exp(-0.5 * t * t)
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
