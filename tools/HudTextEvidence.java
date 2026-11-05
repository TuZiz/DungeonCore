import dev.dungeon.farming.EliteHudSettings;
import net.kyori.adventure.text.serializer.gson.GsonComponentSerializer;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.configuration.file.YamlConfiguration;
import com.google.gson.*;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.HexFormat;

/** Exports the real plugin text passed into Xerr, before its shader-specific positioning glyphs. */
public class HudTextEvidence {
    public static void main(String[] args) throws Exception {
        Path root=Path.of(args[0]);
        Path lang=root.resolve("src/main/resources/lang/elite_zh_CN.yml");
        var settings=EliteHudSettings.Companion.load(root.resolve("src/main/resources/dungeons/lost_altar/elite-hud.yml"),lang);
        var yaml=YamlConfiguration.loadConfiguration(lang.toFile());
        var result=new JsonArray();
        for(int i=0;i<9;i++) {
            int direction=i==0?4:i-1;
            String field=i==0?"text":"directions."+(i-1);
            String text=i==0?yaml.getString("text"):yaml.getStringList("directions").get(i-1);
            String transport=settings.values("石誓守卫",32,direction).get("elite");
            var component=LegacyComponentSerializer.builder().hexColors().useUnusualXRepeatedCharacterHexFormat().build().deserialize(transport);
            var sample=new JsonObject();
            sample.addProperty("path","src/main/resources/lang/elite_zh_CN.yml");sample.addProperty("field",field);
            sample.addProperty("text_hash",HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(text.getBytes(java.nio.charset.StandardCharsets.UTF_8))));
            sample.add("component",JsonParser.parseString(GsonComponentSerializer.gson().serialize(component)));
            sample.addProperty("source","plugin_test");
            sample.addProperty("runtime","EliteHudSettings.values actual plugin text passed to Xerr; final shader-native components verified separately on Leaf26.3");
            var placeholders=new JsonArray();
            if(i==0) { placeholders.add("name");placeholders.add("direction");placeholders.add("distance"); }
            sample.add("placeholders",placeholders);result.add(sample);
        }
        Files.writeString(root.resolve("target/hud-text-evidence.json"),result.toString());
    }
}
