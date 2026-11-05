package dev.dungeon.integration

import org.bukkit.Bukkit
import org.bukkit.entity.Player
import java.lang.reflect.InvocationTargetException

/** Optional reflective boundary, signatures verified against the installed Xerr 2.2.0 JAR. */
class XerrEliteHud(private val worldId: java.util.UUID) {
    private var source: org.bukkit.plugin.Plugin?=null
    private var api: Any?=null
    private var renderer: Any?=null
    private var builder: Any?=null
    private var positioned: Any?=null
    @Synchronized private fun connect(): Boolean {
        val plugin=Bukkit.getPluginManager().getPlugin("Xerr")?.takeIf { it.isEnabled } ?: return false
        if(source===plugin && api!=null) return true
        val loader=plugin.javaClass.classLoader
        fun singleton(name: String)=loader.loadClass(name).let { it.getField("INSTANCE").get(null) }
        source=plugin
        api=loader.loadClass("com.hiusers.xerr.api.XerrAPI").getMethod("get").invoke(null)
        renderer=singleton("com.hiusers.xerr.api.container.BossbarHudRenderer")
        builder=singleton("com.hiusers.xerr.api.builder.ComponentBuilder")
        positioned=singleton("com.hiusers.xerr.api.font.PositionedText")
        return true
    }
    /** Caller owns this player's entity thread. */
    fun render(player: Player,layout: String,values: Map<String,String>) {
        // Recheck current player world at submission time; stale callbacks cannot repaint an exited map.
        if(player.world.uid!=worldId) { remove(player);return }
        if(!connect()) return
        try {
            val layouts=api!!.javaClass.getMethod("layouts").invoke(api)
            val contract=source!!.javaClass.classLoader.loadClass("com.hiusers.xerr.api.layout.XerrLayoutAPI")
            check(contract.getMethod("exists",String::class.java).invoke(layouts,layout)==true) { "Xerr layout missing: $layout" }
            val sourceText=contract.getMethod("renderString",Player::class.java,String::class.java,Map::class.java,Boolean::class.javaPrimitiveType,String::class.java)
                .invoke(layouts,player,layout,values,true,"bossbar:$SLOT") as? String ?: error("Xerr returned no layout: $layout")
            val raw=builder!!.javaClass.getMethod("buildRaw",String::class.java).invoke(builder,sourceText) as String
            val loader=source!!.javaClass.classLoader
            val json=loader.loadClass("com.google.gson.JsonParser").getMethod("parseString",String::class.java).invoke(null,raw)
            val payload=positioned!!.javaClass.getMethod("render",loader.loadClass("com.google.gson.JsonElement"),Map::class.java,Int::class.javaPrimitiveType,Int::class.javaPrimitiveType)
                .invoke(positioned,json,emptyMap<String,Any>(),255,0) as String
            renderer!!.javaClass.getMethod("appendLayoutRaw",Player::class.java,String::class.java,String::class.java).invoke(renderer,player,SLOT,payload)
        } catch(e: InvocationTargetException) { throw IllegalStateException("Xerr elite HUD failed",e.targetException) }
    }
    fun remove(player: Player) {
        if(!connect()) return
        renderer!!.javaClass.getMethod("removeLayout",Player::class.java,String::class.java).invoke(renderer,player,SLOT)
    }
    companion object { const val SLOT="dungeon:elite_direction" }
}
