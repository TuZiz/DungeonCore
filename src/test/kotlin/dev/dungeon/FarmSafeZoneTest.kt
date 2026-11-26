package dev.dungeon

import dev.dungeon.farming.FarmSafeZone
import dev.dungeon.farming.FarmSettings
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class FarmSafeZoneTest {
    private val farm=FarmSettings(true,"lost_altar","lost_altar",150.5,8.0,48.5)

    @Test fun `safe zone matches spawn exclusion circle and never affects another world`() {
        val zone=FarmSafeZone(farm,24.0)
        assertTrue(zone.contains(farm.world,farm.x,farm.y,farm.z))
        assertTrue(zone.contains(farm.world,farm.x+23.9,farm.y,farm.z))
        assertTrue(zone.contains(farm.world,farm.x-12,farm.y,farm.z+12))
        assertFalse(zone.contains(farm.world,farm.x+24,farm.y,farm.z))
        assertFalse(zone.contains(farm.world,farm.x+20,farm.y,farm.z+20))
        assertFalse(zone.contains("world",farm.x,farm.y,farm.z))
        assertFalse(FarmSafeZone(farm.copy(enabled=false),24.0).contains(farm.world,farm.x,farm.y,farm.z))
        assertFalse(FarmSafeZone(farm,0.0).contains(farm.world,farm.x,farm.y,farm.z))
    }

    @Test fun `configured cuboid replaces old circle and respects height and inclusive blocks`() {
        val s=dev.dungeon.farming.SpawnSettings.load(java.nio.file.Path.of("src/main/resources/dungeons/lost_altar/spawning.yml"))
        val configured=dev.dungeon.farming.FarmSettings.load(java.nio.file.Path.of("deploy/lost_altar/dungeons/lost_altar/farming.yml"))
        val zone=FarmSafeZone(configured,s.safeRadius,s.safeZone)
        assertTrue(zone.contains("lost_altar",-130.406,7.0,177.232))
        assertTrue(zone.contains("lost_altar",-164.0,0.0,155.0))
        assertTrue(zone.contains("lost_altar",-123.01,24.99,186.99))
        assertFalse(zone.contains("lost_altar",-123.0,7.0,177.0))
        assertFalse(zone.contains("lost_altar",-131.0,25.0,177.0))
        assertFalse(zone.contains("lost_altar",-131.0,-0.01,177.0))
        assertFalse(zone.contains("lost_altar",150.5,8.0,48.5))
        assertFalse(zone.contains("world",-131.0,7.0,177.0))
    }

    @Test fun `reversed or malformed safe zone never silently falls back`() {
        val c=org.bukkit.configuration.file.YamlConfiguration().apply {
            load(java.io.File("src/main/resources/dungeons/lost_altar/spawning.yml"))
        }
        c.set("safe-zone.min.y",30)
        assertThrows(IllegalArgumentException::class.java) { dev.dungeon.farming.SafeZoneBounds.load(c,java.nio.file.Path.of("spawning.yml")) }
        c.set("safe-zone.min.y","bad")
        assertThrows(IllegalArgumentException::class.java) { dev.dungeon.farming.SafeZoneBounds.load(c,java.nio.file.Path.of("spawning.yml")) }
    }
}
