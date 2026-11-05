package dev.dungeon.session

import dev.dungeon.model.*
import org.bukkit.event.EventHandler
import org.bukkit.event.Listener
import org.bukkit.event.entity.EntityDeathEvent
import org.bukkit.event.entity.PlayerDeathEvent
import org.bukkit.event.player.*
import org.bukkit.event.entity.EntityPickupItemEvent
import org.bukkit.event.block.Action
import java.util.UUID

/** Event callbacks run on the owning player/entity region. No cross-player reads here. */
class GameplayListener(private val sessions: Sessions) : Listener {
    @EventHandler fun join(e: PlayerJoinEvent) { sessions.join(e.player) }
    @EventHandler fun quit(e: PlayerQuitEvent) { sessions.quit(e.player) }
    @EventHandler(ignoreCancelled = true) fun move(e: PlayerMoveEvent) {
        if(!e.hasChangedBlock()) return
        val s = sessions.current(e.player.uniqueId) ?: return
        if(e.to.world.name != s.run.worldName) return
        val stage = s.run.definition.stages.getOrNull(s.run.snapshot().stage) ?: return
        if(stage.objective == Objective.EXPLORE) {
            val p = s.run.definition.points.getValue(stage.target)
            if(p.near(e.to.x,e.to.y,e.to.z,2.0)) sessions.credit(e.player.uniqueId,Objective.EXPLORE,stage.target,event=e.player.uniqueId)
        }
    }
    @EventHandler(ignoreCancelled = true) fun interact(e: PlayerInteractEvent) {
        if(e.action != Action.RIGHT_CLICK_BLOCK || e.hand != org.bukkit.inventory.EquipmentSlot.HAND) return
        val s = sessions.current(e.player.uniqueId) ?: return
        val location = e.clickedBlock?.location ?: return
        if(location.world.name != s.run.worldName || s.run.snapshot().state != RunState.RUNNING) return
        s.run.definition.points.forEach { (id,p) ->
            if(p.near(location.x,location.y,location.z,1.0)) {
                s.run.definition.obstacleDamage[id]?.let { e.player.damage(it) }
                sessions.credit(e.player.uniqueId,Objective.INTERACT,id,event=e.player.uniqueId)
            }
        }
    }
    @EventHandler(ignoreCancelled = true) fun pickup(e: EntityPickupItemEvent) {
        val player = e.entity as? org.bukkit.entity.Player ?: return
        val s = sessions.current(player.uniqueId) ?: return
        if(player.world.name != s.run.worldName) return
        // Collection consumes the stack, preventing repeated drop/pickup credit.
        val snap = s.run.snapshot();val stage = s.run.definition.stages.getOrNull(snap.stage) ?: return
        if(snap.state != RunState.RUNNING || stage.objective != Objective.COLLECT || stage.target != e.item.itemStack.type.name) return
        e.isCancelled = true
        val stack = e.item.itemStack
        val count = minOf(stack.amount,stage.count*s.run.difficulty.goalMultiplier-snap.count)
        if(count == stack.amount) e.item.remove() else { stack.amount -= count;e.item.itemStack = stack }
        repeat(count) { sessions.credit(player.uniqueId,Objective.COLLECT,stage.target,snap.stage,UUID.randomUUID()) }
    }
    @EventHandler fun death(e: EntityDeathEvent) { sessions.killed(e.entity.uniqueId,e.entity.killer?.uniqueId) }
    @EventHandler(priority=org.bukkit.event.EventPriority.HIGHEST) fun playerDeath(e: PlayerDeathEvent) {
        val s = sessions.current(e.player.uniqueId) ?: return
        if(e.player.world.name!=s.run.worldName) return
        if(s.run.definition.keepInventory) DeathRetention.apply(e)
        if(s.run.definition.deathFails) sessions.failDeath(s,e.player.uniqueId)
    }
}
