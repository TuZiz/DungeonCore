package dev.dungeon.boss.battle

import dev.dungeon.integration.BetterModelBridge
import dev.dungeon.scheduling.Dispatch
import org.bukkit.Bukkit
import org.bukkit.entity.Mob
import org.bukkit.plugin.Plugin
import java.util.concurrent.atomic.AtomicBoolean

/** Owns only an unadmitted MM entity while its scheduled onSpawn skill attaches the model. */
class BossModelAwaiter(private val plugin: Plugin,private val entity: Mob,private val dispatch: Dispatch,
    private val bridge: BetterModelBridge,private val name: String,private val scale: Double,
    private val valid: ()->Boolean,private val ready: ()->Unit,private val failed: (Throwable)->Unit): AutoCloseable {
    private val finished=AtomicBoolean()
    @Volatile private var task: io.papermc.paper.threadedregions.scheduler.ScheduledTask?=null
    /** Entity owner only. Never blocks while MM schedules its onSpawn mechanic. */
    fun start() { entity.isInvulnerable=true;entity.isPersistent=false;poll(0) }
    private fun poll(attempt: Int) {
        if(finished.get()) return
        if(!valid() || !entity.isValid || entity.isDead) { fail(IllegalStateException("Boss model admission cancelled: entity=${entity.uniqueId}"));return }
        try {
            if(bridge.existingOrNull(name,entity,scale)!=null) {
                ready()
                finished.set(true)
                return
            }
            check(attempt<40) { "MythicMobs entity=${entity.uniqueId}: BetterModel '$name' onSpawn timed out after 40 ticks; check MM Skills" }
            task=entity.scheduler.runDelayed(plugin,{ poll(attempt+1) },{
                if(finished.compareAndSet(false,true)) failed(IllegalStateException("Boss entity retired during model admission: ${entity.uniqueId}"))
            },1)
        } catch(error: Exception) { fail(error) }
    }
    private fun fail(error: Throwable) {
        if(!finished.compareAndSet(false,true)) return
        if(entity.isValid) entity.remove()
        failed(error)
    }
    override fun close() {
        if(!finished.compareAndSet(false,true)) return
        task?.cancel()
        if(Bukkit.isOwnedByCurrentRegion(entity)) { if(entity.isValid) entity.remove() }
        else if(plugin.isEnabled) dispatch.entity(entity) { entity.remove() }
    }
}
