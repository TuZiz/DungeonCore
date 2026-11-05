package dev.dungeon

import dev.dungeon.model.*
import dev.dungeon.persistence.*
import dev.dungeon.world.TemplateFiles
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import java.nio.file.Files
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Executors

class ReliabilityTest {
    @TempDir lateinit var temp: Path
    private fun run(): Run {
        val d = Definition("test",Mode.PERMANENT,ProgressScope.PARTY,"world","",1,4,100,10,true,
            emptyMap(),listOf(Stage("one",Objective.KILL,"mob",20,emptyList()),Stage("two",Objective.INTERACT,"switch",1,emptyList())),
            emptyMap(),emptyList(),mapOf("normal" to Difficulty("normal",1)),emptyMap())
        return Run(UUID.randomUUID(),d,d.difficulties.getValue("normal"),"world")
    }
    @Test fun `concurrent duplicate kill is counted once and previous stage token rejected`() {
        val r = run();assertTrue(r.transition(RunState.PREPARING));assertTrue(r.transition(RunState.RUNNING))
        val pool = Executors.newFixedThreadPool(4)
        try {
            val ids = List(20) { UUID.randomUUID() }
            val futures = (ids + ids).map { id -> CompletableFuture.runAsync({ r.credit(Objective.KILL,"mob",0,id) },pool) }
            CompletableFuture.allOf(*futures.toTypedArray()).join()
            assertEquals(1,r.snapshot().stage)
            assertFalse(r.credit(Objective.INTERACT,"switch",0,UUID.randomUUID()))
            assertTrue(r.credit(Objective.INTERACT,"switch",1,UUID.randomUUID()))
            assertEquals(RunState.COMPLETED,r.snapshot().state)
            assertFalse(r.transition(RunState.RUNNING));assertTrue(r.transition(RunState.CLEANING))
            assertFalse(r.transition(RunState.PREPARING))
        } finally { pool.shutdownNow() }
    }
    @Test fun `script cancellation and unknown action validation`() {
        val queue = java.util.ArrayDeque<() -> Unit>()
        val engine = dev.dungeon.script.ScriptEngine { _,f -> queue.add(f) }
        var executed = 0
        engine.registerAction("count") { _,_ -> executed++ }
        val rule = dev.dungeon.script.ScriptRule("started",emptyList(),listOf(dev.dungeon.script.ScriptCall("count")))
        engine.validate(listOf(rule))
        var alive = true
        engine.fire(listOf(rule),"started",dev.dungeon.script.ScriptContext(UUID.randomUUID(),0,"RUNNING",emptySet()),{ alive },{ throw it })
        alive = false;queue.removeFirst().invoke();assertEquals(0,executed)
        assertThrows(IllegalArgumentException::class.java) {
            engine.validate(listOf(rule.copy(actions=listOf(dev.dungeon.script.ScriptCall("missing")))))
        }
    }
    @Test fun `schema migration retains unknown fields comments and is idempotent`() {
        val file = temp.resolve("config.yml")
        Files.writeString(file,"# 中文注释\nschema-version: 0\nunknown: '保留'\n")
        dev.dungeon.configuration.SchemaMigration.migrate(file)
        val migrated = Files.readString(file)
        assertTrue(migrated.contains("# 中文注释"));assertTrue(migrated.contains("unknown: '保留'"))
        assertTrue(migrated.contains("schema-version: 1"))
        dev.dungeon.configuration.SchemaMigration.migrate(file);assertEquals(migrated,Files.readString(file))
        assertTrue(Files.exists(temp.resolve("config.yml.v0.bak")))
    }
    @Test fun `default configuration references and rejected reload retain last snapshot`() {
        val root = Files.createDirectory(temp.resolve("dungeons"))
        val example = Files.createDirectory(root.resolve("example"))
        val source = Path.of("src/main/resources/dungeons/example")
        Files.walk(source).use { paths -> paths.forEach { p ->
            val dest = example.resolve(source.relativize(p))
            if(Files.isDirectory(p)) Files.createDirectories(dest) else Files.copy(p,dest)
        } }
        val definitions = dev.dungeon.configuration.Definitions(root,mapOf("DIAMOND" to 64,"EMERALD" to 64));definitions.reload()
        assertEquals(2,definitions.current.getValue("example").stages.size)
        assertTrue(definitions.current.getValue("example").keepInventory)
        val configFile=example.resolve("dungeon.yml")
        val configured=Files.readString(configFile)
        Files.writeString(configFile,configured.replace("keep-inventory: true","keep-inventory: false"))
        definitions.reload("example");assertFalse(definitions.current.getValue("example").keepInventory)
        Files.writeString(configFile,configured.replace("keep-inventory: true\n",""))
        definitions.reload("example");assertTrue(definitions.current.getValue("example").keepInventory)
        val guard = definitions.current.getValue("example").mobs.getValue("guard")
        assertTrue(guard.mythic)
        val mythic = org.bukkit.configuration.file.YamlConfiguration().apply {
            load(Path.of("src/main/resources/integrations/mythicmobs/DungeonExample.yml").toFile())
        }
        assertTrue(mythic.isConfigurationSection(guard.type),"Dungeon mob ID must match supplied MM definition")
        assertEquals("ZOMBIE",mythic.getString("${guard.type}.Type"))
        assertTrue(mythic.getDouble("${guard.type}.Health") > 0)
        val before = definitions.current
        Files.writeString(example.resolve("stages.yml"),"schema-version: 1\nstages: []\n")
        assertThrows(IllegalArgumentException::class.java) { definitions.reload("example") }
        assertSame(before,definitions.current)
    }
    @Test fun `combat configuration cannot target lobby even with different case`() {
        val root = Files.createDirectory(temp.resolve("combat"))
        val source = Path.of("src/main/resources/dungeons/example")
        val example = Files.createDirectory(root.resolve("example"))
        Files.walk(source).use { paths -> paths.forEach { p ->
            val dest = example.resolve(source.relativize(p))
            if(Files.isDirectory(p)) Files.createDirectories(dest) else Files.copy(p,dest)
        } }
        val file = example.resolve("dungeon.yml")
        Files.writeString(file,Files.readString(file).replace("world: 'world'","world: 'starfall_dungeon_hub'"))
        val definitions = dev.dungeon.configuration.Definitions(root,mapOf("DIAMOND" to 64,"EMERALD" to 64),"Starfall_Dungeon_Hub")
        val error = assertThrows(IllegalArgumentException::class.java) { definitions.reload() }
        assertTrue(error.message!!.contains("permanent lobby"))
        assertTrue(definitions.current.isEmpty())
    }
    @Test fun `completion transaction records first clear and unlocks only once`() {
        val store = SqliteStore(temp.resolve("complete.db"));val player = UUID.randomUUID();val run = UUID.randomUUID()
        try {
            assertEquals(1,store.unlocked(player,"campaign").join())
            assertEquals(true,store.complete(player,run,"one","normal",10,"campaign",1).join())
            assertNull(store.complete(player,run,"one","normal",10,"campaign",1).join())
            assertEquals(2,store.unlocked(player,"campaign").join())
            assertEquals(false,store.complete(player,UUID.randomUUID(),"one","normal",20,"campaign",1).join())
        } finally { store.closeAsync().join() }
    }
    @Test fun `pause freezes progress`() {
        val r = run();r.transition(RunState.PREPARING);r.transition(RunState.RUNNING);r.transition(RunState.PAUSED)
        assertFalse(r.credit(Objective.KILL,"mob",0,UUID.randomUUID()));assertEquals(0,r.snapshot().count)
        assertTrue(r.transition(RunState.RUNNING))
    }
    @Test fun `SQLite reward reservation survives reopen and parallel requests`() {
        val path = temp.resolve("test.db");val player = UUID.randomUUID();val run = UUID.randomUUID()
        val store = SqliteStore(path)
        val attempts = List(30) { store.reserveReward(player,"test",run) }
        assertEquals(1,attempts.count { it.join() })
        store.save(player,"story",RunSnapshot(RunState.PAUSED,2,4)).join()
        assertEquals(2 to 4,store.progress(player,"story").join())
        store.rememberReturn(player,ReturnPoint("world",1.0,2.0,3.0,0f,0f)).join()
        assertEquals("world",store.returnPoint(player).join()?.world)
        store.delivered(player,run).join();store.closeAsync().join()
        val reopened = SqliteStore(path)
        try { assertFalse(reopened.reserveReward(player,"test",run).join()) } finally { reopened.closeAsync().join() }
    }
    @Test fun `template deletion cannot target permanent or unowned world`() {
        val templates = Files.createDirectory(temp.resolve("templates"));val worlds = Files.createDirectory(temp.resolve("worlds"))
        val source = Files.createDirectory(templates.resolve("base"));Files.writeString(source.resolve("level.dat"),"map")
        Files.writeString(source.resolve("uid.dat"),"old")
        val permanent = Files.createDirectory(worlds.resolve("permanent"));Files.writeString(permanent.resolve("level.dat"),"keep")
        val files = TemplateFiles(templates,worlds);val id = UUID.randomUUID();val name = files.copy("base",id)
        assertFalse(Files.exists(worlds.resolve(name).resolve("uid.dat")))
        assertThrows(IllegalArgumentException::class.java) { files.delete("permanent",id) }
        Files.writeString(worlds.resolve(name).resolve(".dungeon-owner"),"wrong")
        assertThrows(IllegalArgumentException::class.java) { files.delete(name,id) }
        Files.writeString(worlds.resolve(name).resolve(".dungeon-owner"),id.toString());files.delete(name,id)
        assertTrue(Files.exists(permanent.resolve("level.dat")))
        assertFalse(Files.exists(worlds.resolve(name)))
    }
}
