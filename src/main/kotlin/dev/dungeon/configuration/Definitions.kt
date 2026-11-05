package dev.dungeon.configuration

import dev.dungeon.model.*
import org.bukkit.configuration.file.YamlConfiguration
import java.nio.file.Files
import java.nio.file.Path

/** Parse on IO workers, publish only fully validated snapshots. Running instances keep their definition. */
class Definitions(private val root: Path, private val materials: Map<String,Int>, private val lobbyWorld: String? = null) {
    @Volatile var scriptValidator: ((List<dev.dungeon.script.ScriptRule>) -> Unit)? = null
    @Volatile var current: Map<String, Definition> = emptyMap(); private set
    @Synchronized fun reload(id: String? = null) {
        val next = if(id == null) {
            Files.list(root).use { paths -> paths.filter { Files.isDirectory(it) }.toList().filter { folder ->
                if (Files.exists(folder.resolve("farming.yml"))) {
                    dev.dungeon.farming.FarmConfiguration.requireDedicatedFolder(folder)
                    false
                } else true
            }.associate { it.fileName.toString() to load(it) } }
        } else {
            require(id.matches(Regex("[a-z0-9_-]+"))) { "Invalid dungeon ID: $id" }
            current + (id to load(root.resolve(id)))
        }
        current = next
    }
    fun validate(id: String) { require(id.matches(Regex("[a-z0-9_-]+"))); load(root.resolve(id)) }
    private fun yaml(dir: Path, file: String, chain: Set<Path> = emptySet()): YamlConfiguration {
        val path = dir.resolve(file)
        require(path.toAbsolutePath().normalize() !in chain && chain.size < 8) { "Inheritance cycle/depth at $path" }
        val y = YamlConfiguration()
        require(Files.isRegularFile(path)) { "Missing $path" }
        SchemaMigration.migrate(path)
        y.load(path.toFile())
        require(y.getInt("schema-version") == 1) { "$path schema-version must be 1; migration unavailable, original retained" }
        val parent = y.getString("extends") ?: return y
        require(parent.matches(Regex("[a-z0-9_-]+"))) { "$path extends=$parent invalid component ID" }
        val inherited = yaml(root.parent.resolve("components").resolve(parent),file,chain+path.toAbsolutePath().normalize())
        y.getValues(true).forEach { (key,value) ->
            if(value !is org.bukkit.configuration.ConfigurationSection && key != "extends") inherited.set(key,value)
        }
        return inherited
    }
    private fun load(dir: Path): Definition {
        try { return parse(dir) } catch(e: Exception) { throw IllegalArgumentException("$dir: ${e.message}", e) }
    }
    private fun parse(dir: Path): Definition {
        require(!Files.exists(dir.resolve("rooms.yml"))) { "rooms.yml random generation is not implemented in this MVP" }
        val d = yaml(dir,"dungeon.yml"); val l = yaml(dir,"locations.yml"); val s = yaml(dir,"stages.yml")
        val m = yaml(dir,"mobs.yml"); val r = yaml(dir,"rewards.yml"); val o = yaml(dir,"obstacles.yml")
        fun numeric(y: YamlConfiguration, key: String, required: Boolean = false) {
            val value = y.get(key)
            require((value == null && !required) || (value is Number && value.toDouble().isFinite())) { "${dir.fileName}: $key invalid numeric value: $value" }
        }
        listOf("min-players","max-players","timeout-seconds","reconnect-seconds","chapter").forEach { numeric(d,it) }
        if(d.contains("death-fails")) require(d.get("death-fails") is Boolean) { "dungeon.yml death-fails must be boolean" }
        if(d.contains("keep-inventory")) require(d.get("keep-inventory") is Boolean) { "dungeon.yml keep-inventory must be boolean" }
        fun text(y: YamlConfiguration, key: String) = requireNotNull(y.getString(key)) { "Missing $key" }.also { require(it.isNotBlank()) { "Empty $key" } }
        val points = l.getConfigurationSection("points")?.getKeys(false).orEmpty().associateWith { key ->
            listOf("x","y","z").forEach { numeric(l,"points.$key.$it",true) }
            Point(l.getDouble("points.$key.x"),l.getDouble("points.$key.y"),l.getDouble("points.$key.z"))
        }
        require("spawn" in points && "exit" in points) { "locations.yml points.spawn/exit required" }
        val mobs = m.getConfigurationSection("mobs")?.getKeys(false).orEmpty().associateWith { key ->
            val p = "mobs.$key"
            numeric(m,"$p.count")
            if(m.contains("$p.mythic")) require(m.get("$p.mythic") is Boolean) { "mobs.yml $p.mythic must be boolean" }
            Mob(key,text(m,"$p.type"),m.getBoolean("$p.mythic"),text(m,"$p.location"),m.getInt("$p.count",1)).also {
                require(it.location in points && it.count in 1..100) { "mobs.yml $p invalid location/count" }
                if(!it.mythic) require(org.bukkit.entity.EntityType.valueOf(it.type).isAlive) { "$p.type must be living" }
            }
        }
        val stages = s.getMapList("stages").map { row ->
            require(row["count"] == null || row["count"] is Number) { "stages.yml count invalid: ${row["count"]}" }
            Stage(requireNotNull(row["id"]).toString(),Objective.valueOf(requireNotNull(row["objective"]).toString()),
                requireNotNull(row["target"]).toString(),(row["count"] as? Number)?.toInt() ?: 1,
                (row["mobs"] as? List<*>)?.map { it.toString() }.orEmpty(), row["destination"]?.toString()).also { stage ->
                require(stage.count in 1..10000 && stage.mobs.all { it in mobs }) { "stages.yml ${stage.id}: invalid count/mob reference" }
                if(stage.objective in setOf(Objective.EXPLORE,Objective.INTERACT)) require(stage.target in points) { "${stage.id}: unknown location ${stage.target}" }
                if(stage.objective == Objective.ESCORT) require(stage.target in mobs && stage.target in stage.mobs && stage.destination in points && stage.count == 1) { "${stage.id}: escort needs target mob, matching wave, destination point and count=1" }
                if(stage.objective == Objective.COLLECT) require(materials.containsKey(stage.target)) { "${stage.id}: invalid material" }
                if(stage.objective in setOf(Objective.KILL,Objective.BOSS)) require(stage.target in mobs) { "${stage.id}: unknown mob ${stage.target}" }
            }
        }
        require(stages.isNotEmpty() && stages.map { it.id }.toSet().size == stages.size) { "stages.yml empty/duplicate stages" }
        fun rewards(key: String) = r.getMapList(key).map { row ->
            Reward(requireNotNull(row["material"]).toString(),(row["amount"] as Number).toInt()).also {
                val maxStack = materials[it.material]
                require(maxStack != null && it.amount in 1..maxStack) { "rewards.yml invalid $it" }
            }
        }
        val rewards = rewards("completion");val firstRewards = rewards("first-completion")
        val diffs = d.getConfigurationSection("difficulties")?.getKeys(false).orEmpty().associateWith { key ->
            // Inheritance only changes supported goal-multiplier, so it cannot silently override unrelated settings.
            numeric(d,"difficulties.$key.goal-multiplier")
            val parent = d.getString("difficulties.$key.extends")
            require(parent != key && (parent == null || d.contains("difficulties.$parent"))) { "difficulty $key invalid parent" }
            if(parent != null) require(!d.contains("difficulties.$parent.extends")) { "difficulty inheritance depth exceeds 1" }
            Difficulty(key,d.getInt("difficulties.$key.goal-multiplier",if(parent == null) 1 else d.getInt("difficulties.$parent.goal-multiplier",1))).also { require(it.goalMultiplier in 1..10) }
        }
        require(diffs.isNotEmpty()) { "difficulties required" }
        val damage = o.getConfigurationSection("damage")?.getKeys(false).orEmpty().associateWith {
            require(it in points) { "obstacles.yml damage.$it unknown location" }
            numeric(o,"damage.$it",true)
            o.getDouble("damage.$it").also { n -> require(n.isFinite() && n in 0.0..100.0) }
        }
        val mode = Mode.valueOf(text(d,"mode")); val min = d.getInt("min-players",1); val max = d.getInt("max-players",4)
        require(min in 1..max && max <= 40) { "invalid min/max players" }
        val timeout = d.getLong("timeout-seconds",1800); val reconnect = d.getLong("reconnect-seconds",120)
        require(timeout in 10..86400 && reconnect in 1..3600)
        val world = text(d,"world")
        require(lobbyWorld == null || !world.equals(lobbyWorld,true)) {
            "dungeon.yml world=$world is the permanent lobby; combat dungeons must use a separate world"
        }
        val template = d.getString("template","")!!
        require(mode != Mode.TEMPORARY || template.matches(Regex("[a-zA-Z0-9_-]+"))) { "invalid template" }
        val scriptPath = dir.resolve("scripts/events.yml")
        val rules = if(Files.exists(scriptPath)) {
            val script = yaml(dir,"scripts/events.yml")
            script.getMapList("rules").map { row ->
                fun calls(key: String) = (row[key] as? List<*>)?.map { value ->
                    val entry = value as Map<*,*>
                    dev.dungeon.script.ScriptCall(requireNotNull(entry["type"]).toString(),entry["argument"]?.toString() ?: "",(entry["delay-ticks"] as? Number)?.toLong() ?: 0)
                }.orEmpty()
                dev.dungeon.script.ScriptRule(requireNotNull(row["trigger"]).toString(),calls("conditions"),calls("actions"))
            }.also { rules ->
                require(rules.size <= 64) { "scripts/events.yml exceeds 64 rules" }
                val custom = scriptValidator
                if(custom != null) custom(rules) else {
                    val validator = dev.dungeon.script.ScriptEngine { _,_ -> }
                    validator.registerAction("message") { _,_ -> };validator.validate(rules)
                }
            }
        } else emptyList()
        val chapter = d.getInt("chapter",1);require(chapter in 1..10000) { "dungeon.yml chapter invalid: $chapter" }
        val campaign = d.getString("campaign",dir.fileName.toString())!!
        require(campaign.matches(Regex("[a-z0-9_-]+"))) { "dungeon.yml campaign invalid: $campaign" }
        return Definition(dir.fileName.toString(),mode,ProgressScope.valueOf(text(d,"progress-scope")),world,template,min,max,timeout,reconnect,
            d.getBoolean("death-fails",true),points,stages,mobs,rewards,diffs,damage,rules,campaign,chapter,firstRewards,d.getBoolean("keep-inventory",true))
    }
}
