package dev.dungeon.integration

import dev.dungeon.model.Mob
import org.bukkit.Location
import org.bukkit.entity.Entity
import org.bukkit.entity.EntityType

/** Called exclusively on the spawn location's region thread. Optional API is isolated here. */
class MobSpawner {
    private val mythic = MythicMobsAdapter()
    fun spawn(mob: Mob, location: Location): Entity {
        if(!mob.mythic) return location.world.spawnEntity(location,EntityType.valueOf(mob.type))
        return mythic.spawn(mob.type,location)
    }
}
