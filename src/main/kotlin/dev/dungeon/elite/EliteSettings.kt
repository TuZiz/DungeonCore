package dev.dungeon.elite

import org.bukkit.configuration.file.YamlConfiguration
import java.nio.file.Path

data class EliteAttack(val animation: String,val range: Double,val damage: Double,val windup: Long,
    val duration: Long,val cooldown: Long,val poison: Int,val slow: Int)
data class ModeledElite(val id: String,val model: String,val scale: Double,val aggro: Double,
    val birth: String,val birthTicks: Long,val death: String,val deathTicks: Long,val attacks: List<EliteAttack>)
data class EliteSettings(val enabled: Boolean,val mobs: Map<String,ModeledElite>) {
    companion object {
        fun load(path: Path): EliteSettings {
            val yaml=YamlConfiguration().apply { load(path.toFile()) }
            require(yaml.getInt("schema-version")==1 && yaml.get("enabled") is Boolean) { "$path:schema-version/enabled invalid" }
            fun n(key: String,min: Double,max: Double): Double {
                val value=yaml.get(key)
                require(value is Number && value.toDouble().isFinite() && value.toDouble() in min..max) { "$path:$key invalid: $value" }
                return value.toDouble()
            }
            fun ticks(key: String,min: Int,max: Int): Long {
                val value=n(key,min.toDouble(),max.toDouble())
                require(value==value.toLong().toDouble()) { "$path:$key expected integer: $value" };return value.toLong()
            }
            fun name(key: String)=requireNotNull(yaml.getString(key)) { "$path:$key missing" }.also {
                require(it.matches(Regex("[a-z0-9_]+"))) { "$path:$key invalid: $it" }
            }
            val section=requireNotNull(yaml.getConfigurationSection("mobs")) { "$path:mobs missing" }
            val mobs=section.getKeys(false).associateWith { id ->
                require(id.matches(Regex("[\\p{L}\\p{N}_-]+"))) { "$path:mobs invalid ID: $id" }
                val base="mobs.$id"
                val attacks=requireNotNull(yaml.getConfigurationSection("$base.attacks")) { "$path:$base.attacks missing" }.getKeys(false).map { key ->
                    val at="$base.attacks.$key"
                    val windup=ticks("$at.windup-ticks",4,100);val duration=ticks("$at.duration-ticks",8,160)
                    require(windup<duration) { "$path:$at windup must be shorter than duration" }
                    EliteAttack(name("$at.animation"),n("$at.range",1.0,16.0),n("$at.damage",0.0,40.0),windup,duration,
                        ticks("$at.cooldown-ticks",duration.toInt(),1200),ticks("$at.poison-ticks",0,200).toInt(),ticks("$at.slow-ticks",0,200).toInt())
                }
                require(attacks.isNotEmpty() && attacks.size<=8) { "$path:$base.attacks empty or too many" }
                val aggro=n("$base.aggro-radius",4.0,24.0)
                require(attacks.all { it.range<=aggro }) { "$path:$base attack range exceeds aggro-radius" }
                ModeledElite(id,name("$base.model"),n("$base.model-scale",.25,2.0),aggro,name("$base.birth-animation"),
                    ticks("$base.birth-ticks",1,100),name("$base.death-animation"),ticks("$base.death-ticks",1,100),attacks)
            }
            return EliteSettings(yaml.getBoolean("enabled"),mobs)
        }
    }
}
