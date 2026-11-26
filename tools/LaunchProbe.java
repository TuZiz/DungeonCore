import org.bukkit.*;
import org.bukkit.entity.*;
import org.bukkit.event.*;
import org.bukkit.event.entity.*;
import org.bukkit.event.player.*;
import org.bukkit.event.inventory.*;
import org.bukkit.inventory.*;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;
import io.lumine.mythic.bukkit.events.MythicDropLoadEvent;
import io.lumine.mythic.api.drops.*;
import io.lumine.mythic.api.adapters.*;
import io.lumine.mythic.bukkit.BukkitAdapter;
import java.lang.reflect.Proxy;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;

/** Isolated Leaf event/SPI integration probe; does not emulate a real network client. */
public class LaunchProbe extends JavaPlugin implements Listener {
    final Map<String,IItemDrop> drops=new HashMap<>();
    boolean done;
    void check(boolean value,String detail) { if(!value)throw new AssertionError(detail); }
    public void onEnable() {
        Bukkit.getPluginManager().registerEvents(this,this);
        Bukkit.getScheduler().runTaskLater(this,()->{
            Bukkit.dispatchCommand(Bukkit.getConsoleSender(),"mm reload");
            Bukkit.getScheduler().runTaskTimer(this,this::probe,60,20);
        },100);
    }
    @EventHandler(priority=EventPriority.MONITOR)
    public void drop(MythicDropLoadEvent e) {
        if(e.getDropName().equalsIgnoreCase("dungeonce"))
            e.getDrop().ifPresent(d->drops.put(e.getConfig().getString("id"),(IItemDrop)d));
    }
    Player player(UUID id,AtomicReference<Location> at) {
        return (Player)Proxy.newProxyInstance(getClassLoader(),new Class[]{Player.class},(p,m,a)->switch(m.getName()) {
            case "getUniqueId" -> id;
            case "getLocation" -> at.get().clone();
            case "getWorld" -> at.get().getWorld();
            case "getName" -> "LaunchProbe";
            case "getScheduler" -> Proxy.newProxyInstance(getClassLoader(),new Class[]{io.papermc.paper.threadedregions.scheduler.EntityScheduler.class},(s,method,args)->method.getReturnType()==boolean.class?false:null);
            case "isOnline", "isValid" -> true;
            case "hashCode" -> id.hashCode();
            case "equals" -> p==a[0];
            default -> m.getReturnType()==boolean.class?false:null;
        });
    }
    void call(Event event) {
        try {
            for(var listener:event.getHandlers().getRegisteredListeners())
                if(listener.getPlugin().getName().equals("Dungeon")) listener.callEvent(event);
        } catch(Exception e) { throw new IllegalStateException(e); }
    }
    <T extends Event & Cancellable> void blocked(T event,String message) {
        call(event);check(event.isCancelled(),message);
    }
    void probe() {
        var w=Bukkit.getWorld("lost_altar");if(done||w==null)return;done=true;
        try {
            UUID owner=UUID.randomUUID();
            var at=new AtomicReference<>(new Location(w,-130.406,7,177.232));
            var player=player(owner,at);var other=player(UUID.randomUUID(),at);
            AbstractEntity cause=(AbstractEntity)Proxy.newProxyInstance(getClassLoader(),new Class[]{AbstractEntity.class},(p,m,a)->switch(m.getName()) {
                case "isPlayer" -> true;
                case "getUniqueId" -> owner;
                default -> null;
            });
            DropMetadata metadata=(DropMetadata)Proxy.newProxyInstance(getClassLoader(),new Class[]{DropMetadata.class},(p,m,a)->m.getName().equals("getCause")?Optional.of(cause):null);
            DropMetadata environment=(DropMetadata)Proxy.newProxyInstance(getClassLoader(),new Class[]{DropMetadata.class},(p,m,a)->m.getName().equals("getCause")?Optional.empty():null);
            check(drops.size()==9,"CE drop IDs: "+drops.keySet());
            var plugin=Bukkit.getPluginManager().getPlugin("Dungeon");
            var ownerKey=new NamespacedKey(plugin,"loot_owner");var untilKey=new NamespacedKey(plugin,"loot_until");
            // Real MM SPI -> real CE item -> real Bukkit item spawn -> ownership events.
            for(var entry:drops.entrySet()) for(int amount=1;amount<=3;amount++) {
                var stack=BukkitAdapter.adapt(entry.getValue().getDrop(metadata,amount));
                check(entry.getKey().equals(String.valueOf(net.momirealms.craftengine.bukkit.api.CraftEngineItems.getCustomItemId(stack))),"Wrong CE ID");
                check(stack.getAmount()==amount,"Wrong amount");
                var item=w.dropItem(new Location(w,0,60,0),stack);
                check(owner.toString().equals(item.getPersistentDataContainer().get(ownerKey,PersistentDataType.STRING)),"Missing owner on item entity");
                check(!item.getItemStack().getItemMeta().getPersistentDataContainer().has(ownerKey),"Owner leaked into stack metadata");
                var own=new EntityPickupItemEvent(player,item,0);call(own);check(!own.isCancelled(),"Owner denied");
                blocked(new EntityPickupItemEvent(other,item,0),"Other player stole loot");
                blocked(new InventoryPickupItemEvent(Bukkit.createInventory(null,9),item),"Hopper bypass");
                var vanilla=w.dropItem(new Location(w,1,60,0),new ItemStack(Material.STONE));
                blocked(new ItemMergeEvent(vanilla,item),"Merge bypass");
                blocked(new ItemMergeEvent(item,vanilla),"Reverse merge bypass");
                item.getPersistentDataContainer().set(untilKey,PersistentDataType.LONG,System.currentTimeMillis()-1);
                var expired=new EntityPickupItemEvent(other,item,0);call(expired);check(!expired.isCancelled(),"Expired loot locked");
                check(!item.getPersistentDataContainer().has(ownerKey),"Expired ownership retained");
                var ordinary=new EntityPickupItemEvent(other,vanilla,0);call(ordinary);check(!ordinary.isCancelled(),"Ordinary loot locked");
                item.remove();vanilla.remove();
                check(BukkitAdapter.adapt(entry.getValue().getDrop(environment,amount)).getType().isAir(),"Environment drop exploit");
            }
            call(new PlayerMoveEvent(player,new Location(w,0,60,0),at.get()));
            var victim=w.createEntity(new Location(w,0,60,0),Zombie.class);
            var generic=org.bukkit.damage.DamageSource.builder(org.bukkit.damage.DamageType.GENERIC).build();
            blocked(new EntityDamageByEntityEvent(player,victim,EntityDamageEvent.DamageCause.ENTITY_ATTACK,generic,5),"Safe-zone outgoing attack allowed");
            blocked(new EntityDamageEvent(player,EntityDamageEvent.DamageCause.ENTITY_ATTACK,generic,5),"Safe-zone incoming damage allowed");
            var arrow=w.createEntity(at.get(),Arrow.class);
            blocked(new ProjectileLaunchEvent(arrow),"Safe-zone arrow allowed");
            check(w.getTime()==6000L && Boolean.FALSE.equals(w.getGameRuleValue(GameRules.ADVANCE_TIME)),"Permanent noon not frozen: time="+w.getTime()+" advance="+w.getGameRuleValue(GameRules.ADVANCE_TIME));
            blocked(new org.bukkit.event.world.TimeSkipEvent(w,org.bukkit.event.world.TimeSkipEvent.SkipReason.COMMAND,12000),"Dungeon time skip allowed");
            var normalWorld=Bukkit.getWorlds().getFirst();
            var normalSkip=new org.bukkit.event.world.TimeSkipEvent(normalWorld,org.bukkit.event.world.TimeSkipEvent.SkipReason.COMMAND,12000);
            call(normalSkip);check(!normalSkip.isCancelled(),"Other world time locked");
            var edgeOutside=new Location(w,-122.9,7,177);var edgeInside=new Location(w,-123.1,7,177);
            for(var type:List.of(Zombie.class,Rabbit.class)) {
                var mob=w.createEntity(edgeOutside,type);
                blocked(new io.papermc.paper.event.entity.EntityMoveEvent(mob,edgeOutside,edgeInside),"Mob walked into safe zone: "+type);
                blocked(new EntityTeleportEvent(mob,edgeOutside,edgeInside),"Mob teleported into safe zone: "+type);
                var exit=new io.papermc.paper.event.entity.EntityMoveEvent(mob,edgeInside,edgeOutside);
                call(exit);check(!exit.isCancelled(),"Mob exit blocked");
                var high=new io.papermc.paper.event.entity.EntityMoveEvent(mob,edgeOutside.clone().add(0,30,0),edgeInside.clone().add(0,30,0));
                call(high);check(!high.isCancelled(),"Outside safe-zone height blocked");
            }
            var restored=w.spawn(edgeOutside,Zombie.class);restored.setAI(false);
            restored.teleport(new Location(w,0,60,0));
            // Load-event handling checks the real entity location, not the chunk's nominal coordinates.
            var loadZone=new dev.dungeon.farming.FarmSafeZone(
                dev.dungeon.farming.FarmSettings.Companion.load(getDataFolder().toPath().getParent().resolve("Dungeon/dungeons/lost_altar/farming.yml")),0,
                new dev.dungeon.farming.SafeZoneBounds(-1,59,-1,1,61,1),(p,inside)->kotlin.Unit.INSTANCE);
            new dev.dungeon.farming.FarmBoundary(loadZone).loaded(new org.bukkit.event.world.EntitiesLoadEvent(restored.getLocation().getChunk(),List.of(restored)));
            check(!restored.isValid(),"Loaded safe-zone mob retained");
            var meleeSource=org.bukkit.damage.DamageSource.builder(org.bukkit.damage.DamageType.GENERIC).build();
            var ranged=(Arrow)Proxy.newProxyInstance(getClassLoader(),new Class[]{Arrow.class},(p,m,a)->switch(m.getName()) {
                case "getShooter" -> player;
                case "getWorld" -> w;
                case "getUniqueId" -> UUID.randomUUID();
                default -> m.getReturnType()==boolean.class?false:null;
            });
            blocked(new EntityDamageByEntityEvent(ranged,victim,EntityDamageEvent.DamageCause.PROJECTILE,meleeSource,5),"Safe-zone ranged damage fallback allowed");
            var previous=at.get();at.set(new Location(w,0,60,0));
            call(new PlayerMoveEvent(player,previous,at.get()));
            var outside=new EntityDamageByEntityEvent(player,victim,EntityDamageEvent.DamageCause.ENTITY_ATTACK,generic,5);
            call(outside);check(!outside.isCancelled(),"Outside combat blocked");
            var block=w.getBlockAt(0,60,0);
            blocked(new PlayerBucketEmptyEvent(player,block,block,org.bukkit.block.BlockFace.UP,Material.WATER_BUCKET,new ItemStack(Material.BUCKET),EquipmentSlot.HAND),"Bucket empty allowed");
            blocked(new PlayerBucketFillEvent(player,block,block,org.bukkit.block.BlockFace.UP,Material.BUCKET,new ItemStack(Material.WATER_BUCKET),EquipmentSlot.HAND),"Bucket fill allowed");
            blocked(new org.bukkit.event.block.BlockIgniteEvent(block,org.bukkit.event.block.BlockIgniteEvent.IgniteCause.FLINT_AND_STEEL,player),"Ignite allowed");
            blocked(new EntityChangeBlockEvent(victim,block,Material.AIR.createBlockData()),"Entity grief allowed");
            block.setType(Material.FARMLAND);
            blocked(new PlayerInteractEvent(player,org.bukkit.event.block.Action.PHYSICAL,null,block,org.bukkit.block.BlockFace.UP),"Trampling allowed");
            var door=new PlayerInteractEvent(player,org.bukkit.event.block.Action.RIGHT_CLICK_BLOCK,null,block,org.bukkit.block.BlockFace.UP);
            call(door);check(!door.isCancelled(),"Ordinary interaction blocked");
            block.setType(Material.CHEST);var chest=((org.bukkit.block.Chest)block.getState()).getInventory();
            blocked(new InventoryOpenEvent(view(player,chest)),"Container open allowed");
            var menu=new InventoryOpenEvent(view(player,Bukkit.createInventory(null,9)));
            call(menu);check(!menu.isCancelled(),"Virtual menu blocked");
            block.setType(Material.AIR);
            getLogger().info("LAUNCH_PROBE_PASS noon/time-skip/mob-walk/teleport/load/height/ranged; CE=9 amounts=1,2,3 ownership/expiry/stacking/hopper/merge/environment; safe-zone attack/damage; bucket/fire/grief/trample/container; outside combat/menu/ordinary interactions unaffected");
        } catch(Throwable e) { getLogger().log(java.util.logging.Level.SEVERE,"LAUNCH_PROBE_FAIL",e); }
    }
    InventoryView view(Player player,Inventory inventory) {
        return (InventoryView)Proxy.newProxyInstance(getClassLoader(),new Class[]{InventoryView.class},(p,m,a)->switch(m.getName()) {
            case "getTopInventory" -> inventory;
            case "getPlayer" -> player;
            default -> null;
        });
    }
}
