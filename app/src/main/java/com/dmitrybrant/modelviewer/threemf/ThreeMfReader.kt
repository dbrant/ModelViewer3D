package com.dmitrybrant.modelviewer.threemf

import com.dmitrybrant.modelviewer.util.FloatList
import com.dmitrybrant.modelviewer.util.IntList
import org.xml.sax.Attributes
import org.xml.sax.InputSource
import org.xml.sax.SAXException
import org.xml.sax.helpers.DefaultHandler
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.StringReader
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipException
import java.util.zip.ZipFile
import javax.xml.parsers.ParserConfigurationException
import javax.xml.parsers.SAXParserFactory

/*
* Reader for 3MF (3D Manufacturing Format) files, which are ZIP archives that contain XML model
* parts. Reads the meshes of the objects in the build, placed with the transforms of the build
* items and of the components of objects (which may be in other model parts, as in the Production
* extension), and their colors and textures (from the Materials and Properties extension).
*
* The archive is read through its central directory (rather than sequentially), since some
* archives, such as those from Bambu Studio, have uncompressed entries whose sizes are only given
* after their data. So the file is first copied to a temporary file.
*
* Info on the format: https://3mf.io/specification/ (the core specification, and the Materials and
* Properties, and Production extensions).
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
class ThreeMfReader(private val inputStream: InputStream) {
    /**
     * The triangles of the build, in world coordinates, with three corners each. Triangles with
     * the same texture (or with none) are consecutive, as described by the [parts].
     */
    class Mesh(
        val triangleCount: Int,
        /** Position of each corner of each triangle (9 floats per triangle). */
        val positions: FloatArray,
        /** RGBA color of each corner of each triangle (12 floats per triangle), if any triangle has a color. */
        val colors: FloatArray?,
        /** Texture coordinates of each corner of each triangle (6 floats per triangle), if any triangle has a texture. */
        val texCoords: FloatArray?,
        val parts: List<Part>
    )

    /** A range of consecutive triangles that have the same texture, or none. */
    class Part(val firstTriangle: Int, val triangleCount: Int, val texture: ByteArray?, val textureName: String?)

    /** The resources and build of a model part of the package. */
    private class ModelPart {
        val objects = HashMap<Int, ModelObject>()
        /** Base materials and color groups (with RGBA colors), and texture coordinate groups, by resource ID. */
        val propertyGroups = HashMap<Int, PropertyGroup>()
        /** Paths of textures, by resource ID. */
        val texturePaths = HashMap<Int, String>()
        val buildItems = mutableListOf<Placement>()
    }

    private class ModelObject(val defaultPid: Int, val defaultPindex: Int) {
        val vertices = FloatList(0)
        /** Seven ints per triangle: the indices of its vertices, its property group (or -1), and the property of each corner (or -1). */
        val triangles = IntList(0)
        val components = mutableListOf<Placement>()
    }

    /** A build item or component: an object, which may be in another model part, and how it's placed. */
    private class Placement(val objectId: Int, val transform: DoubleArray, val path: String?)

    private class PropertyGroup(val colors: FloatList?, val texCoords: FloatList?, val textureId: Int)

    /** The triangles of the build that have the same texture, as they're collected. */
    private class TriangleGroup(val texturePath: String?) {
        val positions = FloatList()
        val colors = FloatList()
        val texCoords = FloatList()
    }

    private lateinit var zip: ZipFile
    /** The entries of the archive, by their normalized paths. */
    private lateinit var entries: Map<String, ZipEntry>
    private val modelParts = HashMap<String, ModelPart>()
    private val textures = HashMap<String, ByteArray?>()
    private val triangleGroups = LinkedHashMap<String?, TriangleGroup>()
    private var hasColors = false

    fun read(): Mesh {
        val file = File.createTempFile("model", ".3mf")
        try {
            file.outputStream().use { inputStream.copyTo(it) }
            ZipFile(file).use {
                zip = it
                entries = it.entries().asSequence().associateBy { entry -> normalizePath(entry.name) }
                return readPackage()
            }
        } catch (e: ZipException) {
            throw IOException("Not a valid 3MF file.", e)
        } catch (e: SAXException) {
            throw IOException("Invalid 3MF model: ${e.message}", e)
        } finally {
            file.delete()
        }
    }

    private fun readPackage(): Mesh {
        val rootPath = entries[ROOT_RELATIONSHIPS_PATH]?.let { readRootRelationships(it) } ?: DEFAULT_ROOT_PATH
        if (rootPath !in entries) {
            throw IOException("No 3D model found in file.")
        }
        val root = modelPart(rootPath)
        for (item in root.buildItems) {
            addObject(item.path?.let { modelPart(it) } ?: root, item.objectId, item.transform, 0)
        }
        return buildMesh()
    }

    /** Returns the model part with the given path, which is parsed the first time it's needed. */
    private fun modelPart(path: String): ModelPart {
        val normalized = normalizePath(path)
        return modelParts.getOrPut(normalized) {
            val entry = entries[normalized] ?: throw IOException("Missing model part: $path")
            zip.getInputStream(entry).use { parseModel(it) }
        }
    }

    private fun texture(path: String): ByteArray? {
        return textures.getOrPut(path) { entries[path]?.let { entry -> zip.getInputStream(entry).use { it.readBytes() } } }
    }

    /** Handler for the XML documents of a package, which never refer to any other documents. */
    private open class PackageHandler : DefaultHandler() {
        override fun resolveEntity(publicId: String?, systemId: String?) = InputSource(StringReader(""))
    }

    /** Returns the path of the model part that holds the build, from the package's relationships. */
    private fun readRootRelationships(entry: ZipEntry): String? {
        var rootPath: String? = null
        zip.getInputStream(entry).use {
            parse(it, object : PackageHandler() {
                override fun startElement(uri: String, localName: String, qName: String, attributes: Attributes) {
                    if (localName == "Relationship" && attributes.getValue("Type") == MODEL_RELATIONSHIP_TYPE) {
                        attributes.getValue("Target")?.let { target -> rootPath = normalizePath(target) }
                    }
                }
            })
        }
        return rootPath
    }

    private fun parseModel(stream: InputStream): ModelPart {
        val part = ModelPart()
        parse(stream, object : PackageHandler() {
            private var obj: ModelObject? = null
            private var group: PropertyGroup? = null
            // Other extensions have elements with the same names, elsewhere.
            private var inMesh = false
            private var inBuild = false

            override fun startElement(uri: String, localName: String, qName: String, attributes: Attributes) {
                when (localName) {
                    "object" -> obj = ModelObject(intAttribute(attributes, "pid", -1), intAttribute(attributes, "pindex", 0))
                        .also { part.objects[intAttribute(attributes, "id")] = it }
                    "mesh" -> inMesh = obj != null
                    "vertex" -> if (inMesh) obj?.let {
                        it.vertices.add(floatAttribute(attributes, "x"))
                        it.vertices.add(floatAttribute(attributes, "y"))
                        it.vertices.add(floatAttribute(attributes, "z"))
                    }
                    "triangle" -> if (inMesh) obj?.let {
                        for (name in TRIANGLE_ATTRIBUTES) {
                            it.triangles.add(intAttribute(attributes, name, if (name.startsWith("v")) null else -1))
                        }
                    }
                    "component" -> obj?.components?.add(placement(attributes))
                    "build" -> inBuild = true
                    "item" -> if (inBuild) part.buildItems.add(placement(attributes))
                    "basematerials", "colorgroup" -> group = PropertyGroup(FloatList(), null, -1)
                        .also { part.propertyGroups[intAttribute(attributes, "id")] = it }
                    "base" -> group?.colors?.let { addColor(it, attributes.getValue("displaycolor")) }
                    "color" -> group?.colors?.let { addColor(it, attributes.getValue("color")) }
                    "texture2d" -> attributes.getValue("path")?.let { part.texturePaths[intAttribute(attributes, "id")] = normalizePath(it) }
                    "texture2dgroup" -> group = PropertyGroup(null, FloatList(), intAttribute(attributes, "texid"))
                        .also { part.propertyGroups[intAttribute(attributes, "id")] = it }
                    "tex2coord" -> group?.texCoords?.let {
                        it.add(floatAttribute(attributes, "u"))
                        it.add(floatAttribute(attributes, "v"))
                    }
                }
            }

            override fun endElement(uri: String, localName: String, qName: String) {
                when (localName) {
                    "object" -> obj = null
                    "mesh" -> inMesh = false
                    "build" -> inBuild = false
                    "basematerials", "colorgroup", "texture2dgroup" -> group = null
                }
            }
        })
        return part
    }

    private fun parse(stream: InputStream, handler: PackageHandler) {
        try {
            val factory = SAXParserFactory.newInstance()
            factory.isNamespaceAware = true
            factory.newSAXParser().parse(stream, handler)
        } catch (e: ParserConfigurationException) {
            throw IOException(e)
        }
    }

    private fun placement(attributes: Attributes): Placement {
        val path = attributes.getValue(PRODUCTION_NAMESPACE, "path") ?: attributes.getValue("p:path")
        return Placement(intAttribute(attributes, "objectid"), attributes.getValue("transform")?.let { parseTransform(it) } ?: IDENTITY, path)
    }

    /**
     * Adds the triangles of an object, and of its components, to the mesh, placed with the given
     * transform (a 4x3 affine matrix applied to row vectors, as in 3MF).
     */
    private fun addObject(part: ModelPart, objectId: Int, transform: DoubleArray, depth: Int) {
        if (depth > MAX_COMPONENT_DEPTH) {
            throw IOException("Components are nested too deeply.")
        }
        val obj = part.objects[objectId] ?: throw IOException("Missing object: $objectId")
        addMesh(part, obj, transform)
        for (component in obj.components) {
            addObject(component.path?.let { modelPart(it) } ?: part, component.objectId, multiply(component.transform, transform), depth + 1)
        }
    }

    private fun addMesh(part: ModelPart, obj: ModelObject, m: DoubleArray) {
        val vertexCount = obj.vertices.size / 3
        val v = obj.vertices.array
        val world = FloatArray(vertexCount * 3)
        for (i in 0 until vertexCount) {
            val x = v[i * 3].toDouble()
            val y = v[i * 3 + 1].toDouble()
            val z = v[i * 3 + 2].toDouble()
            world[i * 3] = (x * m[0] + y * m[3] + z * m[6] + m[9]).toFloat()
            world[i * 3 + 1] = (x * m[1] + y * m[4] + z * m[7] + m[10]).toFloat()
            world[i * 3 + 2] = (x * m[2] + y * m[5] + z * m[8] + m[11]).toFloat()
        }
        // A transform that mirrors the object turns its triangles inside out, unless their corners are reversed.
        val corners = if (determinant(m) < 0) intArrayOf(0, 2, 1) else intArrayOf(0, 1, 2)
        val t = obj.triangles.array
        val properties = IntArray(3)
        val color = FloatArray(4)
        for (i in 0 until obj.triangles.size / TRIANGLE_ATTRIBUTES.size) {
            val base = i * TRIANGLE_ATTRIBUTES.size
            // A triangle without its own properties has the object's, at every corner.
            var pid = t[base + 3]
            if (pid < 0) {
                pid = obj.defaultPid
                properties.fill(obj.defaultPindex)
            } else {
                val p1 = if (t[base + 4] >= 0) t[base + 4] else obj.defaultPindex
                properties[0] = p1
                properties[1] = if (t[base + 5] >= 0) t[base + 5] else p1
                properties[2] = if (t[base + 6] >= 0) t[base + 6] else p1
            }
            val group = part.propertyGroups[pid]
            val texturePath = group?.texCoords?.let { part.texturePaths[group.textureId] }
            val triangles = triangleGroups.getOrPut(texturePath) { TriangleGroup(texturePath) }
            for (c in corners) {
                val vertex = t[base + c]
                if (vertex !in 0 until vertexCount) {
                    throw IOException("Invalid vertex index: $vertex")
                }
                triangles.positions.add(world[vertex * 3])
                triangles.positions.add(world[vertex * 3 + 1])
                triangles.positions.add(world[vertex * 3 + 2])

                val p = properties[c]
                val colors = group?.colors
                if (colors != null && p >= 0 && p < colors.size / 4) {
                    colors.array.copyInto(color, 0, p * 4, p * 4 + 4)
                    hasColors = true
                } else if (texturePath != null) {
                    color.fill(1f)
                } else {
                    DEFAULT_COLOR.copyInto(color)
                }
                for (k in 0 until 4) {
                    triangles.colors.add(color[k])
                }

                val texCoords = group?.texCoords
                if (texturePath != null && texCoords != null && p >= 0 && p < texCoords.size / 2) {
                    triangles.texCoords.add(texCoords[p * 2])
                    triangles.texCoords.add(texCoords[p * 2 + 1])
                } else {
                    triangles.texCoords.add(0f)
                    triangles.texCoords.add(0f)
                }
            }
        }
    }

    private fun buildMesh(): Mesh {
        // Triangles without a texture come first, followed by those of each texture.
        val groups = triangleGroups.values.sortedBy { it.texturePath != null }
        val triangleCount = groups.sumOf { it.positions.size / 9 }
        val positions = FloatArray(triangleCount * 9)
        val colors = FloatArray(triangleCount * 12)
        val texCoords = FloatArray(triangleCount * 6)
        val parts = mutableListOf<Part>()
        var first = 0
        for (group in groups) {
            val count = group.positions.size / 9
            group.positions.array.copyInto(positions, first * 9, 0, count * 9)
            group.colors.array.copyInto(colors, first * 12, 0, count * 12)
            group.texCoords.array.copyInto(texCoords, first * 6, 0, count * 6)
            parts.add(Part(first, count, group.texturePath?.let { texture(it) }, group.texturePath))
            first += count
        }
        val hasTextures = parts.any { it.texture != null }
        return Mesh(triangleCount, positions, if (hasColors) colors else null, if (hasTextures) texCoords else null, parts)
    }

    private fun intAttribute(attributes: Attributes, name: String, default: Int? = null): Int {
        val value = attributes.getValue(name) ?: return default ?: throw SAXException("Missing attribute: $name")
        return value.toIntOrNull() ?: value.trim().toIntOrNull() ?: throw SAXException("Invalid $name: $value")
    }

    private fun floatAttribute(attributes: Attributes, name: String): Float {
        val value = attributes.getValue(name) ?: throw SAXException("Missing attribute: $name")
        // This is much faster than toFloatOrNull(), which checks the format with a regular expression first.
        return try {
            java.lang.Float.parseFloat(value)
        } catch (e: NumberFormatException) {
            throw SAXException("Invalid $name: $value")
        }
    }

    /** Adds a color given as #RRGGBB or #RRGGBBAA (in sRGB), or the default color if it's invalid. */
    private fun addColor(colors: FloatList, value: String?) {
        val hex = value?.trim()?.removePrefix("#")
        val rgba = if (hex != null && (hex.length == 6 || hex.length == 8)) hex.toLongOrNull(16) else null
        if (rgba == null) {
            DEFAULT_COLOR.forEach { colors.add(it) }
            return
        }
        val shift = if (hex!!.length == 8) 8 else 0
        colors.add(((rgba shr (16 + shift)) and 0xff) / 255f)
        colors.add(((rgba shr (8 + shift)) and 0xff) / 255f)
        colors.add(((rgba shr shift) and 0xff) / 255f)
        colors.add(if (shift == 8) (rgba and 0xff) / 255f else 1f)
    }

    private fun parseTransform(value: String): DoubleArray {
        val numbers = value.trim().split(WHITESPACE).map { it.toDoubleOrNull() ?: throw SAXException("Invalid transform: $value") }
        if (numbers.size != 12) {
            throw SAXException("Invalid transform: $value")
        }
        return numbers.toDoubleArray()
    }

    companion object {
        private const val PRODUCTION_NAMESPACE = "http://schemas.microsoft.com/3dmanufacturing/production/2015/06"
        private const val MODEL_RELATIONSHIP_TYPE = "http://schemas.microsoft.com/3dmanufacturing/2013/01/3dmodel"
        private const val ROOT_RELATIONSHIPS_PATH = "_rels/.rels"
        private const val DEFAULT_ROOT_PATH = "3d/3dmodel.model"
        private const val MAX_COMPONENT_DEPTH = 32
        private val TRIANGLE_ATTRIBUTES = arrayOf("v1", "v2", "v3", "pid", "p1", "p2", "p3")
        private val IDENTITY = doubleArrayOf(1.0, 0.0, 0.0, 0.0, 1.0, 0.0, 0.0, 0.0, 1.0, 0.0, 0.0, 0.0)
        private val WHITESPACE = Regex("\\s+")

        /** Color of triangles without one, in a model that has colors. */
        private val DEFAULT_COLOR = floatArrayOf(0.8f, 0.8f, 0.8f, 1f)

        /** Part names in a package are case-insensitive, and may be given with a leading slash. */
        private fun normalizePath(path: String) = path.trimStart('/').lowercase(Locale.ROOT)

        /** Product of two 4x3 affine matrices applied to row vectors: the transform [a] followed by [b]. */
        private fun multiply(a: DoubleArray, b: DoubleArray): DoubleArray {
            val result = DoubleArray(12)
            for (row in 0 until 4) {
                for (col in 0 until 3) {
                    var sum = if (row == 3) b[9 + col] else 0.0
                    for (k in 0 until 3) {
                        sum += a[row * 3 + k] * b[k * 3 + col]
                    }
                    result[row * 3 + col] = sum
                }
            }
            return result
        }

        private fun determinant(m: DoubleArray): Double {
            return m[0] * (m[4] * m[8] - m[5] * m[7]) - m[1] * (m[3] * m[8] - m[5] * m[6]) + m[2] * (m[3] * m[7] - m[4] * m[6])
        }
    }
}
