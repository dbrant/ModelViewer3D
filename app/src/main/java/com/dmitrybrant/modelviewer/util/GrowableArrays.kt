package com.dmitrybrant.modelviewer.util

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
 * A growable array of floats, which avoids the overhead of boxing every value in a MutableList<Float>.
 * The backing [array] may be larger than [size].
 */
class FloatList(initialCapacity: Int = 1024) {
    var array = FloatArray(initialCapacity)
        private set
    var size = 0
        private set

    fun add(value: Float) {
        if (size == array.size) {
            array = array.copyOf(maxOf(size * 2, 16))
        }
        array[size++] = value
    }

    operator fun get(index: Int) = array[index]
}

/**
 * A growable array of ints, which avoids the overhead of boxing every value in a MutableList<Int>.
 * The backing [array] may be larger than [size].
 */
class IntList(initialCapacity: Int = 1024) {
    var array = IntArray(initialCapacity)
        private set
    var size = 0
        private set

    fun add(value: Int) {
        if (size == array.size) {
            array = array.copyOf(maxOf(size * 2, 16))
        }
        array[size++] = value
    }

    operator fun get(index: Int) = array[index]

    operator fun set(index: Int, value: Int) {
        array[index] = value
    }

    fun clear() {
        size = 0
    }
}

/**
 * A growable array of doubles, which avoids the overhead of boxing every value in a MutableList<Double>.
 * The backing [array] may be larger than [size].
 */
class DoubleList(initialCapacity: Int = 1024) {
    var array = DoubleArray(initialCapacity)
        private set
    var size = 0
        private set

    fun add(value: Double) {
        if (size == array.size) {
            array = array.copyOf(maxOf(size * 2, 16))
        }
        array[size++] = value
    }

    operator fun get(index: Int) = array[index]

    fun clear() {
        size = 0
    }

    fun toArray() = array.copyOf(size)
}

/**
 * A growable array of longs, which avoids the overhead of boxing every value in a MutableList<Long>.
 * The backing [array] may be larger than [size].
 */
class LongList(initialCapacity: Int = 1024) {
    var array = LongArray(initialCapacity)
        private set
    var size = 0
        private set

    fun add(value: Long) {
        if (size == array.size) {
            array = array.copyOf(maxOf(size * 2, 16))
        }
        array[size++] = value
    }

    operator fun get(index: Int) = array[index]

    fun clear() {
        size = 0
    }

    fun toArray() = array.copyOf(size)
}
