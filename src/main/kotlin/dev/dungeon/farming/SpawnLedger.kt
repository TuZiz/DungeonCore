package dev.dungeon.farming

import org.bukkit.entity.Entity
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

class FarmSpawn(val token: UUID, val owner: UUID, val mob: FarmMob, val created: Long, val anchor: FarmPresence) {
    @Volatile var entity: Entity? = null
    @Volatile var position: FarmPresence? = null
    @Volatile var lastNear: Long = created
    val polling=AtomicBoolean(false)
}
/** Reservations count toward limits before any region callback is queued. */
class SpawnLedger(private val settings: SpawnSettings) {
    val records=ConcurrentHashMap<UUID,FarmSpawn>()
    @Synchronized fun reserve(player: FarmPresence,mob: FarmMob,now: Long): FarmSpawn? {
        val owner=player.id
        val nearby=records.values.filter {
            val p=it.position ?: it.anchor
            player.distanceSquared(p.x,p.y,p.z)<=settings.maxRadius*settings.maxRadius
        }
        if(nearby.size>=settings.nearbyLimit || records.values.count { it.owner==owner }>=settings.playerLimit) return null
        if(mob.elite && (nearby.count { it.mob.elite }>=settings.eliteNearbyLimit || records.values.count { it.owner==owner && it.mob.elite }>=settings.elitePlayerLimit)) return null
        return FarmSpawn(UUID.randomUUID(),owner,mob,now,player).also { records[it.token]=it }
    }
    fun remove(record: FarmSpawn) { records.remove(record.token,record) }
}
