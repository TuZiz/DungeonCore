package dev.dungeon.integration

import org.bukkit.Bukkit
import org.bukkit.inventory.ItemStack

/** Uses the live public service; never manufactures ItemHub or AsterStats metadata. */
class AsterQualityBridge {
    fun build(id: String, group: Group): ItemStack {
        val plugin = checkNotNull(Bukkit.getPluginManager().getPlugin("AsterStats")?.takeIf { it.isEnabled }) {
            "AsterStats is unavailable for boss equipment $id"
        }
        val type = plugin.javaClass.classLoader.loadClass("dev.aster.asterstats.api.AsterStatsQualityApi")
        val service = checkNotNull(Bukkit.getServicesManager().load(type)) { "AsterStats quality service is unavailable" }
        check(type.getMethod("hasQuality", String::class.java).invoke(service,id)==true) { "No quality profile for $id" }
        val create = type.getMethod("createRandomItem",String::class.java)
        val snapshot = type.getMethod("snapshot",ItemStack::class.java)
        val tier = snapshot.returnType.getMethod("getTier")
        // Rejection sampling preserves the provider's conditional distribution within the chosen group.
        repeat(48) {
            val item = checkNotNull(create.invoke(service,id) as? ItemStack) { "AsterStats could not generate $id" }
            val quality = checkNotNull(snapshot.invoke(service,item)) { "Missing AsterStats roll for $id" }
            val actual = when(tier.invoke(quality)) {
                "common" -> Group.COMMON
                "excellent" -> Group.EXCELLENT
                "rare","epic","legendary","mythic","eternal" -> Group.HIGHER
                else -> return@repeat
            }
            if(actual==group) return item
        }
        error("AsterStats $id had no $group in 48 attempts; check quality weights")
    }
    enum class Group { COMMON, EXCELLENT, HIGHER }
}
