package dev.dungeon.session

import java.util.UUID

/** Invites are explicit, expire, and only accepted by the invitee. */
class Parties {
    private val groups = HashMap<UUID,MutableSet<UUID>>()
    private val membership = HashMap<UUID,UUID>()
    private val invites = HashMap<UUID,Pair<UUID,Long>>()
    @Synchronized fun invite(leader: UUID, target: UUID) {
        require(leader != target && target !in membership)
        require(membership[leader] == null || membership[leader] == leader)
        val group = groups.getOrPut(leader) { linkedSetOf(leader) }
        require(group.size < 40)
        membership[leader] = leader; invites[target] = leader to System.currentTimeMillis()+60000
    }
    @Synchronized fun accept(player: UUID) {
        require(player !in membership)
        val invite = requireNotNull(invites.remove(player))
        require(invite.second >= System.currentTimeMillis())
        requireNotNull(groups[invite.first]).add(player); membership[player] = invite.first
    }
    @Synchronized fun members(leader: UUID): Set<UUID> {
        require(membership[leader] == null || membership[leader] == leader) { "Only party leader can start" }
        return groups[leader]?.toSet() ?: setOf(leader)
    }
    @Synchronized fun leave(player: UUID) {
        val leader = membership.remove(player) ?: return
        if(leader == player) groups.remove(leader)?.forEach { membership.remove(it) }
        else groups[leader]?.remove(player)
        invites.entries.removeIf { it.key == player || it.value.first == player }
    }
    @Synchronized fun clear() { groups.clear();membership.clear();invites.clear() }
}
