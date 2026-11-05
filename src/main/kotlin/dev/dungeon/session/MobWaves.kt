package dev.dungeon.session

import dev.dungeon.integration.MobSpawner
import dev.dungeon.model.*
import dev.dungeon.scheduling.Dispatch
import org.bukkit.Bukkit
import org.bukkit.Location
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

class MobWaves(private val dispatch: Dispatch, private val enabled: () -> Boolean,
    private val credit: (UUID,Objective,String,Int,UUID) -> Unit, private val fail: (ActiveSession,Throwable) -> Unit) {
    val tags = ConcurrentHashMap<UUID,Triple<UUID,Int,String>>()
    private val spawner = MobSpawner()
    private val escort = EscortGoal(dispatch)
    fun spawn(s: ActiveSession) {
        val snap = s.run.snapshot()
        val stage = s.run.definition.stages.getOrNull(snap.stage) ?: return
        dispatch.global {
            val world = Bukkit.getWorld(s.run.worldName) ?: return@global fail(s,IllegalStateException("World disappeared"))
            stage.mobs.forEach { id ->
                val mob = s.run.definition.mobs.getValue(id); val p = s.run.definition.points.getValue(mob.location)
                val location = Location(world,p.x,p.y,p.z)
                dispatch.region(location) {
                    if(enabled() && !s.closing.get() && s.run.snapshot().stage == snap.stage && s.run.snapshot().state == RunState.RUNNING) {
                        try { repeat(mob.count * if(stage.objective == Objective.ESCORT) 1 else s.run.difficulty.goalMultiplier) {
                            val entity = spawner.spawn(mob,location)
                            entity.isPersistent = false
                            s.entities.add(entity); tags[entity.uniqueId] = Triple(s.run.id,snap.stage,id)
                            if(stage.objective == Objective.ESCORT && stage.target == id) {
                                val escorted = entity as? org.bukkit.entity.Mob ?: error("Escort target must be a Mob: $id")
                                escort.start(s,escorted,snap.stage,s.run.definition.points.getValue(requireNotNull(stage.destination)),{
                                    s.players.keys.firstOrNull()?.let { credit(it,Objective.ESCORT,id,snap.stage,entity.uniqueId) }
                                },{ fail(s,it) })
                            }
                        } } catch(e: Exception) { fail(s,e) }
                    }
                }
            }
        }
    }
}
