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
 *创建时间: 2024/1/25 10:31
 *描述:
 */
data class ObjectBean(
    var name: String? = null,
    /**
     * Vertex data
     */
    var aVertices: FloatArray? = null,
    /**
     * Texture coordinates
     */
    var aTexCoords: FloatArray? = null,
    /**
     * Normal vectors
     */
    var aNormals: FloatArray? = null,

    var mtl: MtlBean? = null,
    /**
     * Temporary storage for vertex data
     */
    var vertexIndices: MutableList<Int>? = ArrayList(),
    /**
     * Storage for texture data
     */
    var texCoordIndices: MutableList<Int>? = ArrayList(),
    /**
     * Storage for normal vector data
     */
    var normalIndices: MutableList<Int>? = ArrayList(),

    var ambient: Int = -1,
    var diffuse: Int = -1,
    var specular: Int = -1,

    var centerMassX: Double = 0.0,
    var centerMassY: Double = 0.0,
    var centerMassZ: Double = 0.0,

    var maxX:Float = Float.MIN_VALUE,
    var maxY:Float = Float.MIN_VALUE,
    var maxZ:Float = Float.MIN_VALUE,
    var minX:Float = Float.MAX_VALUE,
    var minY:Float = Float.MAX_VALUE,
    var minZ:Float = Float.MAX_VALUE
){
    fun adjustMaxMin(x: Float, y: Float, z: Float) {
        if (x > maxX) {
            maxX = x
        }
        if (y > maxY) {
            maxY = y
        }
        if (z > maxZ) {
            maxZ = z
        }
        if (x < minX) {
            minX = x
        }
        if (y < minY) {
            minY = y
        }
        if (z < minZ) {
            minZ = z
        }
    }
}
