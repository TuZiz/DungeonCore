package dev.dungeon

import dev.dungeon.farming.FarmSettings
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path

class FarmTest {
    @TempDir lateinit var root: Path
    private val valid="schema-version: 1\nenabled: true\nid: 'lost_altar'\nworld: 'lost_altar'\nspawn:\n  x: 0.5\n  y: 49.0\n  z: 0.5\n"
    @Test fun `legacy farm migrates without changing comments and repeated startup is safe`() {
        val source = "# 保留注释\n" + valid + "unknown: '保留'\n"
        Files.writeString(root.resolve("farming.yml"), source)
        val path = dev.dungeon.farming.FarmConfiguration.prepare(root) { error("default must not overwrite existing config") }
        assertEquals(root.resolve("dungeons/lost_altar/farming.yml"), path)
        assertEquals(source, Files.readString(path))
        assertFalse(Files.exists(root.resolve("farming.yml")))
        assertEquals(path, dev.dungeon.farming.FarmConfiguration.prepare(root) { error("unexpected default") })
        val definitions = dev.dungeon.configuration.Definitions(root.resolve("dungeons"), emptyMap())
        definitions.reload()
        assertTrue(definitions.current.isEmpty())
    }
    @Test fun `migration conflict retains both original files`() {
        val legacy = Files.writeString(root.resolve("farming.yml"), valid)
        val folder = Files.createDirectories(root.resolve("dungeons/lost_altar"))
        val target = Files.writeString(folder.resolve("farming.yml"), "# existing\n" + valid)
        assertThrows(IllegalArgumentException::class.java) {
            dev.dungeon.farming.FarmConfiguration.prepare(root) { error("unexpected default") }
        }
        assertEquals(valid, Files.readString(legacy))
        assertEquals("# existing\n" + valid, Files.readString(target))
    }
    @Test fun `farm folder rejects conflicting staged config and mismatched ID`() {
        val folder = Files.createDirectories(root.resolve("dungeons/lost_altar"))
        Files.writeString(folder.resolve("farming.yml"), valid.replace("id: 'lost_altar'", "id: 'another'"))
        val definitions = dev.dungeon.configuration.Definitions(root.resolve("dungeons"), emptyMap())
        assertThrows(IllegalArgumentException::class.java) { definitions.reload() }
        Files.writeString(folder.resolve("farming.yml"), valid)
        Files.writeString(folder.resolve("dungeon.yml"), "schema-version: 1\n")
        assertThrows(IllegalArgumentException::class.java) { definitions.reload() }
    }
    @Test fun `permanent farm rejects bad schema IDs and nonfinite spawn without changing original`() {
        val path=root.resolve("farming.yml")
        val valid="schema-version: 1\nenabled: true\nid: 'lost_altar'\nworld: 'lost_altar'\nspawn:\n  x: 0.5\n  y: 49.0\n  z: 0.5\n"
        Files.writeString(path,valid)
        assertEquals(49.0,FarmSettings.load(path).y)
        assertTrue(FarmSettings.load(path).keepInventory)
        Files.writeString(path,valid+"keep-inventory: false\n")
        assertFalse(FarmSettings.load(path).keepInventory)
        for(bad in listOf(valid.replace("version: 1","version: 9"),valid.replace("world: 'lost_altar'","world: 'dungeon_unsafe'"),valid.replace("x: 0.5","x: .NaN"))) {
            Files.writeString(path,bad)
            assertThrows(IllegalArgumentException::class.java) { FarmSettings.load(path) }
            assertEquals(bad,Files.readString(path))
        }
    }
    @Test fun `spawn orientation is loaded and invalid angles rejected`() {
        val path=root.resolve("farming.yml")
        val configured=valid.replace("  z: 0.5","  z: 0.5\n  yaw: -173.7\n  pitch: -7.2")
        Files.writeString(path,configured)
        val settings=FarmSettings.load(path)
        assertEquals(-173.7f,settings.yaw);assertEquals(-7.2f,settings.pitch)
        for(invalid in listOf(configured.replace("pitch: -7.2","pitch: 91"),configured.replace("yaw: -173.7","yaw: .NaN"),configured+"keep-inventory: 'true'\n")) {
            Files.writeString(path,invalid)
            assertThrows(IllegalArgumentException::class.java) { FarmSettings.load(path) }
        }
    }
}
