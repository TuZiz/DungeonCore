package dev.dungeon.configuration

import org.bukkit.plugin.Plugin
import java.nio.file.Files
import java.nio.file.Path

class DungeonCreator(private val plugin: Plugin, private val root: Path) {
    fun create(id: String) {
        require(id.matches(Regex("[a-z0-9_-]+"))) { "Invalid dungeon ID" }
        val target = root.resolve(id)
        require(!Files.exists(target)) { "Dungeon already exists: $id" }
        Files.createDirectory(target)
        listOf("dungeon.yml","locations.yml","stages.yml","mobs.yml","obstacles.yml","rewards.yml","scripts/events.yml").forEach { file ->
            val dest = target.resolve(file);Files.createDirectories(dest.parent)
            requireNotNull(plugin.getResource("dungeons/example/$file")).use { Files.copy(it,dest) }
        }
    }
}
