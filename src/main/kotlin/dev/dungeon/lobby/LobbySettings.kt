package dev.dungeon.lobby

import org.bukkit.configuration.file.YamlConfiguration
import java.nio.file.Path

data class LobbySettings(val enabled: Boolean, val world: String, val importMap: Boolean, val autoLoad: Boolean,
    val x: Double, val y: Double, val z: Double, val yaw: Float, val pitch: Float) {
    companion object {
        fun load(path: Path): LobbySettings {
            val y = YamlConfiguration().apply { load(path.toFile()) }
            require(y.getInt("schema-version") == 1) { "$path schema-version unsupported" }
            listOf("enabled","import-bundled-map","auto-load").forEach {
                require(y.get(it) is Boolean) { "$path $it must be boolean: ${y.get(it)}" }
            }
            val name = requireNotNull(y.getString("world")) { "$path world missing" }
            require(name.matches(Regex("[a-zA-Z0-9_-]+")) && !name.startsWith("dungeon_",true)) {
                "$path world=$name must be a permanent name, not a temporary dungeon_ name"
            }
            fun coordinate(key: String): Double {
                val value = y.get("spawn.$key")
                require(value is Number && value.toDouble().isFinite()) { "$path spawn.$key invalid: $value" }
                return value.toDouble()
            }
            val x = coordinate("x"); val height = coordinate("y"); val z = coordinate("z")
            val yaw = coordinate("yaw"); val pitch = coordinate("pitch")
            require(x in -29999984.0..29999984.0 && z in -29999984.0..29999984.0 && height in -64.0..320.0 && pitch in -90.0..90.0 && yaw in -360.0..360.0) {
                "$path spawn outside supported bounds"
            }
            return LobbySettings(y.getBoolean("enabled"),name,y.getBoolean("import-bundled-map"),y.getBoolean("auto-load"),x,height,z,yaw.toFloat(),pitch.toFloat())
        }
    }
}
