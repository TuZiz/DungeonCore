package dev.dungeon.lobby

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
import java.util.logging.Level

/** Permanent world reference only. This service has no unload or delete path. */
class LobbyService(private val plugin: Plugin, val settings: LobbySettings,
    private val dispatch: Dispatch, private val provider: WorldProvider, private val messages: Messages,
    private val sessions: Sessions) : AutoCloseable {
    @Volatile private var world: World? = null
    @Volatile private var closed = false

    fun start(primaryWorldFolder: Path, serverDataVersion: Int) {
        if(!settings.enabled) return
        // Some forks migrate a permanent world's region files under their native dimension layout.
        // Look up the loaded world before import so subsequent boots never overwrite migrated chunks.
        dispatch.global {
            if(closed) return@global
            Bukkit.getWorld(settings.world)?.let { ready(it); return@global }
            importAndLoad(primaryWorldFolder,serverDataVersion)
        }
    }
    private fun importAndLoad(primaryWorldFolder: Path, serverDataVersion: Int) {
        dispatch.io {
            val manifest = requireNotNull(plugin.getResource("maps/starfall/manifest.yml")).reader(Charsets.UTF_8).use(MapManifest::read)
            manifest.requireCompatible(serverDataVersion)
            if(settings.importMap) PermanentMapInstaller().install(primaryWorldFolder,plugin.dataFolder.toPath().resolve("lobby"),settings.world,manifest) {
                requireNotNull(plugin.getResource("maps/starfall/world.zip"))
            }
            else require(java.nio.file.Files.isDirectory(primaryWorldFolder.resolve("dimensions/minecraft/${settings.world.lowercase()}/region"))) {
                "lobby.yml world=${settings.world}: registered dimension data not found; no empty world will be generated"
            }
        }.whenComplete { _,error ->
            if(error != null) unavailable(error)
            else dispatch.global {
                if(closed) return@global
                val loaded = Bukkit.getWorld(settings.world)
                if(loaded != null) ready(loaded)
                else if(!settings.autoLoad || !provider.supportsPermanentLoading) unavailable(IllegalStateException(
                    "lobby.yml world=${settings.world} imported but not loaded; provider=${provider.name}. Load externally before starting Dungeon; permanent files retained"))
                else provider.load(settings.world).whenComplete { value,e ->
                    if(e != null) unavailable(e) else dispatch.global { if(!closed) ready(value) }
                }
            }
        }
    }
    private fun ready(value: World) {
        check(value.name == settings.world)
        require(settings.y >= value.minHeight && settings.y < value.maxHeight-1) { "lobby.yml spawn.y outside world height" }
        world = value
        plugin.logger.info("Permanent lobby ready: world=${value.name}, spawn=${settings.x},${settings.y},${settings.z}; excluded from instance cleanup")
    }
    private fun unavailable(error: Throwable) {
        plugin.logger.log(Level.SEVERE,"Permanent lobby unavailable: lobby.yml world=${settings.world}; original map retained",error)
    }
    fun enter(player: Player) {
        dispatch.entity(player) {
            val destination = world
            if(closed || destination == null || !settings.enabled || !player.isOnline) {
                messages.send(player,"unavailable");return@entity
            }
            if(!player.hasPermission("dungeon.use")) return@entity
            val trip = sessions.reserveLobbyTrip(player.uniqueId)
            if(trip == null) { messages.send(player,"in-dungeon");return@entity }
            try {
                player.teleportAsync(Location(destination,settings.x,settings.y,settings.z,settings.yaw,settings.pitch)).whenComplete { ok,e ->
                    sessions.finishLobbyTrip(player.uniqueId,trip)
                    if(e != null) plugin.logger.log(Level.WARNING,"Lobby teleport failed: player=${player.uniqueId} world=${settings.world}",e)
                    if(!closed) messages.send(player,if(e == null && ok == true) "arrived" else "teleport-failed")
                }
            } catch(e: Exception) {
                sessions.finishLobbyTrip(player.uniqueId,trip)
                plugin.logger.log(Level.WARNING,"Lobby teleport failed: player=${player.uniqueId} world=${settings.world}",e)
                messages.send(player,"teleport-failed")
            }
        }
    }
    override fun close() { closed = true;world = null }
}
