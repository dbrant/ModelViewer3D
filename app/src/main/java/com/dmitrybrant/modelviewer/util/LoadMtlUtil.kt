package com.dmitrybrant.modelviewer.util

import android.content.res.Resources
import android.text.TextUtils
import com.dmitrybrant.modelviewer.obj.bean.MtlBean
import java.io.BufferedReader
import java.io.IOException
import java.io.InputStream
import java.io.InputStreamReader
import java.util.StringTokenizer

/**
 *
 *@auth: Hank
 *邮箱: cs16xiaoc1@163.com
 *创建时间: 2024/1/25 10:04
 *描述: Utility class for loading materials.
 */
object LoadMtlUtil {

    /**
     * Material name
     */
    private const val NEWMTL = "newmtl"

    /**
     * Ambient color
     */
    private const val KA = "Ka"

    /**
     * Diffuse color
     */
    private const val KD = "Kd"

    /**
     * Specular color
     */
    private const val KS = "Ks"

    /**
     * Shininess
     */
    private const val NS = "Ns"

    /**
     * Dissolve
     */
    private const val D = "d"

    /**
     * Dissolve
     */
    private const val TR = "Tr"

    /**
     * Ambient color texture
     */
    private const val MAP_KA = "map_Ka"

    /**
     * Diffuse color texture, usually the same as the ambient color texture
     */
    private const val MAP_KD = "map_Kd"

    /**
     * Specular color texture
     */
    private const val MAP_KS = "map_Ks"

    /**
     * Specular highlight texture
     */
    private const val MAP_NS = "map_Ns"

    /**
     * Alpha texture
     */
    private const val MAP_D = "map_d"

    /**
     * Alpha texture
     */
    private const val MAP_TR = "map_Tr"
    private const val MAP_BUMP = "map_Bump"

    /**
     * Read material information from assets.
     * @param assets Asset file path
     * @param res Resources
     * @return Map of material names to MtlBean
     */
    fun loadMtl(assets: String, res: Resources): Map<String, MtlBean> {
        val stream: InputStream
        try {
            stream = res.assets.open(assets)
        } catch (e: IOException) {
            LogUtil.e(e)
            return emptyMap()
        }
        return loadMtl(stream)
    }

    /**
     * Read material information from input stream.
     * @param stream InputStream of the material file
     * @return Map of material names to MtlBean
     */
    fun loadMtl(stream: InputStream): Map<String, MtlBean> {
        val result: MutableMap<String, MtlBean> = HashMap()
        var buffer: BufferedReader? = null
        try {
            buffer = BufferedReader(InputStreamReader(stream))
            var line: String?
            var type: String
            var parts: StringTokenizer
            var currMtl: MtlBean? = null
            while (buffer.readLine().also { line = it } != null) {
                if (TextUtils.isEmpty(line?.trim()) || line!!.trim { it <= ' ' }.startsWith("#")) {
                    continue
                }
                parts = StringTokenizer(line!!.trim { it <= ' ' }, " ")
                val numTokens = parts.countTokens()
                if (numTokens == 0) {
                    continue
                }
                type = parts.nextToken()
                type = type.replace("\\t".toRegex(), "")
                type = type.replace(" ".toRegex(), "")

                if (NEWMTL == type) {
                    val name = if (parts.hasMoreTokens()) parts.nextToken() else "def"
                    if (currMtl != null) {
                        result[currMtl.name!!] = currMtl
                    }
                    currMtl = MtlBean()
                    currMtl.name = name
                } else {
                    if (currMtl != null) {
                        when (type) {
                            KA -> currMtl.Ka_Color = getColorFromParts(parts)
                            KD -> currMtl.Kd_Color = getColorFromParts(parts)
                            KS -> currMtl.Ks_Color = getColorFromParts(parts)
                            NS -> currMtl.ns = parts.nextToken().toFloat()
                            D -> currMtl.alpha = parts.nextToken().toFloat()
                            TR -> currMtl.alpha = 1 - parts.nextToken().toFloat()
                            MAP_KA -> currMtl.Ka_Texture = parts.nextToken()
                            MAP_KD -> currMtl.Kd_Texture = parts.nextToken()
                            MAP_KS -> currMtl.Ks_Texture = parts.nextToken()
                            MAP_NS -> currMtl.Ns_Texture = parts.nextToken()
                            MAP_D, MAP_TR -> currMtl.alphaTexture = parts.nextToken()
                            MAP_BUMP -> currMtl.bumpTexture = parts.nextToken()
                        }
                    }
                }
            }
            if (currMtl != null) {
                result[currMtl.name!!] = currMtl
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
        return result
    }

    /**
     * Get color values from StringTokenizer.
     * @param parts StringTokenizer containing color values
     * @return Float array representing color values
     */
    private fun getColorFromParts(parts: StringTokenizer): FloatArray {
        return floatArrayOf(
            parts.nextToken().toFloat(),
            parts.nextToken().toFloat(),
            parts.nextToken().toFloat()
        )
    }
}
