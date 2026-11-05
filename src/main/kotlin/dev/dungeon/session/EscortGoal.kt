package dev.dungeon.session

import dev.dungeon.model.Point
import dev.dungeon.model.RunState
import dev.dungeon.scheduling.Dispatch
import org.bukkit.Location
import org.bukkit.entity.Mob

/** Every pathfinding/position operation runs on the escorted mob's entity scheduler. */
class EscortGoal(private val dispatch: Dispatch) {
    fun start(s: ActiveSession, mob: Mob, stage: Int, destination: Point, success: () -> Unit, failure: (Throwable) -> Unit) {
        var failedPaths = 0
        fun tick() {
            if(s.closing.get() || s.run.snapshot().stage != stage) return
            dispatch.entity(mob,Runnable { if(!s.closing.get() && s.run.snapshot().stage == stage) failure(IllegalStateException("Escort entity retired")) }) {
                if(s.closing.get() || s.run.snapshot().stage != stage) return@entity
                if(s.run.snapshot().state == RunState.PAUSED) mob.pathfinder.stopPathfinding()
                else if(s.run.snapshot().state == RunState.RUNNING) {
                    val loc = mob.location
                    if(destination.near(loc.x,loc.y,loc.z,2.0)) { mob.pathfinder.stopPathfinding();success();return@entity }
                    if(!mob.pathfinder.moveTo(Location(loc.world,destination.x,destination.y,destination.z),1.0)) failedPaths++ else failedPaths = 0
                    if(failedPaths >= 20) { failure(IllegalStateException("Escort path unavailable for 20 attempts at $destination"));return@entity }
                } else return@entity
                dispatch.later(20) { tick() }
            }
        }
        tick()
    }
}
