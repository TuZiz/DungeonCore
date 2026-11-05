package dev.dungeon.session

import dev.dungeon.persistence.ProgressStore
import dev.dungeon.localization.Messages
import dev.dungeon.scheduling.Dispatch
import java.util.UUID

class RewardDelivery(private val store: ProgressStore, private val dispatch: Dispatch,
    private val messages: Messages, private val current: (UUID) -> ActiveSession?,
    private val enabled: () -> Boolean, private val failure: (ActiveSession,Throwable) -> Unit) {
    fun grant(s: ActiveSession) {
        s.players.forEach { (id,p) ->
            val definition = s.run.definition
            store.complete(id,s.run.id,definition.id,s.run.difficulty.id,System.currentTimeMillis()-s.started,definition.campaign,definition.chapter).whenComplete { first,e ->
                if(e != null) failure(s,e)
                else if(first != null) dispatch.entity(p) {
                    if(enabled() && p.isOnline && current(id) === s && !s.closing.get()) {
                        val items = (definition.rewards + if(first == true) definition.firstRewards else emptyList()).map { org.bukkit.inventory.ItemStack(org.bukkit.Material.valueOf(it.material),it.amount) }
                        // Never drop overflow on the ground; pending reservations are auditable in SQLite.
                        if(p.inventory.storageContents.count { it == null || it.type.isAir } < items.size) messages.send(p,"reward-pending")
                        else {
                            p.inventory.addItem(*items.toTypedArray())
                            store.delivered(id,s.run.id).exceptionally { failure(s,it); null }
                            messages.send(p,"reward")
                        }
                    }
                }
            }
        }
    }
}
