package dev.dungeon.farming

import io.papermc.paper.event.entity.EntityMoveEvent
import org.bukkit.Location
import org.bukkit.entity.Mob
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import org.bukkit.event.entity.EntityTeleportEvent
import org.bukkit.event.world.EntitiesLoadEvent

/** Movement and load callbacks own the affected entity; no cross-region entity reads or teleports. */
class FarmBoundary(private val zone: FarmSafeZone) : Listener {
    private fun inside(at: Location)=zone.contains(at.world.name,at.x,at.y,at.z)

    @EventHandler(priority=EventPriority.HIGHEST,ignoreCancelled=true)
    fun move(event: EntityMoveEvent) {
        if(event.entity !is Mob || !inside(event.to)) return
        event.isCancelled=true
        // Old mobs already inside must not remain trapped there. Removal gives no kill rewards.
        if(inside(event.from)) event.entity.remove()
    }

    @EventHandler(priority=EventPriority.HIGHEST,ignoreCancelled=true)
    fun teleport(event: EntityTeleportEvent) {
        if(event.entity is Mob && event.to?.let(::inside)==true) event.isCancelled=true
    }

    @EventHandler(priority=EventPriority.MONITOR)
    fun loaded(event: EntitiesLoadEvent) {
        event.entities.filterIsInstance<Mob>().forEach { if(inside(it.location)) it.remove() }
    }
}
