package dev.dungeon.model

enum class Mode { PERMANENT, TEMPORARY, STORY }
enum class ProgressScope { SHARED, PARTY }
enum class Objective { KILL, BOSS, INTERACT, EXPLORE, COLLECT, ESCORT }
data class Point(val x: Double, val y: Double, val z: Double) {
    fun near(x: Double, y: Double, z: Double, radius: Double) =
        (this.x-x)*(this.x-x)+(this.y-y)*(this.y-y)+(this.z-z)*(this.z-z) <= radius*radius
}
data class Mob(val id: String, val type: String, val mythic: Boolean, val location: String, val count: Int)
data class Stage(val id: String, val objective: Objective, val target: String, val count: Int, val mobs: List<String>, val destination: String? = null)
data class Reward(val material: String, val amount: Int)
data class Difficulty(val id: String, val goalMultiplier: Int)
data class Definition(
    val id: String, val mode: Mode, val scope: ProgressScope, val world: String, val template: String,
    val min: Int, val max: Int, val timeoutSeconds: Long, val reconnectSeconds: Long, val deathFails: Boolean,
    val points: Map<String, Point>, val stages: List<Stage>, val mobs: Map<String, Mob>,
    val rewards: List<Reward>, val difficulties: Map<String, Difficulty>, val obstacleDamage: Map<String, Double>, val scripts: List<dev.dungeon.script.ScriptRule> = emptyList(),
    val campaign: String = id, val chapter: Int = 1, val firstRewards: List<Reward> = emptyList(),
    val keepInventory: Boolean = true
)
