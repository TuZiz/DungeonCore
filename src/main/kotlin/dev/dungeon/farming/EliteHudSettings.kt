package dev.dungeon.farming

import net.kyori.adventure.text.minimessage.MiniMessage
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer
import org.bukkit.configuration.file.YamlConfiguration
import java.nio.file.Path

class EliteHudSettings(val enabled: Boolean,val layout: String,val range: Double,private val template: String,private val directions: List<String>) {
    @JvmOverloads fun values(name: String,distance: Int,direction: Int,frame: Int=direction*4): Map<String,String> {
        require(frame in 0..31) { "Compass frame out of range: $frame" }
        val component=MiniMessage.miniMessage().deserialize(template,Placeholder.unparsed("name",name),
            Placeholder.unparsed("distance",distance.toString()),Placeholder.unparsed("direction",directions[direction]))
        // Internal RGB transport for Xerr's native parser; author-facing config remains MiniMessage.
        return buildMap {
            put("elite",LegacyComponentSerializer.builder().hexColors().useUnusualXRepeatedCharacterHexFormat().build().serialize(component))
            repeat(32) { put("compass_$it",(it==frame).toString()) }
        }
    }
    companion object {
        fun load(path: Path,language: Path=path.parent.parent.parent.resolve("lang/elite_zh_CN.yml")): EliteHudSettings {
            val c=YamlConfiguration().apply { load(path.toFile()) }
            val texts=YamlConfiguration().apply { load(language.toFile()) }
            require(c.getInt("schema-version")==1 && c.get("enabled") is Boolean) { "$path: invalid schema/enabled" }
            require(texts.getInt("schema-version")==1) { "$language:schema-version invalid" }
            val range=c.get("range") as? Number ?: error("$path:range must be numeric")
            require(range.toDouble().isFinite() && range.toDouble() in 16.0..256.0) { "$path:range invalid" }
            val dirs=texts.getStringList("directions")
            require(dirs.size==8 && dirs.all { it.isNotBlank() }) { "$language:directions requires eight directions" }
            return EliteHudSettings(c.getBoolean("enabled"),requireNotNull(c.getString("layout")),range.toDouble(),requireNotNull(texts.getString("text")),dirs)
        }
    }
}
