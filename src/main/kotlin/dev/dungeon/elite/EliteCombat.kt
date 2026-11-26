package dev.dungeon.elite

import dev.dungeon.farming.FarmPresence
import dev.dungeon.integration.BetterModelBridge
import dev.dungeon.integration.BetterModelEffects
import dev.dungeon.scheduling.Dispatch
import org.bukkit.Bukkit
import org.bukkit.FluidCollisionMode
import org.bukkit.damage.DamageSource
import org.bukkit.damage.DamageType
import org.bukkit.entity.Mob
import org.bukkit.entity.Player
import org.bukkit.plugin.Plugin
import org.bukkit.potion.PotionEffect
import org.bukkit.potion.PotionEffectType
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import java.util.logging.Level
import kotlin.math.abs
import kotlin.math.atan2

/** One rooted plant. Mutable battle state belongs exclusively to this entity's scheduler. */
class EliteCombat(private val plugin: Plugin,val entity: Mob,private val settings: ModeledElite,
    private val dispatch: Dispatch,bridge: BetterModelBridge,private val effects: BetterModelEffects,
    private val players: () -> List<FarmPresence>,private val player: (UUID) -> Player?,
    private val safe: (Double,Double,Double) -> Boolean,private val removed: (UUID) -> Unit): AutoCloseable {
    private val id=entity.uniqueId
    private val ended=AtomicBoolean()
    private val model=bridge.attach(settings.model,entity,settings.scale)
    private var task: io.papermc.paper.threadedregions.scheduler.ScheduledTask?=null
    private var tick=0L
    private val next=LongArray(settings.attacks.size)
    private var cast: Cast?=null
    private var restUntil=0L
    @Volatile private var revision=0
    private data class Cast(val attack: EliteAttack,val impact: Long,val end: Long,val hit: Boolean=false)
    fun start() {
        entity.setAI(false);entity.isInvulnerable=true
        model.play("idle",false);model.play(settings.birth)
        task=entity.scheduler.runAtFixedRate(plugin,{ _ ->
            try { step() } catch(error: Exception) {
                plugin.logger.log(Level.SEVERE,"Modeled elite mob=${settings.id} entity=$id battle failed; removing owned mob",error);close()
            }
        },{ retire() },1,2)
    }
    private fun step() {
        if(ended.get()) return
        if(!entity.isValid || entity.isDead) { retire();return }
        tick+=2
        if(tick<settings.birthTicks) return
        entity.isInvulnerable=false
        val at=entity.location
        if(safe(at.x,at.y,at.z)) { close();return }
        val casting=cast
        if(casting!=null) {
            if(!casting.hit && tick>=casting.impact) { strike(casting.attack);cast=casting.copy(hit=true) }
            if(tick>=casting.end) { cast=null;restUntil=tick+14 }
            return
        }
        if(tick<restUntil) return
        val target=players().filter { it.combat && abs(it.y-at.y)<=3 && !safe(it.x,it.y,it.z) && it.distanceSquared(at.x,at.y,at.z)<=settings.aggro*settings.aggro }
            .minByOrNull { it.distanceSquared(at.x,at.y,at.z) } ?: return
        entity.setRotation(Math.toDegrees(atan2(at.x-target.x,target.z-at.z)).toFloat(),0f)
        val index=settings.attacks.indices.firstOrNull { i -> tick>=next[i] && target.distanceSquared(at.x,at.y,at.z)<=settings.attacks[i].range*settings.attacks[i].range } ?: return
        val attack=settings.attacks[index]
        next[index]=tick+attack.cooldown
        model.play(attack.animation)
        cast=Cast(attack,tick+attack.windup,tick+attack.duration)
    }
    private fun strike(attack: EliteAttack) {
        val at=entity.location.clone();val token=revision
        val source=DamageSource.builder(DamageType.MOB_ATTACK).withDamageLocation(at).build()
        players().filter { it.combat && it.distanceSquared(at.x,at.y,at.z)<=attack.range*attack.range }.forEach { snapshot ->
            player(snapshot.id)?.let { target -> dispatch.entity(target) {
                if(ended.get() || token!=revision || !target.isOnline || target.isDead || target.world.uid!=at.world.uid || !target.hasPermission("dungeon.use")) return@entity
                if(target.gameMode!=org.bukkit.GameMode.SURVIVAL && target.gameMode!=org.bukkit.GameMode.ADVENTURE) return@entity
                val p=target.location
                if(abs(p.y-at.y)>3 || p.distanceSquared(at)>attack.range*attack.range || safe(p.x,p.y,p.z)) return@entity
                val eye=target.eyeLocation
                val end=at.clone().add(0.0,1.0,0.0)
                val to=end.toVector().subtract(eye.toVector())
                val length=to.length()
                if(length>0.01) {
                    // Check the entire short ray's chunk bounds, including corner crossings
                    // that sampling the ray at one-block intervals could miss.
                    val direction=to.clone().normalize()
                    for(x in (minOf(eye.blockX,end.blockX) shr 4)..(maxOf(eye.blockX,end.blockX) shr 4)) {
                        for(z in (minOf(eye.blockZ,end.blockZ) shr 4)..(maxOf(eye.blockZ,end.blockZ) shr 4)) {
                            if(!Bukkit.isOwnedByCurrentRegion(target.world,x,z) || !target.world.isChunkLoaded(x,z)) return@entity
                        }
                    }
                    if(target.world.rayTraceBlocks(eye,direction,length,FluidCollisionMode.NEVER,true)!=null) return@entity
                }
                val hp=target.health
                target.damage(attack.damage,source)
                // Cancelled/blocked hits do not apply poison or slow through safe-zone guards.
                if(target.health<hp && !target.isDead) {
                    if(attack.poison>0) target.addPotionEffect(PotionEffect(PotionEffectType.POISON,attack.poison,0))
                    if(attack.slow>0) target.addPotionEffect(PotionEffect(PotionEffectType.SLOWNESS,attack.slow,0))
                }
            } }
        }
    }
    /** EntityDeathEvent on this entity's owner; MM retains its existing elite drop table. */
    fun died() {
        if(!ended.compareAndSet(false,true)) return
        revision++;task?.cancel();removed(id)
        effects.finish(model,entity.location,settings.death,settings.deathTicks)
    }
    private fun retire() {
        if(ended.compareAndSet(false,true)) { revision++;model.close();removed(id) }
    }
    override fun close() {
        if(!ended.compareAndSet(false,true)) return
        revision++;task?.cancel();model.close();removed(id)
        if(Bukkit.isOwnedByCurrentRegion(entity)) entity.remove()
        else if(plugin.isEnabled) dispatch.entity(entity) { entity.remove() }
    }
}
