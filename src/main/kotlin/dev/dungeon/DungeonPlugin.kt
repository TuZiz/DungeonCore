package dev.dungeon

import dev.dungeon.command.DungeonCommand
import dev.dungeon.configuration.Definitions
import dev.dungeon.localization.Messages
import dev.dungeon.persistence.SqliteStore
import dev.dungeon.scheduling.Dispatch
import dev.dungeon.session.*
import dev.dungeon.world.*
import dev.dungeon.lobby.LobbySettings
import dev.dungeon.lobby.LobbyService
import org.bukkit.Bukkit
import org.bukkit.plugin.java.JavaPlugin
import java.nio.file.Files
import java.util.logging.Level

class DungeonPlugin : JavaPlugin() {
    private var dispatch: Dispatch? = null
    private var store: SqliteStore? = null
    private var sessions: Sessions? = null
    private var lobby: LobbyService? = null
    private var farm: dev.dungeon.farming.FarmService? = null
    private val parties = Parties()
    override fun onEnable() {
        val d = Dispatch(this);dispatch = d
        dev.dungeon.integration.CraftEngineDropBridge(this).register()
        val worldContainer = Bukkit.getWorldContainer().toPath()
        val serverDataVersion = Bukkit.getUnsafe().dataVersion
        // Registry metadata is captured during server initialization, never queried by IO workers.
        val materials = org.bukkit.Material.entries.filter { it.isItem && !it.isAir }.associate { it.name to it.maxStackSize }
        // All resource and configuration IO happens on workers; commands become available only after initialization.
        d.io {
            val root = dataFolder.toPath();Files.createDirectories(root)
            listOf("config.yml","lang/elite_zh_CN.yml","lang/farming_zh_CN.yml","lobby.yml","lang/lobby_zh_CN.yml","lang/zh_CN.yml","integrations/mythicmobs/DungeonExample.yml","dungeons/example/dungeon.yml","dungeons/example/locations.yml",
                "dungeons/example/scripts/events.yml","dungeons/example/stages.yml","dungeons/example/mobs.yml","dungeons/example/obstacles.yml","dungeons/example/rewards.yml").forEach { resource ->
                val dest = root.resolve(resource)
                if(!Files.exists(dest) && !(resource.startsWith("dungeons/example/") && Files.exists(root.resolve(".no-example")))) { Files.createDirectories(dest.parent);requireNotNull(getResource(resource)).use { Files.copy(it,dest) } }
            }
            dev.dungeon.configuration.SchemaMigration.migrate(root.resolve("config.yml"))
            val config = org.bukkit.configuration.file.YamlConfiguration().apply { load(root.resolve("config.yml").toFile()) }
            require(config.getInt("schema-version") == 1) { "config.yml schema-version unsupported" }
            val lobbySettings = LobbySettings.load(root.resolve("lobby.yml"))
            val farmPath=dev.dungeon.farming.FarmConfiguration.prepare(root) {
                requireNotNull(getResource("dungeons/lost_altar/farming.yml")).use { it.readBytes() }
            }
            val farmSettings=dev.dungeon.farming.FarmSettings.load(farmPath)
            val farmFeatures=dev.dungeon.farming.FarmFeatures.load(farmPath.parent,this)
            val farmReadme = farmPath.parent.resolve("README.md")
            if (!Files.exists(farmReadme)) requireNotNull(getResource("dungeons/lost_altar/README.md")).use {
                Files.copy(it, farmReadme)
            }
            val definitions = Definitions(root.resolve("dungeons"),materials,lobbySettings.world);definitions.reload()
            Files.createDirectories(root.resolve("templates"))
            Startup(definitions,Messages(root.resolve("lang/zh_CN.yml"),d),SqliteStore(root.resolve("dungeon.db")),
                lobbySettings,Messages(root.resolve("lang/lobby_zh_CN.yml"),d),farmSettings,Messages(root.resolve("lang/farming_zh_CN.yml"),d),farmFeatures)
        }.thenAccept { startup ->
            val (definitions,messages,db,lobbySettings,lobbyMessages,farmSettings,farmMessages,farmFeatures)=startup
            if(!d.isActive) { db.close();return@thenAccept }
            store = db
            db.initialize().whenComplete { _,e ->
                if(e != null) failed(e) else d.global {
                    val provider = Bukkit.getServicesManager().load(WorldProvider::class.java) ?: PlatformWorldProvider(d)
                    val files = TemplateFiles(dataFolder.toPath().resolve("templates"),worldContainer)
                    val worlds = WorldLifecycle(d,provider,files) {
                        definitions.current.values.filter { it.mode != dev.dungeon.model.Mode.TEMPORARY }.map { it.world }.toSet() + lobbySettings.world + if(farmSettings.enabled) setOf(farmSettings.world) else emptySet()
                    }
                    val service = Sessions(this,definitions,d,worlds,db,messages);sessions = service
                    val hub = LobbyService(this,lobbySettings,d,provider,lobbyMessages,service);lobby = hub
                    definitions.scriptValidator = service.scripts::validate
                    Bukkit.getServicesManager().register(dev.dungeon.script.ScriptEngine::class.java,service.scripts,this,org.bukkit.plugin.ServicePriority.Normal)
                    val farming=dev.dungeon.farming.FarmService(this,farmSettings,d,provider,farmMessages,service,farmFeatures);farm=farming
                    Bukkit.getPluginManager().registerEvents(dev.dungeon.farming.FarmProtection(farmSettings),this)
                    val command = DungeonCommand(definitions,service,parties,d,messages,logger,dev.dungeon.configuration.DungeonCreator(this,dataFolder.toPath().resolve("dungeons")),hub,farming)
                    requireNotNull(getCommand("dungeon")).apply { setExecutor(command);tabCompleter = command }
                    Bukkit.getPluginManager().registerEvents(PermanentRespawns(this,farmSettings) { service.current(it.uniqueId)?.run?.definition },this)
                    Bukkit.getPluginManager().registerEvents(GameplayListener(service),this)
                    Bukkit.getPluginManager().registerEvents(dev.dungeon.lobby.LobbyMobProtection(lobbySettings),this)
                    Bukkit.getOnlinePlayers().forEach { service.online[it.uniqueId] = it }
                    service.tick();worlds.recover { logger.info(it) }
                    val primaryWorld = dev.dungeon.lobby.LobbyWorldRoot.fromPrimaryWorldFolder(
                        requireNotNull(Bukkit.getWorlds().firstOrNull()) { "Primary world unavailable" }.worldFolder.toPath())
                    hub.start(primaryWorld,serverDataVersion)
                    farming.start(primaryWorld,serverDataVersion)
                    logger.info("Dungeon ${pluginMeta.version}; server=${Bukkit.getName()}; MC=${Bukkit.getMinecraftVersion()}; Java=${System.getProperty("java.version")}; provider=${provider.name}; instances=${provider.supportsInstances}; schema=1; dungeons=${definitions.current.size}")
                }
            }
        }.exceptionally { failed(it);null }
    }
    private fun failed(e: Throwable) {
        logger.log(Level.SEVERE,"Dungeon initialization failed; existing configuration retained",e)
        dispatch?.global { Bukkit.getPluginManager().disablePlugin(this) }
    }
    override fun onDisable() {
        farm?.close();farm=null
        lobby?.close();lobby = null
        sessions?.stop();parties.clear();store?.closeAsync()?.exceptionally { logger.log(Level.SEVERE,"SQLite shutdown failed",it);null };dispatch?.close()
        Bukkit.getServicesManager().unregisterAll(this)
        sessions = null;store = null;dispatch = null
    }
    private data class Startup(val definitions: Definitions, val messages: Messages, val db: SqliteStore,
        val lobbySettings: LobbySettings, val lobbyMessages: Messages,
        val farmSettings: dev.dungeon.farming.FarmSettings,val farmMessages: Messages,val farmFeatures: dev.dungeon.farming.FarmFeatures)
}
