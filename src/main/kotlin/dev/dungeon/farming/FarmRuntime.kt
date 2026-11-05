package dev.dungeon.farming

import dev.dungeon.integration.XerrEliteHud
import dev.dungeon.scheduling.Dispatch
import org.bukkit.Bukkit
import org.bukkit.GameMode
import org.bukkit.World
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.HandlerList
import org.bukkit.event.Listener
import org.bukkit.event.entity.EntityDeathEvent
import org.bukkit.event.player.PlayerQuitEvent
import org.bukkit.event.player.PlayerResourcePackStatusEvent
import org.bukkit.event.world.EntitiesLoadEvent
import org.bukkit.plugin.Plugin
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.logging.Level
import kotlin.math.sqrt

/** Owns snapshots, repeating work and HUD lifecycle for one permanent world. */
class FarmRuntime(private val plugin: Plugin,private val world: World,private val settings: FarmSettings,
    private val features: FarmFeatures,private val dispatch: Dispatch) : Listener,AutoCloseable {
    private val snapshots=ConcurrentHashMap<UUID,FarmPresence>()
    private val viewers=ConcurrentHashMap<UUID,Player>()
    private val pending=ConcurrentHashMap.newKeySet<UUID>()
    private val hudFailures=ConcurrentHashMap.newKeySet<String>()
    private val hud=XerrEliteHud(world.uid)
    private val spawning=NearbySpawns(plugin,world,settings,features.spawning,dispatch,::players)
    @Volatile private var closed=false
    private var ticks=0L
    init { Bukkit.getPluginManager().registerEvents(this,plugin);tick();tickHud() }
    private fun players(): List<FarmPresence> {
        val cutoff=System.nanoTime()-3_000_000_000L
        return snapshots.values.filter { it.at>=cutoff }
    }
    private fun tickHud() {
        if(closed || !dispatch.isActive) return
        Bukkit.getOnlinePlayers().forEach { player ->
            val id=player.uniqueId
            if(pending.add(id)) dispatch.entity(player,Runnable { snapshots.remove(id);viewers.remove(id);pending.remove(id) }) {
                try { update(player) } finally { pending.remove(id) }
            }
        }
        dispatch.later(2,::tickHud)
    }
    private fun tick() {
        if(closed || !dispatch.isActive) return
        val now=System.nanoTime()
        if(ticks%20L==0L) spawning.refresh(now)
        if(ticks%features.spawning.interval==0L) spawning.cycle(now)
        ticks+=10
        dispatch.later(10,::tick)
    }
    private fun update(player: Player) {
        if(closed) return
        val id=player.uniqueId
        if(!player.isOnline || player.isDead || player.world.uid!=world.uid || player.gameMode==GameMode.SPECTATOR || !player.hasPermission("dungeon.use")) {
            revoke(player)
            return
        }
        val p=player.location
        val snapshot=FarmPresence(id,p.x,p.y,p.z,p.yaw,System.nanoTime())
        snapshots[id]=snapshot;viewers[id]=player
        if(!features.hud.enabled || !player.hasResourcePack()) { clearHud(player);return }
        val nearest=spawning.ledger.records.values.asSequence().filter { it.mob.elite }.mapNotNull { record ->
            record.position?.takeIf { snapshot.at-it.at<3_000_000_000L }?.let { record to it }
        }.minByOrNull { (_,pos) -> snapshot.distanceSquared(pos.x,pos.y,pos.z) }
        if(nearest==null) { clearHud(player);return }
        val (record,pos)=nearest
        val distance=sqrt(snapshot.distanceSquared(pos.x,pos.y,pos.z))
        if(distance>features.hud.range) { clearHud(player);return }
        try {
            hud.render(player,features.hud.layout,features.hud.values(record.mob.name,distance.toInt(),SpawnGeometry.direction(pos.x-p.x,pos.z-p.z,p.yaw),SpawnGeometry.compassFrame(pos.x-p.x,pos.z-p.z,p.yaw)))
            hudFailures.clear()
        } catch(e: Exception) { report(e) }
        catch(e: LinkageError) { report(IllegalStateException("Xerr API linkage unsupported",e)) }
    }
    private fun clearHud(player: Player) {
        try { hud.remove(player) } catch(e: Exception) { report(e) }
        catch(e: LinkageError) { report(IllegalStateException("Xerr API linkage unsupported",e)) }
    }
    private fun report(e: Exception) {
        if(hudFailures.add(e.message ?: e.javaClass.name)) plugin.logger.log(Level.WARNING,"Elite HUD failed: world=${settings.world}, layout=${features.hud.layout}; spawning remains active",e)
    }
    /** Always clear our slot, even if local viewer tracking was lost during death/retirement/reload. */
    private fun revoke(player: Player) {
        snapshots.remove(player.uniqueId);viewers.remove(player.uniqueId);clearHud(player)
    }
    @EventHandler fun quit(e: PlayerQuitEvent) { revoke(e.player) }
    @EventHandler fun changed(e: org.bukkit.event.player.PlayerChangedWorldEvent) { revoke(e.player) }
    @EventHandler(priority=org.bukkit.event.EventPriority.MONITOR)
    fun respawn(e: org.bukkit.event.player.PlayerRespawnEvent) { revoke(e.player) }
    @EventHandler fun pack(e: PlayerResourcePackStatusEvent) { clearHud(e.player) }
    @EventHandler fun death(e: EntityDeathEvent) {
        if(e.entity is Player) revoke(e.entity as Player)
        if(e.entity.world.uid==world.uid) spawning.died(e.entity)
    }
    @EventHandler fun loaded(e: EntitiesLoadEvent) { if(e.world.uid==world.uid) e.entities.forEach(spawning::recover) }
    override fun close() {
        closed=true;HandlerList.unregisterAll(this)
        viewers.values.forEach { player ->
            if(Bukkit.isOwnedByCurrentRegion(player)) clearHud(player) else dispatch.entity(player) { clearHud(player) }
        }
        spawning.close();snapshots.clear();viewers.clear();pending.clear();hudFailures.clear()
    }
}
