package dev.dungeon.lobby

import org.bukkit.entity.Enemy
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import org.bukkit.event.entity.CreatureSpawnEvent

/** Spawn events execute on the spawning entity's region; no cross-region scans are needed. */
class LobbyMobProtection(private val settings: LobbySettings) : Listener {
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    fun onSpawn(event: CreatureSpawnEvent) {
        if(settings.enabled && event.entity.world.name.equals(settings.world,true) && event.entity is Enemy) {
            event.isCancelled = true
        }
    }
}
