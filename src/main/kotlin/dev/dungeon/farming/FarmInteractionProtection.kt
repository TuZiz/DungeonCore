package dev.dungeon.farming

import org.bukkit.Material
import org.bukkit.entity.ArmorStand
import org.bukkit.entity.Hanging
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener

/** Region-local map interactions. Buttons, doors and plugin inventories remain usable. */
class FarmInteractionProtection(private val settings: FarmSettings) : Listener {
    private fun farm(world: org.bukkit.World)=settings.enabled && world.name==settings.world
    @EventHandler(priority=EventPriority.HIGHEST,ignoreCancelled=true)
    fun empty(e: org.bukkit.event.player.PlayerBucketEmptyEvent) { if(farm(e.block.world)) e.isCancelled=true }
    @EventHandler(priority=EventPriority.HIGHEST,ignoreCancelled=true)
    fun fill(e: org.bukkit.event.player.PlayerBucketFillEvent) { if(farm(e.block.world)) e.isCancelled=true }
    @EventHandler(priority=EventPriority.HIGHEST,ignoreCancelled=true)
    fun ignite(e: org.bukkit.event.block.BlockIgniteEvent) { if(farm(e.block.world)) e.isCancelled=true }
    @EventHandler(priority=EventPriority.HIGHEST,ignoreCancelled=true)
    fun burn(e: org.bukkit.event.block.BlockBurnEvent) { if(farm(e.block.world)) e.isCancelled=true }
    @EventHandler(priority=EventPriority.HIGHEST,ignoreCancelled=true)
    fun spread(e: org.bukkit.event.block.BlockSpreadEvent) {
        if(farm(e.block.world) && e.source.type in setOf(Material.FIRE,Material.SOUL_FIRE)) e.isCancelled=true
    }
    @EventHandler(priority=EventPriority.HIGHEST,ignoreCancelled=true)
    fun change(e: org.bukkit.event.entity.EntityChangeBlockEvent) { if(farm(e.block.world)) e.isCancelled=true }
    @EventHandler(priority=EventPriority.HIGHEST,ignoreCancelled=true)
    fun interact(e: org.bukkit.event.player.PlayerInteractEvent) {
        val block=e.clickedBlock ?: return
        if(!farm(block.world)) return
        if(e.action==org.bukkit.event.block.Action.PHYSICAL && block.type==Material.FARMLAND ||
            e.action==org.bukkit.event.block.Action.RIGHT_CLICK_BLOCK && e.item?.type in destructiveTools) e.isCancelled=true
    }
    @EventHandler(priority=EventPriority.HIGHEST,ignoreCancelled=true)
    fun entityInteract(e: org.bukkit.event.entity.EntityInteractEvent) {
        if(farm(e.block.world) && e.block.type==Material.FARMLAND) e.isCancelled=true
    }
    @EventHandler(priority=EventPriority.HIGHEST,ignoreCancelled=true)
    fun hanging(e: org.bukkit.event.hanging.HangingBreakEvent) { if(farm(e.entity.world)) e.isCancelled=true }
    @EventHandler(priority=EventPriority.HIGHEST,ignoreCancelled=true)
    fun damage(e: org.bukkit.event.entity.EntityDamageEvent) {
        if((e.entity is ArmorStand || e.entity is Hanging) && farm(e.entity.world)) e.isCancelled=true
    }
    @EventHandler(priority=EventPriority.HIGHEST,ignoreCancelled=true)
    fun entityClick(e: org.bukkit.event.player.PlayerInteractEntityEvent) {
        if((e.rightClicked is ArmorStand || e.rightClicked is Hanging) && farm(e.player.world)) e.isCancelled=true
    }
    @EventHandler(priority=EventPriority.HIGHEST,ignoreCancelled=true)
    fun armor(e: org.bukkit.event.player.PlayerArmorStandManipulateEvent) { if(farm(e.player.world)) e.isCancelled=true }
    @EventHandler(priority=EventPriority.HIGHEST,ignoreCancelled=true)
    fun inventory(e: org.bukkit.event.inventory.InventoryOpenEvent) {
        val holder=e.inventory.holder
        val storage=holder is org.bukkit.block.BlockState || holder is org.bukkit.block.DoubleChest ||
            holder is org.bukkit.entity.minecart.StorageMinecart || holder is org.bukkit.entity.ChestBoat ||
            holder is org.bukkit.entity.AbstractHorse
        if(storage && e.inventory.location?.world?.let(::farm)==true) e.isCancelled=true
    }
    @EventHandler(priority=EventPriority.HIGHEST,ignoreCancelled=true)
    fun transfer(e: org.bukkit.event.inventory.InventoryMoveItemEvent) {
        if(e.source.location?.world?.let(::farm)==true || e.destination.location?.world?.let(::farm)==true) e.isCancelled=true
    }
    companion object {
        private val destructiveTools=Material.entries.filter {
            it.name.endsWith("_HOE") || it.name.endsWith("_AXE") || it.name.endsWith("_SHOVEL") ||
                it in setOf(Material.BONE_MEAL,Material.SHEARS,Material.FLINT_AND_STEEL,Material.FIRE_CHARGE)
        }.toSet()
    }
}
