package dev.dungeon.session

import dev.dungeon.configuration.Definitions
import dev.dungeon.integration.MobSpawner
import dev.dungeon.localization.Messages
import dev.dungeon.model.*
import dev.dungeon.persistence.ProgressStore
import dev.dungeon.scheduling.Dispatch
import dev.dungeon.world.WorldLifecycle
import org.bukkit.Bukkit
import org.bukkit.Location
import org.bukkit.entity.Player
import org.bukkit.plugin.Plugin
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.logging.Level

class Sessions(
    private val plugin: Plugin, private val definitions: Definitions, private val dispatch: Dispatch,
    private val worlds: WorldLifecycle, private val store: ProgressStore, private val messages: Messages
) {
    val online = ConcurrentHashMap<UUID,Player>()
    private val runs = ConcurrentHashMap<UUID,ActiveSession>()
    private val members = ConcurrentHashMap<UUID,ActiveSession>()
    private val admission = Any()
    private val lobbyTrips = HashMap<UUID,UUID>()
    fun reserveLobbyTrip(player: UUID): UUID? = synchronized(admission) {
        if(!active || members.containsKey(player) || lobbyTrips.containsKey(player)) null
        else UUID.randomUUID().also { lobbyTrips[player] = it }
    }
    fun finishLobbyTrip(player: UUID, trip: UUID) = synchronized(admission) { lobbyTrips.remove(player,trip) }
    private val waves = MobWaves(dispatch,{ active },{ p,k,t,i,e -> credit(p,k,t,i,e) },{ s,e -> fail(s,e) })
    private val rewards = RewardDelivery(store,dispatch,messages,{ members[it] },{ active },{ s,e -> log(s,e) })
    val scripts = dev.dungeon.script.ScriptEngine { ticks,block -> dispatch.later(ticks,block) }.apply {
        registerAction("message") { context,key ->
            context.players.forEach { id ->
                val s = members[id]
                if(s?.run?.id == context.run) s.players[id]?.let { messages.send(it,key) }
            }
        }
    }
    private fun script(s: ActiveSession, event: String) {
        val snap = s.run.snapshot()
        scripts.fire(s.run.definition.scripts,event,dev.dungeon.script.ScriptContext(s.run.id,snap.stage,snap.state.name,s.players.keys.toSet()),
            { active && !s.closing.get() && s.run.snapshot().stage == snap.stage },{ log(s,it) })
    }
    @Volatile private var active = true
    fun current(id: UUID) = members[id]
    fun start(leader: Player, ids: Set<UUID>, dungeon: String, difficulty: String) {
        val d = requireNotNull(definitions.current[dungeon]) { "Unknown dungeon $dungeon" }
        val diff = requireNotNull(d.difficulties[difficulty]) { "Unknown difficulty $difficulty" }
        try {
            dev.dungeon.integration.MythicMobsAdapter().validate(d.mobs.values.filter { it.mythic }.map { it.type }.toSet())
        } catch(e: IllegalStateException) {
            plugin.logger.log(Level.WARNING,"Dungeon=${d.id} MythicMobs preflight rejected",e)
            messages.send(leader,"invalid")
            return
        }
        require(ids.size in d.min..d.max)
        if(d.mode == Mode.STORY) require(ids.size == 1) { "Story resume currently requires solo" }
        val players = ids.map { requireNotNull(online[it]) { "Party member offline: $it" } }
        val id = UUID.randomUUID()
        val worldName = if(d.mode == Mode.TEMPORARY) "dungeon_$id" else d.world
        var shared = false
        val session: ActiveSession
        synchronized(admission) {
            val existing = if(d.scope == ProgressScope.SHARED && d.mode == Mode.PERMANENT) runs.values.firstOrNull {
                it.run.definition.id == d.id && it.run.snapshot().state == RunState.RUNNING && !it.closing.get()
            } else null
            shared = existing != null
            session = existing ?: ActiveSession(Run(id,d,diff,worldName))
        }
        synchronized(admission) {
            require(active && ids.none { members.containsKey(it) || lobbyTrips.containsKey(it) }) { "Player already in a dungeon or lobby teleport" }
            require(session.players.size + players.size <= d.max && session.run.difficulty.id == diff.id)
            if(d.mode != Mode.TEMPORARY) require(runs.values.none { it !== session && it.run.worldName == worldName }) {
                "Permanent map already occupied; physical isolation requires separate worlds/regions"
            }
            ids.forEach { members[it] = session }; runs[session.run.id] = session
            players.forEach { session.players[it.uniqueId] = it }
        }
        if(!shared) session.run.transition(RunState.PREPARING)
        session.transfers.incrementAndGet()
        val ready = players.map { player ->
            val captured = CompletableFuture<Unit>()
            dispatch.entity(player,Runnable { captured.completeExceptionally(IllegalStateException("Player retired")) }) {
                if(!player.isOnline || !player.hasPermission("dungeon.use")) captured.completeExceptionally(IllegalStateException("Player offline or permission missing"))
                else {
                    val loc = player.location.clone();session.exits[player.uniqueId] = loc
                    store.unlocked(player.uniqueId,d.campaign).thenCompose { unlocked ->
                        require(unlocked >= d.chapter) { "Chapter ${d.chapter} locked for ${player.uniqueId}" }
                        store.rememberReturn(player.uniqueId,dev.dungeon.persistence.ReturnPoint(loc.world.name,loc.x,loc.y,loc.z,loc.yaw,loc.pitch))
                    }.whenComplete { _,e -> if(e != null) captured.completeExceptionally(e) else captured.complete(Unit) }
                }
            }; captured
        }
        val restored = if(d.mode == Mode.STORY) store.progress(leader.uniqueId,d.id).thenAccept { old ->
            if(old != null && old.first in d.stages.indices) {
                // restore before PREPARING isn't allowed: replays begin at stage checkpoint, not partial wave.
                session.run.restore(old.first,0)
            }
        } else CompletableFuture.completedFuture(null)
        CompletableFuture.allOf(*ready.toTypedArray(),restored).thenCompose {
            if(d.mode == Mode.TEMPORARY) worlds.create(d.template,id) else {
                val f = CompletableFuture<org.bukkit.World>()
                dispatch.global {
                    val world = Bukkit.getWorld(worldName)
                    if(world == null) f.completeExceptionally(IllegalStateException("World not loaded: $worldName")) else f.complete(world)
                }; f
            }
        }.whenComplete { world,error ->
            session.transfers.decrementAndGet()
            if(error != null) fail(session,error)
            else if(active && !session.closing.get()) {
                val point = d.points.getValue("spawn")
                session.transfers.incrementAndGet()
                val teleports = players.map { p -> teleport(session,p,Location(world,point.x,point.y,point.z)) }
                CompletableFuture.allOf(*teleports.toTypedArray()).whenComplete { _,e ->
                    session.transfers.decrementAndGet()
                    if(e != null) fail(session,e)
                    else if(session.run.transition(RunState.RUNNING)) { waves.spawn(session); broadcast(session,"entered");script(session,"started") }
                    tryClean(session)
                }
            } else tryClean(session)
        }
    }
    private fun teleport(s: ActiveSession, p: Player, target: Location): CompletableFuture<Unit> {
        val f = CompletableFuture<Unit>()
        dispatch.entity(p,Runnable { f.completeExceptionally(IllegalStateException("Player retired")) }) {
            if(!active || s.closing.get() || members[p.uniqueId] !== s || !p.isOnline) f.completeExceptionally(IllegalStateException("Stale session"))
            else p.teleportAsync(target).whenComplete { ok,e ->
                if(e != null) f.completeExceptionally(e) else if(ok != true) f.completeExceptionally(IllegalStateException("Teleport rejected")) else f.complete(Unit)
            }
        }; return f
    }
    fun credit(player: UUID, kind: Objective, target: String, token: Int? = null, event: UUID = UUID.randomUUID()) {
        val s = members[player] ?: return
        if(s.closing.get()) return
        if(s.run.credit(kind,target,token,event)) {
            save(s)
            if(s.run.snapshot().state == RunState.COMPLETED) { broadcast(s,"completed");script(s,"completed"); rewards.grant(s) }
            else { broadcast(s,"stage");script(s,"stage"); waves.spawn(s) }
        }
    }
    fun killed(entity: UUID, player: UUID?) {
        val tag = waves.tags.remove(entity) ?: return
        val s = runs[tag.first] ?: return
        if(s.run.snapshot().stage != tag.second || s.closing.get()) return
        val objective = s.run.definition.stages.getOrNull(tag.second)?.objective ?: return
        if(objective == Objective.ESCORT) { fail(s,IllegalStateException("Escorted mob died: ${tag.third}"));return }
        if(player == null || members[player] !== s) return
        if(objective in setOf(Objective.KILL,Objective.BOSS)) credit(player,objective,tag.third,tag.second,entity)
    }
    private fun save(s: ActiveSession) {
        if(s.run.definition.mode == Mode.STORY) s.players.keys.forEach { player ->
            store.save(player,s.run.definition.id,s.run.snapshot()).exceptionally { log(s,it); null }
        }
    }
    fun leave(p: Player) {
        val s = members[p.uniqueId] ?: return
        val exit = s.exits[p.uniqueId]
        if(exit == null) {
            if(s.closing.get()) { members.remove(p.uniqueId,s);s.players.remove(p.uniqueId);tryClean(s) }
            return
        }
        if(!s.exiting.add(p.uniqueId)) return
        s.transfers.incrementAndGet()
        dispatch.entity(p,Runnable { s.transfers.decrementAndGet();s.exiting.remove(p.uniqueId) }) {
            if(p.isDead) { s.transfers.decrementAndGet();s.exiting.remove(p.uniqueId);return@entity }
            p.teleportAsync(exit).whenComplete { ok,e ->
                s.transfers.decrementAndGet();s.exiting.remove(p.uniqueId)
                if(e != null || ok != true) { if(e != null) log(s,e); messages.send(p,"exit-failed") }
                else {
                    synchronized(admission) { members.remove(p.uniqueId,s);s.players.remove(p.uniqueId);s.offline.remove(p.uniqueId) }
                    store.forgetReturn(p.uniqueId).exceptionally { log(s,it);null }
                    messages.send(p,"left")
                    if(s.players.isEmpty() && !s.closing.get()) close(s)
                }
                tryClean(s)
            }
        }
    }
    fun quit(p: Player) {
        online.remove(p.uniqueId)
        val s = members[p.uniqueId] ?: return
        s.offline[p.uniqueId] = System.currentTimeMillis()
        s.run.transition(RunState.PAUSED);save(s)
    }
    fun join(p: Player) {
        online[p.uniqueId] = p
        val s = members[p.uniqueId]
        if(s == null) {
            store.returnPoint(p.uniqueId).thenAccept { point ->
                if(point != null) dispatch.global {
                    val world = Bukkit.getWorld(point.world)
                    if(world == null) plugin.logger.severe("Recovery return world missing: player=${p.uniqueId} world=${point.world}")
                    else dispatch.entity(p) {
                        if(active && p.isOnline && members[p.uniqueId] == null) {
                            p.teleportAsync(Location(world,point.x,point.y,point.z,point.yaw,point.pitch)).thenAccept { ok ->
                                if(ok) store.forgetReturn(p.uniqueId)
                            }
                        }
                    }
                }
            }.exceptionally { plugin.logger.log(Level.SEVERE,"Return recovery player=${p.uniqueId}",it);null }
            return
        }
        s.players[p.uniqueId] = p;s.offline.remove(p.uniqueId)
        if(s.closing.get()) leave(p)
        else if(s.offline.isEmpty()) s.run.transition(RunState.RUNNING)
    }
    fun tick() {
        if(!active) return
        val now = System.currentTimeMillis()
        runs.values.forEach { s ->
            if(s.closing.get()) {
                synchronized(admission) {
                    s.offline.entries.filter { now-it.value > s.run.definition.reconnectSeconds*1000 }.forEach { (id,_) ->
                        members.remove(id,s);s.players.remove(id);s.offline.remove(id)
                    }
                }
                s.players.values.filter { !s.offline.containsKey(it.uniqueId) }.forEach { p ->
                    dispatch.entity(p) { if(p.isOnline && !p.isDead) leave(p) }
                }
                tryClean(s)
                return@forEach
            }
            if(now-s.started > s.run.definition.timeoutSeconds*1000 || s.offline.values.any { now-it > s.run.definition.reconnectSeconds*1000 }) {
                // Offline members keep their durable return record, but no stale Player/session references.
                synchronized(admission) {
                    s.offline.keys.toList().forEach { id ->
                        members.remove(id,s);s.players.remove(id);s.offline.remove(id)
                    }
                }
                s.run.transition(RunState.FAILED);close(s)
            }
        }
        dispatch.later(20) { tick() }
    }
    private fun close(s: ActiveSession) {
        s.closing.set(true)
        val snap = s.run.snapshot()
        if(snap.state !in setOf(RunState.COMPLETED,RunState.FAILED,RunState.CLEANING)) s.run.transition(RunState.FAILED)
        s.run.transition(RunState.CLEANING)
        save(s)
        s.players.values.forEach { p -> if(!s.offline.containsKey(p.uniqueId)) leave(p) }
        tryClean(s)
    }
    private fun tryClean(s: ActiveSession) {
        if(!s.closing.get() || s.transfers.get() != 0 || s.players.isNotEmpty()) return
        if(!runs.remove(s.run.id,s)) return
        s.entities.forEach { e -> waves.tags.remove(e.uniqueId);dispatch.entity(e) { e.remove() } };s.entities.clear()
        if(s.run.definition.mode == Mode.TEMPORARY) cleanWorld(s,0)
    }
    private fun cleanWorld(s: ActiveSession, attempt: Int) {
        worlds.clean(s.run.worldName,s.run.id).whenComplete { ok,e ->
            if(e != null) log(s,e)
            if(ok != true) {
                if(active && attempt < 3) dispatch.later(200) { cleanWorld(s,attempt+1) }
                else plugin.logger.warning("Instance retained after cleanup attempts: ${s.run.id}; inspect world before deletion")
            }
        }
    }
    /** Dead permanent-map members respawn locally, while the run still follows its configured failure rule. */
    fun failDeath(s: ActiveSession, player: UUID) {
        if(s.run.definition.mode==Mode.PERMANENT) {
            synchronized(admission) {
                members.remove(player,s);s.players.remove(player);s.offline.remove(player);s.exits.remove(player)
            }
            store.forgetReturn(player).exceptionally { log(s,it);null }
        }
        fail(s,IllegalStateException("Death rule: $player"))
    }
    fun fail(s: ActiveSession, e: Throwable) { log(s,e);s.run.transition(RunState.FAILED);broadcast(s,"failed");close(s) }
    private fun log(s: ActiveSession,e: Throwable) { plugin.logger.log(Level.SEVERE,"Dungeon=${s.run.definition.id} instance=${s.run.id}",e) }
    private fun broadcast(s: ActiveSession,key: String) { s.players.values.forEach { messages.send(it,key,s.run.snapshot().stage.toString()) } }
    fun stop() {
        active = false
        runs.values.forEach { s -> save(s);s.entities.forEach { e -> dispatch.entity(e) { e.remove() } } }
        runs.clear();members.clear();online.clear();waves.tags.clear()
        synchronized(admission) { lobbyTrips.clear() }
        // Loaded worlds are intentionally retained on shutdown. Next boot recovery only deletes offline owned instances.
    }
}
