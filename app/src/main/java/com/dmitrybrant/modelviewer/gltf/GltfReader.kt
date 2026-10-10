package com.dmitrybrant.modelviewer.gltf

import com.dmitrybrant.modelviewer.Material
import com.dmitrybrant.modelviewer.ModelResources
import com.dmitrybrant.modelviewer.util.FloatList
import com.dmitrybrant.modelviewer.util.IntList
import com.dmitrybrant.modelviewer.util.Transforms.determinant
import com.dmitrybrant.modelviewer.util.Transforms.faceNormal
import com.dmitrybrant.modelviewer.util.Transforms.identity
import com.dmitrybrant.modelviewer.util.Transforms.multiply
import com.dmitrybrant.modelviewer.util.Transforms.normalMatrix
import com.dmitrybrant.modelviewer.util.Transforms.rotation
import com.dmitrybrant.modelviewer.util.Transforms.scaling
import com.dmitrybrant.modelviewer.util.Transforms.transformNormal
import com.dmitrybrant.modelviewer.util.Transforms.transformPoint
import com.dmitrybrant.modelviewer.util.Transforms.translation
import com.dmitrybrant.modelviewer.util.Util
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin

/*
* Reads glTF 2.0 models, in their binary form (GLB), or as JSON (.gltf, whose buffers and images
* are embedded as data URIs, or are separate files, which are opened from the given resources).
*
* The meshes of the scene are placed with the transforms of their nodes. Skinned meshes are posed
* by their joints, and morph targets are applied with their default weights (without animation).
* Materials are approximated with diffuse colors and textures, and highlights that depend on their
* roughness (or glossiness). glTF's coordinate system has the Y axis up, and models face +Z.
*
* Specification: https://registry.khronos.org/glTF/specs/2.0/glTF-2.0.html
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
class GltfReader(private val data: ByteArray, private val resources: ModelResources?) {
    /** The vertices and triangles of the scene, and the ranges of triangles with each material. */
    class Mesh(
        val vertexCount: Int,
        /** Position of each vertex (3 floats each). */
        val positions: FloatArray,
        /** Normal of each vertex (3 floats each). */
        val normals: FloatArray,
        /** Texture coordinates of each vertex (2 floats each, with the origin at the bottom left), if any material has a texture. */
        val texCoords: FloatArray?,
        /** Color of each vertex (4 floats each), if any mesh has vertex colors. */
        val colors: FloatArray?,
        /** Vertex indices of the triangles (3 each). */
        val indices: IntArray,
        /** Ranges of indices with their materials, if the scene has any materials. */
        val parts: List<Part>
    )

    class Part(val material: Material, val firstIndex: Int, val indexCount: Int)

    private class Buffer(val data: ByteArray, val offset: Int, val length: Int)

    private class MaterialInfo(
        val material: Material,
        /** The set of texture coordinates that the texture uses. */
        val texCoord: Int,
        /** Affine transform of the texture coordinates (u' = t0 u + t1 v + t2, v' = t3 u + t4 v + t5), if any. */
        val uvTransform: DoubleArray?
    )

    /** The vertices and triangles that have the same material, as they're collected. */
    private class TriangleGroup(val info: MaterialInfo?) {
        val positions = FloatList()
        val normals = FloatList()
        val texCoords = FloatList()
        val colors = FloatList()
        val indices = IntList()
        val vertexCount get() = positions.size / 3
    }

    private var json: Map<*, *> = emptyMap<String, Any>()
    private var binaryChunk: Buffer? = null
    private val nodes get() = json.list("nodes").orEmpty()
    private val parents = HashMap<Int, Int>()
    private val worldTransforms = HashMap<Int, DoubleArray>()
    private val buffers = HashMap<Int, Buffer?>()
    private val images = HashMap<Int, ByteArray?>()
    private val materials = HashMap<Int, MaterialInfo?>()
    private val jointTransforms = HashMap<Int, Array<DoubleArray?>>()
    private val groups = LinkedHashMap<Int, TriangleGroup>()
    private var hasColors = false

    fun read(): Mesh {
        readContainer()
        val asset = json.map("asset")
        val version = asset?.string("version")
        if (asset == null || version?.startsWith("2.") != true) {
            throw IOException(if (version != null) "glTF version $version isn't supported." else "Not a valid glTF file.")
        }
        val unsupported = json.list("extensionsRequired").orEmpty().filterIsInstance<String>().filter { it in COMPRESSION_EXTENSIONS }
        if (unsupported.isNotEmpty()) {
            throw IOException("The model is compressed with ${unsupported.joinToString()}, which isn't supported.")
        }

        for ((i, node) in nodes.withIndex()) {
            for (child in (node as? Map<*, *>)?.list("children").orEmpty()) {
                (child as? Double)?.toInt()?.let { parents.putIfAbsent(it, i) }
            }
        }
        // The nodes of the default scene, or of the first scene, or (without scenes) all nodes without parents.
        val scenes = json.list("scenes").orEmpty()
        val scene = scenes.getOrNull(json.int("scene") ?: 0) as? Map<*, *>
        val roots = scene?.list("nodes")?.mapNotNull { (it as? Double)?.toInt() } ?: nodes.indices.filter { it !in parents }
        val visited = HashSet<Int>()
        val pending = ArrayDeque(roots)
        while (pending.isNotEmpty()) {
            val index = pending.removeFirst()
            val node = nodes.getOrNull(index) as? Map<*, *> ?: continue
            // Each node can only be in the scene once.
            if (!visited.add(index)) {
                continue
            }
            addNode(index, node)
            // The children come next, before the node's siblings.
            node.list("children").orEmpty().mapNotNull { (it as? Double)?.toInt() }.asReversed().forEach { pending.addFirst(it) }
        }
        return buildMesh()
    }

    /** Reads the JSON of the file, and its binary chunk, if it's a GLB file. */
    private fun readContainer() {
        val text = if (data.size >= 12 && String(data, 0, 4, Charsets.ISO_8859_1) == "glTF") {
            val header = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN)
            val version = header.getInt(4)
            if (version != 2) {
                throw IOException("glTF version $version isn't supported.")
            }
            val length = (header.getInt(8).toLong() and 0xffffffffL).coerceAtMost(data.size.toLong()).toInt()
            var jsonText: String? = null
            var pos = 12
            while (pos + 8 <= length) {
                val chunkLength = header.getInt(pos)
                val chunkType = header.getInt(pos + 4)
                if (chunkLength < 0 || chunkLength > length - pos - 8) {
                    throw IOException("Invalid GLB chunk.")
                }
                if (chunkType == CHUNK_JSON && jsonText == null) {
                    jsonText = String(data, pos + 8, chunkLength, Charsets.UTF_8)
                } else if (chunkType == CHUNK_BIN && binaryChunk == null) {
                    binaryChunk = Buffer(data, pos + 8, chunkLength)
                }
                pos += 8 + chunkLength
            }
            jsonText ?: throw IOException("GLB file has no JSON chunk.")
        } else {
            String(data, Charsets.UTF_8).removePrefix("\uFEFF")
        }
        json = JsonParser.parse(text) as? Map<*, *> ?: throw IOException("Not a valid glTF file.")
    }

    /** The object at the given index of one of the top-level lists of the file (like "nodes"), if it's there. */
    private fun element(listName: String, index: Int) = json.list(listName)?.getOrNull(index) as? Map<*, *>

    /** Transform of a node to world coordinates (as a column-major 4x4 matrix). */
    private fun worldTransform(index: Int, depth: Int = 0): DoubleArray {
        worldTransforms[index]?.let { return it }
        val node = nodes.getOrNull(index) as? Map<*, *> ?: return identity()
        val local = localTransform(node)
        val parent = parents[index]?.takeIf { depth < MAX_DEPTH }
        val world = if (parent != null) multiply(worldTransform(parent, depth + 1), local) else local
        worldTransforms[index] = world
        return world
    }

    /** The transform of a node relative to its parent: a matrix, or a translation, rotation (quaternion), and scale. */
    private fun localTransform(node: Map<*, *>): DoubleArray {
        node.numbers("matrix")?.takeIf { it.size == 16 }?.let { return it }
        val t = node.numbers("translation")?.takeIf { it.size == 3 } ?: doubleArrayOf(0.0, 0.0, 0.0)
        val r = node.numbers("rotation")?.takeIf { it.size == 4 } ?: doubleArrayOf(0.0, 0.0, 0.0, 1.0)
        val s = node.numbers("scale")?.takeIf { it.size == 3 } ?: doubleArrayOf(1.0, 1.0, 1.0)
        return multiply(translation(t), multiply(rotation(r[0], r[1], r[2], r[3]), scaling(s)))
    }

    private fun addNode(index: Int, node: Map<*, *>) {
        val mesh = node.int("mesh")?.let { json.list("meshes")?.getOrNull(it) } as? Map<*, *> ?: return
        val skin = node.int("skin")?.takeIf { json.list("skins")?.getOrNull(it) is Map<*, *> }
        val morphWeights = node.numbers("weights") ?: mesh.numbers("weights") ?: DoubleArray(0)
        for (primitive in mesh.list("primitives").orEmpty()) {
            if (primitive is Map<*, *>) {
                addPrimitive(primitive, worldTransform(index), skin, morphWeights)
            }
        }
    }

    private fun addPrimitive(primitive: Map<*, *>, nodeTransform: DoubleArray, skin: Int?, morphWeights: DoubleArray) {
        // Points and lines aren't shown.
        val mode = primitive.int("mode") ?: MODE_TRIANGLES
        if (mode !in MODE_TRIANGLES..MODE_TRIANGLE_FAN) {
            return
        }
        val attributes = primitive.map("attributes") ?: return
        val positionAccessor = attributes.int("POSITION")?.let { Accessor(it) } ?: return
        val vertexCount = positionAccessor.count
        val positions = positionAccessor.floats(3)
        val normals = attributes.int("NORMAL")?.let { Accessor(it) }?.takeIf { it.count == vertexCount }?.floats(3)

        // Morph targets add their displacements, by their weights.
        for ((t, target) in primitive.list("targets").orEmpty().withIndex()) {
            val weight = morphWeights.getOrNull(t)?.toFloat() ?: 0f
            if (weight == 0f || target !is Map<*, *>) {
                continue
            }
            target.int("POSITION")?.let { Accessor(it) }?.takeIf { it.count == vertexCount }?.floats(3)?.let { offsets ->
                for (i in positions.indices) positions[i] += offsets[i] * weight
            }
            val currentNormals = normals ?: continue
            target.int("NORMAL")?.let { Accessor(it) }?.takeIf { it.count == vertexCount }?.floats(3)?.let { offsets ->
                for (i in currentNormals.indices) currentNormals[i] += offsets[i] * weight
            }
        }

        val materialIndex = primitive.int("material") ?: -1
        val info = materialInfo(materialIndex)
        val texCoords = info?.takeIf { it.material.diffuseTexture != null }?.let { attributes.int("TEXCOORD_${it.texCoord}") }
            ?.let { Accessor(it) }?.takeIf { it.count == vertexCount }?.floats(2)
        val colors = attributes.int("COLOR_0")?.let { Accessor(it) }?.takeIf { it.count == vertexCount && it.components in 3..4 }
        hasColors = hasColors || colors != null

        // A transform for each vertex of a skinned mesh, or one for all vertices.
        val transforms = skin?.let { skinTransforms(it, attributes, vertexCount, nodeTransform) } ?: nodeTransform
        val stride = if (transforms.size > 16) 1 else 0
        val transformCount = transforms.size / 16
        val normalMatrices = DoubleArray(transformCount * 9)
        for (i in 0 until transformCount) {
            normalMatrix(transforms, i * 16, normalMatrices, i * 9)
        }

        val triangles = triangleIndices(primitive, mode, vertexCount)
        val group = groups.getOrPut(materialIndex) { TriangleGroup(info) }
        val position = DoubleArray(9)
        val normal = DoubleArray(9)
        val corner = IntArray(3)

        fun addVertex(v: Int, normalIndex: Int) {
            group.positions.add(position[normalIndex * 3].toFloat())
            group.positions.add(position[normalIndex * 3 + 1].toFloat())
            group.positions.add(position[normalIndex * 3 + 2].toFloat())
            group.normals.add(normal[normalIndex * 3].toFloat())
            group.normals.add(normal[normalIndex * 3 + 1].toFloat())
            group.normals.add(normal[normalIndex * 3 + 2].toFloat())
            if (texCoords != null) {
                var u = texCoords[v * 2].toDouble()
                var w = texCoords[v * 2 + 1].toDouble()
                info?.uvTransform?.let { m ->
                    val tu = m[0] * u + m[1] * w + m[2]
                    w = m[3] * u + m[4] * w + m[5]
                    u = tu
                }
                // glTF's texture coordinates have their origin at the top left.
                group.texCoords.add(u.toFloat())
                group.texCoords.add((1 - w).toFloat())
            } else {
                group.texCoords.add(0f)
                group.texCoords.add(0f)
            }
            for (c in 0 until 4) {
                group.colors.add(when {
                    colors == null -> 1f
                    c == 3 -> if (colors.components == 4) colors.float(v, 3) else 1f
                    else -> linearToSrgb(colors.float(v, c).toDouble()).toFloat()
                })
            }
        }

        if (normals != null) {
            val base = group.vertexCount
            val n = DoubleArray(3)
            for (v in 0 until vertexCount) {
                transformPoint(transforms, v * stride * 16, positions[v * 3].toDouble(), positions[v * 3 + 1].toDouble(), positions[v * 3 + 2].toDouble(), position, 0)
                for (c in 0 until 3) {
                    n[c] = normals[v * 3 + c].toDouble()
                }
                transformNormal(normalMatrices, v * stride * 9, n, 0, normal, 0)
                addVertex(v, 0)
            }
            for (t in 0 until triangles.size / 3) {
                val a = triangles[t * 3]
                // A transform that mirrors the mesh turns its triangles inside out, unless their corners are reversed.
                val mirrored = determinant(transforms, a * stride * 16) < 0
                group.indices.add(base + a)
                group.indices.add(base + triangles[t * 3 + if (mirrored) 2 else 1])
                group.indices.add(base + triangles[t * 3 + if (mirrored) 1 else 2])
            }
        } else {
            // Without normals, triangles are flat shaded, so they don't share their vertices.
            for (t in 0 until triangles.size / 3) {
                val mirrored = determinant(transforms, triangles[t * 3] * stride * 16) < 0
                corner[0] = triangles[t * 3]
                corner[1] = triangles[t * 3 + if (mirrored) 2 else 1]
                corner[2] = triangles[t * 3 + if (mirrored) 1 else 2]
                for (c in 0 until 3) {
                    val v = corner[c]
                    transformPoint(transforms, v * stride * 16, positions[v * 3].toDouble(), positions[v * 3 + 1].toDouble(), positions[v * 3 + 2].toDouble(), position, c * 3)
                }
                faceNormal(position, normal)
                for (c in 0 until 3) {
                    group.indices.add(group.vertexCount)
                    addVertex(corner[c], c)
                }
            }
        }
    }

    /** The vertex indices of the triangles of a primitive, which may be a list, strip, or fan of triangles. */
    private fun triangleIndices(primitive: Map<*, *>, mode: Int, vertexCount: Int): IntArray {
        val indices = primitive.int("indices")?.let { Accessor(it).ints() } ?: IntArray(vertexCount) { it }
        for (i in indices) {
            if (i !in 0 until vertexCount) {
                throw IOException("Invalid vertex index: $i")
            }
        }
        return when (mode) {
            MODE_TRIANGLE_STRIP -> IntArray(maxOf(indices.size - 2, 0) * 3) {
                val t = it / 3
                // Every other triangle of a strip is reversed, to keep the same winding.
                val k = it % 3
                indices[t + if (t % 2 == 1 && k < 2) 1 - k else k]
            }
            MODE_TRIANGLE_FAN -> IntArray(maxOf(indices.size - 2, 0) * 3) {
                val t = it / 3
                when (it % 3) {
                    0 -> indices[0]
                    1 -> indices[t + 1]
                    else -> indices[t + 2]
                }
            }
            else -> if (indices.size % 3 == 0) indices else indices.copyOf(indices.size / 3 * 3)
        }
    }

    /**
     * The transform of each vertex of a skinned mesh (16 values each), which is the average of the
     * transforms of its joints, by their weights; or null if no vertex has any joints.
     */
    private fun skinTransforms(skin: Int, attributes: Map<*, *>, vertexCount: Int, nodeTransform: DoubleArray): DoubleArray? {
        val joints = jointTransforms.getOrPut(skin) { jointTransforms(json.list("skins")?.getOrNull(skin) as Map<*, *>) }
        val transforms = DoubleArray(vertexCount * 16)
        val weightSums = DoubleArray(vertexCount)
        var set = 0
        while (true) {
            val jointIndices = attributes.int("JOINTS_$set")?.let { Accessor(it) } ?: break
            val weights = attributes.int("WEIGHTS_$set")?.let { Accessor(it) } ?: break
            if (jointIndices.count != vertexCount || weights.count != vertexCount) {
                break
            }
            for (v in 0 until vertexCount) {
                for (k in 0 until minOf(jointIndices.components, weights.components)) {
                    val weight = weights.float(v, k).toDouble()
                    val m = joints.getOrNull(jointIndices.int(v, k)) ?: continue
                    if (!(weight > 0)) {
                        continue
                    }
                    weightSums[v] += weight
                    for (i in 0 until 16) {
                        transforms[v * 16 + i] += m[i] * weight
                    }
                }
            }
            set++
        }
        if (weightSums.all { it == 0.0 }) {
            return null
        }
        for (v in 0 until vertexCount) {
            if (weightSums[v] > 0) {
                for (i in 0 until 16) {
                    transforms[v * 16 + i] /= weightSums[v]
                }
            } else {
                nodeTransform.copyInto(transforms, v * 16)
            }
        }
        return transforms
    }

    /** The transform of each joint of a skin: its world transform times its inverse bind matrix. */
    private fun jointTransforms(skin: Map<*, *>): Array<DoubleArray?> {
        val joints = skin.list("joints").orEmpty()
        val inverseBindMatrices = skin.int("inverseBindMatrices")?.let { Accessor(it) }?.takeIf { it.components == 16 }
        return Array(joints.size) { j ->
            val joint = (joints[j] as? Double)?.toInt()?.takeIf { nodes.getOrNull(it) is Map<*, *> } ?: return@Array null
            val inverseBind = if (inverseBindMatrices != null && j < inverseBindMatrices.count) {
                DoubleArray(16) { inverseBindMatrices.float(j, it).toDouble() }
            } else {
                identity()
            }
            multiply(worldTransform(joint), inverseBind)
        }
    }

    private fun materialInfo(index: Int): MaterialInfo? {
        if (index !in materials) {
            materials[index] = element("materials", index)?.let { buildMaterial(it) }
        }
        return materials[index]
    }

    private fun buildMaterial(properties: Map<*, *>): MaterialInfo {
        val material = Material(properties.string("name").orEmpty())
        val extensions = properties.map("extensions")
        val pbr = properties.map("pbrMetallicRoughness")
        // The (archived) specular-glossiness extension is used if it's required, or if the material has nothing else.
        val specularGlossiness = extensions?.map("KHR_materials_pbrSpecularGlossiness")?.takeIf {
            pbr == null || json.list("extensionsRequired")?.contains("KHR_materials_pbrSpecularGlossiness") == true
        }
        val baseColor: DoubleArray
        val textureInfo: Map<*, *>?
        // Highlights that are given by a texture (whose values aren't known here) are moderate ones.
        material.specularColor = floatArrayOf(DEFAULT_SPECULAR, DEFAULT_SPECULAR, DEFAULT_SPECULAR)
        material.shininess = DEFAULT_SHININESS
        if (specularGlossiness != null) {
            baseColor = specularGlossiness.numbers("diffuseFactor")?.takeIf { it.size == 4 } ?: doubleArrayOf(1.0, 1.0, 1.0, 1.0)
            textureInfo = specularGlossiness.map("diffuseTexture")
            if (specularGlossiness.map("specularGlossinessTexture") == null) {
                val specular = specularGlossiness.numbers("specularFactor")?.takeIf { it.size == 3 } ?: doubleArrayOf(1.0, 1.0, 1.0)
                setHighlight(material, specular, 1 - (specularGlossiness.number("glossinessFactor") ?: 1.0))
            }
        } else {
            baseColor = pbr?.numbers("baseColorFactor")?.takeIf { it.size == 4 } ?: doubleArrayOf(1.0, 1.0, 1.0, 1.0)
            textureInfo = pbr?.map("baseColorTexture")
            if (pbr?.map("metallicRoughnessTexture") == null) {
                // Metals reflect their own color, and other materials reflect about 4% of light, of all colors.
                val metallic = (pbr?.number("metallicFactor") ?: 1.0).coerceIn(0.0, 1.0)
                val reflectance = DoubleArray(3) { DIELECTRIC_REFLECTANCE + (baseColor[it] - DIELECTRIC_REFLECTANCE) * metallic }
                setHighlight(material, reflectance, pbr?.number("roughnessFactor") ?: 1.0)
            }
        }

        // Colors in glTF are linear, and the app's colors (like those of textures) are sRGB.
        material.diffuseColor = FloatArray(3) { linearToSrgb(baseColor[it]).toFloat() }
        material.opacity = baseColor[3].toFloat().coerceIn(0f, 1f)
        material.alphaMode = when (properties.string("alphaMode")) {
            "BLEND" -> Material.AlphaMode.BLEND
            "MASK" -> Material.AlphaMode.MASK
            else -> Material.AlphaMode.OPAQUE
        }
        material.alphaCutoff = (properties.number("alphaCutoff") ?: 0.5).toFloat()
        if (properties.map("emissiveTexture") == null) {
            val strength = extensions?.map("KHR_materials_emissive_strength")?.number("emissiveStrength") ?: 1.0
            properties.numbers("emissiveFactor")?.takeIf { it.size == 3 }?.let { emissive ->
                material.emissiveColor = FloatArray(3) { linearToSrgb((emissive[it] * strength).coerceIn(0.0, 1.0)).toFloat() }
            }
        }
        if (extensions?.map("KHR_materials_unlit") != null) {
            material.lighting = Material.LIGHTING_NONE
        }

        var texCoord = 0
        var uvTransform: DoubleArray? = null
        val texture = textureInfo?.int("index")?.let { element("textures", it) }
        val imageIndex = texture?.let {
            val textureExtensions = it.map("extensions")
            // Textures in other formats (like WebP or KTX2) may have a PNG or JPEG image as a fallback.
            it.int("source") ?: textureExtensions?.map("EXT_texture_webp")?.int("source")
                ?: textureExtensions?.map("KHR_texture_basisu")?.int("source")
        }
        val image = imageIndex?.let { image(it) }
        if (image != null) {
            material.diffuseTexture = image
            material.diffuseTextureName = (element("images", imageIndex)?.let { it.string("name") ?: it.string("uri") }
                ?.takeUnless { it.startsWith("data:") }) ?: "image $imageIndex"
            texCoord = textureInfo.int("texCoord") ?: 0
            textureInfo.map("extensions")?.map("KHR_texture_transform")?.let { transform ->
                val offset = transform.numbers("offset")?.takeIf { it.size == 2 } ?: doubleArrayOf(0.0, 0.0)
                val scale = transform.numbers("scale")?.takeIf { it.size == 2 } ?: doubleArrayOf(1.0, 1.0)
                val angle = transform.number("rotation") ?: 0.0
                val c = cos(angle)
                val s = sin(angle)
                // Scaled, rotated, and then offset, as in the extension's specification.
                uvTransform = doubleArrayOf(c * scale[0], s * scale[1], offset[0], -s * scale[0], c * scale[1], offset[1])
                texCoord = transform.int("texCoord") ?: texCoord
            }
        }
        return MaterialInfo(material, texCoord, uvTransform)
    }

    /** The encoded image (PNG or JPEG) of an image of the file, which may be in a buffer, or at a URI. */
    private fun image(index: Int): ByteArray? {
        if (index !in images) {
            val image = element("images", index)
            images[index] = image?.int("bufferView")?.let { bufferViewBytes(it) } ?: image?.string("uri")?.let { readUri(it) }
        }
        return images[index]
    }

    private fun bufferViewBytes(index: Int): ByteArray {
        val view = bufferView(index)
        return view.buffer.array().copyOfRange(view.start, view.start + view.length.toInt())
    }

    private fun buffer(index: Int): Buffer {
        if (index !in buffers) {
            val buffer = element("buffers", index)
            val uri = buffer?.string("uri")
            buffers[index] = when {
                buffer == null -> null
                // The buffer of a GLB file without a URI is its binary chunk.
                uri == null -> binaryChunk
                else -> readUri(uri)?.let { Buffer(it, 0, it.size) }
            }
        }
        return buffers[index] ?: throw IOException("Buffer $index isn't available.")
    }

    /** Reads the data at a URI, which is either a data URI, or the (relative) path of a separate file. */
    private fun readUri(uri: String): ByteArray? {
        if (uri.startsWith("data:")) {
            val comma = uri.indexOf(',')
            if (comma < 0) {
                return null
            }
            val payload = uri.substring(comma + 1)
            return if (uri.substring(0, comma).endsWith(";base64")) Util.decodeBase64(payload) else percentDecode(payload)
        }
        return try {
            resources?.open(String(percentDecode(uri), Charsets.UTF_8))?.use { it.readBytes() }
        } catch (e: IOException) {
            null
        }
    }

    private fun buildMesh(): Mesh {
        val vertexCount = groups.values.sumOf { it.vertexCount }
        val indexCount = groups.values.sumOf { it.indices.size }
        if (indexCount == 0) {
            throw IOException("No triangles found in model.")
        }
        val positions = FloatArray(vertexCount * 3)
        val normals = FloatArray(vertexCount * 3)
        val texCoords = FloatArray(vertexCount * 2)
        val colors = FloatArray(vertexCount * 4)
        val indices = IntArray(indexCount)
        val parts = mutableListOf<Part>()
        val hasMaterials = groups.values.any { it.info != null }
        var firstVertex = 0
        var firstIndex = 0
        for (group in groups.values) {
            val count = group.vertexCount
            group.positions.array.copyInto(positions, firstVertex * 3, 0, count * 3)
            group.normals.array.copyInto(normals, firstVertex * 3, 0, count * 3)
            group.texCoords.array.copyInto(texCoords, firstVertex * 2, 0, count * 2)
            group.colors.array.copyInto(colors, firstVertex * 4, 0, count * 4)
            for (i in 0 until group.indices.size) {
                indices[firstIndex + i] = firstVertex + group.indices[i]
            }
            if (hasMaterials) {
                parts.add(Part(group.info?.material ?: Material(), firstIndex, group.indices.size))
            }
            firstVertex += count
            firstIndex += group.indices.size
        }
        val hasTextures = parts.any { it.material.diffuseTexture != null }
        return Mesh(vertexCount, positions, normals, if (hasTextures) texCoords else null, if (hasColors) colors else null, indices, parts)
    }

    /**
     * Reads the elements of an accessor: from its buffer view (or zeros, without one), and its
     * sparse values, which replace some of the elements. Integers that are normalized are converted
     * to fractions (from 0 to 1, or from -1 to 1 if they're signed).
     */
    private inner class Accessor(index: Int) {
        val count: Int
        val components: Int
        private val componentType: Int
        private val componentSize: Int
        private val normalized: Boolean
        private var buffer: ByteBuffer? = null
        private var start = 0
        private var stride = 0
        private var sparseElements: HashMap<Int, Int>? = null
        private var sparseBuffer: ByteBuffer? = null
        private var sparseStart = 0

        init {
            val accessor = element("accessors", index) ?: throw IOException("Invalid accessor: $index")
            count = accessor.int("count")?.takeIf { it >= 0 } ?: throw IOException("Invalid accessor: $index")
            components = TYPE_COMPONENTS[accessor.string("type")] ?: throw IOException("Invalid accessor type: ${accessor.string("type")}")
            componentType = accessor.int("componentType") ?: 0
            componentSize = COMPONENT_SIZES[componentType] ?: throw IOException("Invalid accessor component type: $componentType")
            normalized = accessor["normalized"] == true
            val elementSize = components * componentSize
            if (count.toLong() * components > MAX_VALUES) {
                throw IOException("Accessor $index is too large.")
            }
            accessor.int("bufferView")?.let { viewIndex ->
                val (viewBuffer, viewStart, viewLength, viewStride) = bufferView(viewIndex)
                stride = viewStride ?: elementSize
                val offset = accessor.long("byteOffset") ?: 0L
                if (stride < elementSize || offset < 0 || (count > 0 && offset + (count - 1).toLong() * stride + elementSize > viewLength)) {
                    throw IOException("Accessor $index is out of bounds.")
                }
                buffer = viewBuffer
                start = viewStart + offset.toInt()
            }
            accessor.map("sparse")?.let { sparse ->
                val sparseCount = sparse.int("count")?.takeIf { it in 0..count } ?: throw IOException("Invalid sparse accessor: $index")
                val sparseIndices = sparse.map("indices") ?: throw IOException("Invalid sparse accessor: $index")
                val values = sparse.map("values") ?: throw IOException("Invalid sparse accessor: $index")
                val indexType = sparseIndices.int("componentType") ?: 0
                val indexSize = COMPONENT_SIZES[indexType]?.takeIf { indexType in INDEX_TYPES } ?: throw IOException("Invalid sparse accessor: $index")
                val (indexBuffer, indexStart, indexLength) = bufferView(sparseIndices.int("bufferView") ?: -1)
                val (valueBuffer, valueStart, valueLength) = bufferView(values.int("bufferView") ?: -1)
                val indexOffset = sparseIndices.long("byteOffset") ?: 0L
                val valueOffset = values.long("byteOffset") ?: 0L
                if (indexOffset < 0 || valueOffset < 0 || indexOffset + sparseCount.toLong() * indexSize > indexLength ||
                    valueOffset + sparseCount.toLong() * elementSize > valueLength) {
                    throw IOException("Sparse accessor $index is out of bounds.")
                }
                val elements = HashMap<Int, Int>(sparseCount * 2)
                for (i in 0 until sparseCount) {
                    val element = readInteger(indexBuffer, indexStart + indexOffset.toInt() + i * indexSize, indexType)
                    if (element in 0 until count) {
                        elements[element.toInt()] = i
                    }
                }
                sparseElements = elements
                sparseBuffer = valueBuffer
                sparseStart = valueStart + valueOffset.toInt()
            }
        }

        fun float(element: Int, component: Int): Float {
            val (buffer, pos) = locate(element, component) ?: return 0f
            if (componentType == FLOAT) {
                return buffer.getFloat(pos)
            }
            val value = readInteger(buffer, pos, componentType).toFloat()
            if (!normalized) {
                return value
            }
            return when (componentType) {
                BYTE -> maxOf(value / 127f, -1f)
                UNSIGNED_BYTE -> value / 255f
                SHORT -> maxOf(value / 32767f, -1f)
                UNSIGNED_SHORT -> value / 65535f
                else -> (value / 4294967295.0).toFloat()
            }
        }

        fun int(element: Int, component: Int): Int {
            val (buffer, pos) = locate(element, component) ?: return 0
            if (componentType == FLOAT) {
                return buffer.getFloat(pos).toInt()
            }
            // Values that don't fit (which would be invalid indices) become -1.
            return readInteger(buffer, pos, componentType).takeIf { it <= Int.MAX_VALUE }?.toInt() ?: -1
        }

        /** All values, with the given number of components per element (which the accessor must have). */
        fun floats(expectedComponents: Int): FloatArray {
            if (components != expectedComponents) {
                throw IOException("Accessor has $components components instead of $expectedComponents.")
            }
            return FloatArray(count * components) { float(it / components, it % components) }
        }

        fun ints() = IntArray(count * components) { int(it / components, it % components) }

        /** The buffer and position of a component of an element, or null if it's zero (without a buffer view). */
        private fun locate(element: Int, component: Int): Pair<ByteBuffer, Int>? {
            sparseElements?.get(element)?.let { i ->
                return Pair(sparseBuffer!!, sparseStart + (i * components + component) * componentSize)
            }
            val buffer = this.buffer ?: return null
            return Pair(buffer, start + element * stride + component * componentSize)
        }
    }

    private data class BufferView(val buffer: ByteBuffer, val start: Int, val length: Long, val stride: Int?)

    private fun bufferView(index: Int): BufferView {
        val view = element("bufferViews", index) ?: throw IOException("Invalid buffer view: $index")
        val buffer = buffer(view.int("buffer") ?: -1)
        val offset = view.long("byteOffset") ?: 0L
        val length = view.long("byteLength") ?: throw IOException("Invalid buffer view: $index")
        if (offset < 0 || length < 0 || offset + length > buffer.length) {
            throw IOException("Buffer view $index is out of bounds.")
        }
        val stride = view.int("byteStride")?.takeIf { it > 0 }
        return BufferView(ByteBuffer.wrap(buffer.data).order(ByteOrder.LITTLE_ENDIAN), buffer.offset + offset.toInt(), length, stride)
    }

    companion object {
        private const val CHUNK_JSON = 0x4E4F534A
        private const val CHUNK_BIN = 0x004E4942
        private const val MAX_DEPTH = 256
        private const val MAX_VALUES = 1L shl 28

        private const val MODE_TRIANGLES = 4
        private const val MODE_TRIANGLE_STRIP = 5
        private const val MODE_TRIANGLE_FAN = 6

        private const val BYTE = 5120
        private const val UNSIGNED_BYTE = 5121
        private const val SHORT = 5122
        private const val UNSIGNED_SHORT = 5123
        private const val UNSIGNED_INT = 5125
        private const val FLOAT = 5126
        private val COMPONENT_SIZES = mapOf(BYTE to 1, UNSIGNED_BYTE to 1, SHORT to 2, UNSIGNED_SHORT to 2, UNSIGNED_INT to 4, FLOAT to 4)
        private val INDEX_TYPES = setOf(UNSIGNED_BYTE, UNSIGNED_SHORT, UNSIGNED_INT)
        private val TYPE_COMPONENTS = mapOf("SCALAR" to 1, "VEC2" to 2, "VEC3" to 3, "VEC4" to 4, "MAT2" to 4, "MAT3" to 9, "MAT4" to 16)

        /** Extensions that compress meshes, without which they can't be read. */
        private val COMPRESSION_EXTENSIONS = setOf("KHR_draco_mesh_compression", "EXT_meshopt_compression", "KHR_meshopt_compression")

        private const val DIELECTRIC_REFLECTANCE = 0.04
        private const val DEFAULT_SPECULAR = 0.2f
        private const val DEFAULT_SHININESS = 20f

        private fun readInteger(buffer: ByteBuffer, pos: Int, componentType: Int): Long {
            return when (componentType) {
                BYTE -> buffer.get(pos).toLong()
                UNSIGNED_BYTE -> buffer.get(pos).toLong() and 0xff
                SHORT -> buffer.getShort(pos).toLong()
                UNSIGNED_SHORT -> buffer.getShort(pos).toLong() and 0xffff
                else -> buffer.getInt(pos).toLong() and 0xffffffffL
            }
        }

        /**
         * Sets the highlight of a material, to approximate a physically based one with the given
         * reflectance (at normal incidence, in linear RGB) and roughness.
         */
        private fun setHighlight(material: Material, reflectance: DoubleArray, roughness: Double) {
            val r = roughness.coerceIn(0.05, 1.0)
            val shininess = (2 / r.pow(4) - 2).coerceIn(1.0, 256.0)
            // A sharper highlight is brighter, since it reflects the same amount of light (as in normalized
            // Blinn-Phong), and the highlights of rough materials fade away, since they're spread so widely
            // that they'd only brighten the whole material. The highlight is added to colors that are sRGB,
            // to which a small linear amount adds about the same amount.
            val scale = (shininess + 8) / 8 * (1 - r * r)
            material.specularColor = FloatArray(3) { (reflectance[it] * scale).coerceIn(0.0, 1.0).toFloat() }
            material.shininess = shininess.toFloat()
        }

        private fun linearToSrgb(value: Double): Double {
            val v = value.coerceIn(0.0, 1.0)
            return if (v <= 0.0031308) v * 12.92 else 1.055 * v.pow(1 / 2.4) - 0.055
        }

        /** Decodes the percent-encoded characters (like "%20") of a URI. */
        private fun percentDecode(text: String): ByteArray {
            val output = ByteArrayOutputStream(text.length)
            var i = 0
            while (i < text.length) {
                val code = if (text[i] == '%' && i + 3 <= text.length) text.substring(i + 1, i + 3).toIntOrNull(16) else null
                if (code != null) {
                    output.write(code)
                    i += 3
                } else {
                    val end = text.indexOf('%', i + 1).let { if (it < 0) text.length else it }
                    output.write(text.substring(i, end).toByteArray(Charsets.UTF_8))
                    i = end
                }
            }
            return output.toByteArray()
        }

        private fun Map<*, *>.map(key: String) = this[key] as? Map<*, *>

        private fun Map<*, *>.list(key: String) = this[key] as? List<*>

        private fun Map<*, *>.string(key: String) = this[key] as? String

        private fun Map<*, *>.number(key: String) = this[key] as? Double

        private fun Map<*, *>.int(key: String) = number(key)?.takeIf { it >= Int.MIN_VALUE && it <= Int.MAX_VALUE }?.toInt()

        private fun Map<*, *>.long(key: String) = number(key)?.takeIf { it >= Long.MIN_VALUE && it <= Long.MAX_VALUE }?.toLong()

        private fun Map<*, *>.numbers(key: String) = list(key)?.let { list -> DoubleArray(list.size) { (list[it] as? Double) ?: 0.0 } }
    }
}
