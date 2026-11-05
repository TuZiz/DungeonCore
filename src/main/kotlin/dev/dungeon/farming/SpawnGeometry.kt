package dev.dungeon.farming

import java.util.UUID
import kotlin.math.*

data class FarmPresence(val id: UUID, val x: Double, val y: Double, val z: Double, val yaw: Float, val at: Long) {
    fun distanceSquared(x: Double,y: Double,z: Double)=(this.x-x).pow(2)+(this.y-y).pow(2)+(this.z-z).pow(2)
}
object SpawnGeometry {
    /** Feet positions within exact player-relative limits, nearest first; supports negative Y. */
    fun heights(playerY: Double, search: Int, above: Int, below: Int): List<Int> {
        val low=ceil(playerY-minOf(search,below)).toInt()
        val high=floor(playerY+minOf(search,above)).toInt()
        return (low..high).sortedBy { abs(it-playerY) }
    }
    /** Uniform area distribution, not a bias toward the inner edge. */
    fun candidate(player: FarmPresence, min: Double,max: Double,angleUnit: Double,radiusUnit: Double): Pair<Int,Int> {
        val angle=angleUnit*2*PI
        val radius=sqrt(min*min+radiusUnit*(max*max-min*min))
        return floor(player.x+cos(angle)*radius).toInt() to floor(player.z+sin(angle)*radius).toInt()
    }
    /** Clockwise frame from player-forward; texture 16 points up, 24 points right. */
    fun compassFrame(dx: Double,dz: Double,yaw: Float): Int {
        val bearing=Math.toDegrees(atan2(-dx,dz))
        val relative=((bearing-yaw)%360+360)%360
        return floor((relative+5.625)/11.25).toInt()%32
    }
    /** Minecraft yaw: 0=south, 90=west; result indexes front, front-right, right, ... */
    fun direction(dx: Double,dz: Double,yaw: Float): Int {
        val bearing=Math.toDegrees(atan2(-dx,dz))
        val relative=((bearing-yaw+3600)%360+360)%360
        return floor((relative+22.5)/45).toInt()%8
    }
}
