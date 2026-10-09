package com.dmitrybrant.modelviewer.fbx

import com.dmitrybrant.modelviewer.Material
import com.dmitrybrant.modelviewer.ModelResources
import com.dmitrybrant.modelviewer.util.FloatList
import java.io.IOException
import java.util.Locale
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/*
* Interprets an FBX file: finds the meshes of its models, places them with the transforms of the
* models and their ancestors (in a coordinate system with the Y axis up), and converts their
* polygons into triangles, with their normals, texture coordinates, and materials. Meshes that are
* skinned are posed by their bones (with the bones' transforms in the file, without animation).
*
* Files of version 7 refer to objects by ID, and connect models to their meshes (geometry),
* materials to models, and textures to materials. Files of version 6 refer to objects by name,
* have the meshes inside the models, and connect textures to the models, with a texture for each
* polygon in a layer of the mesh.
*
* Info on the format: the FBX SDK documentation (https://help.autodesk.com/view/FBX/2020/ENU/),
* in particular on the transforms of nodes (FbxNode) and on layer elements (FbxLayerElement).
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
class FbxScene(private val document: FbxParser.Document, private val resources: ModelResources?) {
    /** The triangles of the scene, with three corners each, and the ranges of triangles with each material. */
    class Mesh(
        val triangleCount: Int,
        /** Position of each corner of each triangle (9 floats per triangle). */
        val positions: FloatArray,
        /** Normal of each corner of each triangle (9 floats per triangle). */
        val normals: FloatArray,
        /** Texture coordinates of each corner of each triangle (6 floats per triangle), if any material has a texture. */
        val texCoords: FloatArray?,
        /** Ranges of triangles with their materials, if the scene has any materials. */
        val parts: List<Part>
    )

    class Part(val material: Material, val firstTriangle: Int, val triangleCount: Int)

    private class SceneObject(val id: Any, val node: FbxNode, val type: String, val subclass: String, val name: String)

    /** A link from an object to an object that's connected to it, optionally to one of its properties. */
    private class Link(val child: SceneObject, val property: String?)

    /** The triangles that have the same material and texture, as they're collected. */
    private class TriangleGroup(val material: SceneObject?, val texture: SceneObject?) {
        val positions = FloatList()
        val normals = FloatList()
        val texCoords = FloatList()
    }

    private val isVersion6 = document.version < 7000
    private val objects = LinkedHashMap<Any, SceneObject>()
    private val links = HashMap<Any, MutableList<Link>>()
    private val parents = HashMap<Any, Any>()
    private val worldTransforms = HashMap<Any, DoubleArray>()
    private val groups = LinkedHashMap<Pair<SceneObject?, SceneObject?>, TriangleGroup>()
    private val images = HashMap<SceneObject, ByteArray?>()

    fun build(): Mesh {
        val objectNodes = document.node("Objects") ?: throw IOException("No objects found in FBX file.")
        for (node in objectNodes.children) {
            // Names are "Name\u0000\u0001Class" in binary files, and "Class::Name" in ASCII files.
            val obj = if (isVersion6) {
                val id = node.string(0) ?: continue
                SceneObject(id, node, node.name, node.string(1).orEmpty(), id.substringAfter("::"))
            } else {
                val id = node.id(0) ?: continue
                SceneObject(id, node, node.name, node.string(2).orEmpty(), node.string(1).orEmpty().substringBefore('\u0000').substringAfter("::"))
            }
            objects[obj.id] = obj
        }
        for (connection in document.node("Connections")?.children.orEmpty()) {
            val childId: Any = (if (isVersion6) connection.string(1) else connection.id(1)) ?: continue
            val parentId: Any = (if (isVersion6) connection.string(2) else connection.id(2)) ?: continue
            val child = objects[childId] ?: continue
            links.getOrPut(parentId) { mutableListOf() }.add(Link(child, connection.string(3)))
            // Models are also connected to other objects, such as display layers.
            if (connection.string(0) == "OO" && child.type == "Model" && objects[parentId]?.type == "Model") {
                parents.putIfAbsent(childId, parentId)
            }
        }

        val settings = document.node("GlobalSettings") ?: objectNodes.child("GlobalSettings")
        val axisConversion = axisConversion(Properties(settings))
        for (obj in objects.values) {
            if (obj.type != "Model") {
                continue
            }
            val meshes = if (isVersion6) {
                if (obj.node.child("Vertices") != null) listOf(obj) else emptyList()
            } else {
                linked(obj, "Geometry").filter { it.subclass == "Mesh" }
            }
            if (meshes.isEmpty()) {
                continue
            }
            val properties = Properties(obj.node)
            if (properties.number("Show") == 0.0 || properties.number("Visibility") == 0.0) {
                continue
            }
            // The geometric transform applies only to the model's own meshes, and not to its children.
            val geometricTransform = multiply(translation(properties.vector("GeometricTranslation", 0.0)),
                multiply(rotation(properties.vector("GeometricRotation", 0.0), 0), scaling(properties.vector("GeometricScaling", 1.0))))
            val materials = linked(obj, "Material")
            val textures = if (isVersion6) linked(obj, "Texture") else emptyList()
            for (mesh in meshes) {
                val vertexCount = (mesh.node.child("Vertices")?.numbers()?.size ?: 0) / 3
                val transforms = skinTransforms(mesh, vertexCount, worldTransform(obj), geometricTransform)
                    ?: multiply(worldTransform(obj), geometricTransform)
                for (i in 0 until transforms.size / 16) {
                    multiply(axisConversion, transforms.copyOfRange(i * 16, i * 16 + 16)).copyInto(transforms, i * 16)
                }
                addMesh(mesh.node, transforms, materials, textures)
            }
        }
        return buildMesh()
    }

    private fun linked(obj: SceneObject, type: String) = links[obj.id].orEmpty().map { it.child }.filter { it.type == type }

    /** Transform of a model to world coordinates (as a column-major 4x4 matrix). */
    private fun worldTransform(obj: SceneObject, depth: Int = 0): DoubleArray {
        worldTransforms[obj.id]?.let { return it }
        val local = localTransform(Properties(obj.node))
        val parent = parents[obj.id]?.let { objects[it] }?.takeIf { it.type == "Model" && depth < MAX_DEPTH }
        val world = if (parent != null) multiply(worldTransform(parent, depth + 1), local) else local
        worldTransforms[obj.id] = world
        return world
    }

    /**
     * The transform of a model relative to its parent, which is T * Roff * Rp * Rpre * R * Rpost^-1
     * * Rp^-1 * Soff * Sp * S * Sp^-1 (translation, rotation offset, rotation pivot, pre-rotation,
     * rotation, post-rotation, scaling offset, scaling pivot, and scaling).
     */
    private fun localTransform(p: Properties): DoubleArray {
        val rotationPivot = p.vector("RotationPivot", 0.0)
        val scalingPivot = p.vector("ScalingPivot", 0.0)
        val matrices = listOf(
            translation(p.vector("Lcl Translation", 0.0)),
            translation(p.vector("RotationOffset", 0.0)),
            translation(rotationPivot),
            rotation(p.vector("PreRotation", 0.0), 0),
            rotation(p.vector("Lcl Rotation", 0.0), p.number("RotationOrder")?.toInt() ?: 0),
            transpose(rotation(p.vector("PostRotation", 0.0), 0)),
            translation(DoubleArray(3) { -rotationPivot[it] }),
            translation(p.vector("ScalingOffset", 0.0)),
            translation(scalingPivot),
            scaling(p.vector("Lcl Scaling", 1.0)),
            translation(DoubleArray(3) { -scalingPivot[it] }))
        return matrices.reduce { a, b -> multiply(a, b) }
    }

    /**
     * Conversion from the axis system of the file to one where Y is up, Z is the front (where
     * models face), and X is to the side.
     */
    private fun axisConversion(p: Properties): DoubleArray {
        val up = p.number("UpAxis")?.toInt() ?: 1
        val front = p.number("FrontAxis")?.toInt() ?: 2
        val coord = p.number("CoordAxis")?.toInt() ?: 0
        if (setOf(up, front, coord) != setOf(0, 1, 2)) {
            return identity()
        }
        val m = DoubleArray(16)
        m[coord * 4] = p.number("CoordAxisSign") ?: 1.0
        m[up * 4 + 1] = p.number("UpAxisSign") ?: 1.0
        m[front * 4 + 2] = p.number("FrontAxisSign") ?: 1.0
        m[15] = 1.0
        return m
    }

    /**
     * The transform of each vertex of a mesh that's skinned (deformed by bones), as a column-major
     * 4x4 matrix for each vertex, which places the vertex where its bones are posed; or null if
     * the mesh isn't skinned. Each cluster of the skin moves its vertices with its bone (link),
     * from where the bone was when the mesh was bound to it, and vertices with several bones are
     * moved by the average of their transforms, by their weights.
     *
     * In files, the Transform of a cluster is the transform of the mesh relative to the bone (the
     * TransformLink) when the mesh was bound, so the bone's current transform times the Transform
     * is where the bone puts the mesh.
     */
    private fun skinTransforms(mesh: SceneObject, vertexCount: Int, meshTransform: DoubleArray, geometricTransform: DoubleArray): DoubleArray? {
        val clusters = linked(mesh, "Deformer").filter { it.subclass == "Skin" }
            .flatMap { linked(it, "Deformer") }.filter { it.subclass == "Cluster" }
        if (clusters.isEmpty() || vertexCount == 0) {
            return null
        }
        val transforms = DoubleArray(vertexCount * 16)
        val weights = DoubleArray(vertexCount)
        for (cluster in clusters) {
            val bone = linked(cluster, "Model").firstOrNull() ?: continue
            val indices = cluster.node.child("Indexes")?.ints() ?: continue
            val clusterWeights = cluster.node.child("Weights")?.numbers() ?: continue
            // Without the mesh's transform relative to the bone, it's found from the bone's transform when they were bound.
            val relativeTransform = cluster.node.child("Transform")?.numbers()?.takeIf { it.size == 16 }
                ?: cluster.node.child("TransformLink")?.numbers()?.takeIf { it.size == 16 }?.let { inverse(it) }?.let { multiply(it, meshTransform) }
                ?: continue
            val m = multiply(worldTransform(bone), multiply(relativeTransform, geometricTransform))
            for (i in 0 until minOf(indices.size, clusterWeights.size)) {
                val v = indices[i]
                val weight = clusterWeights[i]
                if (v !in 0 until vertexCount || !(weight > 0)) {
                    continue
                }
                weights[v] += weight
                for (k in 0 until 16) {
                    transforms[v * 16 + k] += m[k] * weight
                }
            }
        }
        if (weights.all { it == 0.0 }) {
            return null
        }
        // Vertices without bones stay with the mesh.
        val transform = multiply(meshTransform, geometricTransform)
        for (v in 0 until vertexCount) {
            if (weights[v] > 0) {
                for (k in 0 until 16) {
                    transforms[v * 16 + k] /= weights[v]
                }
            } else {
                transform.copyInto(transforms, v * 16)
            }
        }
        return transforms
    }

    /**
     * Adds the polygons of a mesh, with the given transforms: one for each vertex (16 values each),
     * or a single one for all vertices.
     */
    private fun addMesh(mesh: FbxNode, transforms: DoubleArray, materials: List<SceneObject>, textures: List<SceneObject>) {
        val vertices = mesh.child("Vertices")?.numbers() ?: return
        val polygonVertices = mesh.child("PolygonVertexIndex")?.ints() ?: return
        val normalLayer = mesh.child("LayerElementNormal")?.let { Layer(it, "Normals", "NormalsIndex") }
        val uvLayer = mesh.child("LayerElementUV")?.let { Layer(it, "UV", "UVIndex") }
        val materialLayer = mesh.child("LayerElementMaterial")?.let { Layer(it, "Materials", "") }
        val textureLayer = mesh.child("LayerElementTexture")?.let { Layer(it, "TextureId", "") }

        val transformCount = transforms.size / 16
        // Offsets into the transforms (and normal matrices) are multiplied by a vertex index, or by 0 if there's only one.
        val stride = if (transformCount > 1) 1 else 0
        val normalMatrices = DoubleArray(transformCount * 9)
        val mirroredTransforms = BooleanArray(transformCount)
        for (i in 0 until transformCount) {
            normalMatrix(transforms, i * 16, normalMatrices, i * 9)
            mirroredTransforms[i] = determinant(transforms, i * 16) < 0
        }
        val position = DoubleArray(9)
        val normal = DoubleArray(9)
        val corners = IntArray(3)
        var polygonStart = 0
        var polygon = 0
        for (end in polygonVertices.indices) {
            if (polygonVertices[end] >= 0) {
                continue
            }
            val material = materialLayer?.let { materials.getOrNull(it.value(polygon, polygonStart, 0)) } ?: materials.firstOrNull()
            val texture = if (isVersion6) {
                textureLayer?.let { textures.getOrNull(it.value(polygon, polygonStart, 0)) }
            } else {
                material?.let { diffuseTexture(it) }
            }
            val group = groups.getOrPut(Pair(material, texture)) { TriangleGroup(material, texture) }
            // A transform that mirrors the mesh turns its polygons inside out, unless their corners are reversed.
            val firstVertex = polygonVertices[polygonStart].let { if (it < 0) it.inv() else it }
            val mirrored = mirroredTransforms.getOrElse(firstVertex * stride) { false }
            // Polygons are split into a fan of triangles around their first corner.
            for (k in polygonStart + 1 until end) {
                corners[0] = polygonStart
                corners[1] = if (mirrored) k + 1 else k
                corners[2] = if (mirrored) k else k + 1
                for (c in 0 until 3) {
                    val corner = corners[c]
                    val v = polygonVertices[corner].let { if (it < 0) it.inv() else it }
                    if (v >= vertices.size / 3) {
                        throw IOException("Invalid vertex index: $v")
                    }
                    transformPoint(transforms, v * stride * 16, vertices[v * 3], vertices[v * 3 + 1], vertices[v * 3 + 2], position, c * 3)
                    val n = normalLayer?.index(polygon, corner, v) ?: -1
                    if (normalLayer != null && n >= 0 && n < normalLayer.values.size / 3) {
                        transformNormal(normalMatrices, v * stride * 9, normalLayer.values, n * 3, normal, c * 3)
                    } else {
                        normal.fill(Double.NaN, c * 3, c * 3 + 3)
                    }
                    val uv = uvLayer?.index(polygon, corner, v) ?: -1
                    if (uvLayer != null && uv >= 0 && uv < uvLayer.values.size / 2) {
                        group.texCoords.add(uvLayer.values[uv * 2].toFloat())
                        group.texCoords.add(uvLayer.values[uv * 2 + 1].toFloat())
                    } else {
                        group.texCoords.add(0f)
                        group.texCoords.add(0f)
                    }
                }
                if (normal.any { it.isNaN() }) {
                    faceNormal(position, normal)
                }
                for (i in 0 until 9) {
                    group.positions.add(position[i].toFloat())
                    group.normals.add(normal[i].toFloat())
                }
            }
            polygonStart = end + 1
            polygon++
        }
    }

    private fun buildMesh(): Mesh {
        val triangleCount = groups.values.sumOf { it.positions.size / 9 }
        if (triangleCount == 0) {
            throw IOException("No meshes found in FBX file.")
        }
        val positions = FloatArray(triangleCount * 9)
        val normals = FloatArray(triangleCount * 9)
        val texCoords = FloatArray(triangleCount * 6)
        val parts = mutableListOf<Part>()
        val hasMaterials = groups.values.any { it.material != null || it.texture != null }
        var hasTextures = false
        var first = 0
        for (group in groups.values) {
            val count = group.positions.size / 9
            group.positions.array.copyInto(positions, first * 9, 0, count * 9)
            group.normals.array.copyInto(normals, first * 9, 0, count * 9)
            group.texCoords.array.copyInto(texCoords, first * 6, 0, count * 6)
            if (hasMaterials) {
                val material = material(group.material, group.texture)
                hasTextures = hasTextures || material.diffuseTexture != null
                parts.add(Part(material, first, count))
            }
            first += count
        }
        return Mesh(triangleCount, positions, normals, if (hasTextures) texCoords else null, parts)
    }

    private fun material(obj: SceneObject?, textureObj: SceneObject?): Material {
        val material = Material(obj?.name.orEmpty())
        if (obj != null) {
            val p = Properties(obj.node)
            val diffuse = p.vector("DiffuseColor") ?: p.vector("Diffuse") ?: doubleArrayOf(0.8, 0.8, 0.8)
            val diffuseFactor = p.number("DiffuseFactor") ?: 1.0
            material.diffuseColor = FloatArray(3) { (diffuse[it] * diffuseFactor).toFloat() }
            val specular = p.vector("SpecularColor") ?: p.vector("Specular")
            if (specular != null) {
                val specularFactor = p.number("SpecularFactor") ?: 1.0
                material.specularColor = FloatArray(3) { (specular[it] * specularFactor).toFloat() }
            }
            (p.number("ShininessExponent") ?: p.number("Shininess"))?.let { material.shininess = it.toFloat() }
            val emissive = p.vector("EmissiveColor") ?: p.vector("Emissive")
            if (emissive != null) {
                val emissiveFactor = p.number("EmissiveFactor") ?: 1.0
                material.emissiveColor = FloatArray(3) { (emissive[it] * emissiveFactor).toFloat() }
            }
            p.number("Opacity")?.let { material.opacity = it.toFloat().coerceIn(0f, 1f) }
        }
        val texture = textureObj?.let { image(it) }
        if (texture != null) {
            // The texture is the diffuse color.
            material.diffuseColor = floatArrayOf(1f, 1f, 1f)
            material.diffuseTexture = texture
            material.diffuseTextureName = textureObj.name
        }
        return material
    }

    /** The texture that gives a material its diffuse color, if it has one. */
    private fun diffuseTexture(material: SceneObject): SceneObject? {
        val textures = links[material.id].orEmpty().filter { it.child.type == "Texture" || it.child.type == "LayeredTexture" }
        val link = textures.firstOrNull { it.property == "DiffuseColor" }
            ?: textures.firstOrNull { link -> DIFFUSE_PROPERTY_NAMES.any { link.property.orEmpty().lowercase(Locale.ROOT).contains(it) } }
            ?: return null
        // A layered texture combines several textures, of which the first is used.
        return if (link.child.type == "LayeredTexture") linked(link.child, "Texture").firstOrNull() else link.child
    }

    /** The image of a texture, which may be embedded in the file (in the texture or its video), or in a separate file. */
    private fun image(texture: SceneObject): ByteArray? {
        // Textures that aren't found are remembered too, so that they're only looked for once.
        if (texture !in images) {
            images[texture] = (listOf(texture) + linked(texture, "Video")).firstNotNullOfOrNull { content(it.node) } ?: openFile(texture.node)
        }
        return images[texture]
    }

    private fun content(node: FbxNode): ByteArray? {
        val content = node.child("Content") ?: return null
        content.properties.firstOrNull { it is ByteArray && it.isNotEmpty() }?.let { return it as ByteArray }
        // In ASCII files, the content is encoded in Base64, possibly in several strings.
        val encoded = content.properties.filterIsInstance<String>().joinToString("")
        return if (encoded.isNotEmpty()) decodeBase64(encoded) else null
    }

    private fun openFile(node: FbxNode): ByteArray? {
        val path = (node.child("RelativeFilename") ?: node.child("FileName") ?: node.child("Filename"))?.string(0)
        if (path.isNullOrBlank()) {
            return null
        }
        return try {
            resources?.open(path)?.use { it.readBytes() }
        } catch (e: IOException) {
            null
        }
    }

    /** The properties of an object: its Properties70 node (or Properties60, in version 6). */
    private class Properties(node: FbxNode?) {
        private val values = HashMap<String, List<Double>>()

        init {
            // The values of a property follow its name, type, label (only in Properties70), and flags.
            node?.child("Properties70")?.children("P")?.forEach { add(it, 4) }
            node?.child("Properties60")?.children("Property")?.forEach { add(it, 3) }
        }

        private fun add(property: FbxNode, start: Int) {
            val name = property.string(0) ?: return
            values[name] = (start until property.properties.size).mapNotNull { property.number(it) }
        }

        fun number(name: String) = values[name]?.firstOrNull()

        fun vector(name: String) = values[name]?.takeIf { it.size >= 3 }?.let { doubleArrayOf(it[0], it[1], it[2]) }

        fun vector(name: String, default: Double) = vector(name) ?: doubleArrayOf(default, default, default)
    }

    /** A layer of a mesh, which gives a value for each polygon corner, vertex, or polygon, or one for all of them. */
    private class Layer(node: FbxNode, valuesName: String, indexName: String) {
        private val mapping = node.child("MappingInformationType")?.string(0)
        val values = node.child(valuesName)?.numbers() ?: DoubleArray(0)
        private val indices = node.child(indexName)?.takeIf {
            node.child("ReferenceInformationType")?.string(0) in setOf("IndexToDirect", "Index")
        }?.ints()

        /** The index of the value for a polygon corner (which is also the index of the corner's vertex in the polygon list). */
        fun index(polygon: Int, corner: Int, vertex: Int): Int {
            val i = when (mapping) {
                "ByPolygonVertex" -> corner
                "ByVertice", "ByVertex", "ByControlPoint" -> vertex
                "ByPolygon" -> polygon
                "AllSame" -> 0
                else -> return -1
            }
            return if (indices != null) indices.getOrElse(i) { -1 } else i
        }

        /** The value for a polygon corner, as an integer (for layers that give indices of materials or textures). */
        fun value(polygon: Int, corner: Int, vertex: Int) = values.getOrNull(index(polygon, corner, vertex))?.toInt() ?: -1
    }

    companion object {
        private const val MAX_DEPTH = 256
        private val DIFFUSE_PROPERTY_NAMES = listOf("diffuse", "basecolor", "base_color", "albedo")
        private val ROTATION_ORDERS = listOf("XYZ", "XZY", "YZX", "YXZ", "ZXY", "ZYX")

        // Column-major 4x4 matrices, applied to column vectors.

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

        private fun transpose(m: DoubleArray) = DoubleArray(16) { m[(it % 4) * 4 + it / 4] }

        /** The determinant of the upper 3x3 part of the transform that starts at the given offset. */
        private fun determinant(m: DoubleArray, o: Int = 0): Double {
            return m[o] * (m[o + 5] * m[o + 10] - m[o + 9] * m[o + 6]) - m[o + 4] * (m[o + 1] * m[o + 10] - m[o + 9] * m[o + 2]) +
                    m[o + 8] * (m[o + 1] * m[o + 6] - m[o + 5] * m[o + 2])
        }

        /** The cofactor matrix of the upper 3x3 part of the transform (column-major, as is the result). */
        private fun cofactors(m: DoubleArray, o: Int, out: DoubleArray, offset: Int) {
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
        private fun normalMatrix(m: DoubleArray, o: Int, out: DoubleArray, offset: Int) {
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

        private fun transformPoint(m: DoubleArray, o: Int, x: Double, y: Double, z: Double, out: DoubleArray, offset: Int) {
            out[offset] = m[o] * x + m[o + 4] * y + m[o + 8] * z + m[o + 12]
            out[offset + 1] = m[o + 1] * x + m[o + 5] * y + m[o + 9] * z + m[o + 13]
            out[offset + 2] = m[o + 2] * x + m[o + 6] * y + m[o + 10] * z + m[o + 14]
        }

        private fun transformNormal(m: DoubleArray, o: Int, normals: DoubleArray, index: Int, out: DoubleArray, offset: Int) {
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
        private fun faceNormal(p: DoubleArray, out: DoubleArray) {
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

        private const val BASE64_ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/"

        /** Decodes Base64 (which java.util.Base64 can't do on older versions of Android). */
        fun decodeBase64(text: String): ByteArray? {
            val output = java.io.ByteArrayOutputStream(text.length * 3 / 4)
            var buffer = 0
            var bits = 0
            for (ch in text) {
                if (ch == '=') break
                val value = BASE64_ALPHABET.indexOf(ch)
                if (value < 0) {
                    if (ch.isWhitespace()) continue
                    return null
                }
                buffer = (buffer shl 6) or value
                bits += 6
                if (bits >= 8) {
                    bits -= 8
                    output.write((buffer shr bits) and 0xff)
                }
            }
            return output.toByteArray()
        }
    }
}
