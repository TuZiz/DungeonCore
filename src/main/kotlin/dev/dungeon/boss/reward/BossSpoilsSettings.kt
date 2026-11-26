package dev.dungeon.boss.reward

import org.bukkit.configuration.file.YamlConfiguration
import java.nio.file.Path

data class BossSpoilsSettings(val materials: List<String>,val sword: String,val chance: Double,
    val common: Double,val excellent: Double) {
    companion object {
        const val ITEM = "dungeon:warden_spoils"
        fun load(path: Path): BossSpoilsSettings {
            val yaml = YamlConfiguration().apply { load(path.toFile()) }
            require(yaml.getInt("schema-version") == 1) { "$path: schema-version=${yaml.get("schema-version")}" }
            val materials = yaml.getList("materials").orEmpty().map {
                require(it is String && it.matches(Regex("[a-z0-9_.-]+:[a-z0-9_./-]+"))) { "$path: materials invalid: $it" }
                it
            }
            require(materials.isNotEmpty() && materials.distinct().size == materials.size) { "$path: materials must contain unique item IDs" }
            val sword = yaml.getString("sword.itemhub-id").orEmpty()
            require(sword.matches(Regex("[^:\\s]+:[^:\\s]+"))) { "$path: sword.itemhub-id invalid: $sword" }
            fun chance(key: String): Double {
                val v = yaml.get("sword.$key")
                require(v is Number && v.toDouble() in 0.0..1.0) { "$path: sword.$key invalid: $v" }
                return v.toDouble()
            }
            val common=chance("common")
            val excellent=chance("excellent")
            require(common+excellent<=1.0) { "$path: sword.common + sword.excellent exceeds 1" }
            return BossSpoilsSettings(materials,sword,chance("chance"),common,excellent)
        }
    }
}
