package dev.dungeon.localization

import dev.dungeon.scheduling.Dispatch
import net.kyori.adventure.text.minimessage.MiniMessage
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder
import org.bukkit.command.CommandSender
import org.bukkit.entity.Player
import org.bukkit.configuration.file.YamlConfiguration
class Messages(private val texts: YamlConfiguration, private val dispatch: Dispatch, private val prefix: String = "") {
    fun section(name: String) = Messages(texts,dispatch,"$prefix$name.")
    fun raw(key: String): String = requireNotNull(texts.getString(prefix+key)) { "lang/zh_CN.yml: missing $prefix$key" }
    fun strings(key: String): List<String> = texts.getStringList(prefix+key)
    private val mini = MiniMessage.miniMessage()
    fun component(key: String, values: Map<String,String> = emptyMap()): net.kyori.adventure.text.Component =
        mini.deserialize(raw(key),
            *values.map { (name,value) -> Placeholder.unparsed(name,value) }.toTypedArray())
    fun componentWith(key: String, values: Map<String,net.kyori.adventure.text.Component>): net.kyori.adventure.text.Component =
        mini.deserialize(raw(key),*values.map { (name,value) -> Placeholder.component(name,value) }.toTypedArray())
    fun componentLines(key: String, values: Map<String,net.kyori.adventure.text.Component>): net.kyori.adventure.text.Component {
        require(texts.isList(prefix+key)) { "lang/zh_CN.yml: expected a list at $prefix$key" }
        return mini.deserialize(strings(key).joinToString("\n"),
            *values.map { (name,value) -> Placeholder.component(name,value) }.toTypedArray())
    }
    fun sendLines(sender: CommandSender, key: String) {
        require(texts.isList(prefix+key)) { "lang/zh_CN.yml: expected a list at $prefix$key" }
        deliver(sender,mini.deserialize(strings(key).joinToString("\n")))
    }
    fun send(sender: CommandSender, key: String, detail: String = "") {
        val text = raw(key)
        val message = mini.deserialize(text, Placeholder.unparsed("detail",detail))
        deliver(sender,message)
    }
    private fun deliver(sender: CommandSender, message: net.kyori.adventure.text.Component) {
        if(sender is Player) dispatch.entity(sender) { sender.sendMessage(message) }
        else dispatch.global { sender.sendMessage(message) }
    }
}
