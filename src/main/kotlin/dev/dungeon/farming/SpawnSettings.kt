package dev.dungeon.farming

import org.bukkit.configuration.file.YamlConfiguration
import java.nio.file.Path

data class FarmMob(val id: String, val name: String, val weight: Int, val elite: Boolean)
data class SpawnSettings(val enabled: Boolean, val interval: Int, val attempts: Int, val minRadius: Double,
    val maxRadius: Double, val vertical: Int, val separation: Double, val safeRadius: Double,
    val playerLimit: Int, val nearbyLimit: Int, val elitePlayerLimit: Int, val eliteNearbyLimit: Int, val despawnRadius: Double,
    val despawnSeconds: Int, val mobs: List<FarmMob>, val maxAbove: Int = 2, val maxBelow: Int = 6) {
    companion object {
        fun load(path: Path): SpawnSettings {
            val c=YamlConfiguration().apply { load(path.toFile()) }
            fun number(key: String, min: Double, max: Double): Double {
                val value=c.get(key)
                require(value is Number && value.toDouble().isFinite() && value.toDouble() in min..max) { "$path:$key invalid: $value" }
                return value.toDouble()
            }
            fun integer(key: String, min: Int, max: Int): Int {
                val n=number(key,min.toDouble(),max.toDouble())
                require(n==n.toInt().toDouble()) { "$path:$key must be integer" };return n.toInt()
            }
            require(c.getInt("schema-version")==1 && c.get("enabled") is Boolean) { "$path: invalid schema-version/enabled" }
            val mobs=c.getMapList("mobs").map { row ->
                val id=row["id"] as? String ?: error("$path:mobs.id missing")
                val name=row["name"] as? String ?: error("$path:mobs.name missing")
                val weight=row["weight"] as? Int ?: error("$path:mobs.weight must be integer")
                val elite=row["elite"] as? Boolean ?: error("$path:mobs.elite must be boolean")
                require(id.matches(Regex("[A-Za-z0-9_-]+")) && name.isNotBlank() && weight in 1..1000) { "$path: invalid mob $id" }
                FarmMob(id,name,weight,elite)
            }
            require(mobs.isNotEmpty() && mobs.size<=32 && mobs.map { it.id }.distinct().size==mobs.size) { "$path:mobs empty/duplicate/too many" }
            val min=number("radius.min",4.0,96.0);val max=number("radius.max",min,128.0)
            return SpawnSettings(c.getBoolean("enabled"),integer("interval-ticks",20,1200),integer("attempts",1,16),min,max,
                integer("vertical-search",1,32),number("player-separation",2.0,min),number("spawn-safe-radius",0.0,128.0),
                integer("limits.per-player",1,32),integer("limits.nearby",1,128),integer("limits.elites-per-player",0,32),integer("limits.elites-nearby",0,32),number("despawn.radius",max+1,256.0),
                integer("despawn.after-seconds",1,300),mobs,
                if(c.contains("height.above")) integer("height.above",0,32) else 2,
                if(c.contains("height.below")) integer("height.below",0,32) else 6)
        }
    }
}
