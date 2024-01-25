package com.dmitrybrant.modelviewer.util

import android.content.res.Resources
import android.text.TextUtils
import com.dmitrybrant.modelviewer.obj.bean.MtlBean
import com.dmitrybrant.modelviewer.obj.bean.ObjectBean
import java.io.BufferedReader
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.InputStreamReader
import java.util.*
import kotlin.collections.ArrayList

/**
 *@auth: Hank
 *邮箱: cs16xiaoc1@163.com
 *创建时间: 2024/1/25 10:07
 *描述: Utility class for loading 3D models.
 */
object LoadObjectUtil {

    /**
     * Corresponding texture file
     */
    private const val MTLLIB = "mtllib"
    /**
     * Group name
     */
    private const val G = "g"
    /**
     * Object name
     */
    private const val O = "o"
    /**
     * Vertices
     */
    private const val V = "v"
    /**
     * Texture coordinates
     */
    private const val VT = "vt"
    /**
     * Normals
     */
    private const val VN = "vn"
    /**
     * Used texture
     */
    private const val USEMTL = "usemtl"
    /**
     * Vertex indices starting from 1 (e.g., v1/vt1/vn1 v2/vt2/vn2 v3/vt3/vn3)
     */
    private const val F = "f"

    /**
     * Read model file information from assets.
     * @param assets Asset file path
     * @param res Resources
     * @return List of ObjectBean
     */
    fun loadObject(assets: String, res: Resources): List<ObjectBean> {
        val stream: InputStream = res.assets.open(assets)
        return loadObject(stream, res, null)
    }

    /**
     * Read model file information from assets with a specified parent directory.
     * @param assets Asset file path
     * @param res Resources
     * @param parent Parent directory
     * @return List of ObjectBean
     */
    fun loadObject(assets: String, res: Resources, parent: String): MutableList<ObjectBean> {
        val stream: InputStream = res.assets.open(assets)
        return loadObject(stream, res, parent)
    }

    /**
     * Read model file information from input stream.
     * @param stream InputStream of the model file
     * @param res Resources
     * @param parent Parent directory
     * @return List of ObjectBean
     */
    fun loadObject(stream: InputStream?, res: Resources, parent: String?): MutableList<ObjectBean> {
        val result = ArrayList<ObjectBean>()
        val vertices = ArrayList<Float>() // Vertex data
        val texCoords = ArrayList<Float>() // Texture coordinate data
        val normals = ArrayList<Float>() // Normal vector data
        var mtlMap: Map<String, MtlBean>? = null // Map of all materials
        var centerMassX = 0.0
        var centerMassY = 0.0
        var centerMassZ = 0.0

        if (stream != null) {
            var buffer: BufferedReader? = null
            try {
                buffer = BufferedReader(InputStreamReader(stream))
                var line: String?
                var type: String
                var parts: StringTokenizer
                var numTokens: Int
                var currObj = ObjectBean()
                var currTexName: String? = null
                var currObjHasFaces = false

                while (buffer.readLine().also { line = it } != null) {
                    if (TextUtils.isEmpty(line?.trim()) || line!!.trim().startsWith("#")) {
                        continue
                    }
                    parts = StringTokenizer(line!!.trim(), " ")
                    numTokens = parts.countTokens()
                    if (numTokens == 0) {
                        continue
                    }
                    type = parts.nextToken()

                    when (type) {
                        MTLLIB -> {
                            if (!parts.hasMoreTokens()) {
                                continue
                            }
                            val mtlPath = parts.nextToken()
                            if (!TextUtils.isEmpty(mtlPath)) {
                                mtlMap = LoadMtlUtil.loadMtl(if (TextUtils.isEmpty(parent)) "" else "$parent${File.separator}$mtlPath", res)
                            }
                        }
                        O -> {
                            val objName = if (parts.hasMoreTokens()) parts.nextToken() else "def"
                            if (currObjHasFaces) {
                                currObj.centerMassX = centerMassX / currObj.vertexIndices!!.size
                                currObj.centerMassY = centerMassX / currObj.vertexIndices!!.size
                                currObj.centerMassZ = centerMassX / currObj.vertexIndices!!.size
                                centerMassX = 0.0
                                centerMassY = 0.0
                                centerMassZ = 0.0
                                result.add(currObj)
                                currObj = ObjectBean()
                                currObjHasFaces = false
                            }
                            currObj.name = objName
                            if (!TextUtils.isEmpty(currTexName) && mtlMap != null) {
                                currObj.mtl = mtlMap[currTexName]
                            }
                        }
                        V -> {
                            var x = parts.nextToken().toFloat()
                            var y = parts.nextToken().toFloat()
                            var z = parts.nextToken().toFloat()
                            vertices.add(x)
                            vertices.add(y)
                            vertices.add(z)
                            centerMassX += x
                            centerMassY += y
                            centerMassZ += z
                            currObj.adjustMaxMin(x,y,z)
                        }
                        VT -> {
                            texCoords.add(parts.nextToken().toFloat())
                            texCoords.add(1f - parts.nextToken().toFloat())
                        }
                        VN -> {
                            normals.add(parts.nextToken().toFloat())
                            normals.add(parts.nextToken().toFloat())
                            normals.add(parts.nextToken().toFloat())
                        }
                        USEMTL -> {
                            currTexName = parts.nextToken()
                            if (currObjHasFaces) {
                                currObj.centerMassX = centerMassX / currObj.vertexIndices!!.size
                                currObj.centerMassY = centerMassY / currObj.vertexIndices!!.size
                                currObj.centerMassZ = centerMassZ / currObj.vertexIndices!!.size
                                centerMassX = 0.0
                                centerMassY = 0.0
                                centerMassZ = 0.0
                                result.add(currObj)
                                currObj = ObjectBean()
                                currObjHasFaces = false
                            }
                            if (!TextUtils.isEmpty(currTexName) && mtlMap != null) {
                                currObj.mtl = mtlMap[currTexName]
                            }
                        }
                        F -> {
                            currObjHasFaces = true
                            val isQuad = numTokens == 5
                            val quadvids = IntArray(4)
                            val quadtids = IntArray(4)
                            val quadnids = IntArray(4)

                            val emptyVt = line!!.contains("//")
                            if (emptyVt) {
                                line = line!!.replace("//", "/")
                            }
                            parts = StringTokenizer(line)
                            parts.nextToken()

                            var subParts = StringTokenizer(parts.nextToken(), "/")
                            val partLength = subParts.countTokens()
                            val hasUV = partLength >= 2 && !emptyVt
                            val hasN = partLength == 3 || partLength == 2 && emptyVt
                            var idx: Int
                            for (i in 1 until numTokens) {
                                if (i > 1) {
                                    subParts = StringTokenizer(parts.nextToken(), "/")
                                }
                                idx = Integer.parseInt(subParts.nextToken())
                                idx = if (idx < 0) (vertices.size / 3) + idx else idx - 1
                                if (!isQuad) {
                                    currObj.vertexIndices!!.add(idx)
                                } else {
                                    quadvids[i - 1] = idx
                                }
                                if (hasUV) {
                                    idx = Integer.parseInt(subParts.nextToken())
                                    idx = if (idx < 0) (texCoords.size / 2) + idx else idx - 1
                                    if (!isQuad) {
                                        currObj.texCoordIndices!!.add(idx)
                                    } else {
                                        quadtids[i - 1] = idx
                                    }
                                }
                                if (hasN) {
                                    idx = Integer.parseInt(subParts.nextToken())
                                    idx = if (idx < 0) (normals.size / 3) + idx else idx - 1
                                    if (!isQuad) {
                                        currObj.normalIndices!!.add(idx)
                                    } else {
                                        quadnids[i - 1] = idx
                                    }
                                }
                            }
                            if (isQuad) {
                                val indices = intArrayOf(0, 1, 2, 0, 2, 3)
                                for (i in 0 until 6) {
                                    val index = indices[i]
                                    currObj.vertexIndices!!.add(quadvids[index])
                                    currObj.texCoordIndices!!.add(quadtids[index])
                                    currObj.normalIndices!!.add(quadnids[index])
                                }
                            }
                        }
                    }
                }
                if (currObjHasFaces) {
                    currObj.centerMassX = centerMassX / currObj.vertexIndices!!.size
                    currObj.centerMassY = centerMassY / currObj.vertexIndices!!.size
                    currObj.centerMassZ = centerMassZ / currObj.vertexIndices!!.size
                    result.add(currObj)
                }

                val size = result.size
                for (j in 0 until size) {
                    val objData = result[j]
                    val aVertices = FloatArray(objData.vertexIndices!!.size * 3)
                    val aTexCoords = FloatArray(objData.texCoordIndices!!.size * 2)
                    val aNormals = FloatArray(objData.normalIndices!!.size * 3)

                    for (i in 0 until objData.vertexIndices!!.size) {
                        val faceIndex = objData.vertexIndices!![i] * 3
                        val vertexIndex = i * 3
                        try {
                            aVertices[vertexIndex] = vertices[faceIndex]
                            aVertices[vertexIndex + 1] = vertices[faceIndex + 1]
                            aVertices[vertexIndex + 2] = vertices[faceIndex + 2]
                        } catch (e: Exception) {
                            LogUtil.e(e)
                        }
                    }

                    if (!texCoords.isEmpty()) {
                        for (i in 0 until objData.texCoordIndices!!.size) {
                            val texCoordIndex = objData.texCoordIndices!![i] * 2
                            val ti = i * 2
                            aTexCoords[ti] = texCoords[texCoordIndex]
                            aTexCoords[ti + 1] = texCoords[texCoordIndex + 1]
                        }
                    }

                    for (i in 0 until objData.normalIndices!!.size) {
                        val normalIndex = objData.normalIndices!![i] * 3
                        val ni = i * 3
                        if (normals.isEmpty()) {
                            throw Exception("There are no normals specified for this model. Please re-export with normals.")
                        }
                        aNormals[ni] = normals[normalIndex]
                        aNormals[ni + 1] = normals[normalIndex + 1]
                        aNormals[ni + 2] = normals[normalIndex + 2]
                    }

                    objData.aVertices = aVertices
                    objData.aTexCoords = aTexCoords
                    objData.aNormals = aNormals
                    objData.vertexIndices!!.clear()
                    objData.texCoordIndices!!.clear()
                    objData.normalIndices!!.clear()
                }

            } catch (e: Exception) {
                LogUtil.e(e)
            } finally {
                try {
                    buffer?.close()
                    stream.close()
                } catch (e: IOException) {
                    LogUtil.e(e)
                }
            }
        }
        return result
    }
}
