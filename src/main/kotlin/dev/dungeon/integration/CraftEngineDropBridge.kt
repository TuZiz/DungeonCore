package dev.dungeon.integration

import org.bukkit.Bukkit
import org.bukkit.Material
import org.bukkit.event.Event
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import org.bukkit.inventory.ItemStack
import org.bukkit.plugin.Plugin
import java.lang.reflect.Proxy
import java.util.Optional

/** MM5.13 drop SPI boundary, verified with local javap. CE builds the real registered ItemStack. */
class CraftEngineDropBridge(private val plugin: Plugin) {
    fun register() {
        val mm=Bukkit.getPluginManager().getPlugin("MythicMobs") ?: return
        val ce=Bukkit.getPluginManager().getPlugin("CraftEngine") ?: return
        val loader=mm.javaClass.classLoader
        @Suppress("UNCHECKED_CAST")
        val eventType=loader.loadClass("io.lumine.mythic.bukkit.events.MythicDropLoadEvent") as Class<out Event>
        val dropType=loader.loadClass("io.lumine.mythic.api.drops.IItemDrop")
        val idrop=loader.loadClass("io.lumine.mythic.api.drops.IDrop")
        val configType=loader.loadClass("io.lumine.mythic.api.config.MythicLineConfig")
        val metadataType=loader.loadClass("io.lumine.mythic.api.drops.DropMetadata")
        val entityType=loader.loadClass("io.lumine.mythic.api.adapters.AbstractEntity")
        val ceApi=ce.javaClass.classLoader.loadClass("net.momirealms.craftengine.bukkit.api.CraftEngineItems")
        val byId=ceApi.getMethod("byId",String::class.java)
        val adapt=loader.loadClass("io.lumine.mythic.bukkit.BukkitAdapter").getMethod("adapt",ItemStack::class.java)
        Bukkit.getPluginManager().registerEvent(eventType,object: Listener {},EventPriority.NORMAL,{ _,event ->
            if(eventType.getMethod("getDropName").invoke(event).toString().equals("dungeonce",true)) {
                val config=eventType.getMethod("getConfig").invoke(event)
                val id=configType.getMethod("getString",String::class.java,String::class.java).invoke(config,"id","").toString()
                require(id.matches(Regex("[a-z0-9_]+:[a-z0-9_/]+"))) { "MythicMobs dungeonce invalid CE item ID: $id" }
                val proxy=Proxy.newProxyInstance(loader,arrayOf(dropType)) { instance,method,args ->
                    when(method.name) {
                        "getDrop" -> {
                            val cause=metadataType.getMethod("getCause").invoke(args!![0]) as Optional<*>
                            val player=cause.isPresent && entityType.getMethod("isPlayer").invoke(cause.get())==true
                            val stack=if(player) {
                                val definition=requireNotNull(byId.invoke(null,id)) { "MythicMobs dungeonce unknown CE item: $id" }
                                definition.javaClass.getMethod("buildBukkitItem").invoke(definition) as ItemStack
                            } else ItemStack(Material.AIR)
                            if(player) stack.amount=(args[1] as Number).toDouble().toInt().coerceIn(1,stack.maxStackSize)
                            adapt.invoke(null,stack)
                        }
                        "hashCode" -> System.identityHashCode(instance)
                        "equals" -> instance===args?.get(0)
                        "toString" -> "DungeonCraftEngineDrop($id)"
                        else -> throw UnsupportedOperationException("MM drop SPI method ${method.name}")
                    }
                }
                eventType.getMethod("register",idrop).invoke(event,proxy)
            }
        },plugin)
        plugin.logger.info("CraftEngine MM drop registered: dungeonce; only player-attributed kills produce real CE stacks")
    }
}
