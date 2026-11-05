package dev.dungeon.configuration

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption

/** Schema 0 has identical fields to 1. Only the explicit version marker is migrated. */
object SchemaMigration {
    fun migrate(path: Path) {
        val original = Files.readString(path)
        val marker = Regex("(?m)^schema-version: +0[ \t]*(?:#.*)?$")
        if(!marker.containsMatchIn(original)) return
        val backup = path.resolveSibling(path.fileName.toString()+".v0.bak")
        if(!Files.exists(backup)) Files.copy(path,backup)
        val temp = Files.createTempFile(path.parent,"schema-",".tmp")
        try {
            Files.writeString(temp,marker.replace(original,"schema-version: 1"))
            Files.move(temp,path,StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING)
        } catch(e: Exception) {
            throw IllegalStateException("Migration $path schema 0 -> 1 failed; backup=$backup: ${e.message}",e)
        } finally { Files.deleteIfExists(temp) }
    }
}
