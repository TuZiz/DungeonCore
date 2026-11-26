package dev.dungeon.farming

import org.bukkit.GameRules
import org.bukkit.World
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import org.bukkit.event.world.TimeSkipEvent

/** World clock and gamerules are changed only from the global region scheduler. */
class FarmDaylight(private val settings: FarmSettings) : Listener {
    fun apply(world: World) {
        if(!settings.enabled || world.name!=settings.world) return
        check(world.setGameRule(GameRules.ADVANCE_TIME,false)) { "Cannot freeze time: world=${world.name}" }
        world.time=6000L
    }

    @EventHandler(priority=EventPriority.HIGHEST,ignoreCancelled=true)
    fun skip(event: TimeSkipEvent) {
        if(settings.enabled && event.world.name==settings.world) event.isCancelled=true
    }
}
