package dev.dungeon.session

import dev.dungeon.farming.FarmSettings
import dev.dungeon.model.Definition
import dev.dungeon.model.Mode
import java.util.UUID

/** Immutable death-time destination. PDC encoding survives disconnects/restarts without blocking IO. */
data class PermanentRespawnPoint(val world: UUID,val x: Double,val y: Double,val z: Double,val yaw: Float,val pitch: Float) {
    fun encode()=listOf(world,x,y,z,yaw,pitch).joinToString("|")
    companion object {
        fun decode(raw: String): PermanentRespawnPoint {
            val p=raw.split('|');require(p.size==6) { "Invalid permanent respawn record" }
            val result=PermanentRespawnPoint(UUID.fromString(p[0]),p[1].toDouble(),p[2].toDouble(),p[3].toDouble(),p[4].toFloat(),p[5].toFloat())
            require(listOf(result.x,result.y,result.z,result.yaw.toDouble(),result.pitch.toDouble()).all { it.isFinite() })
            require(result.x in -29999984.0..29999984.0 && result.z in -29999984.0..29999984.0 && result.y in -64.0..319.0 && result.pitch in -90f..90f)
            return result
        }
        fun forDeath(farm: FarmSettings,definition: Definition?,worldName: String,worldId: UUID): PermanentRespawnPoint? {
            if(farm.enabled && farm.world==worldName) return PermanentRespawnPoint(worldId,farm.x,farm.y,farm.z,farm.yaw,farm.pitch)
            if(definition?.mode!=Mode.PERMANENT || definition.world!=worldName) return null
            val spawn=definition.points.getValue("spawn")
            return PermanentRespawnPoint(worldId,spawn.x,spawn.y,spawn.z,0f,0f)
        }
    }
}
