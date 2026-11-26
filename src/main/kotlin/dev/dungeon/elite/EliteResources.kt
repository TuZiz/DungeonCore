package dev.dungeon.elite

import org.bukkit.plugin.Plugin
import java.nio.file.Files
import java.nio.file.Path
import java.util.logging.Level

object EliteResources {
    /** IO executor only. Missing resources are installed without replacing custom files. */
    fun load(folder: Path,plugin: Plugin): EliteSettings? = try {
        fun copy(resource: String,target: Path) {
            if(!Files.exists(target)) {
                Files.createDirectories(target.parent)
                requireNotNull(plugin.getResource(resource)).use { Files.copy(it,target) }
            }
        }
        copy("dungeons/lost_altar/elites.yml",folder.resolve("elites.yml"))
        val settings=EliteSettings.load(folder.resolve("elites.yml"))
        val plugins=plugin.dataFolder.toPath().parent
        if(settings.enabled) {
            if(Files.isDirectory(plugins.resolve("BetterModel"))) copy("elites/models/briarqueen_rose.bbmodel",plugins.resolve("BetterModel/models/dungeon/briarqueen_rose.bbmodel"))
            if(Files.isDirectory(plugins.resolve("MythicMobs"))) copy("integrations/mythicmobs/BriarqueenRose.yml",plugins.resolve("MythicMobs/mobs/DungeonBriarqueenRose.yml"))
        }
        settings
    } catch(error: Exception) {
        plugin.logger.log(Level.SEVERE,"dungeons/lost_altar/elites.yml: modeled elites paused; ordinary spawning remains available",error);null
    }
}
