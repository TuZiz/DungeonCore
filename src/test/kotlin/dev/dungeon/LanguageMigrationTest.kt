package dev.dungeon

import dev.dungeon.localization.LanguageMigration
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path

class LanguageMigrationTest {
    @TempDir lateinit var root: Path
    private val defaults get() = Files.readString(Path.of("src/main/resources",LanguageMigration.RESOURCE))

    @Test fun `moves custom text and comments once and recovers interrupted cleanup`() {
        val main = root.resolve(LanguageMigration.RESOURCE)
        Files.createDirectories(main.parent)
        val original = "schema-version: 1 # local\r\n# common comment\r\npermission: 'common'\r\n"
        val boss = "schema-version: 0\n# boss comment\npermission: 'boss'\nunknown:\n  text: |+\n    ...\n\n"
        val oldBoss = main.resolveSibling("boss_zh_CN.yml")
        Files.writeString(main,original)
        Files.writeString(oldBoss,boss)
        val result = LanguageMigration.load(root,defaults)
        assertEquals(2,result.getInt("schema-version"))
        assertEquals("common",result.getString("permission"))
        assertEquals("boss",result.getString("boss.permission"))
        assertEquals("...\n\n",result.getString("boss.unknown.text"))
        assertNotNull(result.getString("boss.spoils-name"))
        assertEquals(8,result.getStringList("elite.directions").size)
        val merged = Files.readString(main)
        assertTrue("# common comment" in merged && "# boss comment" in merged && "# local" in merged)
        assertEquals(original,Files.readString(root.resolve("backups/language-v2/zh_CN.yml")))
        assertEquals(boss,Files.readString(root.resolve("backups/language-v2/boss_zh_CN.yml")))
        assertFalse(Files.exists(oldBoss))
        Files.writeString(oldBoss,boss)
        LanguageMigration.load(root,defaults)
        assertFalse(Files.exists(oldBoss))
        assertEquals(merged,Files.readString(main))
    }

    @Test fun `invalid legacy cannot replace original or remove files`() {
        val main=root.resolve(LanguageMigration.RESOURCE)
        Files.createDirectories(main.parent)
        Files.writeString(main,"schema-version: 1\npermission: 'common'\n")
        val malformed=main.resolveSibling("elite_zh_CN.yml")
        Files.writeString(malformed,"schema-version: 9\n")
        val original=Files.readString(main)
        assertThrows(IllegalStateException::class.java) { LanguageMigration.load(root,defaults) }
        assertEquals(original,Files.readString(main))
        assertTrue(Files.exists(malformed))
    }
}
