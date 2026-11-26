import dev.dungeon.elite.*;
import dev.dungeon.farming.*;
import dev.dungeon.integration.*;
import org.bukkit.*;
import org.bukkit.entity.*;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.potion.PotionEffect;
import kr.toxicity.model.api.BetterModel;
import kr.toxicity.model.api.tracker.Tracker;
import java.lang.reflect.*;
import java.util.*;

/** Real server/models plus an explicit Player proxy; this is not a network-client test. */
public class RoseProbe extends JavaPlugin {
    JavaPlugin dungeon;
    Object runtime;
    NearbySpawns spawns;
    ModeledElites module;
    FarmMob type;
    Mob flower;
    Pig owner;
    Player proxy;
    final UUID viewer=UUID.randomUUID();
    Location position;
    double hp=100;
    int damageCalls,poisonCalls,slowCalls,attempts;
    boolean started,done,blockDamage;
    Map<UUID,FarmPresence> snapshots;
    Map<UUID,Player> viewers;
    World heldWorld;
    final List<int[]> heldChunks=new ArrayList<>();
    Object field(Object o,String n)throws Exception{var f=o.getClass().getDeclaredField(n);f.setAccessible(true);return f.get(o);}
    void check(boolean v,String text){if(!v)throw new AssertionError(text);}
    public void onEnable(){Bukkit.getScheduler().runTaskTimer(this,this::poll,100,5);}
    @SuppressWarnings("unchecked") void poll(){
        if(done)return;
        try{
            if(!started){
                if(!BetterModel.modelKeys().contains("briarqueen_rose")){if(++attempts>25)throw new AssertionError("rose model not loaded");return;}
                dungeon=(JavaPlugin)Bukkit.getPluginManager().getPlugin("Dungeon");
                Object farm=field(dungeon,"farm");if(farm==null)return;
                runtime=field(farm,"runtime");if(runtime==null)return;
                module=(ModeledElites)field(runtime,"modeled");check(module!=null,"modeled elite module exists");
                spawns=(NearbySpawns)field(runtime,"spawning");
                type=spawns.getSettings().getMobs().stream().filter(m->m.getId().equals("荆棘妖花")).findFirst().orElseThrow();
                check(type.getElite() && type.getWeight()==2 && type.getHeadroom()==3,"rare elite classification/headroom");
                check(module.ready(type),"actual BM and MM readiness");
                snapshots=(Map<UUID,FarmPresence>)field(runtime,"snapshots");viewers=(Map<UUID,Player>)field(runtime,"viewers");
                position=new Location(Bukkit.getWorld("lost_altar"),-105.5,8,123.5);
                // No network players exist in this fixture. Keep both test sites entity-ticking.
                heldWorld=position.getWorld();
                hold(position);
                hold(new Location(heldWorld,-120.5,7,177.5));
                started=true;attempts=0;
            }
            snapshots.put(viewer,new FarmPresence(viewer,position.getX(),position.getY(),position.getZ(),0f,System.nanoTime(),true));
            if(flower==null){
                flower=spawns.getLedger().getRecords().values().stream().filter(r->r.getMob().getId().equals(type.getId()) && r.getEntity()!=null).map(r->(Mob)r.getEntity()).findFirst().orElse(null);
                if(flower==null){
                    if(++attempts>25)throw new AssertionError("no safe nearby modeled spawn");
                    var attempt=spawns.getClass().getDeclaredMethod("attempt",FarmPresence.class,long.class,List.class);attempt.setAccessible(true);
                    attempt.invoke(spawns,snapshots.get(viewer),System.nanoTime(),List.of(type));return;
                }
                inspect();
            }
        }catch(Throwable error){fail(error);}
    }
    void inspect()throws Exception{
        check(!flower.hasAI() && !flower.isPersistent(),"rooted nonpersistent MM entity");
        check(Math.abs(flower.getHealth()-240)<.01,"240 HP applied");
        check(Math.abs(flower.getAttribute(org.bukkit.attribute.Attribute.ARMOR).getValue()-3)<.01,"MM armor applied");
        check(flower.teleport(new Location(position.getWorld(),-120.5,7,177.5)),"plant test location teleport");
        position=flower.getLocation();
        owner=position.getWorld().spawn(position.clone().add(0,0,3),Pig.class);owner.setAI(false);
        position=position.clone().add(2,0,0);
        proxy=(Player)Proxy.newProxyInstance(getClassLoader(),new Class<?>[]{Player.class},(p,m,a)->{
            return switch(m.getName()){
                case "getUniqueId"->viewer;
                case "getScheduler"->owner.getScheduler();
                case "isOnline","isValid","hasPermission"->true;
                case "isDead","hasResourcePack"->false;
                case "getWorld"->position.getWorld();
                case "getLocation"->position.clone();
                case "getEyeLocation"->position.clone().add(0,1.62,0);
                case "getGameMode"->GameMode.SURVIVAL;
                case "getHealth"->hp;
                case "damage"->{damageCalls++;if(!blockDamage)hp-=(double)a[0];yield null;}
                case "addPotionEffect"->{var effect=(PotionEffect)a[0];if(effect.getType().getKey().getKey().equals("poison"))poisonCalls++;else slowCalls++;yield true;}
                case "hashCode"->System.identityHashCode(p);
                case "equals"->p==a[0];
                case "toString","getName"->"RoseProbePlayer";
                default->{var t=m.getReturnType();if(t==boolean.class)yield false;if(t==int.class)yield 0;if(t==long.class)yield 0L;if(t==double.class)yield 0d;if(t==float.class)yield 0f;yield null;}
            };
        });
        viewers.put(viewer,proxy);
        var active=(Map<?,?>)field(module,"active");var combat=(EliteCombat)active.get(flower.getUniqueId());check(combat!=null,"tracked controller attached by actual nearby spawning");
        var visual=field(combat,"model");Tracker tracker=(Tracker)field(visual,"handle");
        check(tracker.bones().size()==17 && tracker.displays().count()>0,"17 supplied bones and actual display objects");
        var model=BetterModel.modelOrNull("briarqueen_rose");check(model.animations().keySet().containsAll(Set.of("idle","hiss","lunge_bite","whip_crack","wilt","death")),"all supplied animations");
        Bukkit.getScheduler().runTaskLater(this,()->{
            try{
                check(damageCalls>0 && poisonCalls>0 && slowCalls>0,"natural windup scheduled owned-player damage/poison/slow: damage="+damageCalls+" poison="+poisonCalls+" slow="+slowCalls+" source="+flower.getLocation()+" target="+position+" tick="+field(combat,"tick")+" cast="+field(combat,"cast"));
                var settings=(ModeledElite)field(combat,"settings");var mist=settings.getAttacks().getFirst();
                var strike=combat.getClass().getDeclaredMethod("strike",EliteAttack.class);strike.setAccessible(true);
                // Stop natural casting before isolated boundary assertions.
                var cast=combat.getClass().getDeclaredField("restUntil");cast.setAccessible(true);cast.setLong(combat,Long.MAX_VALUE);
                var casting=combat.getClass().getDeclaredField("cast");casting.setAccessible(true);casting.set(combat,null);
                int potions=poisonCalls+slowCalls;blockDamage=true;strike.invoke(combat,mist);
                Bukkit.getScheduler().runTaskLater(this,()->{
                    try{
                        check(poisonCalls+slowCalls==potions,"blocked HP loss never applies secondary effects");blockDamage=false;
                        var wall=position.getWorld().getBlockAt(position.getBlockX()-1,position.getBlockY()+1,position.getBlockZ());var old=wall.getBlockData();wall.setType(Material.STONE,false);
                        int before=damageCalls;strike.invoke(combat,mist);
                        Bukkit.getScheduler().runTaskLater(this,()->{
                            try{
                                wall.setBlockData(old,false);check(damageCalls==before,"solid wall blocks damage");
                                flower.teleport(new Location(position.getWorld(),-122.5,7,177.5));position=new Location(position.getWorld(),-124.5,7,177.5);
                                snapshots.put(viewer,new FarmPresence(viewer,position.getX(),position.getY(),position.getZ(),0f,System.nanoTime(),true));
                                strike.invoke(combat,mist);
                                Bukkit.getScheduler().runTaskLater(this,()->{
                                    try{
                                        check(damageCalls==before,"safe-zone player immune to modeled elite attacks");
                                        flower.setInvulnerable(false);flower.damage(10000);
                                        check(!((Map<?,?>)field(module,"active")).containsKey(flower.getUniqueId()),"death detaches active controller");
                                        snapshots.remove(viewer);viewers.remove(viewer);owner.remove();done=true;
                                        Bukkit.getScheduler().runTaskLater(this,()->{
                                            try{
                                                check(((Set<?>)field(field(module,"effects"),"models")).isEmpty(),"wilt tracker released after full death animation");
                                                check(spawns.getLedger().getRecords().values().stream().noneMatch(r->r.getMob().getId().equals(type.getId())),"death releases elite reservation");
                                                module.close();check(!module.ready(type),"closed optional module rejects future modeled spawns");
                                                new MythicMobsAdapter().validate(Set.of("苔痕行尸"));
                                                getLogger().info("ROSE_PROBE_PASS geometry=52 bones=17; actual nearby MM/BM/armor/animations and Player-proxy windup/damage/poison/wall/safe-zone/death cleanup passed; no network client or Folia runtime test");
                                            }catch(Throwable e){fail(e);}
                                        },50);
                                    }catch(Throwable e){fail(e);}
                                },4);
                            }catch(Throwable e){fail(e);}
                        },4);
                    }catch(Throwable e){fail(e);}
                },4);
            }catch(Throwable e){fail(e);}
        },85);
    }
    void hold(Location at){for(int x=(at.getBlockX()>>4)-2;x<=(at.getBlockX()>>4)+2;x++)for(int z=(at.getBlockZ()>>4)-2;z<=(at.getBlockZ()>>4)+2;z++){heldWorld.addPluginChunkTicket(x,z,this);heldChunks.add(new int[]{x,z});}}
    void cleanup(){if(viewers!=null)viewers.remove(viewer);if(snapshots!=null)snapshots.remove(viewer);if(owner!=null && owner.isValid())owner.remove();if(heldWorld!=null){for(var chunk:heldChunks)heldWorld.removePluginChunkTicket(chunk[0],chunk[1],this);heldChunks.clear();}}
    public void onDisable(){cleanup();}
    void fail(Throwable error){done=true;cleanup();getLogger().log(java.util.logging.Level.SEVERE,"ROSE_PROBE_FAIL",error);}
}
