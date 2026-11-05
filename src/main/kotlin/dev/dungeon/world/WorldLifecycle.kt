package dev.dungeon.world

import dev.dungeon.scheduling.Dispatch
import org.bukkit.Bukkit
import org.bukkit.World
import java.util.UUID
import java.util.concurrent.CompletableFuture

class WorldLifecycle(private val dispatch: Dispatch, val provider: WorldProvider, private val files: TemplateFiles, private val protectedWorlds: () -> Set<String>) {
    fun create(template: String, id: UUID): CompletableFuture<World> {
        if(!provider.supportsInstances) return CompletableFuture.failedFuture(UnsupportedOperationException("Dynamic world API unavailable: ${provider.name}"))
        return dispatch.io { files.copy(template,id) }.thenCompose { provider.load(it) }
    }
    /** Must be called only after admission is closed and every member teleport has completed. */
    fun clean(name: String, id: UUID): CompletableFuture<Boolean> {
        if(name in protectedWorlds()) return CompletableFuture.failedFuture(IllegalStateException("Permanent world protected: $name"))
        val f = CompletableFuture<Boolean>()
        dispatch.global {
            val world = Bukkit.getWorld(name)
            val unloaded = if(world == null) CompletableFuture.completedFuture(true) else provider.unload(world)
            unloaded.whenComplete { ok,error ->
                if(error != null) f.completeExceptionally(error)
                else if(ok != true) f.complete(false)
                else dispatch.global {
                    if(Bukkit.getWorld(name) != null) f.complete(false)
                    else dispatch.io { files.delete(name,id); true }.whenComplete { result,e ->
                        if(e != null) f.completeExceptionally(e) else f.complete(result)
                    }
                }
            }
        }; return f
    }
    fun recover(log: (String) -> Unit) {
        dispatch.io { files.owned() }.thenAccept { entries -> entries.forEach { (name,id) ->
            // Never unload a world on startup: it may contain reconnecting players. Offline leftovers only.
            dispatch.global {
                if(Bukkit.getWorld(name) != null) log("Recovery retained loaded instance $name; administrator inspection required")
                else clean(name,id).whenComplete { ok,e -> log("Recovery $name removed=$ok error=${e?.message}") }
            }
        } }
    }
}
