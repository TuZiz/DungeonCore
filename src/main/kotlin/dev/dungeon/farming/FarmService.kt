package dev.dungeon.farming

import dev.dungeon.integration.MythicMobsAdapter
import dev.dungeon.lobby.MapManifest
import dev.dungeon.lobby.PermanentMapInstaller
import dev.dungeon.localization.Messages
import dev.dungeon.scheduling.Dispatch
import dev.dungeon.session.Sessions
import dev.dungeon.world.WorldProvider
import org.bukkit.Bukkit
import org.bukkit.Location
import org.bukkit.World
import org.bukkit.entity.Player
import org.bukkit.plugin.Plugin
import java.nio.file.Path
import java.nio.file.Files
import java.util.logging.Level

/** One permanent shared farm. No stages, completion rewards, timer or per-player combat sessions. */
class FarmService(private val plugin: Plugin,val settings: FarmSettings,private val dispatch: Dispatch,
    private val provider: WorldProvider,private val messages: Messages,private val sessions: Sessions,
    private val features: FarmFeatures) : AutoCloseable {
    private var runtime: FarmRuntime?=null
    @Volatile private var world: World?=null
    @Volatile private var closed=false
    fun start(primary: Path,dataVersion: Int) {
        if(!settings.enabled) return
        dispatch.io {
            val folder=plugin.dataFolder.toPath().resolve("maps/${settings.id}")
            val manifest=Files.newBufferedReader(folder.resolve("manifest.yml")).use(MapManifest::read)
            manifest.requireCompatible(dataVersion)
            PermanentMapInstaller().install(primary,plugin.dataFolder.toPath().resolve("farming"),settings.world,manifest) {
                Files.newInputStream(folder.resolve("world.zip"))
            }
        }.whenComplete { _,e ->
            if(e!=null) failure(e) else dispatch.global {
                if(closed) return@global
                val loaded=Bukkit.getWorld(settings.world)
                val future=if(loaded!=null) java.util.concurrent.CompletableFuture.completedFuture(loaded) else provider.load(settings.world)
                future.whenComplete { w,error ->
                    if(error!=null) failure(error) else dispatch.global {
                        if(!closed) {
                            w.difficulty=org.bukkit.Difficulty.NORMAL
                            w.pvp=false
                            w.setSpawnLocation(settings.x.toInt(),settings.y.toInt(),settings.z.toInt())
                            world=w
                            runtime=FarmRuntime(plugin,w,settings,features,dispatch)
                            plugin.logger.info("Permanent farming ready: id=${settings.id}, world=${w.name}; no stages or completion rewards")
                        }
                    }
                }
            }
        }
    }
    private fun failure(e: Throwable) { plugin.logger.log(Level.SEVERE,"dungeons/${settings.id}/farming.yml world=${settings.world}: farm unavailable; map retained",e) }
    fun contains(player: Player)=settings.enabled && player.world.name==settings.world
    fun enter(player: Player) {
        dispatch.entity(player) {
            val w=world
            if(closed || w==null || !player.isOnline || !Bukkit.getPluginManager().isPluginEnabled("MythicMobs")) { messages.send(player,"unavailable");return@entity }
            if(!player.hasPermission("dungeon.use")) return@entity
            val trip=sessions.reserveLobbyTrip(player.uniqueId)
            if(trip==null) { messages.send(player,"in-dungeon");return@entity }
            try {
                player.teleportAsync(Location(w,settings.x,settings.y,settings.z,settings.yaw,settings.pitch)).whenComplete { ok,e ->
                    sessions.finishLobbyTrip(player.uniqueId,trip)
                    if(e!=null) failure(e)
                    if(!closed) messages.send(player,if(e==null && ok==true) "arrived" else "teleport-failed")
                }
            } catch(e: Exception) { sessions.finishLobbyTrip(player.uniqueId,trip);failure(e);messages.send(player,"teleport-failed") }
        }
    }
    override fun close() { closed=true;runtime?.close();runtime=null;world=null }
}
