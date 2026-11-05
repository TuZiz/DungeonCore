package dev.dungeon.farming

import java.nio.file.Files
import java.nio.file.Path

/** File-only startup migration; called on the plugin IO executor before definitions load. */
object FarmConfiguration {
    fun prepare(root: Path, defaultConfig: () -> ByteArray): Path {
        val dungeons = root.resolve("dungeons")
        Files.createDirectories(dungeons)
        val legacy = root.resolve("farming.yml")
        if (Files.exists(legacy)) {
            val settings = FarmSettings.load(legacy)
            val destination = dungeons.resolve(settings.id).resolve("farming.yml")
            require(!Files.exists(destination)) { "$legacy: migration conflict with $destination; both files retained" }
            require(!Files.exists(destination.parent.resolve("dungeon.yml"))) {
                "$legacy: migration conflicts with staged dungeon ${destination.parent}"
            }
            Files.createDirectories(destination.parent)
            Files.move(legacy, destination)
        }
        val configs = Files.list(dungeons).use { folders ->
            folders.filter { Files.isDirectory(it) && Files.exists(it.resolve("farming.yml")) }
                .map { it.resolve("farming.yml") }.toList()
        }
        require(configs.size <= 1) { "$dungeons: only one permanent farming configuration is supported: $configs" }
        val path = configs.singleOrNull() ?: dungeons.resolve("lost_altar/farming.yml").also {
            Files.createDirectories(it.parent)
            Files.write(it, defaultConfig(), java.nio.file.StandardOpenOption.CREATE_NEW)
        }
        requireDedicatedFolder(path.parent)
        return path
    }

    fun requireDedicatedFolder(folder: Path) {
        val path = folder.resolve("farming.yml")
        require(!Files.exists(folder.resolve("dungeon.yml"))) { "$folder: farming.yml and dungeon.yml cannot coexist" }
        require(FarmSettings.load(path).id == folder.fileName.toString()) { "$path:id must match directory name" }
    }
}
