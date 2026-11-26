package dev.dungeon.boss.battle

import dev.dungeon.boss.BossModule
import org.bukkit.Bukkit
import org.bukkit.entity.Player
import org.bukkit.entity.LivingEntity
import org.bukkit.entity.Mob
import org.bukkit.entity.Projectile
import org.bukkit.entity.Tameable
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import org.bukkit.event.entity.EntityDamageByEntityEvent
import org.bukkit.event.entity.EntityDamageEvent
import org.bukkit.event.entity.EntityDeathEvent
import org.bukkit.event.entity.EntityTargetEvent
import org.bukkit.event.world.EntitiesLoadEvent

/** Event handlers run on the victim/loaded entity owner. Only UUIDs cross entity boundaries. */
class BossListener(private val module: BossModule): Listener {
    @EventHandler(priority=EventPriority.HIGHEST,ignoreCancelled=true)
    fun guard(e: EntityDamageEvent) {
        val battle=module.forEntity(e.entity.uniqueId)
        if(battle!=null) {
            val source=e.damageSource
            val entityAttack=e is EntityDamageByEntityEvent || source.causingEntity!=null || source.directEntity!=null
            if(!battle.isDamageable() || e.cause in blockedTerrainDamage || (entityAttack && owner(e)==null)) {
                e.isCancelled=true
            }
        } else if(e.entity is LivingEntity && e.entity !is Player &&
            e.damageSource.causingEntity?.uniqueId?.let(module::forEntity)!=null) {
            e.isCancelled=true
        }
    }
    @EventHandler(priority=EventPriority.HIGHEST,ignoreCancelled=true)
    fun target(e: EntityTargetEvent) {
        val mob=e.entity as? Mob ?: return
        val target=e.target ?: return
        // The encounter selects players from snapshots and never needs a vanilla mob target.
        if(module.forEntity(mob.uniqueId)!=null || module.forEntity(target.uniqueId)!=null) e.isCancelled=true
    }
    @EventHandler(priority=EventPriority.MONITOR,ignoreCancelled=true)
    fun damage(e: EntityDamageEvent) {
        val battle=module.forEntity(e.entity.uniqueId) ?: return
        if(battle.isDamageable()) battle.beforeDamage(owner(e),e.finalDamage)
    }
    @EventHandler(priority=EventPriority.HIGHEST,ignoreCancelled=true)
    fun knockback(e: io.papermc.paper.event.entity.EntityKnockbackEvent) {
        val battle=module.forEntity(e.entity.uniqueId) ?: return
        val vector=battle.knockback(e.knockback)
        if(vector==null) e.isCancelled=true else e.knockback=vector
    }
    private fun owner(e: EntityDamageEvent): java.util.UUID? {
        (e.damageSource.causingEntity as? Player)?.let { return it.uniqueId }
        val damager=(e as? EntityDamageByEntityEvent)?.damager ?: return null
        if(damager is Player) return damager.uniqueId
        if(!Bukkit.isOwnedByCurrentRegion(damager)) return null
        if(damager is Projectile) (damager.shooter as? Player)?.let { return it.uniqueId }
        if(damager is Tameable) return damager.owner?.uniqueId
        return null
    }
    @EventHandler(priority=EventPriority.HIGHEST)
    fun drops(e: EntityDeathEvent) {
        if(module.forEntity(e.entity.uniqueId)!=null) { e.drops.clear();e.droppedExp=0 }
    }
    @EventHandler(priority=EventPriority.MONITOR,ignoreCancelled=true)
    fun death(e: EntityDeathEvent) { module.forEntity(e.entity.uniqueId)?.died() }
    @EventHandler fun load(e: EntitiesLoadEvent) { e.entities.forEach(module::recover) }
    @EventHandler fun dependency(e: org.bukkit.event.server.PluginDisableEvent) {
        if(e.plugin.name in setOf("BetterModel","MythicMobs","CraftEngine")) module.dependencyStopped()
    }
    private companion object {
        val blockedTerrainDamage=setOf(EntityDamageEvent.DamageCause.FALL,EntityDamageEvent.DamageCause.FIRE,
            EntityDamageEvent.DamageCause.FIRE_TICK,EntityDamageEvent.DamageCause.SUFFOCATION,EntityDamageEvent.DamageCause.DROWNING)
    }
}
