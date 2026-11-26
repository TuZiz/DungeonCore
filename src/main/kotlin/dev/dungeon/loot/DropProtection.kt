package dev.dungeon.loot

import org.bukkit.NamespacedKey
import org.bukkit.entity.Item
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import org.bukkit.inventory.ItemStack
import org.bukkit.persistence.PersistentDataType
import org.bukkit.plugin.Plugin
import java.nio.file.Path
import java.util.UUID

/** Marks only Dungeon-generated drops; ordinary player-discarded items are never claimed. */
class DropProtection(plugin: Plugin) : Listener {
    private val ownerKey=NamespacedKey(plugin,"loot_owner")
    private val untilKey=NamespacedKey(plugin,"loot_until")
    @Volatile private var seconds=30
    /** IO worker, called before Dungeon gameplay starts. */
    fun load(path: Path) {
        val yaml=org.bukkit.configuration.file.YamlConfiguration().apply { load(path.toFile()) }
        require(yaml.getInt("schema-version")==1) { "$path schema-version unsupported" }
        val raw=yaml.get("owner-seconds")
        require(raw is Int) { "$path owner-seconds=$raw must be integer" }
        val value=raw
        require(value in 0..300) { "$path owner-seconds=$value must be 0..300" }
        seconds=value
    }
    fun mark(stack: ItemStack,owner: UUID) {
        if(seconds==0) return
        val meta=stack.itemMeta ?: return
        meta.persistentDataContainer.set(ownerKey,PersistentDataType.STRING,owner.toString())
        meta.persistentDataContainer.set(untilKey,PersistentDataType.LONG,System.currentTimeMillis()+seconds*1000L)
        stack.itemMeta=meta
    }
    /** ItemSpawnEvent runs on the dropped item's region. Strip stack tags so picked-up loot stacks normally. */
    @EventHandler(priority=EventPriority.HIGHEST,ignoreCancelled=true)
    fun spawn(event: org.bukkit.event.entity.ItemSpawnEvent) {
        val item=event.entity;val stack=item.itemStack;val meta=stack.itemMeta ?: return
        val data=meta.persistentDataContainer
        val owner=data.get(ownerKey,PersistentDataType.STRING) ?: return
        val until=data.get(untilKey,PersistentDataType.LONG) ?: return
        item.persistentDataContainer.set(ownerKey,PersistentDataType.STRING,owner)
        item.persistentDataContainer.set(untilKey,PersistentDataType.LONG,until)
        data.remove(ownerKey);data.remove(untilKey);stack.itemMeta=meta;item.itemStack=stack
    }
    private fun owner(item: Item): String? {
        val data=item.persistentDataContainer
        val until=data.get(untilKey,PersistentDataType.LONG) ?: return null
        if(System.currentTimeMillis()<until) return data.get(ownerKey,PersistentDataType.STRING)
        data.remove(ownerKey);data.remove(untilKey);return null
    }
    @EventHandler(priority=EventPriority.HIGHEST,ignoreCancelled=true)
    fun pickup(event: org.bukkit.event.entity.EntityPickupItemEvent) {
        val owner=owner(event.item) ?: return
        if(event.entity.uniqueId.toString()!=owner) event.isCancelled=true
    }
    @EventHandler(priority=EventPriority.HIGHEST,ignoreCancelled=true)
    fun hopper(event: org.bukkit.event.inventory.InventoryPickupItemEvent) {
        if(owner(event.item)!=null) event.isCancelled=true
    }
    @EventHandler(priority=EventPriority.HIGHEST,ignoreCancelled=true)
    fun merge(event: org.bukkit.event.entity.ItemMergeEvent) {
        if(owner(event.entity)!=null || owner(event.target)!=null) event.isCancelled=true
    }
}
