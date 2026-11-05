package dev.dungeon

import dev.dungeon.lobby.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import java.nio.file.Files
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.security.MessageDigest
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class LobbyTest {
    @TempDir lateinit var temp: Path
    private val resource = Path.of("src/main/resources/maps/starfall")
    private fun manifest() = Files.newBufferedReader(resource.resolve("manifest.yml")).use(MapManifest::read)
    private fun archive(name: String, bytes: ByteArray): ByteArray {
        val output = ByteArrayOutputStream()
        ZipOutputStream(output).use { it.putNextEntry(ZipEntry(name));it.write(bytes);it.closeEntry() }
        return output.toByteArray()
    }
    @Test fun `map version prevents downgrade and permits current server`() {
        val map = manifest();assertEquals(4903,map.dataVersion)
        assertThrows(IllegalArgumentException::class.java) { map.requireCompatible(4440) }
        map.requireCompatible(4903);map.requireCompatible(5023)
    }
    @Test fun `actual map imports with all checksums and preserves edits on repeat startup`() {
        val installer = PermanentMapInstaller();val map = manifest();val markers = temp.resolve("plugin-data")
        val world = installer.install(temp,markers,"Starfall_Dungeon_Hub",map) { Files.newInputStream(resource.resolve("world.zip")) }
        assertTrue(Files.isRegularFile(markers.resolve("starfall_dungeon_hub.permanent")))
        map.files.filterKeys { it.startsWith("dimensions/minecraft/overworld/") }.forEach { (file,expected) ->
            val actual = file.removePrefix("dimensions/minecraft/overworld/")
            val hash = MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(world.resolve(actual))).joinToString("") { "%02x".format(it) }
            assertEquals(expected,hash,file)
        }
        val protected = world.resolve("region/r.0.0.mca");Files.writeString(protected,"operator-modified")
        installer.install(temp,markers,"Starfall_Dungeon_Hub",map) { error("Existing map must never re-import") }
        assertEquals("operator-modified",Files.readString(protected))
        assertTrue(Files.isDirectory(world.resolve("region")))
    }
    @Test fun `existing unowned world is not overwritten`() {
        val world = Files.createDirectories(temp.resolve("dimensions/minecraft/starfall_dungeon_hub"))
        Files.createDirectories(world.resolve("region"));Files.writeString(world.resolve("region/keep"),"keep")
        assertThrows(IllegalArgumentException::class.java) {
            PermanentMapInstaller().install(temp,temp.resolve("plugin-data"),"Starfall_Dungeon_Hub",manifest()) { error("Must not open archive") }
        }
        assertEquals("keep",Files.readString(world.resolve("region/keep")))
    }
    @Test fun `zip traversal or wrong checksum leaves no published world or temporary folder`() {
        val map = manifest();val installer = PermanentMapInstaller()
        listOf("../escape.txt","level.dat").forEach { name ->
            assertThrows(IllegalStateException::class.java) {
                installer.install(temp,temp.resolve("plugin-data"),"Starfall_Dungeon_Hub",map) { ByteArrayInputStream(archive(name,"bad".toByteArray())) }
            }
            assertFalse(Files.exists(temp.resolve("dimensions/minecraft/starfall_dungeon_hub")))
            assertFalse(Files.list(temp).use { paths -> paths.anyMatch { it.fileName.toString().startsWith(".dungeon-lobby-import-") } })
        }
        assertFalse(Files.exists(temp.parent.resolve("escape.txt")))
    }
    @Test fun `lobby config has exact map spawn and cannot use temporary instance name`() {
        val settings = LobbySettings.load(Path.of("src/main/resources/lobby.yml"))
        assertEquals("Starfall_Dungeon_Hub",settings.world);assertEquals(0.5,settings.x)
        assertEquals(101.0,settings.y);assertEquals(20.5,settings.z);assertEquals(180f,settings.yaw)
        val file = temp.resolve("lobby.yml")
        Files.writeString(file,Files.readString(Path.of("src/main/resources/lobby.yml")).replace("Starfall_Dungeon_Hub","dungeon_not_permanent"))
        assertThrows(IllegalArgumentException::class.java) { LobbySettings.load(file) }
    }
    @Test fun `primary level root is derived from dimension folder for modern servers`() {
        val primary = Path.of("server","world","dimensions","minecraft","overworld")
        assertEquals(Path.of("server","world").toAbsolutePath().normalize(),LobbyWorldRoot.fromPrimaryWorldFolder(primary))
        val legacy = Path.of("server","world")
        assertEquals(legacy.toAbsolutePath().normalize(),LobbyWorldRoot.fromPrimaryWorldFolder(legacy))
    }
}
