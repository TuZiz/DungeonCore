package dev.dungeon.farming

import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import org.bukkit.event.entity.CreatureSpawnEvent
import org.bukkit.event.entity.EntityDamageEvent

/** Event location/victim belongs to the current region; never inspects another entity or chunk. */
class FarmSafeZone(private val farm: FarmSettings, private val radius: Double,
    private val bounds: SafeZoneBounds? = null, private val notify: (Player,Boolean)->Unit = { _,_ -> }) : Listener,AutoCloseable {
    private val protected=java.util.concurrent.ConcurrentHashMap.newKeySet<java.util.UUID>()
    private fun track(player: Player,at: org.bukkit.Location) {
        if(inside(at)) protected.add(player.uniqueId) else protected.remove(player.uniqueId)
    }
    /** Updated only from player-owned movement/lifecycle events; damage may occur on another region. */
    private fun safe(player: Player)=player.uniqueId in protected
    fun observe(player: Player) { track(player,player.location) }
    override fun close() { org.bukkit.event.HandlerList.unregisterAll(this);protected.clear() }

    @EventHandler(priority=EventPriority.HIGHEST,ignoreCancelled=true)
    fun attack(event: org.bukkit.event.entity.EntityDamageByEntityEvent) {
        val source=event.damageSource.causingEntity
        val player=source as? Player ?: event.damager as? Player
            ?: (event.damager as? org.bukkit.entity.Projectile)?.shooter as? Player
        if(player!=null && safe(player)) event.isCancelled=true
        // Decorations are map data, not combat targets (handled by FarmInteractionProtection).
    }
    @EventHandler(priority=EventPriority.HIGHEST,ignoreCancelled=true)
    fun launch(event: org.bukkit.event.entity.ProjectileLaunchEvent) {
        val projectile=event.entity
        if(projectile is org.bukkit.entity.EnderPearl || projectile is org.bukkit.entity.FishHook) return
        val shooter=projectile.shooter as? Player
        if(inside(projectile.location) || shooter!=null && safe(shooter)) event.isCancelled=true
    }
    @EventHandler(priority=EventPriority.MONITOR)
    fun quit(event: org.bukkit.event.player.PlayerQuitEvent) { protected.remove(event.player.uniqueId) }
    @EventHandler(priority=EventPriority.MONITOR)
    fun changed(event: org.bukkit.event.player.PlayerChangedWorldEvent) { track(event.player,event.player.location) }
    fun contains(world: String, x: Double, y: Double, z: Double): Boolean {
        if(!farm.enabled || world!=farm.world) return false
        bounds?.let { return it.contains(x,y,z) }
        if(radius<=0) return false
        val dx=x-farm.x;val dz=z-farm.z
        return dx*dx+dz*dz<radius*radius
    }

    @EventHandler(priority=EventPriority.HIGHEST,ignoreCancelled=true)
    fun spawn(event: CreatureSpawnEvent) {
        val at=event.location
        if(contains(at.world.name,at.x,at.y,at.z)) event.isCancelled=true
    }

    @EventHandler(priority=EventPriority.HIGHEST,ignoreCancelled=true)
    fun damage(event: EntityDamageEvent) {
        val player=event.entity as? Player ?: return
        val at=player.location
        if(contains(at.world.name,at.x,at.y,at.z)) event.isCancelled=true
    }

    private fun inside(at: org.bukkit.Location)=contains(at.world.name,at.x,at.y,at.z)
    private fun crossing(player: Player,from: org.bukkit.Location,to: org.bukkit.Location) {
        val was=inside(from);val now=inside(to)
        track(player,to)
        if(was!=now) notify(player,now)
    }
    @EventHandler(priority=EventPriority.MONITOR,ignoreCancelled=true)
    fun move(event: org.bukkit.event.player.PlayerMoveEvent) {
        if(event.hasChangedPosition()) crossing(event.player,event.from,event.to)
    }
    @EventHandler(priority=EventPriority.MONITOR,ignoreCancelled=true)
    fun teleport(event: org.bukkit.event.player.PlayerTeleportEvent) { crossing(event.player,event.from,event.to) }
    @EventHandler(priority=EventPriority.MONITOR)
    fun respawn(event: com.destroystokyo.paper.event.player.PlayerPostRespawnEvent) {
        track(event.player,event.player.location)
        if(inside(event.player.location)) notify(event.player,true)
    }
    @EventHandler(priority=EventPriority.MONITOR)
    fun join(event: org.bukkit.event.player.PlayerJoinEvent) {
        track(event.player,event.player.location)
        if(inside(event.player.location)) notify(event.player,true)
    }
}
