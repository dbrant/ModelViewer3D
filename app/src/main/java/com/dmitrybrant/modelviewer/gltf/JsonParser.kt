package com.dmitrybrant.modelviewer.gltf

import java.io.IOException

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
 * A minimal JSON parser, which reads objects as Maps, arrays as Lists, numbers as Doubles, and
 * strings, booleans, and null as themselves.
 */
class JsonParser private constructor(private val text: String) {
    private var pos = 0

    companion object {
        private const val MAX_DEPTH = 256

        fun parse(text: String): Any? {
            val parser = JsonParser(text)
            val value = parser.readValue(0)
            parser.skipWhitespace()
            if (parser.pos != text.length) {
                throw IOException("Invalid JSON at position ${parser.pos}.")
            }
            return value
        }
    }

    private fun readValue(depth: Int): Any? {
        if (depth > MAX_DEPTH) {
            throw IOException("JSON is nested too deeply.")
        }
        skipWhitespace()
        if (pos >= text.length) {
            throw IOException("Unexpected end of JSON.")
        }
        val c = text[pos]
        return when {
            c == '{' -> readObject(depth)
            c == '[' -> readArray(depth)
            c == '"' -> readString()
            c == '-' || c in '0'..'9' -> readNumber()
            text.startsWith("true", pos) -> true.also { pos += 4 }
            text.startsWith("false", pos) -> false.also { pos += 5 }
            text.startsWith("null", pos) -> null.also { pos += 4 }
            else -> throw IOException("Invalid JSON at position $pos.")
        }
    }

    private fun readObject(depth: Int): Map<String, Any?> {
        val result = LinkedHashMap<String, Any?>()
        pos++
        skipWhitespace()
        if (peek() == '}') {
            pos++
            return result
        }
        while (true) {
            skipWhitespace()
            if (peek() != '"') {
                throw IOException("Invalid JSON object at position $pos.")
            }
            val key = readString()
            skipWhitespace()
            expect(':')
            result[key] = readValue(depth + 1)
            skipWhitespace()
            when (peek()) {
                ',' -> pos++
                '}' -> {
                    pos++
                    return result
                }
                else -> throw IOException("Invalid JSON object at position $pos.")
            }
        }
    }

    private fun readArray(depth: Int): List<Any?> {
        val result = ArrayList<Any?>()
        pos++
        skipWhitespace()
        if (peek() == ']') {
            pos++
            return result
        }
        while (true) {
            result.add(readValue(depth + 1))
            skipWhitespace()
            when (peek()) {
                ',' -> pos++
                ']' -> {
                    pos++
                    return result
                }
                else -> throw IOException("Invalid JSON array at position $pos.")
            }
        }
    }

    private fun readString(): String {
        pos++
        val builder = StringBuilder()
        while (true) {
            if (pos >= text.length) {
                throw IOException("Unexpected end of JSON string.")
            }
            val c = text[pos++]
            when (c) {
                '"' -> return builder.toString()
                '\\' -> {
                    if (pos >= text.length) {
                        throw IOException("Unexpected end of JSON string.")
                    }
                    when (val escaped = text[pos++]) {
                        'b' -> builder.append('\b')
                        'f' -> builder.append('\u000c')
                        'n' -> builder.append('\n')
                        'r' -> builder.append('\r')
                        't' -> builder.append('\t')
                        'u' -> {
                            val code = text.substring(pos, minOf(pos + 4, text.length)).toIntOrNull(16)
                                ?: throw IOException("Invalid JSON escape at position $pos.")
                            builder.append(code.toChar())
                            pos += 4
                        }
                        else -> builder.append(escaped)
                    }
                }
                else -> builder.append(c)
            }
        }
    }

    private fun readNumber(): Double {
        val start = pos
        while (pos < text.length && (text[pos] in '0'..'9' || text[pos] in "+-.eE")) {
            pos++
        }
        return text.substring(start, pos).toDoubleOrNull() ?: throw IOException("Invalid JSON number at position $start.")
    }

    private fun skipWhitespace() {
        while (pos < text.length && (text[pos] == ' ' || text[pos] == '\t' || text[pos] == '\n' || text[pos] == '\r')) {
            pos++
        }
    }

    private fun peek() = if (pos < text.length) text[pos] else '\u0000'

    private fun expect(c: Char) {
        if (peek() != c) {
            throw IOException("Expected '$c' in JSON at position $pos.")
        }
        pos++
    }
}
