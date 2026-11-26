package dev.dungeon.boss.battle

import dev.dungeon.boss.BossPoint
import dev.dungeon.boss.BossSettings
import dev.dungeon.farming.FarmPresence
import dev.dungeon.integration.BetterModelBridge
import dev.dungeon.integration.BetterModelEffects
import dev.dungeon.scheduling.Dispatch
import org.bukkit.Location
import org.bukkit.damage.DamageSource
import org.bukkit.damage.DamageType
import org.bukkit.entity.Mob
import org.bukkit.entity.Player
import org.bukkit.plugin.Plugin
import org.bukkit.util.Vector
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.*

/** Battle state and AI are confined to this boss entity's scheduler. Cross-region players are snapshots. */
class BossEncounter(val id: UUID,val entity: Mob,private val plugin: Plugin,private val settings: BossSettings,
    private val dispatch: Dispatch,bridge: BetterModelBridge,private val effects: BetterModelEffects,
    private val presences: ()->List<FarmPresence>,private val player: (UUID)->Player?,private val safe: (Double,Double,Double)->Boolean,
    private val killed: (BossEncounter,Set<UUID>,BossPoint,Long)->Unit,private val aborted: (BossEncounter)->Unit) : AutoCloseable {
    enum class State { SPAWNING,IDLE,FIGHTING,CASTING,RESETTING,DEAD,CLOSED }
    @Volatile var state=State.SPAWNING;private set
    private val ended=AtomicBoolean()
    private val contributions=ContributionLedger(settings.health)
    private val model=bridge.existing(settings.model,entity,settings.scale)
    private val recoil=BossRecoil(settings.impact)
    private var task: io.papermc.paper.threadedregions.scheduler.ScheduledTask?=null
    private var tick=0L
    private var lastNear=System.currentTimeMillis()
    private var nextAttack=52L
    private var attackNumber=0
    private var cast: Cast?=null
    @Volatile private var generation=0
    private data class Cast(val at: BossPoint,val impact: Long,val end: Long,val radius: Double,val damage: Double,val warning: AutoCloseable)
    fun start() {
        entity.isPersistent=false;entity.removeWhenFarAway=false
        // Keep vanilla physics active; remove goals instead of NoAI, which can suppress travel.
        entity.setAI(true);org.bukkit.Bukkit.getMobGoals().removeAllGoals(entity);entity.target=null;entity.isInvulnerable=true
        val health=requireNotNull(entity.getAttribute(org.bukkit.attribute.Attribute.MAX_HEALTH))
        health.baseValue=settings.health
        require(abs(health.value-settings.health)<.001) { "boss.yml:health=${settings.health} exceeds effective server maximum ${health.value}; lower health or configure the server's attribute limit" }
        entity.health=settings.health
        entity.getAttribute(org.bukkit.attribute.Attribute.SCALE)?.baseValue=1.4
        entity.getAttribute(org.bukkit.attribute.Attribute.KNOCKBACK_RESISTANCE)?.baseValue=settings.impact.resistance
        model.play("spawn");model.moving(false)
        task=entity.scheduler.runAtFixedRate(plugin,{ _ ->
            try { step() } catch(e: Exception) { plugin.logger.log(java.util.logging.Level.SEVERE,"Boss battle=$id entity=${entity.uniqueId}: battle aborted without rewards",e);abort() }
        },{ if(ended.compareAndSet(false,true)) { model.close();effects.clear();aborted(this) } },1,2)
    }
    fun isDamageable()=state==State.IDLE || state==State.FIGHTING || state==State.CASTING
    /** Knockback event on victim owner; preserve a short impulse instead of overwriting with AI. */
    fun knockback(vector: Vector): Vector? = recoil.accept(vector,tick,state==State.IDLE || state==State.FIGHTING)
    fun beforeDamage(player: UUID?,finalDamage: Double) {
        contributions.beforeDamage(player,entity.health,finalDamage,System.currentTimeMillis())
        if(player!=null && finalDamage>0) {
            lastNear=System.currentTimeMillis()
            if(state==State.IDLE) state=State.FIGHTING
            if(state!=State.CASTING && finalDamage<entity.health) model.play("hurt")
        }
    }
    private fun step() {
        if(ended.get()) return
        if(!entity.isValid || entity.isDead) { abort();return }
        contributions.confirm(entity.health)
        tick+=2
        if(state==State.SPAWNING) {
            if(tick>=48) { entity.isInvulnerable=false;state=State.IDLE }
            return
        }
        if(state==State.RESETTING) return
        val at=entity.location
        if(settings.point.distanceSquared(at.x,at.y,at.z)>settings.leash.pow(2) || safe(at.x,at.y,at.z)) { reset();return }
        val nearby=presences().filter { it.combat && settings.point.distanceSquared(it.x,it.y,it.z)<=(settings.leash+8).pow(2) && !safe(it.x,it.y,it.z) }
        val target=nearby.minByOrNull { it.distanceSquared(at.x,at.y,at.z) }
        val now=System.currentTimeMillis()
        if(target!=null) lastNear=now
        else if(now-lastNear>=settings.resetSeconds*1000 && (contributions.snapshot().isNotEmpty() || state!=State.IDLE)) { reset();return }
        val casting=cast
        if(casting!=null) {
            stop()
            if(tick>=casting.impact && casting.impact>0) {
                casting.warning.close()
                effects.show(settings.effect,Location(at.world,casting.at.x,casting.at.y,casting.at.z),casting.radius,"burst",14,onFailure={ dispatch.entity(entity) { abort() } })
                hit(casting)
                cast=casting.copy(impact=-1)
            }
            if(tick>=casting.end) { cast=null;state=State.FIGHTING;nextAttack=tick+35 }
            return
        }
        if(recoil.recovering(tick)) { model.moving(false);return }
        if(target==null) { stop();model.moving(false);return }
        state=State.FIGHTING
        val dx=target.x-at.x;val dz=target.z-at.z;val distance=hypot(dx,dz)
        entity.setRotation(Math.toDegrees(atan2(-dx,dz)).toFloat(),0f)
        if(distance>3.4) {
            model.moving(true)
            val motion=Vector(dx/distance*.13,entity.velocity.y,dz/distance*.13)
            if(entity.isOnGround && entity.velocity.lengthSquared()<.002) motion.y=.32
            entity.velocity=motion
        } else { stop();model.moving(false) }
        if(tick>=nextAttack && distance<=8) attack(at,distance)
    }
    private fun attack(at: Location,distance: Double) {
        val special=++attackNumber%3==0 || distance>4.0
        val attack=if(special) settings.special else if(attackNumber%2==0) settings.sweep else settings.melee
        val radius=attack.radius
        val direction=at.direction.setY(0).normalize()
        val center=at.clone().add(direction.multiply(attack.forward))
        val point=BossPoint(center.x,center.y,center.z)
        if(safe(point.x,point.y,point.z)) { nextAttack=tick+20;return }
        model.moving(false);model.play(attack.animation);stop()
        val warning=effects.show(settings.effect,center,radius,"telegraph",attack.windup+2,true) { dispatch.entity(entity) { abort() } }
        cast=Cast(point,tick+attack.windup,tick+attack.duration,radius,attack.damage,warning)
        state=State.CASTING
    }
    private fun hit(c: Cast) {
        val token=generation
        val at=Location(entity.world,c.at.x,c.at.y,c.at.z)
        // Location attribution avoids inspecting a foreign boss entity from the victim's region.
        val source=DamageSource.builder(DamageType.MOB_ATTACK).withDamageLocation(at).build()
        presences().filter { it.distanceSquared(c.at.x,c.at.y,c.at.z)<=(c.radius+1).pow(2) }.forEach { snapshot ->
            player(snapshot.id)?.let { target -> dispatch.entity(target) {
                if(ended.get() || token!=generation || !target.isOnline || target.isDead || target.world.uid!=at.world.uid) return@entity
                if(target.gameMode!=org.bukkit.GameMode.SURVIVAL && target.gameMode!=org.bukkit.GameMode.ADVENTURE) return@entity
                val p=target.location
                if(abs(p.y-c.at.y)>3.5 || (p.x-c.at.x).pow(2)+(p.z-c.at.z).pow(2)>c.radius.pow(2) || safe(p.x,p.y,p.z)) return@entity
                target.damage(c.damage,source)
            } }
        }
    }
    private fun stop() { entity.velocity=Vector(0.0,entity.velocity.y,0.0) }
    private fun reset() {
        if(state==State.RESETTING) return
        generation++;state=State.RESETTING;cast=null;effects.clear();contributions.reset();stop();model.moving(false)
        entity.isInvulnerable=true
        entity.teleportAsync(Location(entity.world,settings.point.x,settings.point.y,settings.point.z)).whenComplete { ok,e ->
            if(!ended.get()) dispatch.entity(entity) {
                if(e!=null || ok!=true) { plugin.logger.log(java.util.logging.Level.WARNING,"Boss battle=$id: reset teleport failed",e);abort() }
                else { entity.health=settings.health;entity.isInvulnerable=false;state=State.IDLE;lastNear=System.currentTimeMillis();nextAttack=tick+40 }
            }
        }
    }
    /** EntityDeathEvent on this entity owner, before the vanilla entity disappears. */
    fun died() {
        if(!ended.compareAndSet(false,true)) return
        state=State.DEAD;generation++;task?.cancel();cast?.warning?.close();effects.clear()
        contributions.confirm(0.0)
        val now=System.currentTimeMillis();val at=entity.location
        val eligible=contributions.eligible(now,settings.rewards.contribution,settings.rewards.recentSeconds*1000)
        try { effects.finish(model,at,"death",66) }
        finally { killed(this,eligible,BossPoint(at.x,at.y,at.z),now);contributions.reset() }
    }
    private fun abort() {
        if(!ended.compareAndSet(false,true)) return
        state=State.CLOSED;generation++;task?.cancel();contributions.reset();effects.clear();model.close()
        if(entity.isValid) entity.remove()
        aborted(this)
    }
    override fun close() {
        // Tracker packet cleanup is thread-safe in BetterModel. Bukkit entity removal stays on its owner.
        ended.set(true);generation++;state=State.CLOSED;task?.cancel();model.close()
        if(org.bukkit.Bukkit.isOwnedByCurrentRegion(entity)) entity.remove()
        else if(plugin.isEnabled) dispatch.entity(entity) { entity.remove() }
    }
}
