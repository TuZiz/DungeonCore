package dev.dungeon

import dev.dungeon.boss.BossImpact
import dev.dungeon.boss.BossSettings
import dev.dungeon.boss.battle.BossRecoil
import org.bukkit.util.Vector
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import kotlin.math.hypot

class BossRecoilTest {
    @TempDir lateinit var folder: Path
    @Test fun `impulse is capped and AI resumes at deadline`() {
        val recoil=BossRecoil(BossImpact())
        val input=Vector(.6,.4,.8)
        val result=requireNotNull(recoil.accept(input,10,true))
        assertEquals(.16,hypot(result.x,result.z),1e-9)
        assertEquals(.12,result.y,1e-9)
        assertEquals(Vector(.6,.4,.8),input)
        assertTrue(recoil.recovering(15));assertFalse(recoil.recovering(16))
        assertEquals(Vector(.01,-.2,.02),recoil.accept(Vector(.01,-.2,.02),16,true))
    }
    @Test fun `casting blocks impulse and zero impulse does not stall AI`() {
        val recoil=BossRecoil(BossImpact())
        assertNull(recoil.accept(Vector(1.0,1.0,0.0),2,false))
        assertFalse(recoil.recovering(2))
        assertEquals(Vector(),recoil.accept(Vector(),3,true))
        assertFalse(recoil.recovering(3))
    }
    @Test fun `impact settings are optional for existing files but invalid values fail`() {
        val source=Files.readString(Path.of("src/main/resources/dungeons/lost_altar/boss.yml"))
        val file=folder.resolve("boss.yml")
        Files.writeString(file,source)
        assertEquals(BossImpact(),BossSettings.load(file).impact)
        Files.writeString(file,source.replace("recovery-ticks: 6","recovery-ticks: 2.5"))
        assertThrows(IllegalArgumentException::class.java) { BossSettings.load(file) }
        Files.writeString(file,source.replace(Regex("(?ms)^impact:.*?(?=^# 击杀)"),""))
        assertEquals(BossImpact(),BossSettings.load(file).impact)
    }
}
