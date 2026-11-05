package dev.dungeon.localization

import dev.dungeon.scheduling.Dispatch
import net.kyori.adventure.text.minimessage.MiniMessage
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder
import org.bukkit.command.CommandSender
import org.bukkit.entity.Player
import org.bukkit.configuration.file.YamlConfiguration
import java.nio.file.Path

class Messages(path: Path, private val dispatch: Dispatch) {
    private val texts = YamlConfiguration().apply { dev.dungeon.configuration.SchemaMigration.migrate(path);load(path.toFile()); require(getInt("schema-version") == 1) }
    private val mini = MiniMessage.miniMessage()
    fun sendLines(sender: CommandSender, key: String) {
        require(texts.isList(key)) { "Missing language list $key" }
        deliver(sender,mini.deserialize(texts.getStringList(key).joinToString("\n")))
    }
    fun send(sender: CommandSender, key: String, detail: String = "") {
        val text = requireNotNull(texts.getString(key)) { "Missing lang key $key" }
        val message = mini.deserialize(text, Placeholder.unparsed("detail",detail))
        deliver(sender,message)
    }
    private fun deliver(sender: CommandSender, message: net.kyori.adventure.text.Component) {
        if(sender is Player) dispatch.entity(sender) { sender.sendMessage(message) }
        else dispatch.global { sender.sendMessage(message) }
    }
}
