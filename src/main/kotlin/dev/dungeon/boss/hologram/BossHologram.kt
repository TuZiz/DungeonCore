package dev.dungeon.boss.hologram

import dev.dungeon.boss.BossSettings
import dev.dungeon.farming.FarmPresence
import dev.dungeon.localization.Messages
import dev.dungeon.scheduling.Dispatch
import org.bukkit.Bukkit
import org.bukkit.Location
import org.bukkit.NamespacedKey
import org.bukkit.World
import org.bukkit.entity.Display
import org.bukkit.entity.TextDisplay
import org.bukkit.event.EventHandler
import org.bukkit.event.HandlerList
import org.bukkit.event.Listener
import org.bukkit.event.world.EntitiesLoadEvent
import org.bukkit.event.world.EntitiesUnloadEvent
import org.bukkit.persistence.PersistentDataType
import org.bukkit.plugin.Plugin
import java.util.concurrent.atomic.AtomicReference
import java.util.logging.Level

/** One nonpersistent display. Global work renders immutable text; its region owns all entity access. */
class BossHologram(private val plugin: Plugin,private val world: World,boss: BossSettings,
    private val settings: BossHologramSettings,messages: Messages,private val dispatch: Dispatch,
    private val presences: () -> List<FarmPresence>,private val state: () -> BossDisplayState): Listener,AutoCloseable {
    private val at=Location(world,boss.point.x,boss.point.y+settings.height,boss.point.z)
    private val text=BossHologramText(boss,messages)
    private val marker=NamespacedKey(plugin,"boss_hologram")
    private val display=AtomicReference<TextDisplay?>()
    @Volatile private var closed=false
    @Volatile private var error: String?=null
    private val task: io.papermc.paper.threadedregions.scheduler.ScheduledTask
    init {
        Bukkit.getPluginManager().registerEvents(this,plugin)
        task=Bukkit.getGlobalRegionScheduler().runAtFixedRate(plugin,{ pulse() },1,20)
    }
    /** Global scheduler only; never loads a chunk just to show a distant hologram. */
    private fun pulse() {
        if(closed || !dispatch.isActive) return
        if(display.get()==null && presences().none { it.distanceSquared(at.x,at.y-settings.height,at.z)<=settings.range*settings.range }) return
        val component=try { text.render(state(),System.currentTimeMillis()) } catch(e: Exception) { failure(e);return }
        dispatch.region(at) {
            if(closed || !world.isChunkLoaded(at.blockX shr 4,at.blockZ shr 4)) return@region
            try {
                var entity=display.get()
                if(entity!=null && !Bukkit.isOwnedByCurrentRegion(entity)) {
                    display.compareAndSet(entity,null);remove(entity);return@region
                }
                if(entity==null || !entity.isValid) {
                    entity=world.spawn(at,TextDisplay::class.java) { created ->
                        created.isPersistent=false
                        created.persistentDataContainer.set(marker,PersistentDataType.BYTE,1.toByte())
                        created.billboard=Display.Billboard.CENTER
                        created.viewRange=(settings.range/64.0).toFloat()
                        created.isShadowed=true;created.isSeeThrough=false
                        created.isDefaultBackground=false;created.backgroundColor=org.bukkit.Color.fromARGB(0,0,0,0)
                        created.lineWidth=settings.width
                        created.transformation=created.transformation.apply { scale.set(settings.scale) }
                        created.text(component)
                    }
                    display.set(entity)
                } else if(entity.text()!=component) entity.text(component)
                if(closed && display.compareAndSet(entity,null)) remove(entity)
                error=null
            } catch(e: Exception) { failure(e) }
        }
    }
    /** Loaded entity's region. Remove only orphaned Dungeon display markers, never other holograms. */
    @EventHandler fun loaded(event: EntitiesLoadEvent) {
        if(event.world.uid!=world.uid) return
        event.entities.filter { it.persistentDataContainer.has(marker,PersistentDataType.BYTE) && it.uniqueId!=display.get()?.uniqueId }.forEach { it.remove() }
    }
    @EventHandler fun unloaded(event: EntitiesUnloadEvent) {
        if(event.world.uid!=world.uid) return
        val current=display.get() ?: return
        if(event.entities.any { it.uniqueId==current.uniqueId }) display.compareAndSet(current,null)
    }
    private fun failure(e: Exception) {
        val key=e.message ?: e.javaClass.name
        if(error!=key) plugin.logger.log(Level.WARNING,"boss-hologram.yml world=${world.name} at=$at: display unavailable; boss retained",e)
        error=key
    }
    private fun remove(entity: TextDisplay) {
        if(Bukkit.isOwnedByCurrentRegion(entity)) entity.remove()
        else if(plugin.isEnabled && dispatch.isActive) dispatch.entity(entity) { entity.remove() }
    }
    override fun close() {
        closed=true;task.cancel();HandlerList.unregisterAll(this);display.getAndSet(null)?.let(::remove)
    }
}
