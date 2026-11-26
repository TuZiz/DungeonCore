package dev.dungeon.boss

import dev.dungeon.localization.Messages
import dev.dungeon.boss.reward.BossSpoilsSettings
import org.bukkit.plugin.Plugin
import java.nio.file.Files
import java.nio.file.Path
import java.util.logging.Level

data class BossResources(val settings: BossSettings,val messages: Messages,val hologram: dev.dungeon.boss.hologram.BossHologramSettings?,
    val spoils: BossSpoilsSettings) {
    companion object {
        /** IO worker only. Install missing files, never overwrite an administrator's resources. */
        fun load(folder: Path,plugin: Plugin,messages: Messages): BossResources? = try {
            fun copy(resource: String,target: Path) {
                if(!Files.exists(target)) {
                    Files.createDirectories(target.parent)
                    requireNotNull(plugin.getResource(resource)).use { Files.copy(it,target) }
                }
            }
            copy("dungeons/lost_altar/boss.yml",folder.resolve("boss.yml"))
            val settings=BossSettings.load(folder.resolve("boss.yml"))
            val plugins=plugin.dataFolder.toPath().parent
            if(settings.enabled) {
                if(Files.isDirectory(plugins.resolve("BetterModel"))) listOf("stone_site_warden","stone_site_wave").forEach {
                    copy("boss/models/$it.bbmodel",plugins.resolve("BetterModel/models/dungeon/$it.bbmodel"))
                }
                if(Files.isDirectory(plugins.resolve("MythicMobs"))) copy("integrations/mythicmobs/StoneSiteWarden.yml",plugins.resolve("MythicMobs/mobs/DungeonStoneSiteWarden.yml"))
            }
            val hologram=try {
                val path=plugin.dataFolder.toPath().resolve("boss-hologram.yml")
                copy("boss-hologram.yml",path)
                dev.dungeon.boss.hologram.BossHologramSettings.load(path)
            } catch(e: Exception) { plugin.logger.log(Level.SEVERE,"boss-hologram.yml: hologram disabled; boss retained",e);null }
            val spoils=plugin.dataFolder.toPath().resolve("boss-spoils.yml")
            copy("boss-spoils.yml",spoils)
            BossResources(settings,messages,
                hologram,BossSpoilsSettings.load(spoils))
        } catch(e: Exception) {
            plugin.logger.log(Level.SEVERE,"dungeons/lost_altar/boss.yml: boss disabled; permanent dungeon remains available",e);null
        }
    }
}
