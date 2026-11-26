import org.bukkit.*;
import org.bukkit.entity.*;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;
import dev.dungeon.boss.BossModule;
import dev.dungeon.boss.battle.BossEncounter;
import dev.dungeon.integration.BetterModelBridge;
import dev.dungeon.farming.FarmPresence;
import dev.dungeon.boss.hologram.BossHologramText;
import kr.toxicity.model.api.BetterModel;
import kr.toxicity.model.api.tracker.Tracker;
import java.lang.reflect.Field;
import java.util.*;

/** Real isolated server/model/pack integration; no network player or client-render claim. */
public class BossProbe extends JavaPlugin {
    int tries;
    boolean done;
    final UUID noticeId=UUID.randomUUID(),otherId=UUID.randomUUID(),offlineId=UUID.randomUUID();
    int titles,messages,rejected;
    Pig noticeOwner;
    Entity manual;
    Pig timedOut,cancelledAdmission;
    int admissionFailures;
    @SuppressWarnings("unchecked") void flushTrackerTasks(Tracker tracker) throws Exception {
        var f=Tracker.class.getDeclaredField("queuedTask");f.setAccessible(true);
        var queue=(Queue<Runnable>)f.get(tracker);Runnable task;
        while((task=queue.poll())!=null)task.run();
    }
    @SuppressWarnings("unchecked") void prepareNotices(Object runtime) throws Exception {
        var world=Bukkit.getWorld("lost_altar");
        noticeOwner=world.spawn(new Location(world,-24.5,31,-134.5),Pig.class);noticeOwner.setAI(false);
        var snapshots=(Map<UUID,FarmPresence>)field(runtime,"snapshots");
        var viewers=(Map<UUID,Player>)field(runtime,"viewers");
        for(var id:List.of(noticeId,otherId,offlineId)) {
            var proxy=(Player)java.lang.reflect.Proxy.newProxyInstance(getClassLoader(),new Class<?>[]{Player.class},(p,m,a)->{
                return switch(m.getName()) {
                    case "getUniqueId"->id;
                    case "getScheduler"->noticeOwner.getScheduler();
                    case "isOnline"->!id.equals(offlineId);
                    case "isValid","hasPermission"->true;
                    case "getWorld"->id.equals(otherId)?Bukkit.getWorlds().getFirst():world;
                    case "showTitle"->{if(id.equals(noticeId)) {
                        var title=(net.kyori.adventure.title.Title)a[0];
                        check(net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer.plainText().serialize(title.title()).equals("石址守望者"),"configured title content");titles++;
                    }else rejected++;yield null;}
                    case "sendMessage"->{if(id.equals(noticeId))messages++;else rejected++;yield null;}
                    case "hashCode"->System.identityHashCode(p);
                    case "equals"->p==a[0];
                    default->{var t=m.getReturnType();if(t==boolean.class)yield false;if(t==int.class)yield 0;if(t==float.class)yield 0f;if(t==double.class)yield 0d;if(t==long.class)yield 0L;yield null;}
                };
            });
            snapshots.put(id,new FarmPresence(id,-28.5,31,-129.5,0f,System.nanoTime(),false));viewers.put(id,proxy);
        }
    }
    void check(boolean b,String text) { if(!b)throw new AssertionError(text); }
    Object field(Object o,String name) throws Exception {var f=o.getClass().getDeclaredField(name);f.setAccessible(true);return f.get(o);}
    public void onEnable() {Bukkit.getScheduler().runTaskTimer(this,this::probe,100,40);}
    void probe() {
        if(done)return;
        try {
            if(!BetterModel.modelKeys().contains("stone_site_warden")) {if(++tries>12)throw new AssertionError("BetterModel did not load supplied model; keys="+BetterModel.modelKeys());return;}
            var model=BetterModel.modelOrNull("stone_site_warden");
            check(model.animations().keySet().containsAll(Set.of("idle","walk","attack_1","attack_2","special","death","spawn","hurt")),"animation names");
            var plugin=Bukkit.getPluginManager().getPlugin("Dungeon");
            Object farm=field(plugin,"farm");if(farm==null)return;
            Object runtime=field(farm,"runtime");if(runtime==null){if(++tries>15)throw new AssertionError("Farm not loaded");return;}
            BossModule module=(BossModule)field(runtime,"boss");check(module!=null,"Boss module exists");
            if(manual==null) {
                var ce=Bukkit.getPluginManager().getPlugin("CraftEngine");
                var loader=ce.getClass().getClassLoader();
                var engine=loader.loadClass("net.momirealms.craftengine.core.plugin.CraftEngine");
                var key=loader.loadClass("net.momirealms.craftengine.core.util.Key");
                var categoryType=loader.loadClass("net.momirealms.craftengine.core.plugin.gui.category.Category");
                var browserType=loader.loadClass("net.momirealms.craftengine.core.plugin.gui.category.ItemBrowserManager");
                var browser=engine.getMethod("itemBrowserManager").invoke(engine.getMethod("instance").invoke(null));
                var category=(Optional<?>)browserType.getMethod("byId",key)
                    .invoke(browser,key.getMethod("of",String.class).invoke(null,"dungeon:rewards"));
                check(category.isPresent(),"CE registers the dungeon category");
                check(((List<?>)categoryType.getMethod("members").invoke(category.get())).contains("dungeon:warden_spoils_preview"),
                    "CE dungeon category contains the spoils preview");
                var preview=new dev.dungeon.integration.CraftEngineItemsBridge(ce).build("dungeon:warden_spoils_preview");
                var view=preview.getItemMeta();
                check(view.hasItemModel() && !view.getPersistentDataContainer().has(new NamespacedKey(plugin,"boss_spoils")),
                    "preview uses an item model and carries no reward owner data");
                var quality=new dev.dungeon.integration.AsterQualityBridge();
                for(var group:dev.dungeon.integration.AsterQualityBridge.Group.values()) {
                    var sword=quality.build("星芽:短剑",group);
                    check(org.bukkit.inventory.ItemStack.deserializeBytes(sword.serializeAsBytes()).equals(sword),"live quality generation and binary round trip "+group);
                }
            }
            // Same MM API path as /mm mobs spawn: no Dungeon encounter/model attachment.
            if(manual==null) {
                var testWorld=Bukkit.getWorld("lost_altar");
                // No real players in fixture: keep the manual MM entity ticking while onSpawn runs.
                for(int cx=-3;cx<=-1;cx++)for(int cz=-10;cz<=-8;cz++)testWorld.addPluginChunkTicket(cx,cz,this);
                // Earlier failed fixture runs may have saved untracked manual MM mobs.
                // This probe runs only in target/coin-smoke263, never in a user server.
                for(var stale:testWorld.getNearbyEntities(new Location(testWorld,-28.5,31,-129.5),8,8,8))if(stale instanceof LivingEntity)stale.remove();
                manual=new dev.dungeon.integration.MythicMobsAdapter().spawn("石址守望者",new Location(testWorld,-26.5,31,-129.5));return;
            }
            var manualRegistry=BetterModel.registryOrNull(manual.getUniqueId());
            check(manualRegistry!=null && manualRegistry.trackers().size()==1 && manualRegistry.tracker("stone_site_warden")!=null,"MM onSpawn independently creates exactly one model");
            check(module.forEntity(manual.getUniqueId())==null,"manual MM model is not an unauthorized public battle/reward");
            manualRegistry.close();manual.remove();
            var bmBridge=new BetterModelBridge(Bukkit.getPluginManager().getPlugin("BetterModel"));
            var dispatch=(dev.dungeon.scheduling.Dispatch)field(module,"dispatch");
            timedOut=Bukkit.getWorld("lost_altar").spawn(new Location(Bukkit.getWorld("lost_altar"),-26.5,31,-129.5),Pig.class);timedOut.setAI(false);
            new dev.dungeon.boss.battle.BossModelAwaiter(plugin,timedOut,dispatch,bmBridge,"stone_site_warden",1.05,()->true,()->{throw new AssertionError("model-less mob admitted");},e->{check(e.getMessage().contains("timed out"),"precise timeout cause");admissionFailures++;return kotlin.Unit.INSTANCE;}).start();
            cancelledAdmission=Bukkit.getWorld("lost_altar").spawn(timedOut.getLocation(),Pig.class);
            var cancelled=new dev.dungeon.boss.battle.BossModelAwaiter(plugin,cancelledAdmission,dispatch,bmBridge,"stone_site_warden",1.05,()->true,()->{throw new AssertionError("cancelled admission ready");},e->{throw new AssertionError("cancelled admission failed late");});
            cancelled.start();cancelled.close();
            prepareNotices(runtime);
            module.command(Bukkit.getConsoleSender(),List.of("spawn"));
            module.command(Bukkit.getConsoleSender(),List.of("spawn"));
            done=true;
            Bukkit.getScheduler().runTaskLater(this,()->{
                try {
                    var world=Bukkit.getWorld("lost_altar");check(world!=null,"permanent world loaded");
                    var rewards=field(module,"rewards");
                    var received=rewards.getClass().getDeclaredMethod("received",org.bukkit.inventory.ItemStack.class);
                    received.setAccessible(true);
                    var material=new dev.dungeon.integration.CraftEngineItemsBridge(Bukkit.getPluginManager().getPlugin("CraftEngine")).build("genlite:gear");
                    material.setAmount(3);
                    var receiptPlain=net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer.plainText();
                    var receiptText=receiptPlain.serialize((net.kyori.adventure.text.Component)received.invoke(rewards,material));
                    check(receiptText.contains("铁环") && receiptText.endsWith("×3"),"reward chat preserves CE item name and amount: "+receiptText);
                    var sword=new dev.dungeon.integration.AsterQualityBridge().build("星芽:短剑",dev.dungeon.integration.AsterQualityBridge.Group.EXCELLENT);
                    var swordNotice=(net.kyori.adventure.text.Component)received.invoke(rewards,sword);
                    var swordName=receiptPlain.serialize(sword.effectiveName());
                    check(swordName.contains("星芽短剑") && receiptPlain.serialize(swordNotice).contains(swordName)
                        && receiptPlain.serialize(swordNotice).endsWith("×1"),"reward chat preserves full quality sword name");
                    var renderLore=rewards.getClass().getDeclaredMethod("spoilsLore");
                    renderLore.setAccessible(true);
                    var lines=(List<?>)renderLore.invoke(rewards);
                    var plainText=net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer.plainText();
                    var lore=lines.stream().map(value->(net.kyori.adventure.text.Component)value).map(plainText::serialize).toList();
                    check(lore.subList(2,7).equals(List.of("▪ 石址材料 ×1 — 必得","▪ 星芽短剑 — 15%","▪ 材料幸运方块 — 25%",
                        "▪ 生成器幸运方块 — 3%","  └ 每日前 3 次合格击杀参与，最多获得 1 个")),"spoils odds and eligibility on rendered lore: "+lore);
                    check(lore.getLast().contains("右键开启"),"open action remains last");
                    check(admissionFailures==1 && !timedOut.isValid() && !cancelledAdmission.isValid(),"missing-model timeout and cancelled admission clean entities, no late callbacks");
                    check(titles==1 && messages==1 && rejected==0,"one title and chat, other-world/offline players excluded: "+titles+"/"+messages+"/"+rejected);
                    for(var id:List.of(noticeId,otherId,offlineId)) {
                        ((Map<?,?>)field(runtime,"snapshots")).remove(id);((Map<?,?>)field(runtime,"viewers")).remove(id);
                    }
                    noticeOwner.remove();
                    var key=new NamespacedKey(plugin,"world_boss");
                    var entities=world.getEntities().stream().filter(e->e.getPersistentDataContainer().has(key,PersistentDataType.STRING)).toList();
                    check(entities.size()==1,"single boss spawned, count="+entities.size());
                    Mob mob=(Mob)entities.getFirst();
                    check(mob.getLocation().distanceSquared(new Location(world,-28.5,31,-129.5))<.01,"boss placed on screenshot circular arena center, real floor/headroom; actual="+mob.getLocation());
                    check(mob.hasAI() && Bukkit.getMobGoals().getAllGoals(mob).isEmpty() && !mob.isPersistent(),"Dungeon owns goals, physics remains active, no stale persistent boss");
                    check(Math.abs(mob.getMaxHealth()-1000)<.01,"configured base health");
                    BossEncounter encounter=module.forEntity(mob.getUniqueId());check(encounter!=null,"battle identity");
                    BetterModelBridge.Model visual=(BetterModelBridge.Model)field(encounter,"model");
                    Tracker tracker=(Tracker)field(visual,"handle");
                    check(BetterModel.registryOrNull(mob.getUniqueId()).trackers().size()==1 && BetterModel.registryOrNull(mob.getUniqueId()).tracker("stone_site_warden")==tracker,"Dungeon adopts same MM tracker without duplicate");
                    check(Math.abs(mob.getAttribute(org.bukkit.attribute.Attribute.KNOCKBACK_RESISTANCE).getValue()-.75)<.001,"partial knockback resistance");
                    mob.knockback(.4,1,0);
                    check(mob.getVelocity().getX()<0 && Math.abs(mob.getVelocity().getX())<=.16 && mob.getVelocity().getY()<=.12,"actual native knockback reaches limiter");
                    var battleState=encounter.getClass().getDeclaredField("state");battleState.setAccessible(true);battleState.set(encounter,BossEncounter.State.CASTING);
                    var knockback=new io.papermc.paper.event.entity.EntityKnockbackEvent(mob,io.papermc.paper.event.entity.EntityKnockbackEvent.Cause.ENTITY_ATTACK,new org.bukkit.util.Vector(.4,.4,0));
                    Bukkit.getPluginManager().callEvent(knockback);check(knockback.isCancelled(),"casting suppresses knockback");battleState.set(encounter,BossEncounter.State.IDLE);
                    mob.setVelocity(new org.bukkit.util.Vector());
                    check(tracker.bones().size()>=31,"all supplied skeleton bones loaded");
                    check(tracker.displays().count()>0,"real BetterModel displays created");
                    var neutral=new BetterModelBridge(Bukkit.getPluginManager().getPlugin("BetterModel")).effect("stone_site_warden",mob.getLocation(),1);
                    Tracker rest=(Tracker)field(neutral,"handle");
                    for(int frame=0;frame<4;frame++)rest.getPipeline().tick(rest.getPipeline().createAnimationBundler());
                    var pose=new ArrayList<Map<String,Object>>();
                    for(var bone:rest.bones()) {
                        Object state=field(bone,"globalState");var after=state.getClass().getDeclaredMethod("after");after.setAccessible(true);
                        var movement=(kr.toxicity.model.api.bone.BoneMovement)after.invoke(state);
                        var origin=bone.getGroup().getPosition();var position=movement.position();var scale=bone.getGroup().getItemStack().scale();
                        check(position.distance(origin)<0.00001f,"rest pivot agrees with original skeleton: "+bone.name());
                        pose.add(Map.of("name",bone.name().name(),"origin",List.of(origin.x,origin.y,origin.z),"position",List.of(position.x,position.y,position.z),"itemScale",List.of(scale.x,scale.y,scale.z)));
                    }
                    java.nio.file.Files.writeString(getDataFolder().toPath().toAbsolutePath().getParent().getParent().resolve("warden-rest-pose.json"),new com.google.gson.Gson().toJson(pose));neutral.close();
                    // BetterModel deliberately suspends its timer with zero viewers. Evaluate actual
                    // public animation pipelines instead of claiming a network-client render test.
                    for(String name:List.of("walk","attack_1","attack_2","special","hurt")) {
                        visual.play(name,true);
                        for(int frame=0;frame<20;frame++)tracker.getPipeline().tick(tracker.getPipeline().createAnimationBundler());
                        check(tracker.getPipeline().runningAnimation()!=null,"evaluated animation "+name);
                        tracker.stopAnimation(name);
                    }
                    BetterModelBridge bridge=new BetterModelBridge(Bukkit.getPluginManager().getPlugin("BetterModel"));
                    bridge.validate("stone_site_warden","stone_site_wave",Set.of("attack_1","attack_2","special"));
                    var fx=bridge.effect("stone_site_wave",mob.getLocation(),6);
                    fx.play("telegraph",false);
                    Tracker dummy=(Tracker)field(fx,"handle");
                    for(int frame=0;frame<20;frame++)dummy.getPipeline().tick(dummy.getPipeline().createAnimationBundler());
                    check(dummy.getPipeline().runningAnimation()!=null,"warning animation evaluated");
                    fx.play("burst",true);fx.close();
                    // Inject only an immutable combat snapshot: verify real server physics,
                    // without pretending a network player has joined or rendered the model.
                    var at=mob.getLocation();UUID simulated=UUID.randomUUID();
                    @SuppressWarnings("unchecked") var snapshots=(Map<UUID,FarmPresence>)field(runtime,"snapshots");
                    snapshots.put(simulated,new FarmPresence(simulated,at.getX()+6,at.getY(),at.getZ(),0f,System.nanoTime(),true));
                    var attackTick=encounter.getClass().getDeclaredField("nextAttack");attackTick.setAccessible(true);attackTick.setLong(encounter,Long.MAX_VALUE);
                    Bukkit.getScheduler().runTaskLater(this,()->{
                        try {
                            snapshots.remove(simulated);
                            var displayKey=new NamespacedKey(plugin,"boss_hologram");
                            var displays=world.getEntities().stream().filter(e->e.getPersistentDataContainer().has(displayKey,PersistentDataType.BYTE)).toList();
                            check(displays.size()==1,"one real spawn-point TextDisplay");
                            var display=(TextDisplay)displays.getFirst();
                            check(!display.isPersistent() && !display.isSeeThrough() && display.isShadowed(),"nonpersistent wall-respecting hologram");
                            check(display.getLocation().distanceSquared(new Location(world,-28.5,31.35,-129.5))<.01,"hologram stays near arena floor, not moving boss");
                            String plain=net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer.plainText().serialize(display.text());
                            check(plain.split("\\n").length==2 && plain.contains("石址守望者") && plain.contains("战斗") && !plain.contains("掉落") && !plain.contains("1000"),"only boss name and current status: "+plain);
                            var orphan=world.spawn(display.getLocation(),TextDisplay.class);orphan.getPersistentDataContainer().set(displayKey,PersistentDataType.BYTE,(byte)1);
                            var unrelated=world.spawn(display.getLocation(),TextDisplay.class);
                            Bukkit.getPluginManager().callEvent(new org.bukkit.event.world.EntitiesLoadEvent(display.getLocation().getChunk(),List.of(orphan,unrelated)));
                            check(!orphan.isValid() && unrelated.isValid() && display.isValid(),"only orphaned Dungeon holograms removed");unrelated.remove();
                            check(mob.getLocation().distanceSquared(at)>.04,"goal-free boss moves using actual server physics");
                            var zombie=world.spawn(mob.getLocation().add(2,0,0),Zombie.class);
                            var arrow=world.spawn(zombie.getLocation(),Arrow.class);
                            try {
                                zombie.setAI(false);
                                var target=new org.bukkit.event.entity.EntityTargetLivingEntityEvent(zombie,mob,
                                    org.bukkit.event.entity.EntityTargetEvent.TargetReason.CLOSEST_ENTITY);
                                Bukkit.getPluginManager().callEvent(target);
                                check(target.isCancelled(),"ordinary mob cannot target the managed boss");
                                var reverse=new org.bukkit.event.entity.EntityTargetLivingEntityEvent(mob,zombie,
                                    org.bukkit.event.entity.EntityTargetEvent.TargetReason.TARGET_ATTACKED_ENTITY);
                                Bukkit.getPluginManager().callEvent(reverse);
                                check(reverse.isCancelled(),"boss cannot acquire a vanilla mob target");
                                double guardedHealth=mob.getHealth(), mobHealth=zombie.getHealth();
                                mob.damage(7,zombie);
                                arrow.setShooter(zombie);
                                mob.damage(7,org.bukkit.damage.DamageSource.builder(org.bukkit.damage.DamageType.ARROW)
                                    .withCausingEntity(zombie).withDirectEntity(arrow).build());
                                zombie.damage(7,mob);
                                check(mob.getHealth()==guardedHealth && zombie.getHealth()==mobHealth,"real melee and projectile events block boss/mob damage both ways");
                            } finally { arrow.remove(); zombie.remove(); }
                            double hp=mob.getHealth();mob.damage(10);
                            check(mob.getHealth()<hp,"boss accepts ordinary damage after birth");
                            check(tracker.modifier().damageTint() && ((java.util.concurrent.atomic.AtomicLong)field(tracker,"damageTint")).get()>=0,"ordinary damage triggers native temporary red tint");
                            flushTrackerTasks(tracker);
                            check(tracker.bones().stream().anyMatch(b->{try{return (int)field(b,"tint")==0xFF8080;}catch(Exception e){throw new RuntimeException(e);}}),"ordinary hit applies real bone red tint");
                            mob.setInvulnerable(false);mob.damage(100000);
                            check(module.forEntity(mob.getUniqueId())==null,"death removes active battle");
                            check(!tracker.isClosed() && tracker.forRemoval(),"same live tracker retained for death, no disappearance/replacement");
                            check(((java.util.concurrent.atomic.AtomicLong)field(tracker,"damageTint")).get()==-1,"death cancels temporary damage tint");
                            flushTrackerTasks(tracker);
                            check(tracker.bones().stream().allMatch(b->{try{return (int)field(b,"tint")==0xFFFFFF;}catch(Exception e){throw new RuntimeException(e);}}),"death render queue restores untinted texture, cannot leave a red corpse");
                            Object corpses=field(module,"effects");
                            check(((Set<?>)field(corpses,"models")).contains(visual),"original visual owned until animation cleanup");
                            encounter.died();
                            check(((Set<?>)field(corpses,"models")).size()==1,"duplicate death cannot create/restart another visual");
                            Bukkit.getScheduler().runTaskLater(this,()->{
                                try {
                                    Object effects=field(module,"effects");var models=(Set<?>)field(effects,"models");
                                    check(models.isEmpty(),"all warning/death trackers released");
                                    check(tracker.isClosed(),"original corpse tracker closed after death");
                                    check(world.getPluginChunkTickets().getOrDefault(plugin,List.of()).isEmpty(),"Paper arena chunk tickets released");
                                    String waiting=net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer.plainText().serialize(display.text());
                                    check(waiting.contains("距离苏醒") && waiting.matches("(?s).*24:[0-5][0-9].*"),"real post-kill 25 minute countdown, not active state: "+waiting);
                                    check(BossHologramText.Companion.remaining(2001,1000).equals("00:02") && BossHologramText.Companion.remaining(999,1000).equals("00:00"),"countdown rounds up without early zero");
                                    module.command(Bukkit.getConsoleSender(),List.of("spawn"));
                                    Bukkit.getGlobalRegionScheduler().run(this,task->module.dependencyStopped());
                                    Bukkit.getScheduler().runTaskLater(this,()->{
                                        try {
                                            check(world.getEntities().stream().noneMatch(e->e.getPersistentDataContainer().has(key,PersistentDataType.STRING)),"late spawn cannot survive dependency shutdown");
                                            check(world.getPluginChunkTickets().getOrDefault(plugin,List.of()).isEmpty(),"dependency shutdown releases pending arena tickets");
                                            var hologram=(dev.dungeon.boss.hologram.BossHologram)field(module,"hologram");
                                            var pulse=hologram.getClass().getDeclaredMethod("pulse");pulse.setAccessible(true);pulse.invoke(hologram);hologram.close();
                                            Bukkit.getScheduler().runTaskLater(this,()->{
                                                try {
                                                    check(world.getEntities().stream().noneMatch(e->e.getPersistentDataContainer().has(displayKey,PersistentDataType.BYTE)),"closed hologram removed, queued update cannot recreate");
                                                    getLogger().info("BOSS_PROBE_PASS bones="+tracker.bones().size()+" animations="+model.animations().keySet()+" suppliedGeometry=297; rest skeleton pivots, two-line hologram/countdown, same-tracker death without tint/replacement, duplicate death guard and bounded cleanup passed; no client rendering or Folia runtime test");
                                                }catch(Throwable e){fail(e);}
                                            },4);
                                        } catch(Throwable e) {fail(e);}
                                    },20);
                                } catch(Throwable e) {fail(e);}
                            },90);
                        } catch(Throwable e) {fail(e);}
                    },20);
                } catch(Throwable e) {fail(e);}
            },80);
        } catch(Throwable e) {done=true;fail(e);}
    }
    void fail(Throwable e) {getLogger().log(java.util.logging.Level.SEVERE,"BOSS_PROBE_FAIL",e);}
}

