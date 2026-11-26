package dev.dungeon.farming

import dev.dungeon.localization.Messages
import org.bukkit.plugin.Plugin
import java.nio.file.Files
import java.nio.file.Path

data class FarmFeatures(val spawning: SpawnSettings,val hud: EliteHudSettings,val boss: dev.dungeon.boss.BossResources?=null,
    val elites: dev.dungeon.elite.EliteSettings?=null) {
    companion object {
        /** IO executor only; existing settings are never overwritten. */
        fun load(folder: Path,plugin: Plugin,messages: Messages): FarmFeatures {
            listOf("spawning.yml","elite-hud.yml").forEach { name ->
                val path=folder.resolve(name)
                if(!Files.exists(path)) requireNotNull(plugin.getResource("dungeons/lost_altar/$name")).use { Files.copy(it,path) }
            }
            val elite=messages.section("elite")
            return FarmFeatures(SpawnSettings.load(folder.resolve("spawning.yml")),
                EliteHudSettings.load(folder.resolve("elite-hud.yml"),elite.raw("text"),elite.strings("directions")),
                dev.dungeon.boss.BossResources.load(folder,plugin,messages.section("boss")),dev.dungeon.elite.EliteResources.load(folder,plugin))
        }
    }
}
