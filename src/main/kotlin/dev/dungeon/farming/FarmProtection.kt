package dev.dungeon.farming

import org.bukkit.event.EventHandler
import org.bukkit.event.Listener
import org.bukkit.event.block.BlockBreakEvent
import org.bukkit.event.block.BlockPlaceEvent
import org.bukkit.event.entity.EntityExplodeEvent
import org.bukkit.event.block.BlockExplodeEvent
import org.bukkit.event.entity.CreatureSpawnEvent

/** World-local protection; MM custom spawns remain enabled. */
class FarmProtection(private val settings: FarmSettings) : Listener {
    private fun farm(name: String)=settings.enabled && name==settings.world
    @EventHandler(priority=org.bukkit.event.EventPriority.HIGHEST)
    fun death(e: org.bukkit.event.entity.PlayerDeathEvent) {
        if(farm(e.player.world.name) && settings.keepInventory) dev.dungeon.session.DeathRetention.apply(e)
    }
    @EventHandler(ignoreCancelled=true) fun breakBlock(e: BlockBreakEvent) { if(farm(e.block.world.name)) e.isCancelled=true }
    @EventHandler(ignoreCancelled=true) fun place(e: BlockPlaceEvent) { if(farm(e.block.world.name)) e.isCancelled=true }
    @EventHandler(ignoreCancelled=true) fun explode(e: EntityExplodeEvent) { if(farm(e.location.world.name)) e.blockList().clear() }
    @EventHandler(ignoreCancelled=true) fun explode(e: BlockExplodeEvent) { if(farm(e.block.world.name)) e.blockList().clear() }
    @EventHandler(ignoreCancelled=true) fun spawn(e: CreatureSpawnEvent) {
        if(farm(e.location.world.name) && e.spawnReason !in setOf(CreatureSpawnEvent.SpawnReason.CUSTOM,CreatureSpawnEvent.SpawnReason.COMMAND)) e.isCancelled=true
    }
}
