package dev.dungeon.world

import dev.dungeon.scheduling.Dispatch
import org.bukkit.Bukkit
import org.bukkit.World
import org.bukkit.WorldCreator
import java.util.concurrent.CompletableFuture
import java.util.function.Consumer

/** Canvas 1.21.11 documented API, discovered without linking optional server classes. */
class PlatformWorldProvider(private val dispatch: Dispatch) : WorldProvider {
    private val server = Bukkit.getServer()
    private val regionThreaded = runCatching {
        Class.forName("io.papermc.paper.threadedregions.RegionizedServer",false,server.javaClass.classLoader)
    }.isSuccess
    private val canvas = server.name.contains("Canvas",true) && Bukkit.getMinecraftVersion() == "1.21.11"
    private val unloadMethod = if(canvas) server.javaClass.methods.firstOrNull {
        it.name == "unloadWorldAsync" && it.parameterTypes.contentEquals(arrayOf(World::class.java,Boolean::class.javaPrimitiveType,Consumer::class.java))
    } else null
    override val name = if(unloadMethod != null) "Canvas-1.21.11" else if(!regionThreaded && server.name in setOf("Paper","Purpur","Leaf")) "Paper-permanent-worlds" else "Loaded-worlds-only"
    override val supportsInstances = unloadMethod != null
    override val supportsPermanentLoading = (!regionThreaded && server.name in setOf("Paper","Purpur","Leaf")) || canvas
    override fun load(name: String): CompletableFuture<World> {
        if(!supportsPermanentLoading) return CompletableFuture.failedFuture(UnsupportedOperationException("$name: world loading unavailable on ${server.name}"))
        val future = CompletableFuture<World>()
        dispatch.global {
            try { future.complete(requireNotNull(Bukkit.createWorld(WorldCreator(name)))) }
            catch(e: Exception) { future.completeExceptionally(e) }
        }; return future
    }
    override fun unload(world: World): CompletableFuture<Boolean> {
        val future = CompletableFuture<Boolean>()
        if(unloadMethod == null) return CompletableFuture.completedFuture(false)
        dispatch.global {
            try {
                unloadMethod.invoke(server,world,false,Consumer<Any> {
                    // Registered-world absence is the deletion authority; enum naming differs across forks.
                    dispatch.global { future.complete(Bukkit.getWorld(world.uid) == null) }
                })
            } catch(e: Exception) { future.completeExceptionally(e) }
        }; return future
    }
}
