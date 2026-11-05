package dev.dungeon.session

import dev.dungeon.model.Run
import org.bukkit.Location
import org.bukkit.entity.Entity
import org.bukkit.entity.Player
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

class ActiveSession(val run: Run) {
    val players = ConcurrentHashMap<UUID,Player>()
    val exits = ConcurrentHashMap<UUID,Location>()
    val offline = ConcurrentHashMap<UUID,Long>()
    val entities = ConcurrentHashMap.newKeySet<Entity>()
    val closing = AtomicBoolean(false)
    val transfers = AtomicInteger()
    val exiting = ConcurrentHashMap.newKeySet<UUID>()
    val started = System.currentTimeMillis()
}
