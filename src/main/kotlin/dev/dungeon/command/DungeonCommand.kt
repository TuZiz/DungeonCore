package dev.dungeon.command

import dev.dungeon.configuration.Definitions
import dev.dungeon.localization.Messages
import dev.dungeon.session.Parties
import dev.dungeon.session.Sessions
import dev.dungeon.scheduling.Dispatch
import org.bukkit.command.*
import org.bukkit.entity.Player
import java.util.logging.Logger

class DungeonCommand(private val definitions: Definitions, private val sessions: Sessions, private val parties: Parties,
    private val dispatch: Dispatch, private val messages: Messages, private val logger: Logger, private val creator: dev.dungeon.configuration.DungeonCreator,
    private val lobby: dev.dungeon.lobby.LobbyService,
    private val farm: dev.dungeon.farming.FarmService) : CommandExecutor, TabCompleter {
    private val guide = CommandGuide(messages)
    override fun onCommand(sender: CommandSender, command: Command, label: String, args: Array<out String>): Boolean {
        if(!sender.hasPermission("dungeon.use")) { messages.send(sender,"permission");return true }
        if(guide.intercept(sender,args)) return true
        val normalized = args.mapIndexed { index,value -> if(index == 0 || (index == 1 && args[0].equals("party",true))) value.lowercase() else value }.toTypedArray()
        return execute(sender,normalized)
    }
    private fun execute(sender: CommandSender, args: Array<out String>): Boolean {
        try {
            when(args.firstOrNull()?.lowercase()) {
                "lobby" -> lobby.enter(requireNotNull(sender as? Player))
                "list" -> messages.send(sender,"list",(definitions.current.keys + if(farm.settings.enabled) setOf(farm.settings.id) else emptySet()).joinToString(", "))
                "enter" -> {
                    val player = requireNotNull(sender as? Player)
                    require(args.size in 2..3)
                    if(farm.settings.enabled && args[1]==farm.settings.id) farm.enter(player)
                    else sessions.start(player,parties.members(player.uniqueId),args[1],args.getOrElse(2) { "normal" })
                }
                "exit" -> { val p=requireNotNull(sender as? Player);if(farm.contains(p)) lobby.enter(p) else sessions.leave(p) }
                "status" -> {
                    val player = requireNotNull(sender as? Player)
                    val run = sessions.current(player.uniqueId)?.run
                    messages.send(sender,"status",run?.let { "${it.id} ${it.definition.id} ${it.snapshot()}" } ?: "-")
                }
                "party" -> {
                    val p = requireNotNull(sender as? Player)
                    require(sessions.current(p.uniqueId) == null) { "Exit dungeon before changing party" }
                    when(args.getOrNull(1)) {
                        "invite" -> {
                            val target = sessions.online.values.firstOrNull { it.name.equals(args.getOrNull(2),true) }
                            requireNotNull(target)
                            parties.invite(p.uniqueId,target.uniqueId);messages.send(target,"invite",p.name)
                        }
                        "accept" -> parties.accept(p.uniqueId)
                        "leave" -> parties.leave(p.uniqueId)
                        else -> return guide.show(sender,"party").let { true }
                    }
                    messages.send(sender,"ok")
                }
                "create" -> {
                    if(!sender.hasPermission("dungeon.admin")) { messages.send(sender,"permission");return true }
                    require(args.size == 2)
                    dispatch.io { creator.create(args[1]);definitions.reload(args[1]) }.whenComplete { _,e ->
                        if(e == null) messages.send(sender,"ok") else { logger.log(java.util.logging.Level.WARNING,"Dungeon create ${args[1]} failed",e);messages.send(sender,"config-error") }
                    }
                }
                "reload", "validate" -> {
                    if(!sender.hasPermission("dungeon.admin")) { messages.send(sender,"permission");return true }
                    dispatch.io {
                        if(args[0] == "validate") {
                            val ids = args.getOrNull(1)?.let { listOf(it) } ?: definitions.current.keys
                            ids.forEach { definitions.validate(it) }
                        } else definitions.reload(args.getOrNull(1))
                    }.whenComplete { _,e ->
                        if(e == null) messages.send(sender,"ok") else {
                            logger.log(java.util.logging.Level.WARNING,"Configuration reload rejected; previous snapshot retained",e)
                            messages.send(sender,"config-error")
                        }
                    }
                }
                "test", "debug" -> {
                    if(!sender.hasPermission("dungeon.admin")) messages.send(sender,"permission")
                    else if(args[0] == "test") {
                        val player = requireNotNull(sender as? Player);require(args.size in 2..3)
                        sessions.start(player,setOf(player.uniqueId),args[1],args.getOrElse(2) { "normal" })
                    } else {
                        val player = requireNotNull(sender as? Player)
                        val run = sessions.current(player.uniqueId)?.run
                        messages.send(sender,"status",run?.let { "instance=${it.id}, world=${it.worldName}, ${it.snapshot()}" } ?: "-")
                    }
                }
                else -> guide.show(sender)
            }
        } catch(e: Exception) { messages.send(sender,"invalid");logger.fine("Command rejected: ${e.message}") }
        return true
    }
    override fun onTabComplete(sender: CommandSender, command: Command, alias: String, args: Array<out String>): List<String> {
        if(!sender.hasPermission("dungeon.use")) return emptyList()
        val admin = sender.hasPermission("dungeon.admin")
        val sub = args.firstOrNull()?.lowercase()
        if(!admin && sub in setOf("create","reload","validate","test","debug")) return emptyList()
        val candidates = when(args.size) {
            1 -> listOf("help","list") + (if(sender is Player) listOf("lobby","enter","exit","status","party") else emptyList()) +
                (if(admin) listOf("create","reload","validate") + if(sender is Player) listOf("test","debug") else emptyList() else emptyList())
            2 -> when(sub) {
                "help" -> listOf("player","party") + if(admin) listOf("admin") else emptyList()
                "party" -> listOf("invite","accept","leave")
                "enter","test","reload","validate" -> definitions.current.keys.toList() + if(farm.settings.enabled && sub=="enter") listOf(farm.settings.id) else emptyList()
                else -> emptyList()
            }
            3 -> when {
                sub in setOf("enter","test") -> definitions.current[args[1]]?.difficulties?.keys?.toList().orEmpty()
                sub == "party" && args[1].equals("invite",true) -> sessions.online.values.filter { it !== sender }.map { it.name }
                else -> emptyList()
            }
            else -> emptyList()
        }
        return candidates.filter { it.startsWith(args.lastOrNull().orEmpty(),true) }.sorted()
    }
}
