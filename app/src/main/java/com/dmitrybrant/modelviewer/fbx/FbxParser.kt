package com.dmitrybrant.modelviewer.fbx

import com.dmitrybrant.modelviewer.util.DoubleList
import com.dmitrybrant.modelviewer.util.LongList
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.zip.DataFormatException
import java.util.zip.Inflater
import kotlin.math.pow

/*
* Parser for FBX files, in their binary and ASCII forms, which reads them into a tree of nodes.
* Nodes with data that isn't needed for displaying a model (animations and poses) are skipped.
*
* Info on the binary format: https://code.blender.org/2013/08/fbx-binary-file-format-specification/
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
object FbxParser {
    /** A parsed file: its format version (e.g. 7400 for version 7.4), and its top-level nodes. */
    class Document(val version: Int, val nodes: List<FbxNode>) {
        fun node(name: String) = nodes.firstOrNull { it.name == name }
    }

    private val BINARY_MAGIC = "Kaydara FBX Binary  \u0000".toByteArray(Charsets.ISO_8859_1)
    private const val BINARY_HEADER_SIZE = 27

    /** Nodes that aren't needed for displaying a model, which are skipped. */
    private val SKIPPED_NODES = setOf("Takes", "AnimationStack", "AnimationLayer", "AnimationCurveNode", "AnimationCurve", "Pose")

    fun parse(data: ByteArray): Document {
        try {
            if (data.size >= BINARY_HEADER_SIZE && BINARY_MAGIC.indices.all { data[it] == BINARY_MAGIC[it] }) {
                val version = ByteBuffer.wrap(data, 23, 4).order(ByteOrder.LITTLE_ENDIAN).int
                return Document(version, BinaryParser(data, version).readNodes(BINARY_HEADER_SIZE, data.size))
            }
            val nodes = AsciiParser(data).readNodes(true)
            if (nodes.none { it.name == "Objects" }) {
                throw IOException("Not a valid FBX file.")
            }
            val version = nodes.firstOrNull { it.name == "FBXHeaderExtension" }?.child("FBXVersion")?.number(0)?.toInt() ?: 7000
            return Document(version, nodes)
        } catch (e: IndexOutOfBoundsException) {
            throw IOException("Invalid FBX file.", e)
        }
    }

    private class BinaryParser(private val data: ByteArray, version: Int) {
        // Starting with version 7.5, offsets and counts are 64 bits.
        private val wide = version >= 7500
        private val headerSize = if (wide) 25 else 13
        private val buffer = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN)
        private var inflater: Inflater? = null

        fun readNodes(start: Int, end: Int): List<FbxNode> {
            val nodes = mutableListOf<FbxNode>()
            var pos = start
            while (pos + headerSize <= end) {
                val endOffset = if (wide) buffer.getLong(pos) else buffer.getInt(pos).toLong() and 0xffffffffL
                if (endOffset == 0L) {
                    // A null record ends a list of nodes.
                    break
                }
                val propertyCount = if (wide) buffer.getLong(pos + 8) else buffer.getInt(pos + 4).toLong() and 0xffffffffL
                val nameLength = data[pos + headerSize - 1].toInt() and 0xff
                if (endOffset <= pos || endOffset > end || propertyCount > Int.MAX_VALUE) {
                    throw IOException("Invalid FBX node.")
                }
                val name = String(data, pos + headerSize, nameLength, Charsets.ISO_8859_1)
                if (name !in SKIPPED_NODES) {
                    var p = pos + headerSize + nameLength
                    val properties = ArrayList<Any>(propertyCount.toInt())
                    for (i in 0 until propertyCount.toInt()) {
                        p = readProperty(p, properties)
                    }
                    val children = if (p < endOffset) readNodes(p, endOffset.toInt()) else emptyList()
                    nodes.add(FbxNode(name, properties, children))
                }
                pos = endOffset.toInt()
            }
            return nodes
        }

        /** Reads a property into the list, and returns the position after it. */
        private fun readProperty(start: Int, properties: MutableList<Any>): Int {
            var p = start
            val type = data[p++].toInt().toChar()
            when (type) {
                'Y' -> properties.add(buffer.getShort(p)).also { p += 2 }
                'C' -> properties.add(data[p] != 0.toByte()).also { p += 1 }
                'I' -> properties.add(buffer.getInt(p)).also { p += 4 }
                'F' -> properties.add(buffer.getFloat(p)).also { p += 4 }
                'D' -> properties.add(buffer.getDouble(p)).also { p += 8 }
                'L' -> properties.add(buffer.getLong(p)).also { p += 8 }
                'f', 'd', 'l', 'i', 'b' -> {
                    val length = buffer.getInt(p)
                    val encoding = buffer.getInt(p + 4)
                    val storedLength = buffer.getInt(p + 8)
                    p += 12
                    val elementSize = when (type) {
                        'd', 'l' -> 8
                        'f', 'i' -> 4
                        else -> 1
                    }
                    if (length < 0 || storedLength < 0 || length.toLong() * elementSize > Int.MAX_VALUE) {
                        throw IOException("Invalid FBX array.")
                    }
                    val values = if (encoding == 1) {
                        ByteBuffer.wrap(inflate(p, storedLength, length * elementSize))
                    } else {
                        ByteBuffer.wrap(data, p, length * elementSize).slice()
                    }.order(ByteOrder.LITTLE_ENDIAN)
                    properties.add(when (type) {
                        'f' -> FloatArray(length).also { values.asFloatBuffer().get(it) }
                        'd' -> DoubleArray(length).also { values.asDoubleBuffer().get(it) }
                        'l' -> LongArray(length).also { values.asLongBuffer().get(it) }
                        'i' -> IntArray(length).also { values.asIntBuffer().get(it) }
                        else -> BooleanArray(length) { values.get(it) != 0.toByte() }
                    })
                    p += storedLength
                }
                'S', 'R' -> {
                    val length = buffer.getInt(p)
                    p += 4
                    if (length < 0 || p + length > data.size) {
                        throw IOException("Invalid FBX property.")
                    }
                    properties.add(if (type == 'S') String(data, p, length, Charsets.UTF_8) else data.copyOfRange(p, p + length))
                    p += length
                }
                else -> throw IOException("Unknown FBX property type: $type")
            }
            return p
        }

        private fun inflate(offset: Int, length: Int, outputLength: Int): ByteArray {
            val inflater = this.inflater ?: Inflater().also { this.inflater = it }
            inflater.reset()
            inflater.setInput(data, offset, length)
            val output = ByteArray(outputLength)
            try {
                var total = 0
                while (total < outputLength) {
                    val n = inflater.inflate(output, total, outputLength - total)
                    if (n == 0 && (inflater.finished() || inflater.needsInput() || inflater.needsDictionary())) {
                        break
                    }
                    total += n
                }
                if (total != outputLength) {
                    throw IOException("Invalid compressed FBX array.")
                }
            } catch (e: DataFormatException) {
                throw IOException("Invalid compressed FBX array.", e)
            }
            return output
        }
    }

    /**
     * Parser for ASCII files, which have lines like "Name: value, value, ... {", with child nodes
     * up to a closing brace. Lists of values may continue on the next line after a comma.
     */
    private class AsciiParser(private val data: ByteArray) {
        private var pos = 0

        // Values of the node that's being read: numbers are collected without boxing them, unless
        // the node also has other kinds of values.
        private val longs = LongList()
        private val doubles = DoubleList()
        private var allIntegers = true
        private var mixed: MutableList<Any>? = null
        private var longValue = 0L
        private var doubleValue = 0.0
        private var isInteger = false

        fun readNodes(topLevel: Boolean): List<FbxNode> {
            val nodes = mutableListOf<FbxNode>()
            while (true) {
                skipWhitespace(true)
                if (pos >= data.size) {
                    if (!topLevel) {
                        throw IOException("Unexpected end of FBX file.")
                    }
                    break
                }
                if (data[pos] == '}'.code.toByte()) {
                    pos++
                    if (topLevel) {
                        throw IOException("Unexpected end of FBX node.")
                    }
                    break
                }
                val nameStart = pos
                while (pos < data.size && data[pos] != ':'.code.toByte() && !isWhitespace(data[pos])) {
                    pos++
                }
                if (pos >= data.size || data[pos] != ':'.code.toByte()) {
                    throw IOException("Invalid FBX file.")
                }
                val name = String(data, nameStart, pos - nameStart, Charsets.ISO_8859_1)
                pos++
                if (name in SKIPPED_NODES) {
                    skipNode()
                    continue
                }
                val properties = readValues()
                skipWhitespace(false)
                val children = if (pos < data.size && data[pos] == '{'.code.toByte()) {
                    pos++
                    readNodes(false)
                } else {
                    emptyList()
                }
                nodes.add(FbxNode(name, properties, children))
            }
            return nodes
        }

        /** Reads the values of a node, up to the end of its line, or the start of its children. */
        private fun readValues(): List<Any> {
            longs.clear()
            doubles.clear()
            allIntegers = true
            mixed = null
            var afterComma = false
            while (pos < data.size) {
                val c = data[pos].toInt().toChar()
                when {
                    c == ' ' || c == '\t' || c == '\r' -> pos++
                    c == '\n' -> {
                        // The values continue on the next line after a comma, at the end of the
                        // line, or at the start of the next line (as in some files of version 6).
                        if (!afterComma && !nextLineStartsWithComma()) break
                        pos++
                    }
                    c == ';' -> skipLine()
                    c == '{' || c == '}' -> break
                    c == ',' -> {
                        afterComma = true
                        pos++
                    }
                    c == '"' -> {
                        val end = indexOf('"'.code.toByte(), pos + 1)
                        addValue(String(data, pos + 1, end - pos - 1, Charsets.UTF_8).replace("&quot;", "\""))
                        pos = end + 1
                        afterComma = false
                    }
                    else -> {
                        val start = pos
                        while (pos < data.size && !isDelimiter(data[pos])) {
                            pos++
                        }
                        // The length of an array is written as "*123", before its values (in an "a" node).
                        if (c != '*') {
                            if (parseNumber(start, pos)) {
                                addNumber()
                            } else {
                                addValue(String(data, start, pos - start, Charsets.ISO_8859_1))
                            }
                        }
                        afterComma = false
                    }
                }
            }
            mixed?.let { return it }
            return when {
                longs.size == 0 -> emptyList()
                allIntegers -> listOf(longs.toArray())
                else -> listOf(doubles.toArray())
            }
        }

        private fun addNumber() {
            val mixed = this.mixed
            if (mixed != null) {
                mixed.add(if (isInteger) longValue else doubleValue)
                return
            }
            longs.add(longValue)
            doubles.add(doubleValue)
            allIntegers = allIntegers && isInteger
        }

        private fun addValue(value: Any) {
            val mixed = this.mixed ?: MutableList<Any>(longs.size) { i ->
                if (allIntegers || doubles[i] == longs[i].toDouble()) longs[i] else doubles[i]
            }.also { this.mixed = it }
            mixed.add(value)
        }

        /**
         * Parses a number from the given range of the data, into [longValue], [doubleValue], and
         * [isInteger], and returns whether it's a number.
         */
        private fun parseNumber(start: Int, end: Int): Boolean {
            var i = start
            val negative = data[i] == '-'.code.toByte()
            if (negative || data[i] == '+'.code.toByte()) {
                i++
            }
            var mantissa = 0L
            var digits = 0
            var fractionDigits = 0
            isInteger = true
            while (i < end && data[i] in DIGIT_RANGE) {
                mantissa = mantissa * 10 + (data[i] - '0'.code.toByte())
                digits++
                i++
            }
            if (i < end && data[i] == '.'.code.toByte()) {
                isInteger = false
                i++
                while (i < end && data[i] in DIGIT_RANGE) {
                    mantissa = mantissa * 10 + (data[i] - '0'.code.toByte())
                    digits++
                    fractionDigits++
                    i++
                }
            }
            var exponent = 0
            if (digits > 0 && i < end && (data[i] == 'e'.code.toByte() || data[i] == 'E'.code.toByte())) {
                isInteger = false
                i++
                val negativeExponent = i < end && data[i] == '-'.code.toByte()
                if (i < end && (data[i] == '-'.code.toByte() || data[i] == '+'.code.toByte())) {
                    i++
                }
                while (i < end && data[i] in DIGIT_RANGE) {
                    exponent = exponent * 10 + (data[i] - '0'.code.toByte())
                    i++
                }
                if (negativeExponent) exponent = -exponent
            }
            if (i != end || digits == 0) {
                return false
            }
            if (digits > 18) {
                // Too many digits to collect in a Long.
                doubleValue = String(data, start, end - start, Charsets.ISO_8859_1).toDouble()
                longValue = doubleValue.toLong()
                isInteger = false
                return true
            }
            if (negative) mantissa = -mantissa
            val scale = exponent - fractionDigits
            doubleValue = when {
                scale == 0 -> mantissa.toDouble()
                scale > 0 -> mantissa * 10.0.pow(scale)
                else -> mantissa / 10.0.pow(-scale)
            }
            longValue = if (isInteger) mantissa else doubleValue.toLong()
            return true
        }

        /** Skips a node and all of its children, without reading them. */
        private fun skipNode() {
            readValues()
            skipWhitespace(false)
            if (pos >= data.size || data[pos] != '{'.code.toByte()) {
                return
            }
            var depth = 0
            while (pos < data.size) {
                when (data[pos].toInt().toChar()) {
                    '{' -> depth++
                    '}' -> if (--depth == 0) {
                        pos++
                        return
                    }
                    '"' -> pos = indexOf('"'.code.toByte(), pos + 1)
                    ';' -> skipLine()
                }
                pos++
            }
            throw IOException("Unexpected end of FBX file.")
        }

        private fun skipWhitespace(newlines: Boolean) {
            while (pos < data.size) {
                val b = data[pos]
                when {
                    b == ';'.code.toByte() && newlines -> skipLine()
                    b == ' '.code.toByte() || b == '\t'.code.toByte() || b == '\r'.code.toByte() -> pos++
                    b == '\n'.code.toByte() && newlines -> pos++
                    else -> return
                }
            }
        }

        private fun nextLineStartsWithComma(): Boolean {
            var i = pos + 1
            while (i < data.size && (data[i] == ' '.code.toByte() || data[i] == '\t'.code.toByte() || data[i] == '\r'.code.toByte())) {
                i++
            }
            return i < data.size && data[i] == ','.code.toByte()
        }

        private fun skipLine() {
            while (pos < data.size && data[pos] != '\n'.code.toByte()) {
                pos++
            }
        }

        private fun indexOf(b: Byte, from: Int): Int {
            for (i in from until data.size) {
                if (data[i] == b) return i
            }
            throw IOException("Unexpected end of FBX file.")
        }

        private fun isWhitespace(b: Byte) = b == ' '.code.toByte() || b == '\t'.code.toByte() || b == '\r'.code.toByte() || b == '\n'.code.toByte()

        private fun isDelimiter(b: Byte) = isWhitespace(b) || b == ','.code.toByte() || b == '{'.code.toByte() || b == '}'.code.toByte()

        companion object {
            private val DIGIT_RANGE = '0'.code.toByte()..'9'.code.toByte()
        }
    }
}
