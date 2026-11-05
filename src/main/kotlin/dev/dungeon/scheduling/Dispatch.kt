package dev.dungeon.scheduling

import org.bukkit.Bukkit
import org.bukkit.Location
import org.bukkit.entity.Entity
import org.bukkit.plugin.Plugin
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

class Dispatch(private val plugin: Plugin) : AutoCloseable {
    private val active = AtomicBoolean(true)
    private val tasks = java.util.concurrent.ConcurrentHashMap.newKeySet<io.papermc.paper.threadedregions.scheduler.ScheduledTask>()
    private val io = Executors.newFixedThreadPool(2) { r -> Thread(r, "Dungeon-IO").apply { isDaemon = true } }
    val isActive: Boolean get() = active.get()
    fun <T> io(block: () -> T): CompletableFuture<T> = CompletableFuture.supplyAsync({ check(active.get()); block() }, io)
    fun global(block: () -> Unit) {
        if(active.get()) Bukkit.getGlobalRegionScheduler().run(plugin) { if(active.get()) block() }
    }
    fun region(location: Location, block: () -> Unit) {
        if(active.get()) tasks.add(Bukkit.getRegionScheduler().run(plugin, location) { task -> tasks.remove(task); if(active.get()) block() })
    }
    fun entity(entity: Entity, retired: Runnable? = null, block: () -> Unit) {
        if(!active.get()) { retired?.run();return }
        entity.scheduler.run(plugin, { task ->
            tasks.remove(task)
            if(active.get() && entity.isValid) block() else retired?.run()
        }, retired)?.let { tasks.add(it) } ?: retired?.run()
    }
    fun later(ticks: Long, block: () -> Unit) {
        if(active.get()) Bukkit.getGlobalRegionScheduler().runDelayed(plugin, { if(active.get()) block() }, ticks.coerceAtLeast(1))
    }
    override fun close() {
        active.set(false)
        Bukkit.getGlobalRegionScheduler().cancelTasks(plugin)
        tasks.forEach { it.cancel() }; tasks.clear()
        Bukkit.getAsyncScheduler().cancelTasks(plugin)
        io.shutdownNow()
    }
}
