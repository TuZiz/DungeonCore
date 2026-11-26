package dev.dungeon.elite

import dev.dungeon.farming.FarmMob
import dev.dungeon.farming.FarmPresence
import dev.dungeon.integration.BetterModelBridge
import dev.dungeon.integration.BetterModelEffects
import dev.dungeon.integration.MythicMobsAdapter
import dev.dungeon.scheduling.Dispatch
import org.bukkit.Bukkit
import org.bukkit.entity.Entity
import org.bukkit.entity.Mob
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.HandlerList
import org.bukkit.event.Listener
import org.bukkit.event.entity.EntityDeathEvent
import org.bukkit.event.server.PluginDisableEvent
import org.bukkit.plugin.Plugin
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.logging.Level

/** Optional model readiness and lifecycle; ordinary mobs never depend on this module. */
class ModeledElites(private val plugin: Plugin,private val settings: EliteSettings,private val dispatch: Dispatch,
    private val players: () -> List<FarmPresence>,private val player: (UUID) -> Player?,
    private val safe: (Double,Double,Double) -> Boolean): Listener,AutoCloseable {
    private val active=ConcurrentHashMap<UUID,EliteCombat>()
    private val prepared=ConcurrentHashMap.newKeySet<String>()
    private val errors=ConcurrentHashMap<String,String>()
    @Volatile private var bridge: BetterModelBridge?=null
    @Volatile private var effects: BetterModelEffects?=null
    @Volatile private var dependency: Plugin?=null
    @Volatile private var closed=false
    init { Bukkit.getPluginManager().registerEvents(this,plugin) }
    /** Global scheduler only; exact IDs/animations are checked before random selection. */
    fun ready(mob: FarmMob): Boolean {
        if(closed || !settings.enabled) return false
        return try {
            val s=requireNotNull(settings.mobs[mob.id]) { "elites.yml:mobs.${mob.id} missing" }
            require(s.model==mob.model && mob.elite) { "spawning.yml:mobs.${mob.id} model/elite does not match elites.yml" }
            val bm=requireNotNull(Bukkit.getPluginManager().getPlugin("BetterModel")?.takeIf { it.isEnabled }) { "BetterModel unavailable" }
            if(dependency!==bm) {
                bridge=BetterModelBridge(bm);effects?.close()
                effects=BetterModelEffects(plugin,requireNotNull(bridge),dispatch,players,player);dependency=bm
            }
            requireNotNull(bridge).validateAnimations(s.model,setOf("idle",s.birth,s.death)+s.attacks.map { it.animation })
            MythicMobsAdapter().validate(setOf(mob.id))
            if(prepared.add(mob.id)) plugin.logger.info("Modeled elite ready: mob=${mob.id} model=${mob.model}; rooted attacks, region-owned hits and existing MM elite drops")
            errors.remove(mob.id);true
        } catch(error: Exception) { unavailable(mob,error);false }
        catch(error: LinkageError) { unavailable(mob,error);false }
    }
    private fun unavailable(mob: FarmMob,error: Throwable) {
        prepared.remove(mob.id)
        val key=error.message ?: error.javaClass.name
        if(errors.put(mob.id,key)!=key) plugin.logger.log(Level.WARNING,"dungeons/lost_altar/elites.yml mob=${mob.id}: modeled elite skipped; ordinary spawning retained",error)
    }
    /** Spawn location's owner only. Reject a lost dependency before exposing a vanilla fallback. */
    fun attach(mob: FarmMob,entity: Entity) {
        check(!closed && prepared.contains(mob.id) && dependency?.isEnabled==true) { "Modeled elite ${mob.id} not ready" }
        val s=requireNotNull(settings.mobs[mob.id])
        val combat=EliteCombat(plugin,entity as? Mob ?: error("Modeled elite ${mob.id} must be a Mob"),s,dispatch,
            requireNotNull(bridge),requireNotNull(effects),players,player,safe) { active.remove(it) }
        active[entity.uniqueId]=combat
        try { combat.start();if(closed || dependency?.isEnabled!=true) combat.close() }
        catch(error: Exception) { combat.close();throw error }
    }
    fun forget(entity: Entity) { active[entity.uniqueId]?.close() }
    @EventHandler(priority=EventPriority.MONITOR,ignoreCancelled=true)
    fun died(event: EntityDeathEvent) { active[event.entity.uniqueId]?.died() }
    @EventHandler fun disabled(event: PluginDisableEvent) {
        if(event.plugin.name in setOf("BetterModel","MythicMobs")) stop()
    }
    private fun stop() {
        prepared.clear();active.values.toList().forEach { it.close() };active.clear()
        effects?.close();effects=null;bridge=null;dependency=null
    }
    override fun close() { closed=true;HandlerList.unregisterAll(this);stop();errors.clear() }
}
