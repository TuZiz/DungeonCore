package dev.dungeon.integration

import org.bukkit.inventory.ItemStack
import org.bukkit.plugin.Plugin

/** Builds actual CraftEngine registered items; never substitutes a vanilla lookalike. Player owner only. */
class CraftEngineItemsBridge(plugin: Plugin) {
    private val api=plugin.javaClass.classLoader.loadClass("net.momirealms.craftengine.bukkit.api.CraftEngineItems")
    private val find=api.getMethod("byId",String::class.java)
    fun validate(ids: Set<String>) { ids.forEach { check(find.invoke(null,it)!=null) { "Unknown boss reward CraftEngine item $it" } } }
    fun build(id: String): ItemStack {
        val definition=requireNotNull(find.invoke(null,id)) { "Unknown boss reward CraftEngine item $id" }
        return (definition.javaClass.getMethod("buildBukkitItem").invoke(definition) as ItemStack).also { it.amount=1 }
    }
}
