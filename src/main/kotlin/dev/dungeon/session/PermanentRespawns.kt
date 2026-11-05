package dev.dungeon.session

import dev.dungeon.farming.FarmSettings
import dev.dungeon.model.Definition
import org.bukkit.Bukkit
import org.bukkit.Location
import org.bukkit.NamespacedKey
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import org.bukkit.event.entity.PlayerDeathEvent
import org.bukkit.event.player.PlayerRespawnEvent
import org.bukkit.persistence.PersistentDataType
import org.bukkit.plugin.Plugin

/** Death/respawn events own the player. Only change the event destination, never access destination blocks. */
class PermanentRespawns(private val plugin: Plugin,private val farm: FarmSettings,
    private val definition: (Player) -> Definition?) : Listener {
    private val key=NamespacedKey(plugin,"permanent_death_spawn")
    @EventHandler(priority=EventPriority.LOWEST,ignoreCancelled=true)
    fun death(event: PlayerDeathEvent) {
        val player=event.player
        val target=PermanentRespawnPoint.forDeath(farm,definition(player),player.world.name,player.world.uid)
        if(target==null) player.persistentDataContainer.remove(key)
        else player.persistentDataContainer.set(key,PersistentDataType.STRING,target.encode())
    }
    @EventHandler(priority=EventPriority.MONITOR)
    fun cancelledDeath(event: PlayerDeathEvent) {
        if(event.isCancelled) event.player.persistentDataContainer.remove(key)
    }
    @EventHandler(priority=EventPriority.HIGHEST)
    fun respawn(event: PlayerRespawnEvent) {
        if(event.respawnReason==PlayerRespawnEvent.RespawnReason.END_PORTAL) return
        val data=event.player.persistentDataContainer
        val raw=data.get(key,PersistentDataType.STRING) ?: return
        data.remove(key) // A death record is consumed once; it must never affect future vanilla respawns.
        try {
            val target=PermanentRespawnPoint.decode(raw)
            val world=Bukkit.getWorld(target.world)
            if(world==null) {
                plugin.logger.warning("Permanent respawn world unavailable: player=${event.player.uniqueId} world=${target.world}; vanilla destination retained")
                return
            }
            event.respawnLocation=Location(world,target.x,target.y,target.z,target.yaw,target.pitch)
        } catch(e: IllegalArgumentException) {
            plugin.logger.log(java.util.logging.Level.WARNING,"Invalid permanent death spawn record: player=${event.player.uniqueId}",e)
        }
    }
}
