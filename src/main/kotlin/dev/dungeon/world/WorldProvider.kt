package dev.dungeon.world

import org.bukkit.World
import java.util.concurrent.CompletableFuture

/** Register through ServicesManager before Dungeon enables. Callers use the global scheduler. */
interface WorldProvider {
    val name: String
    val supportsInstances: Boolean
    /** Separate from unload/instance support: permanent worlds are never disposed by Dungeon. */
    val supportsPermanentLoading: Boolean get() = false
    fun load(name: String): CompletableFuture<World>
    fun unload(world: World): CompletableFuture<Boolean>
}
