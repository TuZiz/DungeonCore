package dev.dungeon.lobby

import java.nio.file.Path

/** Paper 26 exposes the primary world's folder at dimensions/minecraft/overworld. */
object LobbyWorldRoot {
    fun fromPrimaryWorldFolder(folder: Path): Path {
        val path = folder.toAbsolutePath().normalize()
        val overworld = path.fileName?.toString()?.equals("overworld",true) == true
        val namespace = path.parent?.fileName?.toString()?.equals("minecraft",true) == true
        val dimensions = path.parent?.parent?.fileName?.toString()?.equals("dimensions",true) == true
        return if(overworld && namespace && dimensions) path.parent.parent.parent else path
    }
}
