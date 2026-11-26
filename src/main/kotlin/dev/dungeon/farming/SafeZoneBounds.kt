package dev.dungeon.farming

import org.bukkit.configuration.file.YamlConfiguration
import java.nio.file.Path

/** Inclusive block coordinates, converted to half-open continuous bounds for entities. */
data class SafeZoneBounds(val minX: Int,val minY: Int,val minZ: Int,val maxX: Int,val maxY: Int,val maxZ: Int) {
    fun contains(x: Double,y: Double,z: Double)=x>=minX && x<maxX+1.0 &&
        y>=minY && y<maxY+1.0 && z>=minZ && z<maxZ+1.0

    companion object {
        fun load(c: YamlConfiguration,path: Path): SafeZoneBounds? {
            if(!c.contains("safe-zone")) return null
            require(c.get("safe-zone.enabled") is Boolean) { "$path:safe-zone.enabled expected boolean" }
            if(!c.getBoolean("safe-zone.enabled")) return null
            fun coordinate(key: String): Int {
                val value=c.get("safe-zone.$key")
                require(value is Int && value in -29999984..29999984) { "$path:safe-zone.$key invalid: $value" }
                return value
            }
            val b=SafeZoneBounds(coordinate("min.x"),coordinate("min.y"),coordinate("min.z"),
                coordinate("max.x"),coordinate("max.y"),coordinate("max.z"))
            require(b.minX<=b.maxX && b.minY<=b.maxY && b.minZ<=b.maxZ && b.minY>=-64 && b.maxY<=319) {
                "$path:safe-zone invalid bounds: $b"
            }
            return b
        }
    }
}
