package dev.dungeon.lobby

import org.bukkit.configuration.file.YamlConfiguration
import java.io.Reader

data class MapManifest(val dataVersion: Int, val files: Map<String,String>) {
    fun requireCompatible(serverDataVersion: Int) {
        require(serverDataVersion >= dataVersion) { "Lobby map DataVersion=$dataVersion is newer than server=$serverDataVersion; downgrade rejected" }
    }
    companion object {
        fun read(reader: Reader): MapManifest {
            val y = YamlConfiguration().apply { load(reader) }
            require(y.getInt("schema-version") == 1)
            val entries = y.getMapList("files")
            val files = entries.associate { entry ->
                val path = entry["path"];val hash = entry["sha256"]
                require(path is String && path.isNotBlank() && hash is String && hash.matches(Regex("[a-f0-9]{64}"))) { "Invalid map manifest entry: $entry" }
                path to hash
            }
            require(files.size == entries.size) { "Duplicate map manifest path" }
            require(files.isNotEmpty() && "level.dat" in files && files.keys.any { it.endsWith(".mca") })
            return MapManifest(y.getInt("data-version").also { require(it > 0) },files)
        }
    }
}
