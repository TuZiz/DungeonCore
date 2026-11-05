package dev.dungeon.integration

import org.bukkit.Bukkit
import org.bukkit.Location
import org.bukkit.entity.Entity
import java.lang.reflect.InvocationTargetException
import java.util.Optional

/** Optional dependency boundary. API signatures verified against MythicMobs 5.13.0 and installed 5.14.0. */
class MythicMobsAdapter {
    private fun <T> api(block: (Any, Class<*>) -> T): T {
        val plugin = Bukkit.getPluginManager().getPlugin("MythicMobs")
        check(plugin != null && plugin.isEnabled) { "MythicMobs is unavailable; MM dungeon cannot start" }
        try {
            val type = plugin.javaClass.classLoader.loadClass("io.lumine.mythic.bukkit.MythicBukkit")
            return block(type.getMethod("inst").invoke(null), type)
        } catch(e: InvocationTargetException) {
            throw IllegalStateException("MythicMobs ${plugin.pluginMeta.version}: ${e.targetException.message}",e.targetException)
        } catch(e: ReflectiveOperationException) {
            throw IllegalStateException("Unsupported MythicMobs API ${plugin.pluginMeta.version}",e)
        } catch(e: LinkageError) {
            throw IllegalStateException("MythicMobs dependency linkage failed (${plugin.pluginMeta.version})",e)
        }
    }

    /** Called during player admission before any membership, return record or world is modified. */
    fun validate(ids: Set<String>) {
        if(ids.isEmpty()) return
        api { instance,type ->
            val manager = type.getMethod("getMobManager").invoke(instance)
            val find = manager.javaClass.getMethod("getMythicMob",String::class.java)
            ids.forEach { id ->
                check((find.invoke(manager,id) as Optional<*>).isPresent) { "MythicMobs mob ID not loaded: $id" }
            }
            val helper = type.getMethod("getAPIHelper").invoke(instance)
            helper.javaClass.getMethod("spawnMythicMob",String::class.java,Location::class.java)
        }
    }

    /** Global scheduler only. Disable() in MM 5.14 only flips the active flag; no world access or file IO. */
    fun suspendFarmSpawners(world: String, group: String): Int = api { instance,type ->
        val manager=type.getMethod("getSpawnerManager").invoke(instance)
        val spawners=manager.javaClass.getMethod("getSpawnersByGroup",String::class.java).invoke(manager,group) as Collection<*>
        var count=0
        spawners.filterNotNull().forEach { spawner ->
            if(spawner.javaClass.getMethod("getWorldName").invoke(spawner)==world) {
                spawner.javaClass.getMethod("Disable").invoke(spawner);count++
            }
        }
        count
    }

    /** Must run on the spawn location's region scheduler, never on a database/IO thread. */
    fun spawn(id: String, location: Location): Entity = api { instance,type ->
        val helper = type.getMethod("getAPIHelper").invoke(instance)
        helper.javaClass.getMethod("spawnMythicMob",String::class.java,Location::class.java)
            .invoke(helper,id,location) as? Entity ?: error("MythicMobs returned no entity for $id")
    }
}
