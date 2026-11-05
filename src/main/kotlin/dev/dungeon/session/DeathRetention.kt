package dev.dungeon.session

import org.bukkit.event.entity.PlayerDeathEvent

/** Runs synchronously on the dying player's owning thread, before Bukkit processes drops. */
object DeathRetention {
    fun apply(event: PlayerDeathEvent) {
        event.keepInventory=true
        event.drops.clear()
        event.keepLevel=true
        event.droppedExp=0
        event.setShouldDropExperience(false)
    }
}
