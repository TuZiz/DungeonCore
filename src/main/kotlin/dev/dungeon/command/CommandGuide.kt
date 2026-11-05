package dev.dungeon.command

import dev.dungeon.localization.Messages
import org.bukkit.command.CommandSender
import org.bukkit.entity.Player

/** Presentation and syntax checks only; gameplay conditions remain with their services. */
class CommandGuide(private val messages: Messages) {
    private val admin = setOf("create","reload","validate","test","debug")
    private val playerOnly = setOf("lobby","enter","exit","status","party","test","debug")
    fun show(sender: CommandSender, topic: String = "player") {
        if(topic == "admin" && !sender.hasPermission("dungeon.admin")) messages.send(sender,"permission")
        else messages.sendLines(sender,"help.$topic")
    }
    fun intercept(sender: CommandSender, args: Array<out String>): Boolean {
        val sub = args.firstOrNull()?.lowercase()
        if(sub == null) {
            show(sender)
            if(sender.hasPermission("dungeon.admin")) messages.send(sender,"help-admin-hint")
            return true
        }
        if(sub == "help") {
            val topic = args.getOrElse(1) { "player" }.lowercase()
            if(args.size > 2 || topic !in setOf("player","party","admin")) messages.send(sender,"usage.help")
            else show(sender,topic)
            return true
        }
        if(sub !in playerOnly && sub !in admin && sub != "list") {
            messages.send(sender,"unknown-command");show(sender);return true
        }
        if(sub in admin && !sender.hasPermission("dungeon.admin")) { messages.send(sender,"permission");return true }
        if(sub in playerOnly && sender !is Player) { messages.send(sender,"player-only");return true }
        val valid = when(sub) {
            "enter", "test" -> args.size in 2..3
            "create" -> args.size == 2
            "reload", "validate" -> args.size in 1..2
            "party" -> when(args.getOrNull(1)?.lowercase()) {
                "invite" -> args.size == 3
                "accept", "leave" -> args.size == 2
                else -> false
            }
            else -> args.size == 1
        }
        if(!valid) {
            if(sub == "party") show(sender,"party") else messages.send(sender,"usage.$sub")
            return true
        }
        return false
    }
}
