package dev.dungeon.boss.hologram

import org.bukkit.configuration.file.YamlConfiguration
import java.nio.file.Path

data class BossHologramSettings(val enabled: Boolean,val height: Double,val range: Double,val scale: Float,val width: Int) {
    companion object {
        /** IO worker only; malformed display settings do not disable the boss. */
        fun load(path: Path): BossHologramSettings {
            val yaml=YamlConfiguration().apply { load(path.toFile()) }
            require(yaml.getInt("schema-version")==1 && yaml.get("enabled") is Boolean) { "$path:schema-version/enabled invalid" }
            fun number(key: String,min: Double,max: Double): Double {
                val value=yaml.get(key)
                require(value is Number && value.toDouble().isFinite() && value.toDouble() in min..max) { "$path:$key invalid: $value" }
                return value.toDouble()
            }
            val width=number("line-width",80.0,600.0)
            require(width==width.toInt().toDouble()) { "$path:line-width expected integer: $width" }
            return BossHologramSettings(yaml.getBoolean("enabled"),number("height-offset",0.0,16.0),
                number("range",16.0,96.0),number("scale",.4,2.0).toFloat(),width.toInt())
        }
    }
}
