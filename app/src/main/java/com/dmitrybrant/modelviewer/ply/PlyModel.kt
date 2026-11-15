package com.dmitrybrant.modelviewer.ply

import android.opengl.GLES20
import android.opengl.Matrix
import com.dmitrybrant.modelviewer.MeshModel
import com.dmitrybrant.modelviewer.Light
import com.dmitrybrant.modelviewer.R
import com.dmitrybrant.modelviewer.util.FloatList
import com.dmitrybrant.modelviewer.util.IntList
import com.dmitrybrant.modelviewer.util.Util
import java.io.*
import java.nio.ByteBuffer
import java.nio.ByteOrder

/*
*
* Info on the PLY format: https://en.wikipedia.org/wiki/PLY_(file_format)
*
* Copyright 2017- Dmitry Brant. All rights reserved.
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
 * A model from a PLY (Stanford) file, in ASCII or binary (either byte order). A file with faces is
 * shown as a mesh, and a file with only vertices is shown as a point cloud.
 *
 * Faces may be polygons with any number of sides. Vertex properties may be of any type, and normals
 * in the file are used when present. Other properties and elements are skipped.
 */
class PlyModel(inputStream: InputStream) : MeshModel() {
    private val pointColor = floatArrayOf(1.0f, 1.0f, 1.0f, 1.0f)
    private var isPointCloud = false

    init {
        val stream = BufferedInputStream(inputStream, INPUT_BUFFER_SIZE)
        read(stream)
        if (vertexCount <= 0 || vertexBuffer == null) {
            throw IOException("Invalid model.")
        }
    }

    override fun setup(boundSize: Float) {
        if (isPointCloud) {
            if (GLES20.glIsProgram(glProgram)) {
                GLES20.glDeleteProgram(glProgram)
                glProgram = -1
            }
            glProgram = Util.compileProgram(R.raw.point_cloud_vertex, R.raw.point_cloud_fragment, arrayOf("a_Position", "a_Color"))
        } else {
            super.setup(boundSize)
        }
        initModelMatrix(boundSize)
    }

    public override fun initModelMatrix(boundSize: Float) {
        val yRotation = 180f
        initModelMatrix(boundSize, 0.0f, yRotation, 0.0f)
        var scale = getBoundScale(boundSize)
        if (scale == 0.0f) {
            scale = 1.0f
        }
        floorOffset = (minY - centerMassY) / scale
    }

    // Header

    private enum class ScalarType(val size: Int, val colorScale: Float) {
        INT8(1, 1f / 255f), UINT8(1, 1f / 255f),
        INT16(2, 1f / 65535f), UINT16(2, 1f / 65535f),
        // Colors with larger integer types are rare, and are usually in the range of 0 to 255.
        INT32(4, 1f / 255f), UINT32(4, 1f / 255f),
        INT64(8, 1f / 255f), UINT64(8, 1f / 255f),
        // Floating-point colors are in the range of 0 to 1.
        FLOAT32(4, 1f), FLOAT64(8, 1f);

        companion object {
            fun of(name: String) = when (name) {
                "char", "int8" -> INT8
                "uchar", "uint8" -> UINT8
                "short", "int16" -> INT16
                "ushort", "uint16" -> UINT16
                "int", "int32" -> INT32
                "uint", "uint32" -> UINT32
                "long", "int64" -> INT64
                "ulong", "uint64" -> UINT64
                "float", "float32" -> FLOAT32
                "double", "float64" -> FLOAT64
                else -> null
            }
        }
    }

    /** A property of an element. For a list property, [countType] is the type of its count, and [type] is the type of its items. */
    private class Property(val name: String, val type: ScalarType, val countType: ScalarType?) {
        val isList get() = countType != null
        val isFaceIndexList get() = isList && (name == "vertex_indices" || name == "vertex_index")
    }

    private class Element(val name: String, val count: Int) {
        val properties = mutableListOf<Property>()

        fun indexOf(names: Set<String>) = properties.indexOfFirst { it.name in names && !it.isList }
    }

    private class Header(val isBinary: Boolean, val isBigEndian: Boolean, val elements: List<Element>)

    /** Reads the header, a line at a time, which leaves the stream at the start of the data. */
    private fun readHeader(stream: InputStream): Header {
        val elements = mutableListOf<Element>()
        var isBinary = false
        var isBigEndian = false
        var sawMagic = false
        var headerSize = 0
        val lineBytes = ByteArrayOutputStream()

        while (true) {
            lineBytes.reset()
            while (true) {
                val b = stream.read()
                if (b < 0 || ++headerSize > MAX_HEADER_SIZE) {
                    throw IOException("Invalid PLY header.")
                }
                if (b == '\n'.code) {
                    break
                }
                lineBytes.write(b)
            }
            val line = lineBytes.toString("ISO-8859-1").trim()
            if (line.isEmpty()) {
                continue
            }
            val tokens = line.split(spaceRegex)
            if (!sawMagic) {
                if (tokens[0] != "ply") {
                    throw IOException("Not a PLY file.")
                }
                sawMagic = true
                continue
            }
            when (tokens[0]) {
                "format" -> {
                    val format = tokens.getOrElse(1) { "" }
                    if (format.contains("binary")) {
                        isBinary = true
                        isBigEndian = format.contains("big")
                    }
                }
                "element" -> {
                    val count = tokens.getOrNull(2)?.toIntOrNull()
                    if (count == null || count < 0) {
                        throw IOException("Invalid element in PLY header.")
                    }
                    elements.add(Element(tokens[1], count))
                }
                "property" -> {
                    val element = elements.lastOrNull() ?: continue
                    val property = if (tokens.size >= 5 && tokens[1] == "list") {
                        val countType = ScalarType.of(tokens[2])
                        val itemType = ScalarType.of(tokens[3])
                        if (countType == null || itemType == null) {
                            throw IOException("Unsupported property type in PLY header: ${tokens[2]} ${tokens[3]}")
                        }
                        Property(tokens.last(), itemType, countType)
                    } else {
                        val type = (if (tokens.size >= 3) ScalarType.of(tokens[1]) else null)
                            ?: throw IOException("Unsupported property type in PLY header: ${tokens.drop(1).joinToString(" ")}")
                        Property(tokens.last(), type, null)
                    }
                    element.properties.add(property)
                }
                "end_header" -> return Header(isBinary, isBigEndian, elements)
                // "comment", "obj_info", etc.
                else -> {}
            }
        }
    }

    // Reading

    /** Where the properties of interest are, among the properties of the vertex element. */
    private class VertexLayout(element: Element) {
        val x = element.indexOf(setOf("x"))
        val y = element.indexOf(setOf("y"))
        val z = element.indexOf(setOf("z"))
        val nx = element.indexOf(setOf("nx"))
        val ny = element.indexOf(setOf("ny"))
        val nz = element.indexOf(setOf("nz"))
        val r = element.indexOf(redNames)
        val g = element.indexOf(greenNames)
        val b = element.indexOf(blueNames)
        val a = element.indexOf(alphaNames)
        val rScale = element.properties.getOrNull(r)?.type?.colorScale ?: 1f
        val gScale = element.properties.getOrNull(g)?.type?.colorScale ?: 1f
        val bScale = element.properties.getOrNull(b)?.type?.colorScale ?: 1f
        val aScale = element.properties.getOrNull(a)?.type?.colorScale ?: 1f

        val hasColors = r >= 0 && g >= 0 && b >= 0
        val hasNormals = nx >= 0 && ny >= 0 && nz >= 0
    }

    /** The data that's read from the file. */
    private class Contents(vertexCount: Int, layout: VertexLayout) {
        val vertices = FloatList(initialCapacity(vertexCount, 3))
        val normals = FloatList(if (layout.hasNormals) initialCapacity(vertexCount, 3) else 16)
        val colors = FloatList(initialCapacity(vertexCount, 4))
        val indices = IntList()
        var hasFaces = false
        /** Corners of the current polygon. */
        var corners = IntArray(64)
            private set

        /** Adds the properties of interest of a vertex, given the values of all its properties. */
        fun addVertex(layout: VertexLayout, values: FloatArray, pointColor: FloatArray) {
            vertices.add(values[layout.x])
            vertices.add(values[layout.y])
            vertices.add(values[layout.z])
            if (layout.hasNormals) {
                normals.add(values[layout.nx])
                normals.add(values[layout.ny])
                normals.add(values[layout.nz])
            }
            if (layout.hasColors) {
                colors.add(values[layout.r] * layout.rScale)
                colors.add(values[layout.g] * layout.gScale)
                colors.add(values[layout.b] * layout.bScale)
                colors.add(if (layout.a >= 0) values[layout.a] * layout.aScale else 1f)
            } else {
                for (c in pointColor) {
                    colors.add(c)
                }
            }
        }

        fun ensureCorners(count: Int) {
            if (count > corners.size) {
                corners = IntArray(count)
            }
        }

        /** Adds the triangles of a polygon (as a fan), given the indices of its corners. */
        fun addPolygon(count: Int) {
            for (k in 1 until count - 1) {
                indices.add(corners[0])
                indices.add(corners[k])
                indices.add(corners[k + 1])
            }
        }

        companion object {
            /** Initial capacity of an array, which isn't too large, since the counts in the header can't be trusted. */
            private fun initialCapacity(count: Int, itemsPerVertex: Int) =
                (count.toLong() * itemsPerVertex).coerceIn(16L, MAX_INITIAL_CAPACITY.toLong()).toInt()
        }
    }

    private fun read(stream: BufferedInputStream) {
        val header = readHeader(stream)
        val vertexElement = header.elements.firstOrNull { it.name == "vertex" }
        if (vertexElement == null || vertexElement.count <= 0) {
            throw IOException("No vertices found in model.")
        }
        val layout = VertexLayout(vertexElement)
        if (layout.x < 0 || layout.y < 0 || layout.z < 0) {
            throw IOException("Vertices in model have no positions.")
        }

        val contents = Contents(vertexElement.count, layout)
        if (header.isBinary) {
            readBinary(stream, header, layout, contents)
        } else {
            readText(stream, header, layout, contents)
        }

        val vertices = contents.vertices.array
        vertexCount = contents.vertices.size / 3
        val indices = contents.indices
        // Faces with indices out of range make the model invalid.
        for (i in 0 until indices.size) {
            if (indices[i] !in 0 until vertexCount) {
                throw IOException("Invalid face in model.")
            }
        }

        var centerMassX = 0.0
        var centerMassY = 0.0
        var centerMassZ = 0.0
        for (i in 0 until vertexCount) {
            val x = vertices[i * 3]
            val y = vertices[i * 3 + 1]
            val z = vertices[i * 3 + 2]
            adjustMaxMin(x, y, z)
            centerMassX += x
            centerMassY += y
            centerMassZ += z
        }
        this.centerMassX = (centerMassX / vertexCount).toFloat()
        this.centerMassY = (centerMassY / vertexCount).toFloat()
        this.centerMassZ = (centerMassZ / vertexCount).toFloat()

        vertexBuffer = allocateFloats(vertices, contents.vertices.size)
        isPointCloud = indices.size == 0
        if (!isPointCloud) {
            indexCount = indices.size
            indexBuffer = allocateInts(indices.array, indices.size)
            normalBuffer = if (layout.hasNormals) {
                allocateFloats(contents.normals.array, contents.normals.size)
            } else {
                allocateFloats(faceNormals(vertices, vertexCount, indices))
            }
        }
        // Point clouds always need colors, but meshes only have them if the file does.
        if (isPointCloud || layout.hasColors) {
            colorBuffer = allocateFloats(contents.colors.array, contents.colors.size)
        }
    }

    /** Normals for each vertex, which are those of the last face (or triangle of a polygon) that uses the vertex. */
    private fun faceNormals(vertices: FloatArray, vertexCount: Int, indices: IntList): FloatArray {
        val normals = FloatArray(vertexCount * 3)
        val normal = FloatArray(3)
        var i = 0
        while (i + 2 < indices.size) {
            val a = indices[i] * 3
            val b = indices[i + 1] * 3
            val c = indices[i + 2] * 3
            Util.calculateNormal(vertices[a], vertices[a + 1], vertices[a + 2],
                vertices[b], vertices[b + 1], vertices[b + 2],
                vertices[c], vertices[c + 1], vertices[c + 2], normal)
            for (k in intArrayOf(a, b, c)) {
                normals[k] = normal[0]
                normals[k + 1] = normal[1]
                normals[k + 2] = normal[2]
            }
            i += 3
        }
        return normals
    }

    // ASCII

    private fun readText(stream: InputStream, header: Header, layout: VertexLayout, contents: Contents) {
        val reader = BufferedReader(InputStreamReader(stream, Charsets.ISO_8859_1), INPUT_BUFFER_SIZE)
        val tokens = LineTokens()

        for (element in header.elements) {
            if (contents.hasFaces && element.name != "vertex" && element.name != "face") {
                // Nothing else is needed.
                break
            }
            val isVertex = element.name == "vertex"
            val isFace = element.name == "face"
            val values = FloatArray(element.properties.size)
            var row = 0
            while (row < element.count) {
                val line = reader.readLine() ?: throw EOFException("Unexpected end of file.")
                tokens.split(line)
                if (tokens.count == 0) {
                    continue
                }
                row++
                if (isVertex) {
                    // Lists in vertices aren't expected, so tokens correspond to properties.
                    if (tokens.count < values.size) {
                        throw IOException("Invalid vertex in model.")
                    }
                    for (i in values.indices) {
                        values[i] = tokens.float(i)
                    }
                    contents.addVertex(layout, values, pointColor)
                } else if (isFace) {
                    contents.hasFaces = true
                    var t = 0
                    for (property in element.properties) {
                        if (property.isList) {
                            val n = if (t < tokens.count) tokens.int(t) else -1
                            if (n < 0 || t + n >= tokens.count) {
                                throw IOException("Invalid face in model.")
                            }
                            t++
                            if (property.isFaceIndexList) {
                                contents.ensureCorners(n)
                                for (k in 0 until n) {
                                    contents.corners[k] = tokens.int(t + k)
                                }
                                contents.addPolygon(n)
                            }
                            t += n
                        } else {
                            t++
                        }
                    }
                }
            }
        }
    }

    /** The positions of the whitespace-separated tokens in a line of text. */
    private class LineTokens {
        private var line = ""
        private var starts = IntArray(32)
        private var ends = IntArray(32)
        var count = 0
            private set

        fun split(line: String) {
            this.line = line
            count = 0
            var i = 0
            val len = line.length
            while (i < len) {
                while (i < len && line[i] <= ' ') i++
                if (i >= len) break
                val start = i
                while (i < len && line[i] > ' ') i++
                if (count == starts.size) {
                    starts = starts.copyOf(count * 2)
                    ends = ends.copyOf(count * 2)
                }
                starts[count] = start
                ends[count] = i
                count++
            }
        }

        fun float(index: Int): Float {
            return line.substring(starts[index], ends[index]).toFloatOrNull()
                ?: throw IOException("Invalid vertex in model.")
        }

        /** Parses a non-negative integer (a list count or index), without making a substring. */
        fun int(index: Int): Int {
            var value = 0L
            for (i in starts[index] until ends[index]) {
                val c = line[i]
                if (c !in '0'..'9' || value > Int.MAX_VALUE) {
                    throw IOException("Invalid face in model.")
                }
                value = value * 10 + (c - '0')
            }
            if (value > Int.MAX_VALUE) {
                throw IOException("Invalid face in model.")
            }
            return value.toInt()
        }
    }

    // Binary

    /** Reads binary data from a stream, through a buffer that's refilled as needed. */
    private class BinaryReader(private val stream: InputStream, bigEndian: Boolean) {
        private val order = if (bigEndian) ByteOrder.BIG_ENDIAN else ByteOrder.LITTLE_ENDIAN
        private var bytes = ByteArray(0x10000)
        private var buffer = ByteBuffer.wrap(bytes).order(order)
        /** Position of the next unread byte in the buffer. */
        private var position = 0
        /** End of the data in the buffer. */
        private var limit = 0

        /** Makes sure that at least [n] bytes are in the buffer, after the current position. */
        fun require(n: Long) {
            if (limit - position >= n) {
                return
            }
            if (n > MAX_ROW_SIZE) {
                throw IOException("Invalid model.")
            }
            val remaining = limit - position
            if (n > bytes.size) {
                val newBytes = ByteArray(maxOf(n.toInt(), bytes.size * 2))
                System.arraycopy(bytes, position, newBytes, 0, remaining)
                bytes = newBytes
                buffer = ByteBuffer.wrap(bytes).order(order)
            } else {
                System.arraycopy(bytes, position, bytes, 0, remaining)
            }
            position = 0
            limit = remaining
            while (limit < n) {
                val count = stream.read(bytes, limit, bytes.size - limit)
                if (count < 0) {
                    throw EOFException("Unexpected end of file.")
                }
                limit += count
            }
        }

        /** Moves past [n] bytes, which must be in the buffer. */
        fun advance(n: Int) {
            position += n
        }

        /** Skips [n] bytes, which don't need to be in the buffer. */
        fun skip(n: Long) {
            var left = n
            while (left > 0) {
                if (position == limit) {
                    require(1)
                }
                val chunk = minOf(left, (limit - position).toLong()).toInt()
                position += chunk
                left -= chunk
            }
        }

        /** Reads a value of the given type, at the given offset from the current position. */
        fun scalar(offset: Int, type: ScalarType): Double {
            val p = position + offset
            return when (type) {
                ScalarType.INT8 -> bytes[p].toDouble()
                ScalarType.UINT8 -> (bytes[p].toInt() and 0xff).toDouble()
                ScalarType.INT16 -> buffer.getShort(p).toDouble()
                ScalarType.UINT16 -> (buffer.getShort(p).toInt() and 0xffff).toDouble()
                ScalarType.INT32 -> buffer.getInt(p).toDouble()
                ScalarType.UINT32 -> (buffer.getInt(p).toLong() and 0xffffffffL).toDouble()
                ScalarType.INT64 -> buffer.getLong(p).toDouble()
                ScalarType.UINT64 -> buffer.getLong(p).toULong().toDouble()
                ScalarType.FLOAT32 -> buffer.getFloat(p).toDouble()
                ScalarType.FLOAT64 -> buffer.getDouble(p)
            }
        }

        /** Reads an integer (a list count or index), which must be in the range of an Int. */
        fun index(offset: Int, type: ScalarType): Int {
            val value = scalar(offset, type)
            if (value < 0 || value > Int.MAX_VALUE) {
                throw IOException("Invalid face in model.")
            }
            return value.toInt()
        }
    }

    private fun readBinary(stream: InputStream, header: Header, layout: VertexLayout, contents: Contents) {
        val reader = BinaryReader(stream, header.isBigEndian)

        for (element in header.elements) {
            if (contents.hasFaces && element.name != "vertex" && element.name != "face") {
                // Nothing else is needed.
                break
            }
            val properties = element.properties
            val isVertex = element.name == "vertex"
            val isFace = element.name == "face"
            val values = FloatArray(properties.size)

            if (properties.none { it.isList }) {
                // Fixed-size rows: find the offsets of the properties within each row.
                val offsets = IntArray(properties.size)
                var rowSize = 0
                for (i in properties.indices) {
                    offsets[i] = rowSize
                    rowSize += properties[i].type.size
                }
                if (!isVertex) {
                    // Skip other elements whole.
                    reader.skip(rowSize.toLong() * element.count)
                    continue
                }
                for (row in 0 until element.count) {
                    reader.require(rowSize.toLong())
                    for (i in properties.indices) {
                        values[i] = reader.scalar(offsets[i], properties[i].type).toFloat()
                    }
                    contents.addVertex(layout, values, pointColor)
                    reader.advance(rowSize)
                }
                continue
            }

            // Rows of varying size, which are read property by property.
            if (isFace) {
                contents.hasFaces = true
            }
            for (row in 0 until element.count) {
                for (i in properties.indices) {
                    val property = properties[i]
                    val countType = property.countType
                    if (countType != null) {
                        reader.require(countType.size.toLong())
                        val n = reader.index(0, countType)
                        reader.advance(countType.size)
                        val itemSize = property.type.size
                        if (isFace && property.isFaceIndexList) {
                            reader.require(n.toLong() * itemSize)
                            contents.ensureCorners(n)
                            for (k in 0 until n) {
                                contents.corners[k] = reader.index(k * itemSize, property.type)
                            }
                            contents.addPolygon(n)
                            reader.advance(n * itemSize)
                        } else {
                            reader.skip(n.toLong() * itemSize)
                        }
                    } else {
                        reader.require(property.type.size.toLong())
                        if (isVertex) {
                            values[i] = reader.scalar(0, property.type).toFloat()
                        }
                        reader.advance(property.type.size)
                    }
                }
                if (isVertex) {
                    contents.addVertex(layout, values, pointColor)
                }
            }
        }
    }

    override fun draw(viewMatrix: FloatArray, projectionMatrix: FloatArray, light: Light) {
        if (!isPointCloud) {
            super.draw(viewMatrix, projectionMatrix, light)
            return
        }
        if (vertexBuffer == null) {
            return
        }
        GLES20.glUseProgram(glProgram)
        val mvpMatrixHandle = GLES20.glGetUniformLocation(glProgram, "u_MVP")
        val positionHandle = GLES20.glGetAttribLocation(glProgram, "a_Position")
        val colorHandle = GLES20.glGetAttribLocation(glProgram, "a_Color")
        val pointThicknessHandle = GLES20.glGetUniformLocation(glProgram, "u_PointThickness")
        val ambientColorHandle = GLES20.glGetUniformLocation(glProgram, "u_ambientColor")
        GLES20.glEnableVertexAttribArray(positionHandle)
        GLES20.glVertexAttribPointer(positionHandle, COORDS_PER_VERTEX, GLES20.GL_FLOAT, false, VERTEX_STRIDE, vertexBuffer)
        GLES20.glEnableVertexAttribArray(colorHandle)
        GLES20.glVertexAttribPointer(colorHandle, 4, GLES20.GL_FLOAT, false, 4 * BYTES_PER_FLOAT, colorBuffer)
        Matrix.multiplyMM(mvMatrix, 0, viewMatrix, 0, modelMatrix, 0)
        Matrix.multiplyMM(mvpMatrix, 0, projectionMatrix, 0, mvMatrix, 0)
        GLES20.glUniformMatrix4fv(mvpMatrixHandle, 1, false, mvpMatrix, 0)
        GLES20.glUniform1f(pointThicknessHandle, 3.0f)
        if (ambientColorHandle >= 0) {
            GLES20.glUniform4fv(ambientColorHandle, 1, pointColor, 0)
        }
        GLES20.glDrawArrays(GLES20.GL_POINTS, 0, vertexCount)
        GLES20.glDisableVertexAttribArray(colorHandle)
        GLES20.glDisableVertexAttribArray(positionHandle)
    }

    companion object {
        private val spaceRegex = "\\s+".toRegex()

        /** Largest header that's read. */
        private const val MAX_HEADER_SIZE = 0x100000

        /** Largest initial capacity of the arrays for the model, which grow from there to fit what's read. */
        private const val MAX_INITIAL_CAPACITY = 1 shl 22

        /** Largest row (or list) of binary data that's read into memory at once. */
        private const val MAX_ROW_SIZE = 1L shl 28

        // Vertex color properties may be named either "red" or "diffuse_red", etc.
        private val redNames = setOf("red", "diffuse_red")
        private val greenNames = setOf("green", "diffuse_green")
        private val blueNames = setOf("blue", "diffuse_blue")
        private val alphaNames = setOf("alpha", "diffuse_alpha")
    }
}
