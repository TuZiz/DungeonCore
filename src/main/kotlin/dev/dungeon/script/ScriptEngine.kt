package dev.dungeon.script

import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

data class ScriptCall(val type: String, val argument: String = "", val delayTicks: Long = 0)
data class ScriptRule(val trigger: String, val conditions: List<ScriptCall>, val actions: List<ScriptCall>)
data class ScriptContext(val run: UUID, val stage: Int, val state: String, val players: Set<UUID>)
fun interface ScriptCondition { fun matches(context: ScriptContext, argument: String): Boolean }
fun interface ScriptAction { fun execute(context: ScriptContext, argument: String) }
/** Extension callbacks must dispatch platform work; this engine never assumes entity ownership. */
class ScriptEngine(private val later: (Long, () -> Unit) -> Unit) {
    private val triggers = ConcurrentHashMap.newKeySet<String>().apply { addAll(setOf("started","stage","completed")) }
    fun registerTrigger(id: String) { require(id.matches(Regex("[a-z0-9_-]+")));check(triggers.add(id)) }
    private val conditions = ConcurrentHashMap<String,ScriptCondition>()
    private val actions = ConcurrentHashMap<String,ScriptAction>()
    init {
        registerCondition("state") { c,a -> c.state == a }
        registerCondition("stage") { c,a -> c.stage.toString() == a }
    }
    fun registerCondition(id: String, condition: ScriptCondition) { check(conditions.putIfAbsent(id,condition) == null) }
    fun registerAction(id: String, action: ScriptAction) { check(actions.putIfAbsent(id,action) == null) }
    fun validate(rules: List<ScriptRule>) {
        rules.forEach { rule ->
            require(triggers.contains(rule.trigger)) { "Unknown trigger ${rule.trigger}" }
            require(rule.conditions.size <= 16 && rule.actions.size <= 64 && rule.actions.sumOf { it.delayTicks } <= 600)
            rule.conditions.forEach { require(conditions.containsKey(it.type)) { "Unknown condition ${it.type}" } }
            rule.actions.forEach { require(actions.containsKey(it.type) && it.delayTicks in 0..600) { "Invalid action ${it.type}" } }
        }
    }
    fun fire(rules: List<ScriptRule>, trigger: String, context: ScriptContext, valid: () -> Boolean, failure: (Throwable) -> Unit) {
        val deadline = System.nanoTime()+30_000_000_000L
        rules.filter { it.trigger == trigger }.forEach { rule ->
            try {
                if(!valid() || !rule.conditions.all { conditions.getValue(it.type).matches(context,it.argument) }) return@forEach
                fun step(index: Int) {
                    if(index >= rule.actions.size || !valid() || System.nanoTime() > deadline) return
                    val action = rule.actions[index]
                    later(action.delayTicks.coerceAtLeast(1)) {
                        if(valid() && System.nanoTime() <= deadline) try {
                            actions.getValue(action.type).execute(context,action.argument);step(index+1)
                        } catch(e: Exception) { failure(e) }
                    }
                }
                step(0)
            } catch(e: Exception) { failure(e) }
        }
    }
}
