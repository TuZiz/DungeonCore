package dev.dungeon.boss

import dev.dungeon.boss.battle.*
import dev.dungeon.boss.reward.*
import dev.dungeon.boss.hologram.*
import dev.dungeon.farming.FarmPresence
import dev.dungeon.integration.BetterModelBridge
import dev.dungeon.integration.BetterModelEffects
import dev.dungeon.integration.CraftEngineItemsBridge
import dev.dungeon.integration.MythicMobsAdapter
import dev.dungeon.scheduling.Dispatch
import org.bukkit.Bukkit
import org.bukkit.Location
import org.bukkit.World
import org.bukkit.command.CommandSender
import org.bukkit.entity.Entity
import org.bukkit.entity.Mob
import org.bukkit.entity.Player
import org.bukkit.event.HandlerList
import org.bukkit.persistence.PersistentDataType
import org.bukkit.plugin.Plugin
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.logging.Level
import kotlin.math.pow
import net.kyori.adventure.title.Title
import java.time.Duration

/** Owns dependency readiness, one encounter, arena and durable rewards for one permanent world. */
class BossModule(private val plugin: Plugin,private val world: World,private val resources: BossResources,
    private val dispatch: Dispatch,private val presences: ()->List<FarmPresence>,private val player: (UUID)->Player?,
    private val safe: (Double,Double,Double)->Boolean): AutoCloseable {
    private val s=resources.settings
    private val text=resources.messages
    private val marker=org.bukkit.NamespacedKey(plugin,"world_boss")
    private val store=BossRewardStore(plugin.dataFolder.toPath().resolve("boss-rewards.db"))
    private val arena=BossArena(plugin,world,dispatch)
    private val spawning=AtomicBoolean()
    private val generation=AtomicLong()
    private val listener=BossListener(this)
    @Volatile private var closed=false
    @Volatile private var battle: BossEncounter?=null
    @Volatile private var pending: BossModelAwaiter?=null
    @Volatile private var nextAt=Long.MAX_VALUE
    @Volatile private var scheduleFailed=false
    private var bridge: BetterModelBridge?=null
    @Volatile private var rewards: BossRewards?=null
    private var effects: BetterModelEffects?=null
    private var error: String?=null
    private val hologram=resources.hologram?.takeIf { it.enabled }?.let {
        BossHologram(plugin,world,s,it,text,dispatch,presences,::displayState)
    }
    private fun displayState(): BossDisplayState {
        val current=battle
        val status=when {
            scheduleFailed -> BossDisplayStatus.PAUSED
            nextAt==Long.MAX_VALUE -> BossDisplayStatus.LOADING
            rewards==null -> BossDisplayStatus.PAUSED
            spawning.get() -> BossDisplayStatus.SUMMONING
            current!=null -> when(current.state) {
                BossEncounter.State.SPAWNING -> BossDisplayStatus.SUMMONING
                BossEncounter.State.FIGHTING,BossEncounter.State.CASTING -> BossDisplayStatus.FIGHTING
                BossEncounter.State.RESETTING -> BossDisplayStatus.RESETTING
                else -> BossDisplayStatus.ACTIVE
            }
            System.currentTimeMillis()>=nextAt -> BossDisplayStatus.READY
            else -> BossDisplayStatus.WAITING
        }
        return BossDisplayState(status,nextAt)
    }
    init {
        Bukkit.getPluginManager().registerEvents(listener,plugin)
        store.initialize().thenCompose { store.nextSpawn(world.name,System.currentTimeMillis()+s.initialSeconds*1000) }.whenComplete { at,e ->
            if(e!=null) { scheduleFailed=true;failed(e) } else if(!closed) { nextAt=at;dispatch.global(::tick) }
        }
    }
    private fun ready(): Boolean {
        if(rewards!=null) return true
        return try {
            fun dependency(id: String)=requireNotNull(Bukkit.getPluginManager().getPlugin(id)?.takeIf { it.isEnabled }) { "Optional dependency $id not enabled" }
            val bm=dependency("BetterModel");dependency("MythicMobs");val ce=dependency("CraftEngine")
            val adapter=BetterModelBridge(bm)
            adapter.validate(s.model,s.effect,setOf(s.melee.animation,s.sweep.animation,s.special.animation))
            MythicMobsAdapter().validate(setOf(s.mob))
            val items=CraftEngineItemsBridge(ce);items.validate(setOf(s.rewards.materialId,s.rewards.generatorId)+resources.spoils.materials)
            bridge=adapter;effects=BetterModelEffects(plugin,adapter,dispatch,presences,player)
            rewards=BossRewards(plugin,dispatch,store,items,s.rewards,text,resources.spoils)
            error=null
            plugin.logger.info("World boss ready: world=${world.name}, mob=${s.mob}, model=${s.model}, BetterModel=${bm.pluginMeta.version}; personal rewards and SQLite enabled")
            true
        } catch(e: Exception) { unavailable(e);false } catch(e: LinkageError) { unavailable(e);false }
    }
    private fun tick() {
        if(closed || !dispatch.isActive) return
        if(ready() && System.currentTimeMillis()>=nextAt && battle==null && presences().any { it.combat && !safe(it.x,it.y,it.z) }) spawn(false)
        dispatch.later(100,::tick)
    }
    /** Global scheduler only; DB and chunk loads complete asynchronously before any spawn. */
    private fun spawn(force: Boolean) {
        if(closed || battle!=null || !spawning.compareAndSet(false,true)) return
        if(!ready()) { spawning.set(false);return }
        val token=generation.get()
        val visual=requireNotNull(bridge)
        val fx=requireNotNull(effects)
        val at=Location(world,s.point.x,s.point.y,s.point.z)
        val due=System.currentTimeMillis()+s.respawnSeconds*1000
        store.schedule(world.name,due).thenCompose { arena.acquire(at) }.whenComplete { _,e ->
            if(e!=null) { arena.release();nextAt=System.currentTimeMillis()+60000;spawning.set(false);if(!closed && generation.get()==token) failed(e) }
            else dispatch.region(at) {
                var entity: Mob?=null
                var prepared: BossEncounter?=null
                try {
                    if(closed || generation.get()!=token) { arena.release();return@region }
                    require(!safe(at.x,at.y,at.z)) { "boss.yml:arena overlaps safe zone" }
                    require(at.clone().subtract(0.0,1.0,0.0).block.type.isSolid && (0..3).all { at.clone().add(0.0,it.toDouble(),0.0).block.isPassable }) { "boss.yml:arena unsafe floor/headroom: $at" }
                    entity=MythicMobsAdapter().spawn(s.mob,at) as? Mob ?: error("Boss ${s.mob} must be a Mob")
                    val id=UUID.randomUUID();entity.persistentDataContainer.set(marker,PersistentDataType.STRING,id.toString())
                    val mob=entity
                    val admission=BossModelAwaiter(plugin,mob,dispatch,visual,s.model,s.scale,
                        { !closed && generation.get()==token },{
                            val encounter=BossEncounter(id,mob,plugin,s,dispatch,visual,fx,presences,player,safe,::killed,::aborted)
                            prepared=encounter
                            battle=encounter;nextAt=due;encounter.start()
                            if(closed || generation.get()!=token) {
                                encounter.close();if(battle===encounter) battle=null;arena.release()
                            } else {
                                announceSpawn(encounter,token)
                                plugin.logger.info("World boss spawned: battle=$id world=${world.name} forced=$force")
                            }
                            pending=null;spawning.set(false)
                        },{ error ->
                            prepared?.close();battle=null;pending=null;arena.release();spawning.set(false)
                            nextAt=System.currentTimeMillis()+60000
                            if(!closed && generation.get()==token) failed(error)
                        })
                    pending=admission;admission.start()
                } catch(error: Exception) { prepared?.close();entity?.remove();battle=null;arena.release();nextAt=System.currentTimeMillis()+60000;failed(error) }
                finally { if(pending==null) spawning.set(false) }
            }
        }
    }
    /** Called after successful spawn; delivery and world checks run on each player's owner. */
    private fun announceSpawn(encounter: BossEncounter,token: Long) {
        val title=Title.title(text.component("spawn-title"),text.component("spawn-subtitle"),
            Title.Times.times(Duration.ofMillis(400),Duration.ofSeconds(3),Duration.ofMillis(600)))
        val message=text.component("spawned",mapOf("detail" to "${s.point.x.toInt()}, ${s.point.y.toInt()}, ${s.point.z.toInt()}"))
        presences().map { it.id }.distinct().forEach { id -> player(id)?.let { target -> dispatch.entity(target) {
            if(closed || generation.get()!=token || battle!==encounter || !target.isOnline ||
                target.world.uid!=world.uid || !target.hasPermission("dungeon.use")) return@entity
            target.showTitle(title)
            target.sendMessage(message)
        } } }
    }
    private fun killed(encounter: BossEncounter,ids: Set<UUID>,point: BossPoint,at: Long) {
        if(closed || battle!==encounter) return
        battle=null
        nextAt=at+s.respawnSeconds*1000
        store.schedule(world.name,nextAt).exceptionally { failed(it);null }
        ids.forEach { id -> player(id)?.let { target -> dispatch.entity(target) {
            if(closed || !target.isOnline || target.world.uid!=world.uid || !target.hasPermission("dungeon.use")) return@entity
            val p=target.location
            if(point.distanceSquared(p.x,p.y,p.z)<=s.rewards.range.pow(2)) rewards?.award(encounter.id,target,at)
        } } }
        // Keep the chunks alive for the supplied model's full death animation.
        dispatch.later(70) { if(battle==null && !spawning.get()) arena.release() }
        plugin.logger.info("World boss killed: battle=${encounter.id} eligibleDamage=${ids.size}; range/online checked on each player owner")
    }
    private fun aborted(encounter: BossEncounter) {
        if(battle!==encounter) return
        battle=null;arena.release();nextAt=System.currentTimeMillis()+s.respawnSeconds*1000
        if(!closed) store.schedule(world.name,nextAt).exceptionally { failed(it);null }
    }
    fun forEntity(id: UUID): BossEncounter?=battle?.takeIf { it.entity.uniqueId==id }
    fun dependencyStopped() {
        if(closed) return
        generation.incrementAndGet()
        pending?.close();pending=null;spawning.set(false)
        battle?.close();battle=null;effects?.close();effects=null;rewards?.close();rewards=null;bridge=null;arena.release()
    }
    fun recover(entity: Entity) {
        if(entity.world.uid==world.uid && entity.persistentDataContainer.has(marker,PersistentDataType.STRING) && forEntity(entity.uniqueId)==null) entity.remove()
    }
    fun claim(player: Player) { rewards?.claim(player) ?: text.send(player,"unavailable") }
    fun command(sender: CommandSender,args: List<String>) {
        if(!sender.hasPermission("dungeon.admin")) { text.send(sender,"permission");return }
        dispatch.global {
            when(args.singleOrNull()?.lowercase() ?: "status") {
                "spawn" -> if(battle!=null || spawning.get()) text.send(sender,"already-active") else if(ready()) { spawn(true);text.send(sender,"summoning") } else text.send(sender,"unavailable")
                "status" -> {
                    val current=battle
                    if(current!=null) text.send(sender,"state.${current.state.name.lowercase()}",current.id.toString())
                    else if(rewards==null) text.send(sender,"status-unavailable")
                    else text.send(sender,"status-waiting",((nextAt-System.currentTimeMillis()).coerceAtLeast(0)/1000).toString())
                }
                else -> text.send(sender,"usage")
            }
        }
    }
    private fun unavailable(e: Throwable) { if(error!=e.message) { error=e.message;plugin.logger.log(Level.WARNING,"boss.yml world=${world.name}: boss paused; dungeon remains available",e) } }
    private fun failed(e: Throwable) { plugin.logger.log(Level.SEVERE,"boss.yml world=${world.name}: boss operation failed; rewards/map retained",e) }
    override fun close() {
        closed=true;generation.incrementAndGet();pending?.close();pending=null;spawning.set(false);hologram?.close();HandlerList.unregisterAll(listener);battle?.close();battle=null;effects?.close();rewards?.close();arena.close()
        store.closeAsync().exceptionally { failed(it);null }
        effects=null;rewards=null;bridge=null
    }
}
