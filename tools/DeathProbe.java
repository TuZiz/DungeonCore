import org.bukkit.*;
import org.bukkit.damage.*;
import org.bukkit.entity.Player;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.java.JavaPlugin;
import net.kyori.adventure.text.Component;
import java.lang.reflect.Proxy;
import java.util.*;
import dev.dungeon.farming.*;
import dev.dungeon.session.DeathRetention;

public class DeathProbe extends JavaPlugin {
    boolean done;
    int homeDeathCalls,homeRespawnCalls;
    public void onEnable() { getServer().getScheduler().runTaskTimer(this,this::probe,40,20); }
    void require(boolean value,String message) { if(!value)throw new AssertionError(message); }
    PlayerDeathEvent event(World world) {
        UUID id=UUID.randomUUID();
        var holder=world.spawn(new Location(world,0,60,0),org.bukkit.entity.ArmorStand.class);holder.setPersistent(false);
        Player player=(Player)Proxy.newProxyInstance(Player.class.getClassLoader(),new Class[]{Player.class},(p,m,a)->switch(m.getName()) {
            case "getWorld" -> world;
            case "getLocation" -> world.getSpawnLocation().clone();
            case "getUniqueId" -> id;
            case "getName" -> "DeathProbe";
            case "getPersistentDataContainer" -> holder.getPersistentDataContainer();
            default -> m.getReturnType()==boolean.class?false:null;
        });
        return new PlayerDeathEvent(player,DamageSource.builder(DamageType.GENERIC).build(),
            new ArrayList<>(List.of(new ItemStack(Material.DIAMOND,3))),15,Component.empty(),true);
    }
    void retained(PlayerDeathEvent e) {
        require(e.getKeepInventory() && e.getKeepLevel(),"Inventory/level not retained");
        require(e.getDrops().isEmpty() && e.getDroppedExp()==0 && !e.shouldDropExperience(),"Duplicate drops/XP remain");
    }
    void homeConflict() throws Exception {
        var jar=java.nio.file.Path.of("D:/codex/DreamSkyblock/target/DreamSkyblock-1.0-SNAPSHOT.jar");
        var loader=new java.net.URLClassLoader(new java.net.URL[]{jar.toUri().toURL()},getClassLoader());
        var type=Class.forName("ym.dreamSkyblock.login.HomeRespawn",true,loader);
        var unsafeField=sun.misc.Unsafe.class.getDeclaredField("theUnsafe");unsafeField.setAccessible(true);
        var unsafe=(sun.misc.Unsafe)unsafeField.get(null);
        var home=unsafe.allocateInstance(type);
        var sessions=type.getDeclaredField("sessions");sessions.setAccessible(true);sessions.set(home,new HashMap<UUID,Object>());
        var bridge=(org.bukkit.event.Listener)Proxy.newProxyInstance(getClassLoader(),new Class[]{org.bukkit.event.Listener.class},(p,m,a)->null);
        var key=new NamespacedKey("dungeon","permanent_death_spawn");
        getServer().getPluginManager().registerEvent(PlayerDeathEvent.class,bridge,org.bukkit.event.EventPriority.MONITOR,(l,e)->{
            var event=(PlayerDeathEvent)e;
            if(!event.isCancelled() && event.getPlayer().getPersistentDataContainer().has(key,org.bukkit.persistence.PersistentDataType.STRING)) {
                try { type.getMethod("death",PlayerDeathEvent.class).invoke(home,event);homeDeathCalls++; }
                catch(Exception error) { throw new org.bukkit.event.EventException(error); }
            }
        },this);
        getServer().getPluginManager().registerEvent(org.bukkit.event.player.PlayerRespawnEvent.class,bridge,org.bukkit.event.EventPriority.HIGHEST,(l,e)->{
            var event=(org.bukkit.event.player.PlayerRespawnEvent)e;
            if(event.getRespawnReason()!=org.bukkit.event.player.PlayerRespawnEvent.RespawnReason.DEATH) return;
            if(event.getPlayer().getPersistentDataContainer().has(key,org.bukkit.persistence.PersistentDataType.STRING)) {
                try { type.getMethod("respawn",org.bukkit.event.player.PlayerRespawnEvent.class).invoke(home,event);homeRespawnCalls++; }
                catch(Exception error) { throw new org.bukkit.event.EventException(error); }
            }
        },this);
    }
    void probe() {
        World world=Bukkit.getWorld("lost_altar");if(done||world==null)return;done=true;
        try {
            homeConflict();
            var configured=FarmSettings.Companion.load(getDataFolder().toPath().getParent().resolve("Dungeon/dungeons/lost_altar/farming.yml"));
            var protectedDeath=event(world);Bukkit.getPluginManager().callEvent(protectedDeath);retained(protectedDeath);
            var vanilla=new Location(Bukkit.getWorlds().getFirst(),1,70,1);
            var end=new org.bukkit.event.player.PlayerRespawnEvent(protectedDeath.getPlayer(),vanilla,false,false,false,org.bukkit.event.player.PlayerRespawnEvent.RespawnReason.END_PORTAL);
            Bukkit.getPluginManager().callEvent(end);require(end.getRespawnLocation().equals(vanilla),"End portal respawn overridden");
            var respawn=new org.bukkit.event.player.PlayerRespawnEvent(protectedDeath.getPlayer(),vanilla,true,false,false,org.bukkit.event.player.PlayerRespawnEvent.RespawnReason.DEATH);
            Bukkit.getPluginManager().callEvent(respawn);
            require(homeDeathCalls==1 && homeRespawnCalls==1,"Real DreamSkyblock handlers did not yield to Dungeon ownership");
            var destination=respawn.getRespawnLocation();
            require(destination.getWorld().equals(world)&&destination.getX()==configured.getX()&&destination.getY()==configured.getY()&&destination.getZ()==configured.getZ(),"Permanent respawn destination wrong");
            require(destination.getYaw()==configured.getYaw()&&destination.getPitch()==configured.getPitch(),"Respawn orientation wrong");
            var again=new org.bukkit.event.player.PlayerRespawnEvent(protectedDeath.getPlayer(),vanilla,false,false,false,org.bukkit.event.player.PlayerRespawnEvent.RespawnReason.DEATH);
            Bukkit.getPluginManager().callEvent(again);require(again.getRespawnLocation().equals(vanilla),"Death record reused");
            var outside=event(Bukkit.getWorlds().getFirst());Bukkit.getPluginManager().callEvent(outside);
            require(!outside.getKeepInventory() && outside.getDrops().size()==1 && outside.getDroppedExp()==15,"Outside world changed");
            var outsideRespawn=new org.bukkit.event.player.PlayerRespawnEvent(outside.getPlayer(),vanilla,false,false,false,org.bukkit.event.player.PlayerRespawnEvent.RespawnReason.DEATH);
            Bukkit.getPluginManager().callEvent(outsideRespawn);require(outsideRespawn.getRespawnLocation().equals(vanilla),"Outside respawn changed");
            var cancelled=event(world);cancelled.setCancelled(true);Bukkit.getPluginManager().callEvent(cancelled);
            var cancelledRespawn=new org.bukkit.event.player.PlayerRespawnEvent(cancelled.getPlayer(),vanilla,false,false,false,org.bukkit.event.player.PlayerRespawnEvent.RespawnReason.DEATH);
            Bukkit.getPluginManager().callEvent(cancelledRespawn);require(cancelledRespawn.getRespawnLocation().equals(vanilla),"Cancelled death created respawn override");
            var direct=event(world);DeathRetention.INSTANCE.apply(direct);retained(direct);
            require(configured.getX()==-130.406 && configured.getY()==7 && configured.getZ()==177.232,"Spawn coordinates differ");
            require(configured.getYaw()==127.3f && configured.getPitch()==-3.2f,"Spawn rotation differs");
            var location=new Location(world,configured.getX(),configured.getY(),configured.getZ());
            location.getChunk().load();
            require(location.getBlock().isPassable() && location.clone().add(0,1,0).getBlock().isPassable(),"Spawn headroom obstructed");
            require(location.clone().add(0,-1,0).getBlock().getType().isSolid(),"Spawn floor missing");
            var hit=new org.bukkit.event.entity.EntityDamageEvent(protectedDeath.getPlayer(),org.bukkit.event.entity.EntityDamageEvent.DamageCause.ENTITY_ATTACK,DamageSource.builder(DamageType.GENERIC).build(),5.0);
            Bukkit.getPluginManager().callEvent(hit);require(hit.isCancelled(),"Safe-zone player damage not blocked");
            var outsideHit=new org.bukkit.event.entity.EntityDamageEvent(outside.getPlayer(),org.bukkit.event.entity.EntityDamageEvent.DamageCause.ENTITY_ATTACK,DamageSource.builder(DamageType.GENERIC).build(),5.0);
            Bukkit.getPluginManager().callEvent(outsideHit);require(!outsideHit.isCancelled(),"Outside damage blocked");
            var animal=world.createEntity(location,org.bukkit.entity.Sheep.class);
            for(var reason:List.of(org.bukkit.event.entity.CreatureSpawnEvent.SpawnReason.CUSTOM,org.bukkit.event.entity.CreatureSpawnEvent.SpawnReason.COMMAND)) {
                var spawn=new org.bukkit.event.entity.CreatureSpawnEvent(animal,reason);
                Bukkit.getPluginManager().callEvent(spawn);require(spawn.isCancelled(),"Safe-zone spawn allowed: "+reason);
            }
            var outsideAnimal=world.createEntity(new Location(world,0,60,0),org.bukkit.entity.Sheep.class);
            var outsideSpawn=new org.bukkit.event.entity.CreatureSpawnEvent(outsideAnimal,org.bukkit.event.entity.CreatureSpawnEvent.SpawnReason.CUSTOM);
            Bukkit.getPluginManager().callEvent(outsideSpawn);require(!outsideSpawn.isCancelled(),"Outside custom spawn blocked");
            var area=SpawnSettings.Companion.load(getDataFolder().toPath().getParent().resolve("Dungeon/dungeons/lost_altar/spawning.yml"));
            var notices=new ArrayList<Boolean>();
            var zone=new FarmSafeZone(configured,area.getSafeRadius(),area.getSafeZone(),(p,inside)->{notices.add(inside);return kotlin.Unit.INSTANCE;});
            var exit=location.clone().add(50,0,0);
            zone.move(new org.bukkit.event.player.PlayerMoveEvent(protectedDeath.getPlayer(),location,exit));
            zone.move(new org.bukkit.event.player.PlayerMoveEvent(protectedDeath.getPlayer(),exit,location));
            zone.move(new org.bukkit.event.player.PlayerMoveEvent(protectedDeath.getPlayer(),location,location.clone().add(0,0,1)));
            require(notices.equals(List.of(false,true)),"Safe-zone enter/leave notifications wrong or repeated");
            require(!zone.contains(world.getName(),-131,25,177),"Safe-zone height ignored");
            require(!zone.contains(world.getName(),150.5,8,48.5),"Old circular safe zone still active");
            getLogger().info("DEATH_PROBE_PASS: real DreamSkyblock death/respawn guards yield; Dungeon respawn/rotation and one-shot ownership; retained inventory/XP; configured spawn terrain; cuboid height, enter/leave notifications, damage/custom/command spawns protected; outside and old circle unaffected");
        }catch(Throwable e){getLogger().log(java.util.logging.Level.SEVERE,"DEATH_PROBE_FAIL",e);}
    }
}
