package dev.dungeon.boss.reward

import dev.dungeon.boss.BossRewardsSettings
import dev.dungeon.integration.CraftEngineItemsBridge
import dev.dungeon.localization.Messages
import dev.dungeon.scheduling.Dispatch
import org.bukkit.NamespacedKey
import org.bukkit.Material
import org.bukkit.entity.Player
import org.bukkit.inventory.ItemStack
import net.kyori.adventure.text.format.TextDecoration
import net.kyori.adventure.text.Component
import java.math.BigDecimal
import java.util.Base64
import org.bukkit.persistence.PersistentDataType
import org.bukkit.plugin.Plugin
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.logging.Level

/** Async durable rolls -> player-owned inventory delivery. One in-flight claim per player. */
class BossRewards(private val plugin: Plugin,private val dispatch: Dispatch,private val store: BossRewardStore,
    private val items: CraftEngineItemsBridge,private val settings: BossRewardsSettings,private val text: Messages,
    private val spoilsSettings: BossSpoilsSettings): AutoCloseable {
    private val busy=ConcurrentHashMap.newKeySet<UUID>()
    private val receiptKey=NamespacedKey(plugin,"boss_reward_receipt")
    private val packageKey=NamespacedKey(plugin,"boss_spoils")
    private val spoils=BossSpoilsOpening(plugin,dispatch,store,settings,spoilsSettings,text,receiptKey,packageKey,
        { player -> claim(player,false) },::failure)
    @Volatile private var closed=false
    fun award(battle: UUID,player: Player,killedAt: Long) {
        if(closed) return
        store.roll(battle,player.uniqueId,killedAt,settings,BossSpoilsSettings.ITEM).whenComplete { roll,e ->
            if(e!=null) failure(player,e)
            else if(!closed && roll.fresh) claim(player,false)
        }
    }
    fun claim(player: Player,explicit: Boolean=true) {
        val id=player.uniqueId
        if(closed || !busy.add(id)) { if(explicit) text.send(player,"claim-busy");return }
        store.pending(id).whenComplete { grants,e ->
            if(e!=null) { busy.remove(id);failure(player,e) }
            else dispatch.entity(player,Runnable { busy.remove(id) }) {
                if(closed || !player.isOnline) { busy.remove(id);return@entity }
                if(grants.isEmpty()) { if(explicit) text.send(player,"no-pending");busy.remove(id) }
                else next(player,grants,0)
            }
        }
    }
    private fun next(player: Player,grants: List<BossGrant>,index: Int) {
        val id=player.uniqueId
        if(closed || !player.isOnline || index>=grants.size) { busy.remove(id);return }
        val grant=grants[index]
        if(grant.state!="PENDING") {
            val found=(player.inventory.contents.asSequence()+player.enderChest.contents.asSequence()).filterNotNull().any {
                it.itemMeta?.persistentDataContainer?.get(receiptKey,PersistentDataType.STRING)==grant.receipt
            }
            if(found) {
                val confirm=if(grant.state=="REVIEW") store.reconcile(id,grant.receipt) else store.delivered(id,grant.receipt)
                confirm.whenComplete { _,e -> if(e!=null) failure(player,e) }
            } else text.send(player,"review")
            next(player,grants,index+1);return
        }
        if(player.inventory.firstEmpty()<0) { text.send(player,"full");busy.remove(id);return }
        store.reserve(id,grant.receipt).whenComplete { reserved,e ->
            if(e!=null) { busy.remove(id);failure(player,e);return@whenComplete }
            if(!reserved) { busy.remove(id);return@whenComplete }
            dispatch.entity(player,Runnable { release(player,grant) }) {
                if(closed || !player.isOnline) { release(player,grant);return@entity }
                val slot=player.inventory.firstEmpty()
                if(slot<0) { text.send(player,"full");release(player,grant);return@entity }
                val notification = try {
                    val stack=when {
                        grant.item==BossSpoilsSettings.ITEM -> ItemStack(Material.PAPER)
                        grant.item.startsWith("stack:") ->
                            ItemStack.deserializeBytes(Base64.getDecoder().decode(grant.item.removePrefix("stack:")))
                        else -> items.build(grant.item)
                    }
                    val meta=stack.itemMeta
                    meta.persistentDataContainer.set(receiptKey,PersistentDataType.STRING,grant.receipt);stack.itemMeta=meta
                    if(grant.item==BossSpoilsSettings.ITEM) stack.editMeta { box ->
                        box.setItemModel(NamespacedKey("dungeon","warden_spoils"))
                        box.persistentDataContainer.set(packageKey,PersistentDataType.STRING,id.toString())
                        box.setMaxStackSize(1)
                        box.itemName(text.component("spoils-name").decoration(TextDecoration.ITALIC,false))
                        box.lore(spoilsLore())
                    }
                    val detail=received(stack)
                    player.inventory.setItem(slot,stack)
                    detail
                } catch(error: Exception) { release(player,grant);failure(player,error);return@entity }
                // Do not reissue automatically after a DB failure or crash in the physical-delivery window.
                store.delivered(id,grant.receipt).whenComplete { delivered,error ->
                    if(error!=null) { busy.remove(id);failure(player,error) }
                    else dispatch.entity(player,Runnable { busy.remove(id) }) {
                        if(!closed && player.isOnline) {
                            if(delivered) player.sendMessage(notification)
                            next(player,grants,index+1)
                        } else busy.remove(id)
                    }
                }
            }
        }
    }
    private fun received(stack: ItemStack): Component = text.componentWith("delivered-item", mapOf(
        "item" to stack.effectiveName().decoration(TextDecoration.ITALIC,false),
        "amount" to Component.text(stack.amount)
    ))
    private fun spoilsLore(): List<Component> {
        fun percent(chance: Double) = BigDecimal.valueOf(chance).movePointRight(2).stripTrailingZeros().toPlainString()+"%"
        return listOf(
            text.component("spoils-source"),
            Component.empty(),
            text.component("spoils-loot-material"),
            text.component("spoils-loot-sword",mapOf("chance" to percent(spoilsSettings.chance))),
            text.component("spoils-loot-material-block",mapOf("chance" to percent(settings.materialChance))),
            text.component("spoils-loot-generator",mapOf("chance" to percent(settings.generatorChance))),
            text.component("spoils-loot-generator-limit",mapOf("attempts" to settings.dailyAttempts.toString(),"wins" to settings.dailyWins.toString())),
            Component.empty(),
            text.component("spoils-use")
        ).map { it.decoration(TextDecoration.ITALIC,false) }
    }
    private fun release(player: Player,grant: BossGrant) {
        store.release(player.uniqueId,grant.receipt).whenComplete { _,e -> if(e!=null) failure(player,e) }
        busy.remove(player.uniqueId)
    }
    private fun failure(player: Player,e: Throwable) {
        plugin.logger.log(Level.SEVERE,"Boss reward player=${player.uniqueId}: durable delivery failed; records retained",e)
        if(!closed) text.send(player,"storage-error")
    }
    override fun close() { closed=true;spoils.close();busy.clear() }
}
