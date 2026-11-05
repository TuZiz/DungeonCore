package dev.dungeon.model

import java.util.UUID

enum class RunState { WAITING, PREPARING, RUNNING, PAUSED, COMPLETED, FAILED, CLEANING }
data class RunSnapshot(val state: RunState, val stage: Int, val count: Int)
/** All transitions and counters share this monitor; callbacks never perform IO inside it. */
class Run(val id: UUID, val definition: Definition, val difficulty: Difficulty, val worldName: String) {
    private var state = RunState.WAITING
    private var stage = 0
    private var count = 0
    private val credited = HashSet<UUID>()
    @Synchronized fun snapshot() = RunSnapshot(state, stage, count)
    @Synchronized fun transition(next: RunState): Boolean {
        val allowed = when(state) {
            RunState.WAITING -> setOf(RunState.PREPARING, RunState.FAILED)
            RunState.PREPARING -> setOf(RunState.RUNNING, RunState.FAILED)
            RunState.RUNNING -> setOf(RunState.PAUSED, RunState.COMPLETED, RunState.FAILED)
            RunState.PAUSED -> setOf(RunState.RUNNING, RunState.FAILED)
            RunState.COMPLETED, RunState.FAILED -> setOf(RunState.CLEANING)
            RunState.CLEANING -> emptySet()
        }
        if(next !in allowed) return false
        state = next; return true
    }
    /** Stage token makes late kills from previous waves harmless. */
    @Synchronized fun credit(kind: Objective, target: String, token: Int?, event: UUID): Boolean {
        if(state != RunState.RUNNING || stage >= definition.stages.size) return false
        val goal = definition.stages[stage]
        if(goal.objective != kind || goal.target != target || (token != null && token != stage)) return false
        if(!credited.add(event)) return false
        count++
        if(count < goal.count * (if(goal.objective in setOf(Objective.KILL,Objective.BOSS,Objective.COLLECT)) difficulty.goalMultiplier else 1)) return false
        stage++; count = 0; credited.clear()
        if(stage == definition.stages.size) state = RunState.COMPLETED
        return true
    }
    @Synchronized fun restore(index: Int, progress: Int) {
        require(state in setOf(RunState.WAITING,RunState.PREPARING) && index in definition.stages.indices && progress >= 0)
        stage = index; count = progress
    }
}
