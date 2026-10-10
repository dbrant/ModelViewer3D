package com.dmitrybrant.modelviewer.util

import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/*
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

/**
 * Transforms as column-major 4x4 matrices of doubles, which are applied to column vectors, for
 * placing the parts of models. Functions that take an offset ("o") read the matrix that starts at
 * that offset of a larger array.
 */
object Transforms {
    private val ROTATION_ORDERS = listOf("XYZ", "XZY", "YZX", "YXZ", "ZXY", "ZYX")

    fun identity() = DoubleArray(16).also { it[0] = 1.0; it[5] = 1.0; it[10] = 1.0; it[15] = 1.0 }

    fun translation(t: DoubleArray) = identity().also { it[12] = t[0]; it[13] = t[1]; it[14] = t[2] }

    fun scaling(s: DoubleArray) = identity().also { it[0] = s[0]; it[5] = s[1]; it[10] = s[2] }

    /** Rotation by Euler angles (in degrees) about the X, Y, and Z axes, applied in the given order (as in FbxEuler::EOrder). */
    fun rotation(angles: DoubleArray, order: Int): DoubleArray {
        var result = identity()
        for (axis in ROTATION_ORDERS.getOrElse(order) { ROTATION_ORDERS[0] }) {
            val a = Math.toRadians(angles[axis - 'X'])
            val c = cos(a)
            val s = sin(a)
            val r = identity()
            when (axis) {
                'X' -> { r[5] = c; r[6] = s; r[9] = -s; r[10] = c }
                'Y' -> { r[0] = c; r[2] = -s; r[8] = s; r[10] = c }
                else -> { r[0] = c; r[1] = s; r[4] = -s; r[5] = c }
            }
            result = multiply(r, result)
        }
        return result
    }

    /** Rotation by a quaternion (which is normalized, in case it isn't already). */
    fun rotation(x: Double, y: Double, z: Double, w: Double): DoubleArray {
        val length = sqrt(x * x + y * y + z * z + w * w)
        if (length == 0.0 || !length.isFinite()) {
            return identity()
        }
        val qx = x / length
        val qy = y / length
        val qz = z / length
        val qw = w / length
        val m = identity()
        m[0] = 1 - 2 * (qy * qy + qz * qz)
        m[1] = 2 * (qx * qy + qw * qz)
        m[2] = 2 * (qx * qz - qw * qy)
        m[4] = 2 * (qx * qy - qw * qz)
        m[5] = 1 - 2 * (qx * qx + qz * qz)
        m[6] = 2 * (qy * qz + qw * qx)
        m[8] = 2 * (qx * qz + qw * qy)
        m[9] = 2 * (qy * qz - qw * qx)
        m[10] = 1 - 2 * (qx * qx + qy * qy)
        return m
    }

    fun multiply(a: DoubleArray, b: DoubleArray): DoubleArray {
        val result = DoubleArray(16)
        for (col in 0 until 4) {
            for (row in 0 until 4) {
                var sum = 0.0
                for (k in 0 until 4) {
                    sum += a[k * 4 + row] * b[col * 4 + k]
                }
                result[col * 4 + row] = sum
            }
        }
        return result
    }

    fun transpose(m: DoubleArray) = DoubleArray(16) { m[(it % 4) * 4 + it / 4] }

    /** The determinant of the upper 3x3 part of the transform that starts at the given offset. */
    fun determinant(m: DoubleArray, o: Int = 0): Double {
        return m[o] * (m[o + 5] * m[o + 10] - m[o + 9] * m[o + 6]) - m[o + 4] * (m[o + 1] * m[o + 10] - m[o + 9] * m[o + 2]) +
                m[o + 8] * (m[o + 1] * m[o + 6] - m[o + 5] * m[o + 2])
    }

    /** The cofactor matrix of the upper 3x3 part of the transform (column-major, as is the result). */
    fun cofactors(m: DoubleArray, o: Int, out: DoubleArray, offset: Int) {
        out[offset] = m[o + 5] * m[o + 10] - m[o + 6] * m[o + 9]
        out[offset + 1] = m[o + 6] * m[o + 8] - m[o + 4] * m[o + 10]
        out[offset + 2] = m[o + 4] * m[o + 9] - m[o + 5] * m[o + 8]
        out[offset + 3] = m[o + 2] * m[o + 9] - m[o + 1] * m[o + 10]
        out[offset + 4] = m[o] * m[o + 10] - m[o + 2] * m[o + 8]
        out[offset + 5] = m[o + 1] * m[o + 8] - m[o] * m[o + 9]
        out[offset + 6] = m[o + 1] * m[o + 6] - m[o + 2] * m[o + 5]
        out[offset + 7] = m[o + 2] * m[o + 4] - m[o] * m[o + 6]
        out[offset + 8] = m[o] * m[o + 5] - m[o + 1] * m[o + 4]
    }

    /**
     * The inverse transpose of the upper 3x3 part of the transform, which transforms normals
     * (up to their length, since they're normalized anyway).
     */
    fun normalMatrix(m: DoubleArray, o: Int, out: DoubleArray, offset: Int) {
        // The cofactor matrix is the inverse transpose, multiplied by the determinant.
        cofactors(m, o, out, offset)
        if (determinant(m, o) < 0) {
            for (i in offset until offset + 9) {
                out[i] = -out[i]
            }
        }
    }

    /** The inverse of an affine transform, or null if it has none. */
    fun inverse(m: DoubleArray): DoubleArray? {
        val det = determinant(m)
        if (det == 0.0 || !det.isFinite()) {
            return null
        }
        // The inverse of the 3x3 part is the transpose of its cofactor matrix, divided by the determinant.
        val c = DoubleArray(9)
        cofactors(m, 0, c, 0)
        val result = identity()
        for (col in 0 until 3) {
            for (row in 0 until 3) {
                result[col * 4 + row] = c[row * 3 + col] / det
            }
        }
        for (row in 0 until 3) {
            result[12 + row] = -(result[row] * m[12] + result[4 + row] * m[13] + result[8 + row] * m[14])
        }
        return result
    }

    fun transformPoint(m: DoubleArray, o: Int, x: Double, y: Double, z: Double, out: DoubleArray, offset: Int) {
        out[offset] = m[o] * x + m[o + 4] * y + m[o + 8] * z + m[o + 12]
        out[offset + 1] = m[o + 1] * x + m[o + 5] * y + m[o + 9] * z + m[o + 13]
        out[offset + 2] = m[o + 2] * x + m[o + 6] * y + m[o + 10] * z + m[o + 14]
    }

    fun transformNormal(m: DoubleArray, o: Int, normals: DoubleArray, index: Int, out: DoubleArray, offset: Int) {
        val x = normals[index]
        val y = normals[index + 1]
        val z = normals[index + 2]
        val nx = m[o] * x + m[o + 3] * y + m[o + 6] * z
        val ny = m[o + 1] * x + m[o + 4] * y + m[o + 7] * z
        val nz = m[o + 2] * x + m[o + 5] * y + m[o + 8] * z
        val length = sqrt(nx * nx + ny * ny + nz * nz)
        val scale = if (length > 0) 1 / length else 0.0
        out[offset] = nx * scale
        out[offset + 1] = ny * scale
        out[offset + 2] = nz * scale
    }

    /** Sets the normal of every corner of a triangle to that of its face. */
    fun faceNormal(p: DoubleArray, out: DoubleArray) {
        val e1x = p[3] - p[0]
        val e1y = p[4] - p[1]
        val e1z = p[5] - p[2]
        val e2x = p[6] - p[0]
        val e2y = p[7] - p[1]
        val e2z = p[8] - p[2]
        val nx = e1y * e2z - e1z * e2y
        val ny = e1z * e2x - e1x * e2z
        val nz = e1x * e2y - e1y * e2x
        val length = sqrt(nx * nx + ny * ny + nz * nz)
        val scale = if (length > 0) 1 / length else 0.0
        for (c in 0 until 3) {
            out[c * 3] = nx * scale
            out[c * 3 + 1] = ny * scale
            out[c * 3 + 2] = nz * scale
        }
    }
}
