package dev.dungeon

import dev.dungeon.farming.FarmSettings
import dev.dungeon.model.*
import dev.dungeon.session.PermanentRespawnPoint
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.util.UUID

class PermanentRespawnTest {
    private val world=UUID.randomUUID()
    private val farm=FarmSettings(true,"lost_altar","lost_altar",181.771,8.0,11.174,-173.7f,-7.2f)
    private fun definition(mode: Mode)=Definition("stage",mode,ProgressScope.PARTY,"stage_world","",1,4,100,10,true,
        mapOf("spawn" to Point(2.5,64.0,-3.5)),emptyList(),emptyMap(),emptyList(),emptyMap(),emptyMap())
    @Test fun `farm death uses exact configured coordinates and survives encoded player data`() {
        val point=PermanentRespawnPoint.forDeath(farm,null,"lost_altar",world)!!
        assertEquals(181.771,point.x);assertEquals(8.0,point.y);assertEquals(11.174,point.z)
        assertEquals(-173.7f,point.yaw);assertEquals(-7.2f,point.pitch)
        assertEquals(point,PermanentRespawnPoint.decode(point.encode()))
    }
    @Test fun `only permanent matching maps override respawn`() {
        val target=PermanentRespawnPoint.forDeath(farm,definition(Mode.PERMANENT),"stage_world",world)!!
        assertEquals(2.5,target.x);assertEquals(64.0,target.y)
        for(mode in listOf(Mode.TEMPORARY,Mode.STORY)) assertNull(PermanentRespawnPoint.forDeath(farm,definition(mode),"stage_world",world))
        assertNull(PermanentRespawnPoint.forDeath(farm,null,"outside",world))
        assertNull(PermanentRespawnPoint.forDeath(farm.copy(enabled=false),null,"lost_altar",world))
        assertNull(PermanentRespawnPoint.forDeath(farm,definition(Mode.PERMANENT),"outside",world))
    }
    @Test fun `corrupt or unsafe persisted destinations are rejected`() {
        val point=PermanentRespawnPoint(world,1.0,8.0,2.0,0f,0f)
        for(raw in listOf("bad",point.encode().replace("|8.0|","|NaN|"),point.copy(pitch=180f).encode(),point.copy(x=3e8).encode()))
            assertThrows(IllegalArgumentException::class.java) { PermanentRespawnPoint.decode(raw) }
    }
}
