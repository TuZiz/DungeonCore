import org.bukkit.*;
import org.bukkit.entity.Player;
import org.bukkit.boss.BossBar;
import org.bukkit.plugin.java.JavaPlugin;
import dev.dungeon.farming.*;
import dev.dungeon.integration.XerrEliteHud;
import com.hiusers.xerr.api.container.BossbarHudRenderer;
import com.hiusers.xerr.api.bossbar.BossbarHudContext;
import java.lang.reflect.Proxy;
import java.nio.file.*;
import java.util.*;
/** Isolated real Xerr 2.3 render probe; never installed on the live server. */
public class CompassProbe extends JavaPlugin {
    public void onEnable() { getServer().getScheduler().runTaskLater(this,()-> {
        try {
            checkHud(getServer().getWorlds().getFirst(),getDataFolder().toPath().getParent().resolve("Dungeon/dungeons/lost_altar"));
            getLogger().info("COMPASS_PROBE_PASS: 32 unique directions, one image per frame, world-exit guard/orphan cleanup/world-change/late callback and Quest preservation");
        } catch(Throwable e) { getLogger().log(java.util.logging.Level.SEVERE,"COMPASS_PROBE_FAIL",e); }
    },80); }
    private void require(boolean condition,String message) { if(!condition) throw new AssertionError(message); }
    private void checkHud(World world,Path folder) throws Exception {
        UUID id=UUID.randomUUID();
        var currentWorld=new java.util.concurrent.atomic.AtomicReference<World>(world);
        Player player=(Player)Proxy.newProxyInstance(Player.class.getClassLoader(),new Class<?>[]{Player.class},(p,m,a)->switch(m.getName()) {
            case "getUniqueId" -> id;
            case "getName" -> "DungeonHudProbe";
            case "isOnline","hasPermission","hasResourcePack" -> true;
            case "getWorld" -> currentWorld.get();
            case "getLocation" -> new Location(currentWorld.get(),0.5,49,0.5);
            case "hashCode" -> id.hashCode();
            case "equals" -> p==a[0];
            default -> m.getReturnType()==boolean.class ? false : null;
        });
        BossBar bar=(BossBar)Proxy.newProxyInstance(BossBar.class.getClassLoader(),new Class<?>[]{BossBar.class},(p,m,a)->m.getName().equals("getPlayers")?List.of(player):null);
        var f=BossbarHudRenderer.class.getDeclaredField("playerBossbar");f.setAccessible(true);
        @SuppressWarnings("unchecked") var contexts=(Map<UUID,BossbarHudContext>)f.get(null);
        contexts.put(id,new BossbarHudContext(bar,new LinkedHashMap<>()));
        try {
            var settings=EliteHudSettings.Companion.load(folder.resolve("elite-hud.yml"),folder.getParent().getParent().resolve("lang/elite_zh_CN.yml"));
            var renderer=new XerrEliteHud(world.getUID());var output=new com.google.gson.JsonArray();
            String[] directions={"正前方","右前方","右侧","右后方","身后","左后方","左侧","左前方"};
            Set<String> images=new HashSet<>();
            for(int frame=0;frame<32;frame++) {
                int i=((frame+2)/4)%8;
                renderer.render(player,settings.getLayout(),settings.values("石誓守卫",32,i,frame));
                Object refresh=((Map<?,?>)contexts.get(id).getComponents()).get(XerrEliteHud.SLOT);
                Object component=refresh.getClass().getMethod("getComponent").invoke(refresh);
                String raw=(String)component.getClass().getMethod("toRawMessage").invoke(component);
                require(raw.contains(directions[i]) && raw.contains("石誓守卫") && raw.contains("32"),"HUD text missing: "+raw);
                require(!raw.contains("<#") && !raw.contains("(color=") && !raw.contains("{{"),"HUD markup leaked: "+raw);
                require(raw.contains("pixel_position_dark") || raw.contains("pixel_position_light"),"Unresolved font: "+raw);
                require(!java.util.regex.Pattern.compile("pixel_position_[0-9]+").matcher(raw).find(),"Virtual font leaked");
                List<String> imagesInFrame=new ArrayList<>();
                collectImages(com.google.gson.JsonParser.parseString(raw),imagesInFrame);
                require(imagesInFrame.size()==1,"Expected one compass image: "+raw);
                require(images.add(imagesInFrame.getFirst()),"Repeated direction glyph: "+raw);
                output.add(com.google.gson.JsonParser.parseString(raw));
            }
            var foreignWorld=(World)Proxy.newProxyInstance(World.class.getClassLoader(),new Class<?>[]{World.class},(p,m,a)->switch(m.getName()) {
                case "getUID" -> new UUID(7,7);case "getName" -> "outside";default -> null;
            });
            BossbarHudRenderer.INSTANCE.appendLayoutRaw(player,"quest:regression","{\"text\":\"KEEP QUEST\"}");
            Object quest=contexts.get(id).getComponents().get("quest:regression");
            currentWorld.set(foreignWorld);
            renderer.render(player,settings.getLayout(),settings.values("stale",1,0));
            require(!contexts.get(id).getComponents().containsKey(XerrEliteHud.SLOT),"Out-of-world render repainted HUD");
            require(contexts.get(id).getComponents().get("quest:regression")==quest,"Quest HUD changed");
            // Reproduce orphaned Xerr slot with an empty FarmRuntime viewer registry.
            currentWorld.set(world);renderer.render(player,settings.getLayout(),settings.values("orphan",1,0));currentWorld.set(foreignWorld);
            var dungeon=Bukkit.getPluginManager().getPlugin("Dungeon");
            var farmField=dungeon.getClass().getDeclaredField("farm");farmField.setAccessible(true);Object farm=farmField.get(dungeon);
            var runtimeField=farm.getClass().getDeclaredField("runtime");runtimeField.setAccessible(true);Object runtime=runtimeField.get(farm);
            require(runtime!=null,"FarmRuntime not ready");
            var update=runtime.getClass().getDeclaredMethod("update",Player.class);update.setAccessible(true);update.invoke(runtime,player);
            require(!contexts.get(id).getComponents().containsKey(XerrEliteHud.SLOT),"Orphan HUD survived outside polling");
            currentWorld.set(world);renderer.render(player,settings.getLayout(),settings.values("switch",1,0));currentWorld.set(foreignWorld);
            Bukkit.getPluginManager().callEvent(new org.bukkit.event.player.PlayerChangedWorldEvent(player,world));
            require(!contexts.get(id).getComponents().containsKey(XerrEliteHud.SLOT),"World-change event left HUD");
            require(contexts.get(id).getComponents().get("quest:regression")==quest,"World change cleared Quest HUD");
            renderer.render(player,settings.getLayout(),settings.values("late",1,0));
            require(!contexts.get(id).getComponents().containsKey(XerrEliteHud.SLOT),"Late render survived world change");
            renderer.remove(player);
            require(!contexts.get(id).getComponents().containsKey(XerrEliteHud.SLOT),"HUD slot leaked");
            Files.createDirectories(getDataFolder().toPath());
            Files.writeString(getDataFolder().toPath().resolve("hud-components.json"),output.toString());
        } finally { contexts.remove(id); }
    }
    private void collectImages(com.google.gson.JsonElement node,List<String> output) {
        if(node.isJsonArray()) { node.getAsJsonArray().forEach(e->collectImages(e,output));return; }
        if(!node.isJsonObject()) return;
        var object=node.getAsJsonObject();
        if(object.has("font") && object.get("font").getAsString().startsWith("xerr_img:"))
            output.add(object.get("font").getAsString()+"|"+object.get("text").getAsString());
        object.entrySet().forEach(e->collectImages(e.getValue(),output));
    }
}
