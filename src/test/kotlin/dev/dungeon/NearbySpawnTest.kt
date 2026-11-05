package dev.dungeon

import dev.dungeon.farming.*
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.nio.file.Path
import java.util.UUID
import java.util.concurrent.Executors

class NearbySpawnTest {
    @Test fun `all deployed mob types have guaranteed copper and elite silver bonus`() {
        val drops=org.bukkit.configuration.file.YamlConfiguration.loadConfiguration(
            Path.of("deploy/lost_altar/MythicMobs/droptables/遗落圣坛.yml").toFile())
        val mobs=org.bukkit.configuration.file.YamlConfiguration.loadConfiguration(
            Path.of("deploy/lost_altar/MythicMobs/mobs/遗落圣坛.yml").toFile())
        assertEquals(7,mobs.getKeys(false).size)
        for(mob in mobs.getKeys(false)) {
            val table=mobs.getStringList("$mob.Drops").single()
            val entries=drops.getStringList("$table.Drops")
            assertTrue(entries.contains("dungeonce{id=currency:copper_coin} 1to3 1.0"),mob)
            if(table=="AltarEliteDrops") assertTrue(entries.contains("dungeonce{id=currency:silver_coin} 1 0.10"),mob)
        }
        assertEquals(2,settings().maxAbove);assertEquals(6,settings().maxBelow)
    }
    @Test fun `height search excludes roofs and deep floors including negative fractional Y`() {
        for(y in listOf(8.0,8.9,-0.2,-20.7)) {
            val heights=SpawnGeometry.heights(y,12,2,6)
            assertTrue(heights.isNotEmpty())
            assertTrue(heights.all { it-y<=2 && y-it<=6 })
            assertEquals(heights.sortedBy { kotlin.math.abs(it-y) },heights)
        }
        assertEquals(listOf(8),SpawnGeometry.heights(8.0,12,0,0))
        assertTrue(SpawnGeometry.heights(8.5,12,0,0).isEmpty())
        assertEquals(listOf(8,7,9),SpawnGeometry.heights(8.0,1,2,6))
        assertFalse(SpawnGeometry.heights(20.0,12,2,6).contains(8))
    }
    private fun settings()=SpawnSettings.load(Path.of("src/main/resources/dungeons/lost_altar/spawning.yml"))
    private fun player(x: Double=0.0)=FarmPresence(UUID.randomUUID(),x,49.0,0.0,0f,0)
    @Test fun `spawn caps scale with dispersed players and concurrent requests cannot overfill owner`() {
        val s=settings();val ledger=SpawnLedger(s);val p=player();val pool=Executors.newFixedThreadPool(4)
        try {
            pool.invokeAll((1..40).map { java.util.concurrent.Callable { ledger.reserve(p,s.mobs.first(),0) } })
            assertEquals(s.playerLimit,ledger.records.size)
            repeat(5) { val other=player(1000.0*(it+1));repeat(s.playerLimit) { assertNotNull(ledger.reserve(other,s.mobs.first(),0)) } }
            assertEquals(36,ledger.records.size)
        } finally { pool.shutdownNow() }
    }
    @Test fun `nearby groups share density and one elite per owner`() {
        val s=settings();val ledger=SpawnLedger(s);val elite=s.mobs.first { it.elite };val p=player()
        assertNotNull(ledger.reserve(p,elite,0));assertNull(ledger.reserve(p,elite,0))
        assertNotNull(ledger.reserve(player(),elite,0));assertNull(ledger.reserve(player(),elite,0))
        repeat(30) { ledger.reserve(player(),s.mobs.first(),0) }
        assertEquals(s.nearbyLimit,ledger.records.size)
    }
    @Test fun `relative compass handles all cardinal directions and player rotation`() {
        assertEquals(0,SpawnGeometry.direction(0.0,1.0,0f))
        assertEquals(2,SpawnGeometry.direction(-1.0,0.0,0f))
        assertEquals(4,SpawnGeometry.direction(0.0,-1.0,0f))
        assertEquals(6,SpawnGeometry.direction(1.0,0.0,0f))
        assertEquals(0,SpawnGeometry.direction(-1.0,0.0,90f))
        assertEquals(0,SpawnGeometry.direction(1.0,0.0,-90f))
        assertEquals(1,SpawnGeometry.direction(-1.0,1.0,0f))
    }
    @Test fun `compass frames follow relative yaw and wrap at north boundary`() {
        for(frame in 0..31) {
            val angle=Math.toRadians(frame*11.25)
            assertEquals(frame,SpawnGeometry.compassFrame(-kotlin.math.sin(angle),kotlin.math.cos(angle),0f))
            assertEquals(frame,SpawnGeometry.compassFrame(-kotlin.math.sin(angle),kotlin.math.cos(angle),720f))
        }
        assertEquals(0,SpawnGeometry.compassFrame(-1.0,0.0,90f))
        assertEquals(8,SpawnGeometry.compassFrame(-1.0,0.0,0f))
        assertEquals(16,SpawnGeometry.compassFrame(0.0,-1.0,0f))
        assertEquals(24,SpawnGeometry.compassFrame(1.0,0.0,0f))
        val h=EliteHudSettings.load(Path.of("src/main/resources/dungeons/lost_altar/elite-hud.yml"))
        for(frame in 0..31) {
            val values=h.values("target",10,((frame+2)/4)%8,frame)
            assertEquals(listOf("compass_$frame"),values.filter { it.key.startsWith("compass_") && it.value=="true" }.keys.toList())
        }
    }
    @Test fun `generated candidate stays within radius up to block rounding`() {
        val p=player()
        for(a in 0..99) for(r in 0..9) {
            val (x,z)=SpawnGeometry.candidate(p,16.0,32.0,a/100.0,r/10.0)
            val distance=kotlin.math.hypot(x+0.5-p.x,z+0.5-p.z)
            assertTrue(distance in 15.0..33.0)
        }
    }
    @Test fun `elite HUD uses configured direction and escapes dynamic name`() {
        val h=EliteHudSettings.load(Path.of("src/main/resources/dungeons/lost_altar/elite-hud.yml"))
        val raw=h.values("<red>测试",25,4).getValue("elite")
        assertTrue(raw.contains("身后"));assertTrue(raw.contains("25"));assertTrue(raw.contains("<red>测试"))
    }
}
