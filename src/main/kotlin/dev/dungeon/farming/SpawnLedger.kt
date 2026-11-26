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
/** Atomic reservations plus a horizontal grid. Only immutable positions cross region boundaries. */
class SpawnLedger(private val settings: SpawnSettings) {
    val records=ConcurrentHashMap<UUID,FarmSpawn>()
    private data class Cell(val x: Int,val z: Int)
    private val cells=HashMap<Cell,MutableSet<UUID>>()
    private val owners=HashMap<UUID,Int>()
    private val elites=HashMap<UUID,Int>()
    private fun cell(p: FarmPresence)=Cell(kotlin.math.floor(p.x/32).toInt(),kotlin.math.floor(p.z/32).toInt())
    private fun at(r: FarmSpawn)=r.position ?: r.anchor
    @Synchronized fun nearby(player: FarmPresence,radius: Double): List<FarmSpawn> {
        val result=ArrayList<FarmSpawn>()
        val low=cell(player.copy(x=player.x-radius,z=player.z-radius))
        val high=cell(player.copy(x=player.x+radius,z=player.z+radius))
        for(x in low.x..high.x) for(z in low.z..high.z) cells[Cell(x,z)]?.forEach { id ->
            records[id]?.let { r -> val p=at(r)
                if(player.distanceSquared(p.x,p.y,p.z)<=radius*radius) result.add(r)
            }
        }
        return result
    }
    @Synchronized fun reserve(player: FarmPresence,mob: FarmMob,now: Long,anchor: FarmPresence=player): FarmSpawn? {
        val nearby=nearby(player,settings.maxRadius)
        if(nearby.size>=settings.nearbyLimit || (owners[player.id] ?: 0)>=settings.playerLimit) return null
        if(mob.elite && (nearby.count { it.mob.elite }>=settings.eliteNearbyLimit || (elites[player.id] ?: 0)>=settings.elitePlayerLimit)) return null
        return FarmSpawn(UUID.randomUUID(),player.id,mob,now,anchor).also {
            records[it.token]=it
            cells.getOrPut(cell(anchor)) { HashSet() }.add(it.token)
            owners.merge(it.owner,1,Int::plus)
            if(mob.elite) elites.merge(it.owner,1,Int::plus)
        }
    }
    @Synchronized fun update(record: FarmSpawn,position: FarmPresence) {
        if(records[record.token]!==record) return
        val old=cell(at(record));val next=cell(position)
        if(old!=next) {
            cells[old]?.let { it.remove(record.token);if(it.isEmpty()) cells.remove(old) }
            cells.getOrPut(next) { HashSet() }.add(record.token)
        }
        record.position=position
    }
    @Synchronized fun remove(record: FarmSpawn) {
        if(!records.remove(record.token,record)) return
        val c=cell(at(record))
        cells[c]?.let { it.remove(record.token);if(it.isEmpty()) cells.remove(c) }
        fun decrement(map: MutableMap<UUID,Int>) {
            val count=(map[record.owner] ?: 1)-1
            if(count==0) map.remove(record.owner) else map[record.owner]=count
        }
        decrement(owners);if(record.mob.elite) decrement(elites)
    }
    @Synchronized fun clear() { records.clear();cells.clear();owners.clear();elites.clear() }
}
