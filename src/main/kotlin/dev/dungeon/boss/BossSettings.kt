package dev.dungeon.boss

import org.bukkit.configuration.file.YamlConfiguration
import java.nio.file.Path
import java.time.ZoneId

data class BossPoint(val x: Double,val y: Double,val z: Double) {
    fun distanceSquared(x: Double,y: Double,z: Double)=(this.x-x)*(this.x-x)+(this.y-y)*(this.y-y)+(this.z-z)*(this.z-z)
}
data class BossRewardsSettings(val contribution: Double,val recentSeconds: Long,val range: Double,
    val materialId: String,val materialChance: Double,val generatorId: String,val generatorChance: Double,
    val dailyAttempts: Int,val dailyWins: Int,val zone: ZoneId)
data class BossAttack(val animation: String,val windup: Long,val duration: Long,val radius: Double,val damage: Double,val forward: Double)
data class BossImpact(val resistance: Double=.75,val horizontal: Double=.16,val vertical: Double=.12,val recoveryTicks: Long=6)
data class BossSettings(val enabled: Boolean,val mob: String,val model: String,val effect: String,val point: BossPoint,
    val health: Double,val scale: Double,val respawnSeconds: Long,val initialSeconds: Long,val leash: Double,
    val resetSeconds: Long,val melee: BossAttack,val sweep: BossAttack,val special: BossAttack,
    val rewards: BossRewardsSettings,val impact: BossImpact=BossImpact()) {
    companion object {
        fun load(path: Path): BossSettings {
            val c=YamlConfiguration().apply { load(path.toFile()) }
            fun n(k: String,min: Double,max: Double): Double {
                val v=c.get(k)
                require(v is Number && v.toDouble().isFinite() && v.toDouble() in min..max) { "$path:$k invalid value $v ($min..$max)" }
                return v.toDouble()
            }
            fun integer(k: String,min: Int,max: Int): Long {
                val v=n(k,min.toDouble(),max.toDouble());require(v==v.toLong().toDouble()) { "$path:$k expected integer: $v" };return v.toLong()
            }
            fun id(k: String,pattern: String): String= requireNotNull(c.getString(k)) { "$path:$k missing" }.also {
                require(it.matches(Regex(pattern))) { "$path:$k invalid ID $it" }
            }
            require(c.getInt("schema-version")==1 && c.get("enabled") is Boolean) { "$path: invalid schema-version/enabled" }
            val zone=try { ZoneId.of(c.getString("rewards.day-zone")) } catch(e: Exception) { throw IllegalArgumentException("$path:rewards.day-zone invalid",e) }
            fun attack(k: String): BossAttack {
                val windup=integer("attacks.$k.windup-ticks",8,200);val duration=integer("attacks.$k.duration-ticks",10,240)
                require(windup<duration) { "$path:attacks.$k windup must be shorter than duration" }
                return BossAttack(id("attacks.$k.animation","[a-z0-9_]+"),windup,duration,n("attacks.$k.radius",1.0,10.0),n("attacks.$k.damage",1.0,60.0),n("attacks.$k.forward",0.0,4.0))
            }
            fun impactNumber(k: String,default: Double,min: Double,max: Double)=if(c.contains("impact.$k")) n("impact.$k",min,max) else default
            val recovery=impactNumber("recovery-ticks",6.0,2.0,20.0)
            require(recovery==recovery.toLong().toDouble()) { "$path:impact.recovery-ticks expected integer: $recovery" }
            return BossSettings(c.getBoolean("enabled"),id("mythic-mob","[\\p{L}\\p{N}_-]+"),id("model","[a-z0-9_]+"),id("effect-model","[a-z0-9_]+"),
                BossPoint(n("arena.x",-29999984.0,29999984.0),n("arena.y",-60.0,310.0),n("arena.z",-29999984.0,29999984.0)),
                n("health",20.0,100000.0),n("model-scale",.1,4.0),integer("respawn-seconds",60,86400),integer("initial-seconds",5,86400),
                n("arena.leash-radius",8.0,32.0),integer("reset-seconds",10,300),attack("melee"),attack("sweep"),attack("special"),
                BossRewardsSettings(n("rewards.contribution",.001,1.0),integer("rewards.recent-seconds",5,600),n("rewards.range",8.0,128.0),
                    id("rewards.material.id","[a-z0-9_]+:[a-z0-9_/]+"),n("rewards.material.chance",0.0,1.0),
                    id("rewards.generator.id","[a-z0-9_]+:[a-z0-9_/]+"),n("rewards.generator.chance",0.0,1.0),
                    integer("rewards.generator.daily-attempts",1,100).toInt(),integer("rewards.generator.daily-wins",1,100).toInt(),zone),
                BossImpact(impactNumber("knockback-resistance",.75,0.0,1.0),impactNumber("max-horizontal",.16,0.0,.5),
                    impactNumber("max-vertical",.12,0.0,.4),recovery.toLong()))
        }
    }
}
