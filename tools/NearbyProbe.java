import org.bukkit.*;
import org.bukkit.entity.Player;
import org.bukkit.boss.BossBar;
import org.bukkit.plugin.java.JavaPlugin;
import dev.dungeon.farming.*;
import dev.dungeon.integration.XerrEliteHud;
import dev.dungeon.scheduling.Dispatch;
import com.hiusers.xerr.api.container.BossbarHudRenderer;
import com.hiusers.xerr.api.bossbar.BossbarHudContext;
import java.lang.reflect.Proxy;
import java.nio.file.*;
import java.util.*;

/** Runs real MM spawns and Xerr rendering without a client; never installed on the live server. */
public class NearbyProbe extends JavaPlugin {
    private NearbySpawns spawns;
    private Dispatch dispatch;
    private final List<FarmPresence> players = new ArrayList<>();
    private int cycles;
    private int maxSpawned;
    private long emptySince;
    private boolean finished;
    public void onEnable() { getServer().getScheduler().runTaskTimer(this,this::tick,100,10); }
    private void require(boolean condition,String message) { if(!condition) throw new AssertionError(message); }
    private void tick() {
        if(finished) return;
        try {
            var world=getServer().getWorld("lost_altar");
            if(world==null) return;
            if(spawns==null) {
                var folder=getDataFolder().toPath().getParent().resolve("Dungeon/dungeons/lost_altar");
                var settings=SpawnSettings.Companion.load(folder.resolve("spawning.yml"));
                var farm=FarmSettings.Companion.load(folder.resolve("farming.yml"));
                for(int x=-2;x<=2;x++) for(int z=-2;z<=2;z++) world.getChunkAt(x,z).addPluginChunkTicket(this);
                dispatch=new Dispatch(this);
                spawns=new NearbySpawns(this,world,farm,settings,dispatch,()->new ArrayList<>(players));
                players.add(new FarmPresence(UUID.randomUUID(),0.5,49,0.5,0,System.nanoTime()));
                checkHud(world,folder);
            }
            if(cycles++<24) {
                var legacy=io.lumine.mythic.bukkit.MythicBukkit.inst().getSpawnerManager().getSpawnerByName("Altar_01");
                if(legacy!=null) legacy.Enable();
                spawns.cycle(System.nanoTime());
                if(legacy!=null) {
                    var active=legacy.getClass().getDeclaredField("active");active.setAccessible(true);
                    require(!active.getBoolean(legacy),"Legacy MM spawner still active");
                }
                long actual=spawns.getLedger().getRecords().values().stream().filter(r->r.getEntity()!=null).count();
                maxSpawned=Math.max(maxSpawned,(int)actual);
                require(actual<=6,"Owner cap exceeded: "+actual);
                for(var record:spawns.getLedger().getRecords().values()) {
                    var entity=record.getEntity();if(entity==null) continue;
                    require(entity.isValid(),"Invalid MM entity");
                    require(!entity.isPersistent(),"Farm mob persists across restart");
                    var l=entity.getLocation();
                    require(!l.getBlock().isLiquid(),"Spawn in liquid");
                    require(io.lumine.mythic.bukkit.MythicBukkit.inst().getAPIHelper().isMythicMob(entity),"Not a real MM mob");
                }
                return;
            }
            require(maxSpawned>0,"No actual MM mob spawned");
            if(emptySince==0) { players.clear();emptySince=System.nanoTime(); }
            spawns.refresh(System.nanoTime()+20_000_000_000L);
            if(!spawns.getLedger().getRecords().isEmpty()) return;
            getLogger().info("NEARBY_PROBE_PASS: actual MM mobs="+maxSpawned+", cap<=6, nonpersistent, no-player cleanup, native Xerr 8 directions and slot removal");
            finished=true;spawns.close();dispatch.close();
        } catch(Throwable e) { finished=true;getLogger().log(java.util.logging.Level.SEVERE,"NEARBY_PROBE_FAIL",e); }
    }
    private void checkHud(World world,Path folder) throws Exception {
        UUID id=UUID.randomUUID();
        Player player=(Player)Proxy.newProxyInstance(Player.class.getClassLoader(),new Class<?>[]{Player.class},(p,m,a)->switch(m.getName()) {
            case "getUniqueId" -> id;
            case "getName" -> "DungeonHudProbe";
            case "isOnline","hasPermission","hasResourcePack" -> true;
            case "getWorld" -> world;
            case "getLocation" -> new Location(world,0.5,49,0.5);
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
            for(int i=0;i<8;i++) {
                renderer.render(player,settings.getLayout(),settings.values("石誓守卫",32,i));
                Object refresh=((Map<?,?>)contexts.get(id).getComponents()).get(XerrEliteHud.SLOT);
                Object component=refresh.getClass().getMethod("getComponent").invoke(refresh);
                String raw=(String)component.getClass().getMethod("toRawMessage").invoke(component);
                require(raw.contains(directions[i]) && raw.contains("石誓守卫") && raw.contains("32"),"HUD text missing: "+raw);
                require(!raw.contains("<#") && !raw.contains("(color=") && !raw.contains("{{"),"HUD markup leaked: "+raw);
                require(raw.contains("pixel_position_dark") || raw.contains("pixel_position_light"),"Unresolved font: "+raw);
                require(!java.util.regex.Pattern.compile("pixel_position_[0-9]+").matcher(raw).find(),"Virtual font leaked");
                output.add(com.google.gson.JsonParser.parseString(raw));
            }
            renderer.remove(player);
            require(!contexts.get(id).getComponents().containsKey(XerrEliteHud.SLOT),"HUD slot leaked");
            Files.createDirectories(getDataFolder().toPath());
            Files.writeString(getDataFolder().toPath().resolve("hud-components.json"),output.toString());
        } finally { contexts.remove(id); }
    }
    public void onDisable() { if(spawns!=null)spawns.close();if(dispatch!=null)dispatch.close();getServer().getWorlds().forEach(w->w.removePluginChunkTickets(this)); }
}
