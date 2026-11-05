package dev.dungeon.world

import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.util.UUID

/** Only direct children bearing a matching ownership marker can ever be deleted. */
class TemplateFiles(private val templates: Path, private val worlds: Path) {
    fun copy(template: String, id: UUID): String {
        require(template.matches(Regex("[a-zA-Z0-9_-]+")))
        val source = templates.resolve(template).normalize()
        require(Files.isDirectory(source) && !Files.isSymbolicLink(source)) { "Missing offline template $source" }
        val sourceReal = source.toRealPath()
        val name = "dungeon_$id"; val target = worlds.resolve(name)
        require(!Files.exists(target)); Files.createDirectory(target)
        Files.writeString(target.resolve(".dungeon-owner"),id.toString())
        Files.walk(source).use { paths -> paths.forEach { p ->
            check(!Thread.currentThread().isInterrupted) { "Template copy cancelled: $target" }
            require(!Files.isSymbolicLink(p) && p.toRealPath().startsWith(sourceReal)) { "Template link escape rejected: $p" }
            val rel = source.relativize(p)
            if(rel.toString().isNotEmpty() && p.fileName.toString() !in setOf("uid.dat","session.lock",".dungeon-owner")) {
                val dest = target.resolve(rel)
                if(Files.isDirectory(p)) Files.createDirectories(dest) else Files.copy(p,dest)
            }
        } }; return name
    }
    fun owned(): List<Pair<String, UUID>> = Files.list(worlds).use { paths -> paths.filter { p ->
        Files.isDirectory(p,LinkOption.NOFOLLOW_LINKS) && p.fileName.toString().startsWith("dungeon_")
    }.toList().mapNotNull { p ->
        val id = runCatching { UUID.fromString(p.fileName.toString().removePrefix("dungeon_")) }.getOrNull()
        if(id != null && Files.isRegularFile(p.resolve(".dungeon-owner"),LinkOption.NOFOLLOW_LINKS) && Files.readString(p.resolve(".dungeon-owner")) == id.toString()) p.fileName.toString() to id else null
    } }
    fun delete(name: String, id: UUID) {
        require(name == "dungeon_$id")
        val root = worlds.toAbsolutePath().normalize(); val target = root.resolve(name).normalize()
        require(target.parent == root && !Files.isSymbolicLink(target))
        val marker = target.resolve(".dungeon-owner")
        require(Files.isRegularFile(marker,LinkOption.NOFOLLOW_LINKS) && Files.readString(marker) == id.toString()) { "Ownership mismatch: $target" }
        // Files.walk does not follow links. A link within a generated world is deleted, never traversed.
        Files.walk(target).use { it.sorted(Comparator.reverseOrder()).forEach { p -> Files.delete(p) } }
    }
}
