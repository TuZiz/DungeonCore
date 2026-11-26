package dev.dungeon.boss.battle

import dev.dungeon.boss.BossImpact
import org.bukkit.util.Vector
import kotlin.math.hypot

/** Victim owner only. Caps received impulses without manufacturing extra knockback. */
class BossRecoil(private val settings: BossImpact) {
    private var until=0L
    fun accept(input: Vector,tick: Long,allowed: Boolean): Vector? {
        if(!allowed) return null
        val output=input.clone()
        val horizontal=hypot(output.x,output.z)
        if(horizontal>settings.horizontal) {
            val ratio=settings.horizontal/horizontal
            output.x*=ratio;output.z*=ratio
        }
        output.y=output.y.coerceAtMost(settings.vertical)
        if(output.lengthSquared()>1e-8) until=tick+settings.recoveryTicks
        return output
    }
    fun recovering(tick: Long)=tick<until
}
