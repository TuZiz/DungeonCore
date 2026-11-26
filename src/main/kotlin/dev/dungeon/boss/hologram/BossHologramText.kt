package dev.dungeon.boss.hologram

import dev.dungeon.boss.BossSettings
import dev.dungeon.localization.Messages
import net.kyori.adventure.text.Component
import java.math.BigDecimal

enum class BossDisplayStatus { LOADING,PAUSED,WAITING,READY,SUMMONING,ACTIVE,FIGHTING,RESETTING }
data class BossDisplayState(val status: BossDisplayStatus,val nextAt: Long)

/** Pure display of the actual module deadline; never maintains a second refresh timer. */
class BossHologramText(private val boss: BossSettings,private val messages: Messages) {
    fun render(state: BossDisplayState,now: Long): Component {
        val values: MutableMap<String,Component> =mapOf("name" to boss.mob,"health" to number(boss.health),
            "respawn" to number(boss.respawnSeconds/60.0),"contribution" to number(boss.health*boss.rewards.contribution),
            "recent" to boss.rewards.recentSeconds.toString(),"range" to number(boss.rewards.range),
            "material" to number(boss.rewards.materialChance*100),"generator" to number(boss.rewards.generatorChance*100),
            "attempts" to boss.rewards.dailyAttempts.toString(),"wins" to boss.rewards.dailyWins.toString())
            .mapValues { Component.text(it.value) }.toMutableMap()
        values["status"]=messages.component("hologram.status.${state.status.name.lowercase()}",
            mapOf("time" to remaining(state.nextAt,now)))
        return messages.componentLines("hologram.lines",values)
    }
    private fun number(value: Double)=BigDecimal.valueOf(value).stripTrailingZeros().toPlainString()
    companion object {
        fun remaining(deadline: Long,now: Long): String {
            val seconds=if(deadline<=now) 0L else (deadline-now)/1000+if((deadline-now)%1000>0) 1 else 0
            return if(seconds>=3600) "%02d:%02d:%02d".format(seconds/3600,seconds/60%60,seconds%60)
                else "%02d:%02d".format(seconds/60,seconds%60)
        }
    }
}
