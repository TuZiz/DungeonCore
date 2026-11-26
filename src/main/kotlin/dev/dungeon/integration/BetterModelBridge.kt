package dev.dungeon.integration

import org.bukkit.Location
import org.bukkit.entity.Entity
import org.bukkit.entity.Player
import org.bukkit.plugin.Plugin
import java.lang.reflect.InvocationTargetException
import java.util.concurrent.atomic.AtomicBoolean

/** Optional BetterModel 3.6 API boundary, verified against the installed BukkitAdapter signatures. */
class BetterModelBridge(plugin: Plugin) {
    private val loader=plugin.javaClass.classLoader
    private fun type(name: String)=loader.loadClass("kr.toxicity.model.api.$name")
    private val api=type("BetterModel")
    private val adapter=type("bukkit.platform.BukkitAdapter")
    private val rendererType=type("data.renderer.ModelRenderer")
    private val trackerType=type("tracker.Tracker")
    private val modifierType=type("animation.AnimationModifier")
    // Dungeon owns hurt clips; BetterModel supplies ordinary temporary damage tint.
    private val trackerModifier=type("tracker.TrackerModifier").getField("DEFAULT").get(null).let { value ->
        val builder=value.javaClass.getMethod("toBuilder").invoke(value)
        builder.javaClass.getMethod("damageAnimation",Boolean::class.javaPrimitiveType).invoke(builder,false)
        builder.javaClass.getMethod("damageTint",Boolean::class.javaPrimitiveType).invoke(builder,true)
        builder.javaClass.getMethod("build").invoke(builder)
    }
    private val once=modifierType.getField("DEFAULT_WITH_PLAY_ONCE").get(null).let { value ->
        val builder=modifierType.getMethod("toBuilder").invoke(value)
        builder.javaClass.getMethod("priority",Int::class.javaPrimitiveType).invoke(builder,100)
        builder.javaClass.getMethod("override",java.lang.Boolean::class.java).invoke(builder,true)
        builder.javaClass.getMethod("build").invoke(builder)
    }
    private fun renderer(name: String)=requireNotNull(api.getMethod("modelOrNull",String::class.java).invoke(null,name)) {
        "BetterModel model '$name' not loaded; install boss/models and rebuild BetterModel pack before restarting"
    }
    fun validate(model: String,effect: String,attacks: Set<String>) {
        listOf(model to (setOf("idle","walk","spawn","hurt","death")+attacks),effect to setOf("telegraph","burst")).forEach { (id,expected) ->
            validateAnimations(id,expected)
        }
    }
    fun validateAnimations(model: String,expected: Set<String>) {
        val names=(rendererType.getMethod("animations").invoke(renderer(model)) as Map<*,*>).keys
        check(names.containsAll(expected)) { "BetterModel model=$model missing animations ${expected-names.toSet()}" }
    }
    /** Entity owner only. BetterModel owns automatic tracking, display packets and scheduler lifecycle. */
    fun attach(name: String,entity: Entity,scale: Double): Model {
        val adapted=adapter.getMethod("adapt",Entity::class.java).invoke(null,entity)
        val tracker=rendererType.getMethod("create",type("platform.PlatformEntity"),type("tracker.TrackerModifier")).invoke(renderer(name),adapted,trackerModifier)
        return Model(tracker).apply { scale(scale) }
    }
    /** Entity owner. Adopt the MM onSpawn tracker; never silently create a duplicate/fallback. */
    fun existing(name: String,entity: Entity,scale: Double): Model {
        return requireNotNull(existingOrNull(name,entity,scale)) {
            "MythicMobs entity=${entity.uniqueId} has no BetterModel '$name'; configure bm:model onSpawn before starting Dungeon boss"
        }
    }
    fun existingOrNull(name: String,entity: Entity,scale: Double): Model? {
        val registry=api.getMethod("registryOrNull",java.util.UUID::class.java).invoke(null,entity.uniqueId)
        val tracker=registry?.let { type("tracker.EntityTrackerRegistry").getMethod("tracker",String::class.java).invoke(it,name) }
        if(tracker==null || trackerType.getMethod("isClosed").invoke(tracker)==true) return null
        return Model(tracker).apply { scale(scale) }
    }
    /** Location owner creates FX. Each viewer is spawned from that player's own scheduler. */
    fun effect(name: String,location: Location,scale: Double): Model {
        val adapted=adapter.getMethod("adapt",Location::class.java).invoke(null,location.clone())
        val tracker=rendererType.getMethod("create",type("platform.PlatformLocation"),type("tracker.TrackerModifier")).invoke(renderer(name),adapted,trackerModifier)
        return Model(tracker).apply { scale(scale) }
    }
    inner class Model(private val handle: Any): AutoCloseable {
        private var movement: String?=null
        private val terminal=AtomicBoolean()
        private fun invoke(method: String,vararg args: Any) {
            try {
                when(method) {
                    "close" -> trackerType.getMethod("close").invoke(handle)
                    "animate" -> check(trackerType.getMethod("animate",String::class.java,modifierType).invoke(handle,args[0],if(args[1]==true) once else modifierType.getField("DEFAULT").get(null))==true) { "BetterModel animation ${args[0]} rejected" }
                    "stopAnimation" -> trackerType.getMethod("stopAnimation",String::class.java).invoke(handle,args[0])
                }
            } catch(e: InvocationTargetException) { throw IllegalStateException("BetterModel $method failed",e.targetException) }
        }
        fun play(animation: String,playOnce: Boolean=true) { if(!terminal.get()) invoke("animate",animation,playOnce) }
        /** Entity owner. Retain the same displays/viewers through death, without a second spawn. */
        fun die(animation: String) {
            if(!terminal.compareAndSet(false,true)) return
            val names=(rendererType.getMethod("animations").invoke(trackerType.getMethod("renderer").invoke(handle)) as Map<*,*>).keys
            // BetterModel's MONITOR listener may already have installed its native death clip.
            val native=animation=="death" && trackerType.getMethod("forRemoval").invoke(handle)==true
            names.filter { !native || it!="death" }.forEach { invoke("stopAnimation",it!!) }
            type("tracker.EntityTracker").getMethod("cancelDamageTint").invoke(handle)
            // Native damage may have queued a red update before death. Restore the original
            // untinted texture after that queued update, on BetterModel's render pipeline.
            trackerType.getMethod("task",Runnable::class.java).invoke(handle,Runnable {
                if(trackerType.getMethod("isClosed").invoke(handle)!=true) {
                    val action=type("tracker.TrackerUpdateAction")
                    trackerType.getMethod("update",action).invoke(handle,action.getMethod("tint",Int::class.javaPrimitiveType).invoke(null,0xFFFFFF))
                }
            })
            trackerType.getMethod("forRemoval",Boolean::class.javaPrimitiveType).invoke(handle,true)
            if(!native) {
                check(trackerType.getMethod("animate",String::class.java,modifierType,Runnable::class.java)
                    .invoke(handle,animation,once,Runnable { close() })==true) { "BetterModel death animation $animation rejected" }
            }
        }
        fun moving(walk: Boolean) {
            if(terminal.get()) return
            val name=if(walk) "walk" else "idle"
            if(movement==name) return
            movement?.let { invoke("stopAnimation",it) };movement=name;play(name,false)
        }
        fun scale(value: Double) {
            val scaler=type("tracker.ModelScaler")
            trackerType.getMethod("scaler",scaler).invoke(handle,scaler.getMethod("value",Float::class.javaPrimitiveType).invoke(null,value.toFloat()))
        }
        fun show(player: Player) {
            if(trackerType.getMethod("isClosed").invoke(handle)==true) return
            val adapted=adapter.getMethod("adapt",Player::class.java).invoke(null,player)
            type("tracker.DummyTracker").getMethod("spawn",type("platform.PlatformPlayer")).invoke(handle,adapted)
        }
        override fun close() { terminal.set(true);invoke("close") }
    }
}
