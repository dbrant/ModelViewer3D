package com.dmitrybrant.modelviewer.fbx

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
 * A node of an FBX document, with its name, properties, and child nodes.
 *
 * Properties of binary files are numbers (Short, Int, Long, Float, Double, or Boolean), strings,
 * byte arrays, or arrays of numbers. In ASCII files, the properties of a node that are all
 * numbers are read as a single LongArray (if they're all integers) or DoubleArray, and other
 * properties as Longs, Doubles, and Strings.
 */
class FbxNode(val name: String, val properties: List<Any>, val children: List<FbxNode>) {
    fun child(name: String) = children.firstOrNull { it.name == name }

    fun children(name: String) = children.filter { it.name == name }

    fun string(index: Int) = properties.getOrNull(index) as? String

    /** The property at the given index as a number, or null if it isn't one. */
    fun number(index: Int): Double? {
        val first = properties.firstOrNull()
        if (properties.size == 1 && (first is LongArray || first is DoubleArray)) {
            return numbers().getOrNull(index)
        }
        return when (val value = properties.getOrNull(index)) {
            is Number -> value.toDouble()
            is Boolean -> if (value) 1.0 else 0.0
            else -> null
        }
    }

    /** The property at the given index as an ID (64-bit integer), or null if it isn't one. */
    fun id(index: Int): Long? {
        val first = properties.firstOrNull()
        if (properties.size == 1 && first is LongArray) {
            return first.getOrNull(index)
        }
        return when (val value = properties.getOrNull(index)) {
            is Long -> value
            is Int -> value.toLong()
            is Double -> value.toLong()
            else -> null
        }
    }

    /**
     * All of the numbers of the node: the elements of an array property, or the properties
     * themselves, or (in ASCII files of version 7 and above) those of the node's "a" child.
     */
    fun numbers(): DoubleArray {
        child("a")?.let { return it.numbers() }
        if (properties.size == 1) {
            when (val value = properties[0]) {
                is DoubleArray -> return value
                is FloatArray -> return DoubleArray(value.size) { value[it].toDouble() }
                is IntArray -> return DoubleArray(value.size) { value[it].toDouble() }
                is LongArray -> return DoubleArray(value.size) { value[it].toDouble() }
                is BooleanArray -> return DoubleArray(value.size) { if (value[it]) 1.0 else 0.0 }
            }
        }
        return properties.mapNotNull { (it as? Number)?.toDouble() }.toDoubleArray()
    }

    /** All of the numbers of the node, as integers. */
    fun ints(): IntArray {
        child("a")?.let { return it.ints() }
        if (properties.size == 1) {
            when (val value = properties[0]) {
                is IntArray -> return value
                is LongArray -> return IntArray(value.size) { value[it].toInt() }
            }
        }
        val numbers = numbers()
        return IntArray(numbers.size) { numbers[it].toInt() }
    }
}
