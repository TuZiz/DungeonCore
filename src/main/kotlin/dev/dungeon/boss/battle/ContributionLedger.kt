package dev.dungeon.boss.battle

import java.util.UUID
import kotlin.math.max
import kotlin.math.min

/** Boss entity's owning thread only. Confirm HP loss after the event has actually been applied. */
class ContributionLedger(private val maxHealth: Double) {
    data class Contribution(val damage: Double,val lastHit: Long)
    private data class Pending(val player: UUID?,val health: Double,val expected: Double,val time: Long)
    private val damage=mutableMapOf<UUID,Contribution>()
    private var pending: Pending?=null
    fun beforeDamage(player: UUID?,health: Double,finalDamage: Double,now: Long) {
        confirm(health)
        pending=Pending(player,health,if(finalDamage.isFinite()) max(0.0,min(finalDamage,health)) else 0.0,now)
    }
    fun confirm(currentHealth: Double) {
        val hit=pending ?: return
        pending=null
        val player=hit.player ?: return
        val actual=min(hit.expected,max(0.0,hit.health-currentHealth))
        if(actual<=0) return
        val old=damage[player]
        damage[player]=Contribution((old?.damage ?: 0.0)+actual,hit.time)
    }
    fun eligible(now: Long,threshold: Double,recentMillis: Long): Set<UUID> = damage.filterValues {
        it.damage+1e-8>=maxHealth*threshold && now-it.lastHit in 0..recentMillis
    }.keys.toSet()
    fun snapshot()=damage.toMap()
    fun reset() { damage.clear();pending=null }
}
