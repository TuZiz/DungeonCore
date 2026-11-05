package dev.dungeon.farming

import dev.dungeon.integration.MythicMobsAdapter
import dev.dungeon.scheduling.Dispatch
import org.bukkit.Bukkit
import org.bukkit.Location
import org.bukkit.NamespacedKey
import org.bukkit.World
import org.bukkit.entity.Entity
import org.bukkit.persistence.PersistentDataType
import org.bukkit.plugin.Plugin
import java.util.concurrent.ThreadLocalRandom
import java.util.logging.Level
import kotlin.math.*

/** Global tick consumes immutable player/entity snapshots; all block reads and spawns run on their region. */
class NearbySpawns(private val plugin: Plugin, private val world: World, private val farm: FarmSettings,
    val settings: SpawnSettings, private val dispatch: Dispatch, private val players: () -> List<FarmPresence>) : AutoCloseable {
    val ledger=SpawnLedger(settings)
    private val mm=MythicMobsAdapter()
    private val marker=NamespacedKey(plugin,"farm_mob")
    @Volatile private var closed=false
    private var ready=false
    private var lastError: String?=null
    private var warnedSpawners=false

    fun cycle(now: Long) {
        if(closed || !settings.enabled) return
        if(!Bukkit.getPluginManager().isPluginEnabled("MythicMobs")) {
            ready=false;failure(IllegalStateException("MythicMobs unavailable; nearby spawning paused"));return
        }
        if(!ready) try {
            mm.validate(settings.mobs.map { it.id }.toSet());ready=true;lastError=null
            plugin.logger.info("Nearby spawning ready: world=${farm.world}, per-player=${settings.playerLimit}, nearby=${settings.nearbyLimit}; MM spawners must be disabled")
        } catch(e: Exception) { failure(e);return }
        try {
            if(mm.suspendFarmSpawners(farm.world,farm.id)>0 && !warnedSpawners) {
                warnedSpawners=true
                plugin.logger.warning("Disabled legacy MythicMobs spawners: world=${farm.world}, group=${farm.id}; Dungeon owns spawning")
            }
        } catch(e: Exception) { failure(e);return }
        players().shuffled().forEach { player -> repeat(settings.attempts) { attempt(player,now) } }
    }
    private fun attempt(player: FarmPresence,now: Long) {
        val random=ThreadLocalRandom.current()
        var draw=random.nextInt(settings.mobs.sumOf { it.weight })
        val mob=settings.mobs.first { draw-=it.weight;draw<0 }
        val (x,z)=SpawnGeometry.candidate(player,settings.minRadius,settings.maxRadius,random.nextDouble(),random.nextDouble())
        val loc=Location(world,x+0.5,player.y,z+0.5)
        val record=ledger.reserve(player.copy(x=loc.x,z=loc.z),mob,now) ?: return
        dispatch.region(loc) {
            try {
                val current=players().firstOrNull { it.id==player.id }
                if(closed || current==null || !world.isChunkLoaded(x shr 4,z shr 4) ||
                    current.distanceSquared(loc.x,current.y,loc.z)>settings.maxRadius.pow(2) ||
                    current.distanceSquared(loc.x,current.y,loc.z)<settings.minRadius.pow(2) ||
                    (loc.x-farm.x).pow(2)+(loc.z-farm.z).pow(2)<settings.safeRadius.pow(2)) return@region
                val y=safeY(x,z,current.y) ?: return@region
                loc.y=y.toDouble()
                if(players().any { it.distanceSquared(loc.x,loc.y,loc.z)<settings.separation.pow(2) }) return@region
                // Recover only our own tagged entities left by an interrupted hot-disable in this loaded chunk.
                world.getChunkAt(x shr 4,z shr 4).entities.forEach(::recover)
                val entity=mm.spawn(mob.id,loc)
                entity.isPersistent=false
                entity.persistentDataContainer.set(marker,PersistentDataType.STRING,record.token.toString())
                record.position=FarmPresence(entity.uniqueId,loc.x,loc.y,loc.z,0f,now)
                record.entity=entity
                if(closed) { entity.remove();ledger.remove(record) }
            } catch(e: Exception) { failure(e) }
            finally { if(record.entity==null) ledger.remove(record) }
        }
    }
    private fun safeY(x: Int,z: Int,playerY: Double): Int? {
        // Avoid other chunks entirely: every candidate is a vertical column owned by this region.
        for(y in SpawnGeometry.heights(playerY,settings.vertical,settings.maxAbove,settings.maxBelow)) {
            if(y<=world.minHeight || y+1>=world.maxHeight) continue
            val floor=world.getBlockAt(x,y-1,z)
            if(!floor.type.isSolid || floor.isLiquid || floor.type.name in HAZARDS || floor.boundingBox.maxY<y-0.01) continue
            val feet=world.getBlockAt(x,y,z);val head=world.getBlockAt(x,y+1,z)
            if(feet.isPassable && head.isPassable && !feet.isLiquid && !head.isLiquid &&
                feet.type.name !in HAZARDS && head.type.name !in HAZARDS) return y
        }
        return null
    }
    fun refresh(now: Long) {
        ledger.records.values.forEach { record ->
            val entity=record.entity
            if(entity==null) return@forEach
            if(!record.polling.compareAndSet(false,true)) return@forEach
            dispatch.entity(entity,Runnable { ledger.remove(record);record.polling.set(false) }) {
                try {
                    if(closed || entity.isDead || entity.world.uid!=world.uid) { entity.remove();ledger.remove(record);return@entity }
                    val p=entity.location
                    record.position=FarmPresence(entity.uniqueId,p.x,p.y,p.z,0f,now)
                    if(players().any { it.distanceSquared(p.x,p.y,p.z)<=settings.despawnRadius.pow(2) }) record.lastNear=now
                    else if(now-record.lastNear>=settings.despawnSeconds*1_000_000_000L) { entity.remove();ledger.remove(record) }
                } finally { record.polling.set(false) }
            }
        }
    }
    /** Called from the loaded entity's region event; clears leftover owned mobs after reload/restart. */
    fun recover(entity: Entity) {
        val value=entity.persistentDataContainer.get(marker,PersistentDataType.STRING) ?: return
        if(ledger.records.values.none { it.token.toString()==value }) entity.remove()
    }
    fun died(entity: Entity) { ledger.records.values.firstOrNull { it.entity?.uniqueId==entity.uniqueId }?.let(ledger::remove) }
    private fun failure(e: Exception) {
        val key=e.message ?: e.javaClass.name
        if(lastError!=key) plugin.logger.log(Level.WARNING,"dungeons/${farm.id}/spawning.yml world=${farm.world}: nearby spawn failed",e)
        lastError=key
    }
    override fun close() {
        closed=true
        ledger.records.values.forEach { record -> record.entity?.let { entity ->
            if(Bukkit.isOwnedByCurrentRegion(entity)) entity.remove()
            else dispatch.entity(entity) { entity.remove() }
        } }
        // Mobs are nonpersistent and marked for EntitiesLoadEvent recovery; no world unload/delete.
        ledger.records.clear()
    }
    companion object {
        private val HAZARDS=setOf("LAVA","FIRE","SOUL_FIRE","CAMPFIRE","SOUL_CAMPFIRE","CACTUS","MAGMA_BLOCK","POWDER_SNOW","SWEET_BERRY_BUSH","WITHER_ROSE","POINTED_DRIPSTONE")
    }
}
