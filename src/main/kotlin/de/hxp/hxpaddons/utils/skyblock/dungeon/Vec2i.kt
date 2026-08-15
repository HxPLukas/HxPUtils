package de.hxp.hxpaddons.utils.skyblock.dungeon

data class Vec2i(val x: Int, val z: Int) {
    fun add(other: Vec2i) = Vec2i(x + other.x, z + other.z)
    fun multiply(factor: Double) = Vec2i((x * factor).toInt(), (z * factor).toInt())
    fun divide(factor: Double) = Vec2i((x / factor).toInt(), (z / factor).toInt())
}
