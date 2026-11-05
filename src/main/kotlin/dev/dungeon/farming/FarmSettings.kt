package dev.dungeon.farming

import org.bukkit.configuration.file.YamlConfiguration
import java.nio.file.Path

data class FarmSettings(val enabled: Boolean,val id: String,val world: String,val x: Double,val y: Double,val z: Double,
    val yaw: Float=0f,val pitch: Float=0f,val keepInventory: Boolean=true) {
    companion object {
        fun load(path: Path): FarmSettings {
            val c=YamlConfiguration().apply { load(path.toFile()) }
            require(c.getInt("schema-version")==1) { "$path:schema-version expected 1" }
            require(c.get("enabled") is Boolean) { "$path:enabled expected boolean" }
            fun id(key: String)=requireNotNull(c.getString(key)).also {
                require(it.matches(Regex("[a-z][a-z0-9_]*")) && !it.startsWith("dungeon_")) { "$path:$key invalid permanent ID $it" }
            }
            fun coordinate(key: String)=c.get("spawn.$key").let {
                require(it is Number && it.toDouble().isFinite()) { "$path:spawn.$key invalid $it" };it.toDouble()
            }
            val x=coordinate("x");val y=coordinate("y");val z=coordinate("z")
            fun angle(key: String,min: Double,max: Double): Float {
                val value=c.get("spawn.$key") ?: return 0f
                require(value is Number && value.toDouble().isFinite() && value.toDouble() in min..max) { "$path:spawn.$key invalid $value" }
                return value.toFloat()
            }
            require(!c.contains("keep-inventory") || c.get("keep-inventory") is Boolean) { "$path:keep-inventory expected boolean" }
            require(x in -29999984.0..29999984.0 && z in -29999984.0..29999984.0 && y in -64.0..319.0) { "$path:spawn out of bounds" }
            return FarmSettings(c.getBoolean("enabled"),id("id"),id("world"),x,y,z,
                angle("yaw",-360.0,360.0),angle("pitch",-90.0,90.0),c.getBoolean("keep-inventory",true))
        }
    }
}
