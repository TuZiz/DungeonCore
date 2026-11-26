package dev.dungeon.localization

import org.bukkit.configuration.ConfigurationSection
import org.bukkit.configuration.file.YamlConfiguration
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption

/** Startup IO only. Preserve YAML source text while moving old files into their new namespaces. */
object LanguageMigration {
    const val RESOURCE = "lang/zh_CN.yml"
    private val groups = listOf("lobby", "farming", "elite", "boss")
    private val marker = Regex("""(?m)^schema-version:[ \t]+[012]([ \t]*(?:#[^\r\n]*)?)\r?$""")

    fun load(root: Path, defaults: String): YamlConfiguration {
        val current = root.resolve(RESOURCE)
        val archive = root.resolve("backups/language-v2")
        val legacy = groups.associateWith { current.resolveSibling("${it}_zh_CN.yml") }
        try {
            val bundled = parse(defaults)
            require(bundled.get("schema-version")==2)
            val original = current.takeIf { Files.exists(it) }?.let(Files::readString)
            val version = original?.let { parse(it).get("schema-version") }
            val oldFiles = legacy.filterValues { Files.exists(it) }
            if(version==2) {
                // A crash after the atomic replacement can leave the verified originals in lang/.
                oldFiles.values.forEach { removeBackedUp(it, archive) }
                return parse(requireNotNull(original)).apply { setDefaults(bundled) }
            }
            require(original==null || version==0 || version==1) { "unsupported schema-version $version" }
            val contents = oldFiles.mapValues { Files.readString(it.value) }
            val main = original?.removePrefix("\uFEFF") ?: defaults.substringBefore("\nlobby:")
            val oldMain = parse(main)
            require(groups.none { oldMain.contains(it) }) { "legacy common file already uses a reserved module key" }
            require(marker.containsMatchIn(main)) { "missing standalone schema-version marker" }
            val normalizedMain = main.lineSequence().filter { it!="..." }.joinToString("\n")
            val merged = buildString {
                append(marker.replace(normalizedMain) { "schema-version: 2"+it.groupValues[1] })
                for(group in groups) {
                    if(lastOrNull()!='\n') appendLine()
                    appendLine("$group:")
                    val source = contents[group]
                    if(source==null) {
                        val section = defaults.substringAfter("\n$group:\n").lineSequence()
                            .takeWhile { it.isBlank() || it.startsWith(" ") }.joinToString("\n")
                        appendLine(section.trimEnd())
                    } else {
                        val legacyVersion = parse(source).get("schema-version")
                        require(legacyVersion==0 || legacyVersion==1) { "${legacy.getValue(group)}: unsupported schema-version $legacyVersion" }
                        val body = marker.replace(source.removePrefix("\uFEFF")) { it.groupValues[1].trimStart() }
                            .lineSequence().filter { it !in setOf("---","...") }
                        append(body.joinToString("\n") { if(it.isEmpty()) "" else "  $it" })
                    }
                }
            }
            val result = parse(merged)
            require(result.get("schema-version")==2)
            require(values(result, groups.toSet()+"schema-version")==values(oldMain,setOf("schema-version"))) {
                "common message values changed during merge"
            }
            contents.forEach { (group,source) ->
                val section = requireNotNull(result.getConfigurationSection(group))
                require(values(section,emptySet())==values(parse(source),setOf("schema-version"))) {
                    "${legacy.getValue(group)}: message values changed during merge"
                }
            }

            original?.let { backup(current,it,archive) }
            contents.forEach { (group,source) -> backup(legacy.getValue(group),source,archive) }
            Files.createDirectories(current.parent)
            val temp = Files.createTempFile(current.parent,"lang-v2-",".tmp")
            try {
                Files.writeString(temp,merged)
                require(if(original==null) Files.notExists(current) else Files.readString(current)==original) {
                    "$current changed during migration"
                }
                Files.move(temp,current,StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING)
            } finally { Files.deleteIfExists(temp) }
            oldFiles.values.forEach { removeBackedUp(it, archive) }
            return result.apply { setDefaults(bundled) }
        } catch(error: Exception) {
            throw IllegalStateException("$current: language migration to schema 2 failed (backup: $archive): ${error.message}",error)
        }
    }

    private fun parse(text: String) = YamlConfiguration().apply { loadFromString(text.removePrefix("\uFEFF")) }

    private fun values(section: ConfigurationSection, excluded: Set<String>): Map<String,Any> =
        section.getValues(true).filter { (key,value) -> key.substringBefore('.') !in excluded && value !is ConfigurationSection }

    private fun backup(path: Path, original: String, archive: Path) {
        require(Files.readString(path)==original) { "$path changed during migration" }
        Files.createDirectories(archive)
        val saved = archive.resolve(path.fileName)
        if(Files.notExists(saved)) Files.copy(path,saved)
        require(Files.readString(saved)==original) { "$saved differs from $path; originals retained" }
    }

    private fun removeBackedUp(path: Path, archive: Path) {
        val saved=archive.resolve(path.fileName)
        if(Files.exists(saved) && Files.mismatch(saved,path)==-1L) Files.delete(path)
    }
}
