package com.dmitrybrant.modelviewer.obj

import com.dmitrybrant.modelviewer.Material
import com.dmitrybrant.modelviewer.MeshModel
import com.dmitrybrant.modelviewer.MeshPart
import com.dmitrybrant.modelviewer.ModelResources
import com.dmitrybrant.modelviewer.util.FloatList
import com.dmitrybrant.modelviewer.util.IntList
import java.io.BufferedReader
import java.io.IOException
import java.io.InputStream
import java.io.InputStreamReader
import kotlin.math.sqrt

/*
* Info on the OBJ format: https://en.wikipedia.org/wiki/Wavefront_.obj_file
* Info on the MTL (material library) format: https://paulbourke.net/dataformats/mtl/
*
* Supports polygonal faces with positions, texture coordinates, and normals, as well as vertex
* colors (a common extension, where "v" lines have red, green, and blue values after the position).
* Materials are read from the material libraries that the model refers to, if they can be found
* through the given [ModelResources]: diffuse, specular, and emissive colors, shininess,
* transparency, illumination model, and diffuse textures.
*
* Copyright 2017-2026 Dmitry Brant. All rights reserved.
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
class ObjModel(inputStream: InputStream, private val resources: ModelResources? = null) : MeshModel() {
    private val positions = FloatList()
    private val colors = FloatList()
    private var hasColors = false
    private val texCoords = FloatList()
    private val normals = FloatList()

    // Each distinct combination of position, texture coordinate, and normal that's used by a face
    // becomes a vertex of the mesh. Vertices that share a position are linked together, for finding them quickly.
    private val vertexPosition = IntList()
    private val vertexTexCoord = IntList()
    private val vertexNormal = IntList()
    private val nextVertexWithPosition = IntList()
    private val firstVertexWithPosition = IntList()

    private val materials = HashMap<String, Material>()
    private val textures = HashMap<String, ByteArray?>()

    init {
        read(BufferedReader(InputStreamReader(inputStream), INPUT_BUFFER_SIZE))
        if (vertexCount <= 0 || indexCount <= 0) {
            throw IOException("Invalid model.")
        }
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

    private fun read(reader: BufferedReader) {
        // Triangles (as vertex indices) for each material, in the order in which the materials are first used.
        val trianglesByMaterial = LinkedHashMap<String, IntList>()
        var triangles = trianglesByMaterial.getOrPut("") { IntList() }
        val faceVertices = IntList(16)
        var maxColor = 0f
        var sumX = 0.0
        var sumY = 0.0
        var sumZ = 0.0

        while (true) {
            val line = reader.readLine()?.trim() ?: break
            if (line.isEmpty() || line[0] == '#') {
                continue
            }
            val tokens = line.split(WHITESPACE)
            when (tokens[0]) {
                "v" -> {
                    if (tokens.size < 4) {
                        throw IOException("Invalid vertex: $line")
                    }
                    val x = tokens[1].toFloat()
                    val y = tokens[2].toFloat()
                    val z = tokens[3].toFloat()
                    positions.add(x)
                    positions.add(y)
                    positions.add(z)
                    adjustMaxMin(x, y, z)
                    sumX += x
                    sumY += y
                    sumZ += z
                    firstVertexWithPosition.add(-1)
                    if (tokens.size == 7) {
                        if (!hasColors) {
                            // Vertices before the first one with a color are white.
                            hasColors = true
                            repeat(positions.size - 3) { colors.add(1f) }
                        }
                        for (i in 4..6) {
                            val c = tokens[i].toFloat()
                            maxColor = maxOf(maxColor, c)
                            colors.add(c)
                        }
                    } else if (hasColors) {
                        repeat(3) { colors.add(1f) }
                    }
                }
                "vt" -> {
                    texCoords.add(tokens.getOrNull(1)?.toFloat() ?: 0f)
                    texCoords.add(tokens.getOrNull(2)?.toFloat() ?: 0f)
                }
                "vn" -> {
                    if (tokens.size < 4) {
                        throw IOException("Invalid normal: $line")
                    }
                    normals.add(tokens[1].toFloat())
                    normals.add(tokens[2].toFloat())
                    normals.add(tokens[3].toFloat())
                }
                "f" -> {
                    faceVertices.clear()
                    for (i in 1 until tokens.size) {
                        faceVertices.add(vertexFor(tokens[i]))
                    }
                    // Split polygons into a fan of triangles.
                    for (i in 1 until faceVertices.size - 1) {
                        triangles.add(faceVertices[0])
                        triangles.add(faceVertices[i])
                        triangles.add(faceVertices[i + 1])
                    }
                }
                "usemtl" -> {
                    triangles = trianglesByMaterial.getOrPut(line.substring(tokens[0].length).trim()) { IntList() }
                }
                "mtllib" -> {
                    readMaterialLibraries(line.substring(tokens[0].length).trim())
                }
            }
        }

        val positionCount = positions.size / 3
        vertexCount = vertexPosition.size
        if (positionCount > 0) {
            centerMassX = (sumX / positionCount).toFloat()
            centerMassY = (sumY / positionCount).toFloat()
            centerMassZ = (sumZ / positionCount).toFloat()
        }

        val vertexArray = FloatArray(vertexCount * 3)
        for (v in 0 until vertexCount) {
            positions.array.copyInto(vertexArray, v * 3, vertexPosition[v] * 3, vertexPosition[v] * 3 + 3)
        }
        vertexBuffer = allocateFloats(vertexArray)

        // Combine the triangles of all materials into one array, with a part for each material.
        val indexArray = IntArray(trianglesByMaterial.values.sumOf { it.size })
        val parts = mutableListOf<MeshPart>()
        val useMaterials = trianglesByMaterial.keys.any { it in materials }
        var offset = 0
        for ((name, list) in trianglesByMaterial) {
            if (list.size == 0) {
                continue
            }
            list.array.copyInto(indexArray, offset, 0, list.size)
            if (useMaterials) {
                // A material that wasn't found in the libraries gets the default gray.
                parts.add(MeshPart(materials[name] ?: Material(name), offset, list.size))
            }
            offset += list.size
        }
        indexCount = indexArray.size
        indexBuffer = allocateInts(indexArray)
        this.parts = parts

        normalBuffer = allocateFloats(buildNormals(indexArray))

        if (hasColors) {
            // Colors may be given from 0 to 1, or sometimes from 0 to 255.
            val scale = if (maxColor > 1f) 1f / 255f else 1f
            val colorArray = FloatArray(vertexCount * 4)
            for (v in 0 until vertexCount) {
                val p = vertexPosition[v]
                colorArray[v * 4] = colors[p * 3] * scale
                colorArray[v * 4 + 1] = colors[p * 3 + 1] * scale
                colorArray[v * 4 + 2] = colors[p * 3 + 2] * scale
                colorArray[v * 4 + 3] = 1f
            }
            colorBuffer = allocateFloats(colorArray)
        }

        // Texture coordinates are only useful if there are textures.
        if (parts.any { it.material.diffuseTexture != null }) {
            val texCoordArray = FloatArray(vertexCount * 2)
            for (v in 0 until vertexCount) {
                val t = vertexTexCoord[v]
                if (t >= 0) {
                    texCoordArray[v * 2] = texCoords[t * 2]
                    texCoordArray[v * 2 + 1] = texCoords[t * 2 + 1]
                }
            }
            texCoordBuffer = allocateFloats(texCoordArray)
        }
    }

    /**
     * Returns the mesh vertex for a face corner, which is given as "v", "v/vt", "v//vn", or "v/vt/vn"
     * (each a 1-based index, or a negative index relative to the end of the list).
     */
    private fun vertexFor(corner: String): Int {
        val indices = corner.split('/')
        val p = resolveIndex(indices[0], positions.size / 3, corner)
        val t = if (indices.size > 1 && indices[1].isNotEmpty()) resolveIndex(indices[1], texCoords.size / 2, corner) else -1
        val n = if (indices.size > 2 && indices[2].isNotEmpty()) resolveIndex(indices[2], normals.size / 3, corner) else -1

        var v = firstVertexWithPosition[p]
        while (v >= 0) {
            if (vertexTexCoord[v] == t && vertexNormal[v] == n) {
                return v
            }
            v = nextVertexWithPosition[v]
        }
        v = vertexPosition.size
        vertexPosition.add(p)
        vertexTexCoord.add(t)
        vertexNormal.add(n)
        nextVertexWithPosition.add(firstVertexWithPosition[p])
        firstVertexWithPosition[p] = v
        return v
    }

    private fun resolveIndex(str: String, count: Int, corner: String): Int {
        val index = str.toIntOrNull() ?: throw IOException("Invalid face: $corner")
        val resolved = if (index < 0) count + index else index - 1
        if (resolved !in 0 until count) {
            throw IOException("Invalid face: $corner")
        }
        return resolved
    }

    /**
     * Uses the normals given in the file, and for any vertices without one, computes a smooth
     * normal from the faces around its position.
     */
    private fun buildNormals(indices: IntArray): FloatArray {
        val normalArray = FloatArray(vertexCount * 3)
        var needsComputedNormals = false
        for (v in 0 until vertexCount) {
            val n = vertexNormal[v]
            if (n >= 0) {
                normals.array.copyInto(normalArray, v * 3, n * 3, n * 3 + 3)
            } else {
                needsComputedNormals = true
            }
        }
        if (!needsComputedNormals) {
            return normalArray
        }
        // Accumulate the (area-weighted) normals of the faces around each position.
        val p = positions.array
        val positionNormals = FloatArray(positions.size)
        for (i in indices.indices step 3) {
            val a = vertexPosition[indices[i]] * 3
            val b = vertexPosition[indices[i + 1]] * 3
            val c = vertexPosition[indices[i + 2]] * 3
            val e1x = p[b] - p[a]
            val e1y = p[b + 1] - p[a + 1]
            val e1z = p[b + 2] - p[a + 2]
            val e2x = p[c] - p[a]
            val e2y = p[c + 1] - p[a + 1]
            val e2z = p[c + 2] - p[a + 2]
            val nx = e1y * e2z - e1z * e2y
            val ny = e1z * e2x - e1x * e2z
            val nz = e1x * e2y - e1y * e2x
            for (k in intArrayOf(a, b, c)) {
                positionNormals[k] += nx
                positionNormals[k + 1] += ny
                positionNormals[k + 2] += nz
            }
        }
        for (v in 0 until vertexCount) {
            if (vertexNormal[v] < 0) {
                val k = vertexPosition[v] * 3
                val length = sqrt(positionNormals[k] * positionNormals[k] + positionNormals[k + 1] * positionNormals[k + 1] +
                        positionNormals[k + 2] * positionNormals[k + 2])
                if (length > 0f) {
                    normalArray[v * 3] = positionNormals[k] / length
                    normalArray[v * 3 + 1] = positionNormals[k + 1] / length
                    normalArray[v * 3 + 2] = positionNormals[k + 2] / length
                }
            }
        }
        return normalArray
    }

    private fun readMaterialLibraries(names: String) {
        // A line can list several libraries, but a single file name might also contain spaces.
        val files = names.split(WHITESPACE)
        if (files.size > 1 && files.all { it.endsWith(".mtl", ignoreCase = true) }) {
            files.forEach { readMaterialLibrary(it) }
        } else {
            readMaterialLibrary(names)
        }
    }

    private fun readMaterialLibrary(name: String) {
        val stream = openResource(name) ?: return
        val reader = BufferedReader(InputStreamReader(stream))
        reader.use {
            var material: Material? = null
            val hasDiffuseColor = HashSet<Material>()
            val hasOpacity = HashSet<Material>()
            while (true) {
                val line = reader.readLine()?.trim() ?: break
                if (line.isEmpty() || line[0] == '#') {
                    continue
                }
                val tokens = line.split(WHITESPACE)
                val keyword = tokens[0].lowercase()
                if (keyword == "newmtl") {
                    val materialName = line.substring(tokens[0].length).trim()
                    material = Material(materialName).also { materials[materialName] = it }
                    continue
                }
                val m = material ?: continue
                // Values that can't be parsed are ignored, since the model is still useful without them.
                when (keyword) {
                    "kd" -> parseColor(tokens)?.let {
                        m.diffuseColor = it
                        hasDiffuseColor.add(m)
                    }
                    "ks" -> parseColor(tokens)?.let { m.specularColor = it }
                    "ke" -> parseColor(tokens)?.let { m.emissiveColor = it }
                    "ns" -> tokens.getOrNull(1)?.toFloatOrNull()?.let { m.shininess = it }
                    "d" -> tokens.last().toFloatOrNull()?.let {
                        m.opacity = it.coerceIn(0f, 1f)
                        hasOpacity.add(m)
                    }
                    "tr" -> tokens.getOrNull(1)?.toFloatOrNull()?.let {
                        // "Tr" is the inverse of "d", but some exporters write "Tr 1" for opaque materials.
                        if (m !in hasOpacity && it < 1f) {
                            m.opacity = (1f - it).coerceIn(0f, 1f)
                        }
                    }
                    "illum" -> tokens.getOrNull(1)?.toIntOrNull()?.let {
                        m.lighting = when (it) {
                            0 -> Material.LIGHTING_NONE
                            1 -> Material.LIGHTING_DIFFUSE
                            else -> Material.LIGHTING_SPECULAR
                        }
                    }
                    "map_kd" -> readTextureMap(tokens, m)
                }
            }
            // A texture without a diffuse color is shown as is.
            for (m in materials.values) {
                if (m.diffuseTexture != null && m !in hasDiffuseColor) {
                    m.diffuseColor = floatArrayOf(1f, 1f, 1f)
                }
            }
        }
    }

    private fun parseColor(tokens: List<String>): FloatArray? {
        // Colors can also be given as "spectral" curves or in CIE XYZ space, which aren't supported.
        val r = tokens.getOrNull(1)?.toFloatOrNull() ?: return null
        val g = tokens.getOrNull(2)?.toFloatOrNull() ?: r
        val b = tokens.getOrNull(3)?.toFloatOrNull() ?: r
        return floatArrayOf(r, g, b)
    }

    /**
     * Reads a texture map statement, which is the file name, preceded by options such as "-s 2 2"
     * (which scales the texture coordinates) or "-o 0.5 0" (which offsets them).
     */
    private fun readTextureMap(tokens: List<String>, material: Material) {
        var i = 1
        // Options start with "-", but the last token is always (part of) the file name.
        while (i < tokens.size - 1 && tokens[i].startsWith("-")) {
            val option = tokens[i++].lowercase()
            when (option) {
                "-o", "-s", "-t" -> {
                    // From one to three numbers (u, v, w)
                    val values = mutableListOf<Float>()
                    while (values.size < 3 && i < tokens.size - 1) {
                        values.add(tokens[i].toFloatOrNull() ?: break)
                        i++
                    }
                    if (option == "-s" && values.isNotEmpty()) {
                        material.textureScale = floatArrayOf(values[0], values.getOrElse(1) { 1f })
                    } else if (option == "-o" && values.isNotEmpty()) {
                        material.textureOffset = floatArrayOf(values[0], values.getOrElse(1) { 0f })
                    }
                }
                "-mm" -> i += 2
                else -> i++
            }
        }
        if (i >= tokens.size) {
            return
        }
        val fileName = tokens.subList(i, tokens.size).joinToString(" ")
        val data = if (textures.containsKey(fileName)) textures[fileName] else openResource(fileName)?.use { it.readBytes() }
        textures[fileName] = data
        if (data != null) {
            material.diffuseTexture = data
            material.diffuseTextureName = fileName
        }
    }

    private fun openResource(path: String): InputStream? {
        return try {
            resources?.open(path)
        } catch (e: IOException) {
            null
        }
    }

    companion object {
        private val WHITESPACE = "\\s+".toRegex()
    }
}
