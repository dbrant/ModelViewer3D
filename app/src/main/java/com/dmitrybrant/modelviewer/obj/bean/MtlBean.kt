package com.dmitrybrant.modelviewer.obj.bean

//  ┏┓　　　┏┓
//┏┛┻━━━┛┻┓
//┃　　　　　　　┃
//┃　　　━　　　┃
//┃　┳┛　┗┳　┃
//┃　　　　　　　┃
//┃　　　┻　　　┃
//┃　　　　　　　┃
//┗━┓　　　┏━┛
//    ┃　　　┃                  神兽保佑
//    ┃　　　┃                  永无BUG！
//    ┃　　　┗━━━┓
//    ┃　　　　　　　┣┓
//    ┃　　　　　　　┏┛
//    ┗┓┓┏━┳┓┏┛
//      ┃┫┫　┃┫┫
//      ┗┻┛　┗┻┛
/**
 *@auth: Hank
 *邮箱: cs16xiaoc1@163.com
 *创建时间: 2024/1/25 10:07
 *描述:
 */
data class MtlBean (
    /**
     * Name
     */
    var name: String? = null,

    /**
     * Ambient Color
     */
    var Ka_Color: FloatArray = floatArrayOf(1f, 1f, 1f),

    /**
     * Diffuse Color
     */
    var Kd_Color: FloatArray = floatArrayOf(1f, 1f, 1f),

    /**
     * Specular Color
     */
    var Ks_Color: FloatArray = floatArrayOf(1f, 1f, 1f),

    /**
     * Shininess
     */
    var ns: Float = 0f,

    /**
     * Transparency, 0 for fully transparent, 1 for fully opaque
     */
    var alpha: Float = 1f,

    /**
     * Ambient Color Texture
     */
    var Ka_Texture: String? = null,

    /**
     * Diffuse Color Texture, usually the same as Ambient Color Texture
     */
    var Kd_Texture: String? = null,

    /**
     * Specular Color Texture
     */
    var Ks_Texture: String? = null,

    /**
     * Shininess Texture
     */
    var Ns_Texture: String? = null,

    /**
     * Transparency Texture
     */
    var alphaTexture: String? = null,
    var bumpTexture: String? = null
)