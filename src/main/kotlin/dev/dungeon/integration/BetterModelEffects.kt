package dev.dungeon.integration

import dev.dungeon.farming.FarmPresence
import dev.dungeon.scheduling.Dispatch
import org.bukkit.Bukkit
import org.bukkit.Location
import org.bukkit.entity.Player
import org.bukkit.plugin.Plugin
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import java.util.logging.Level

/** Short-lived BetterModel trackers, including warning geometry; no particles or armor-stand substitutes. */
class BetterModelEffects(private val plugin: Plugin,private val bridge: BetterModelBridge,private val dispatch: Dispatch,
    private val players: ()->List<FarmPresence>,private val player: (UUID)->Player?) : AutoCloseable {
    private val models=ConcurrentHashMap.newKeySet<BetterModelBridge.Model>()
    private val tasks=ConcurrentHashMap.newKeySet<io.papermc.paper.threadedregions.scheduler.ScheduledTask>()
    private val generation=AtomicLong()
    @Volatile private var closed=false
    /** Entity owner hands the live tracker over for bounded corpse cleanup. No replacement model. */
    fun finish(model: BetterModelBridge.Model,at: Location,animation: String,ticks: Long) {
        if(closed) { model.close();return }
        val token=generation.get()
        models.add(model)
        try {
            if(closed || generation.get()!=token) { remove(model);return }
            model.die(animation)
            val task=Bukkit.getRegionScheduler().runDelayed(plugin,at,{ task -> tasks.remove(task);remove(model) },ticks)
            tasks.add(task)
            if(closed || generation.get()!=token) { task.cancel();tasks.remove(task);remove(model) }
        } catch(error: Exception) { remove(model);throw error }
    }
    /** An attack center may cross the boss's region boundary; create it on that location's owner. */
    fun show(model: String,at: Location,scale: Double,animation: String,ticks: Long,loop: Boolean=false,onFailure: (() -> Unit)?=null): AutoCloseable {
        check(!closed)
        val point=at.clone()
        val token=generation.get()
        val cancelled=AtomicBoolean()
        val handle=AtomicReference<BetterModelBridge.Model?>()
        fun active()=!closed && !cancelled.get() && generation.get()==token
        val open={
            if(active()) {
                var tracker: BetterModelBridge.Model?=null
                try {
                    val created=bridge.effect(model,point,scale);tracker=created
                    models.add(created);handle.set(created)
                    if(!active()) remove(created)
                    else {
                        created.play(animation,!loop)
                        players().filter { it.distanceSquared(point.x,point.y,point.z)<=96.0*96 }.forEach { snapshot ->
                            player(snapshot.id)?.let { viewer -> dispatch.entity(viewer) {
                                if(active() && viewer.isOnline && viewer.world.uid==point.world.uid && viewer.location.distanceSquared(point)<=96.0*96) created.show(viewer)
                            } }
                        }
                        tasks.add(Bukkit.getRegionScheduler().runDelayed(plugin,point,{ task -> tasks.remove(task);remove(created) },ticks))
                    }
                } catch(e: Exception) {
                    tracker?.let(::remove)
                    if(active()) { plugin.logger.log(Level.SEVERE,"Boss visual model=$model animation=$animation location=$point failed",e);onFailure?.invoke() }
                }
            }
        }
        if(Bukkit.isOwnedByCurrentRegion(point.world,point.blockX shr 4,point.blockZ shr 4)) open() else dispatch.region(point,open)
        return AutoCloseable { cancelled.set(true);handle.getAndSet(null)?.let(::remove) }
    }
    private fun remove(model: BetterModelBridge.Model) { if(models.remove(model)) model.close() }
    fun clear() { generation.incrementAndGet();tasks.forEach { it.cancel() };tasks.clear();models.toList().forEach(::remove) }
    override fun close() { closed=true;clear() }
}
