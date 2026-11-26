package dev.dungeon.boss.reward

import dev.dungeon.boss.BossRewardsSettings
import dev.dungeon.integration.AsterQualityBridge
import dev.dungeon.localization.Messages
import dev.dungeon.scheduling.Dispatch
import org.bukkit.Bukkit
import org.bukkit.NamespacedKey
import org.bukkit.entity.Player
import org.bukkit.event.Event
import org.bukkit.event.EventHandler
import org.bukkit.event.HandlerList
import org.bukkit.event.Listener
import org.bukkit.event.block.Action
import org.bukkit.event.player.PlayerInteractEvent
import org.bukkit.inventory.EquipmentSlot
import org.bukkit.inventory.ItemStack
import org.bukkit.persistence.PersistentDataType
import org.bukkit.plugin.Plugin
import java.util.Base64
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ThreadLocalRandom

/** Right-click validation and stack generation happen on the player owner. */
class BossSpoilsOpening(plugin: Plugin,private val dispatch: Dispatch,private val store: BossRewardStore,
    private val limits: BossRewardsSettings,private val settings: BossSpoilsSettings,private val text: Messages,
    private val receiptKey: NamespacedKey,private val ownerKey: NamespacedKey,
    private val claim: (Player)->Unit,private val failure: (Player,Throwable)->Unit): Listener,AutoCloseable {
    private val busy = ConcurrentHashMap.newKeySet<UUID>()
    private val quality = AsterQualityBridge()
    @Volatile private var closed = false
    init { Bukkit.getPluginManager().registerEvents(this,plugin) }

    @EventHandler
    fun interact(event: PlayerInteractEvent) {
        if(closed || event.hand!=EquipmentSlot.HAND || event.action !in setOf(Action.RIGHT_CLICK_AIR,Action.RIGHT_CLICK_BLOCK)) return
        val stack=event.item ?: return
        val owner=tag(stack,ownerKey) ?: return
        event.setUseItemInHand(Event.Result.DENY)
        val player=event.player
        val id=player.uniqueId
        if(owner!=id.toString()) { text.send(player,"spoils-owner");return }
        val receipt=tag(stack,receiptKey) ?: run { text.send(player,"spoils-invalid");return }
        if(!busy.add(id)) return
        store.spoilsStatus(id,receipt,BossSpoilsSettings.ITEM).whenComplete { status,error ->
            if(error!=null) { busy.remove(id);failure(player,error);return@whenComplete }
            dispatch.entity(player,Runnable { busy.remove(id) }) {
                if(closed || !player.isOnline || find(player,receipt)<0) { busy.remove(id);return@entity }
                when(status) {
                    BossRewardStore.SpoilsStatus.UNKNOWN -> { busy.remove(id);text.send(player,"spoils-invalid") }
                    BossRewardStore.SpoilsStatus.OPENED -> complete(player,receipt)
                    BossRewardStore.SpoilsStatus.UNOPENED -> open(player,receipt)
                }
            }
        }
    }

    private fun open(player: Player,receipt: String) {
        val id=player.uniqueId
        val random=ThreadLocalRandom.current()
        val material=settings.materials[random.nextInt(settings.materials.size)]
        val group=if(random.nextDouble()<settings.chance) {
            val roll=random.nextDouble()
            when {
                roll<settings.common -> AsterQualityBridge.Group.COMMON
                roll<settings.common+settings.excellent -> AsterQualityBridge.Group.EXCELLENT
                else -> AsterQualityBridge.Group.HIGHER
            }
        } else null
        val proposed=BossSpoilsPlan(material,settings.sword.takeIf { group!=null },group?.name)
        store.planSpoils(id,receipt,BossSpoilsSettings.ITEM,proposed).whenComplete { plan,error ->
            if(error!=null) { busy.remove(id);failure(player,error);return@whenComplete }
            dispatch.entity(player,Runnable { busy.remove(id) }) {
                if(closed || !player.isOnline || plan==null || find(player,receipt)<0) { busy.remove(id);return@entity }
                generate(player,receipt,plan)
            }
        }
    }

    private fun generate(player: Player,receipt: String,plan: BossSpoilsPlan) {
        val id=player.uniqueId
        val rewards=mutableListOf(plan.material)
        try {
            if(plan.sword!=null) {
                val group=AsterQualityBridge.Group.valueOf(checkNotNull(plan.group))
                val sword=quality.build(plan.sword,group)
                rewards.add("stack:"+Base64.getEncoder().encodeToString(sword.serializeAsBytes()))
            }
        } catch(error: Exception) { busy.remove(id);failure(player,error);return }
        store.openSpoils(id,receipt,BossSpoilsSettings.ITEM,rewards,limits).whenComplete { status,error ->
            if(error!=null) { busy.remove(id);failure(player,error);return@whenComplete }
            dispatch.entity(player,Runnable { busy.remove(id) }) {
                if(!closed && player.isOnline && status==BossRewardStore.SpoilsStatus.OPENED) complete(player,receipt)
                else busy.remove(id)
            }
        }
    }

    private fun complete(player: Player,receipt: String) {
        val slot=find(player,receipt)
        if(slot>=0) player.inventory.setItem(slot,null)
        busy.remove(player.uniqueId)
        text.send(player,"spoils-opened-title")
        claim(player)
    }
    private fun find(player: Player,receipt: String): Int = player.inventory.storageContents.indexOfFirst {
        it!=null && tag(it,ownerKey)==player.uniqueId.toString() && tag(it,receiptKey)==receipt
    }
    private fun tag(stack: ItemStack,key: NamespacedKey): String? =
        stack.itemMeta?.persistentDataContainer?.get(key,PersistentDataType.STRING)

    override fun close() { closed=true;HandlerList.unregisterAll(this);busy.clear() }
}
