package dev.dungeon.boss.battle

import dev.dungeon.scheduling.Dispatch
import org.bukkit.Bukkit
import org.bukkit.Location
import org.bukkit.World
import org.bukkit.plugin.Plugin
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/** Pins only the 3x3 arena chunks; async load then region-owned ticket changes. Never unloads the world. */
class BossArena(private val plugin: Plugin,private val world: World,private val dispatch: Dispatch): AutoCloseable {
    private val tickets=ConcurrentHashMap.newKeySet<Pair<Int,Int>>()
    private val generation=AtomicLong()
    private val regionThreaded=runCatching { Class.forName("io.papermc.paper.threadedregions.RegionizedServer",false,plugin.server.javaClass.classLoader);true }.getOrDefault(false)
    @Volatile private var closed=false
    fun acquire(at: Location): CompletableFuture<Unit> {
        val token=generation.incrementAndGet()
        val cx=at.blockX shr 4;val cz=at.blockZ shr 4
        val futures=(-1..1).flatMap { dx -> (-1..1).map { dz ->
            val x=cx+dx;val z=cz+dz;val f=CompletableFuture<Unit>()
            world.getChunkAtAsync(x,z,true).whenComplete { chunk,e ->
                if(e!=null) f.completeExceptionally(e)
                else if(closed || !dispatch.isActive || generation.get()!=token) f.completeExceptionally(IllegalStateException("Boss arena closed or released"))
                else dispatch.region(Location(world,(x*16).toDouble(),at.y,(z*16).toDouble())) {
                    if(closed || generation.get()!=token) f.completeExceptionally(IllegalStateException("Boss arena closed or released"))
                    else try {
                        // Folia has no safe cross-region shutdown hook for our tickets. Keep no
                        // permanent ticket there; the owning entity's retirement aborts the battle.
                        if(!regionThreaded) { chunk.addPluginChunkTicket(plugin);tickets.add(x to z) }
                        f.complete(Unit)
                    } catch(error: Exception) { f.completeExceptionally(error) }
                }
            };f
        } }
        return CompletableFuture.allOf(*futures.toTypedArray()).thenApply { Unit }
    }
    fun release() {
        generation.incrementAndGet()
        val held=tickets.toList();tickets.removeAll(held.toSet())
        held.forEach { (x,z) ->
            if(Bukkit.isOwnedByCurrentRegion(world,x,z)) world.removePluginChunkTicket(x,z,plugin)
            else if(plugin.isEnabled) dispatch.region(Location(world,(x*16).toDouble(),0.0,(z*16).toDouble())) { world.removePluginChunkTicket(x,z,plugin) }
        }
    }
    override fun close() { closed=true;release() }
}
