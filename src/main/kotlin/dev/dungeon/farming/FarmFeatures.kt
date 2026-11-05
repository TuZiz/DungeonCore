package dev.dungeon.farming

import org.bukkit.plugin.Plugin
import java.nio.file.Files
import java.nio.file.Path

data class FarmFeatures(val spawning: SpawnSettings,val hud: EliteHudSettings) {
    companion object {
        /** IO executor only; existing settings are never overwritten. */
        fun load(folder: Path,plugin: Plugin): FarmFeatures {
            listOf("spawning.yml","elite-hud.yml").forEach { name ->
                val path=folder.resolve(name)
                if(!Files.exists(path)) requireNotNull(plugin.getResource("dungeons/lost_altar/$name")).use { Files.copy(it,path) }
            }
            return FarmFeatures(SpawnSettings.load(folder.resolve("spawning.yml")),EliteHudSettings.load(folder.resolve("elite-hud.yml")))
        }
    }
}
