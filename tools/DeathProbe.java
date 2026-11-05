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
    public void onEnable() { getServer().getScheduler().runTaskTimer(this,this::probe,40,20); }
    void require(boolean value,String message) { if(!value)throw new AssertionError(message); }
    PlayerDeathEvent event(World world) {
        UUID id=UUID.randomUUID();
        var holder=world.spawn(new Location(world,0,60,0),org.bukkit.entity.ArmorStand.class);holder.setPersistent(false);
        Player player=(Player)Proxy.newProxyInstance(Player.class.getClassLoader(),new Class[]{Player.class},(p,m,a)->switch(m.getName()) {
            case "getWorld" -> world;
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
    void probe() {
        World world=Bukkit.getWorld("lost_altar");if(done||world==null)return;done=true;
        try {
            var configured=FarmSettings.Companion.load(getDataFolder().toPath().getParent().resolve("Dungeon/dungeons/lost_altar/farming.yml"));
            var protectedDeath=event(world);Bukkit.getPluginManager().callEvent(protectedDeath);retained(protectedDeath);
            var vanilla=new Location(Bukkit.getWorlds().getFirst(),1,70,1);
            var end=new org.bukkit.event.player.PlayerRespawnEvent(protectedDeath.getPlayer(),vanilla,false,false,false,org.bukkit.event.player.PlayerRespawnEvent.RespawnReason.END_PORTAL);
            Bukkit.getPluginManager().callEvent(end);require(end.getRespawnLocation().equals(vanilla),"End portal respawn overridden");
            var respawn=new org.bukkit.event.player.PlayerRespawnEvent(protectedDeath.getPlayer(),vanilla,true,false,false,org.bukkit.event.player.PlayerRespawnEvent.RespawnReason.DEATH);
            Bukkit.getPluginManager().callEvent(respawn);
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
            require(configured.getX()==181.771 && configured.getY()==8 && configured.getZ()==11.174,"Spawn coordinates differ");
            require(configured.getYaw()==-173.7f && configured.getPitch()==-7.2f,"Spawn rotation differs");
            var location=new Location(world,configured.getX(),configured.getY(),configured.getZ());
            location.getChunk().load();
            require(location.getBlock().isPassable() && location.clone().add(0,1,0).getBlock().isPassable(),"Spawn headroom obstructed");
            require(location.clone().add(0,-1,0).getBlock().getType().isSolid(),"Spawn floor missing");
            getLogger().info("DEATH_PROBE_PASS: configured respawn/rotation override bed, one-shot record, end/outside/cancel unchanged; farm event retained inventory/XP with drops cleared, outside unchanged; configured spawn floor and headroom safe");
        }catch(Throwable e){getLogger().log(java.util.logging.Level.SEVERE,"DEATH_PROBE_FAIL",e);}
    }
}
