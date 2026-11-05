from pathlib import Path
import shutil,subprocess,zipfile,xml.etree.ElementTree as ET
r=Path('D:/codex/DungeonCore');live=Path('C:/Users/32633/Desktop/leaf');test=r/'target/altar-smoke262';(test/'plugins').mkdir(parents=True,exist_ok=True)

with zipfile.ZipFile(live/'purpur-26.2-2633.jar') as z:
 libs=[str(live/'libraries'/line.split('\t')[2]) for line in z.read('META-INF/libraries.list').decode().splitlines()]
assert all(Path(x).is_file() for x in libs)
(test/'java.args').write_text('-Xms512M\n-Xmx1536M\n-Dfile.encoding=UTF-8\n-cp\n"'+str(live/'versions/26.2/purpur-26.2.jar')+';'+(';'.join(libs))+'"\norg.bukkit.craftbukkit.Main\n--nogui\n',encoding='utf-8')
(test/'java.args').write_text((test/'java.args').read_text(encoding='utf-8').replace('\\','/'),encoding='utf-8')
shutil.copy2(live/'eula.txt',test/'eula.txt')
(test/'server.properties').write_text('server-ip=127.0.0.1\nserver-port=0\nonline-mode=false\nenable-query=false\nenable-rcon=false\nlevel-name=smoke-base\nlevel-type=minecraft:flat\ngenerator-settings={"layers":[],"biome":"minecraft:the_void","features":false,"lakes":false,"structure_overrides":[]}\nallow-nether=false\nmax-players=1\nview-distance=2\nsimulation-distance=2\nspawn-protection=0\n',encoding='utf-8')
(test/'bukkit.yml').write_text('settings:\n  allow-end: false\n',encoding='utf-8')
for name in ('MythicMobs-5.13.0.jar','craft-engine-paper-plugin-26.9.2.jar'):shutil.copy2(live/'plugins'/name,test/'plugins'/name)
shutil.copy2(r/'target/Dungeon-0.1.0.jar',test/'plugins/Dungeon.jar')
shutil.copytree(live/'plugins/MythicMobs',test/'plugins/MythicMobs',dirs_exist_ok=True)
ce=test/'plugins/CraftEngine';ce.mkdir(exist_ok=True)
for dirname in ('libs','translations'):
 shutil.copytree(live/'plugins/CraftEngine'/dirname,ce/dirname,dirs_exist_ok=True)
for dirname in ('internal','genlite','genlite-materials','货币'):
 shutil.copytree(live/'plugins/CraftEngine/resources'/dirname,ce/'resources'/dirname,dirs_exist_ok=True)
d=test/'plugins/Dungeon';d.mkdir(exist_ok=True)
for name in ('maps','lang','lobby'):
 if (live/'plugins/Dungeon'/name).is_dir():shutil.copytree(live/'plugins/Dungeon'/name,d/name,dirs_exist_ok=True)
for name in ('lobby.yml','config.yml','.no-example'):shutil.copy2(live/'plugins/Dungeon'/name,d/name)
shutil.copytree(live/'plugins/Dungeon/dungeons/lost_altar',d/'dungeons/lost_altar',dirs_exist_ok=True)
(d/'dungeons').mkdir(exist_ok=True)
source=r/'target/AltarProbe.java'
source.write_text('''import org.bukkit.*;import org.bukkit.plugin.java.JavaPlugin;import org.bukkit.event.*;import io.lumine.mythic.bukkit.events.MythicDropLoadEvent;import io.lumine.mythic.api.drops.*;import io.lumine.mythic.api.adapters.*;import io.lumine.mythic.bukkit.*;import java.lang.reflect.*;import java.util.*;
public class AltarProbe extends JavaPlugin implements Listener {
 private final Map<String,IItemDrop> drops=new HashMap<>();
 public void onEnable(){getServer().getPluginManager().registerEvents(this,this);getServer().getScheduler().runTaskLater(this,()->{getServer().dispatchCommand(getServer().getConsoleSender(),"mm reload");getServer().getScheduler().runTaskTimer(this,()->probe(),60,40);},100);}
 @EventHandler(priority=EventPriority.MONITOR) public void drop(MythicDropLoadEvent e){if(e.getDropName().equalsIgnoreCase("dungeonce")) e.getDrop().ifPresent(x->drops.put(e.getConfig().getString("id"),(IItemDrop)x));}
 private boolean done=false;
 void probe(){if(done)return;World w=Bukkit.getWorld("lost_altar");if(w==null)return;done=true;try{
 if(drops.size()!=7)throw new IllegalStateException("expected 7 CE IDs, got "+drops.keySet());
 AbstractEntity player=(AbstractEntity)Proxy.newProxyInstance(getClassLoader(),new Class[]{AbstractEntity.class},(p,m,a)->m.getName().equals("isPlayer")?true:null);
 for(var entry:drops.entrySet()){
 DropMetadata meta=(DropMetadata)Proxy.newProxyInstance(getClassLoader(),new Class[]{DropMetadata.class},(p,m,a)->m.getName().equals("getCause")?Optional.of(player):null);
 var item=BukkitAdapter.adapt(entry.getValue().getDrop(meta,2));
 var id=net.momirealms.craftengine.bukkit.api.CraftEngineItems.getCustomItemId(item);
 if(!entry.getKey().equals(String.valueOf(id))||item.getAmount()!=2)throw new IllegalStateException("bad real CE drop "+entry.getKey()+":"+id);
 DropMetadata environment=(DropMetadata)Proxy.newProxyInstance(getClassLoader(),new Class[]{DropMetadata.class},(p,m,a)->m.getName().equals("getCause")?Optional.empty():null);
 if(!BukkitAdapter.adapt(entry.getValue().getDrop(environment,2)).getType().isAir())throw new IllegalStateException("environment farm yielded items");
 }
 for(String id:new String[]{"AltarMossWalker","AltarTideRemnant","AltarBoneArcher","AltarVineSpider","AltarStoneGuard","AltarRuneKeeper"}){var e=MythicBukkit.inst().getAPIHelper().spawnMythicMob(id,new Location(w,0.5,49,16.5));if(e==null)throw new IllegalStateException(id);e.remove();}
 if(w.getBlockAt(0,48,0).getType().isAir()||!w.getBlockAt(0,49,0).isPassable()||!w.getBlockAt(0,50,0).isPassable())throw new IllegalStateException("unsafe entrance");
 getLogger().info("ALTAR_PROBE_PASS mobs=6 CE=7 env-drop=blocked spawn=safe world="+w.getName());
 }catch(Throwable e){e.printStackTrace();getLogger().severe("ALTAR_PROBE_FAILED "+e);}}
}''',encoding='utf-8')
cp=(r/'target/classpath.txt').read_text().strip()+';'+str(live/'plugins/MythicMobs-5.13.0.jar')+';'+str(live/'plugins/craft-engine-paper-plugin-26.9.2.jar')
classes=r/'target/altar-probe-classes';classes.mkdir(exist_ok=True)
jdk=Path('C:/Program Files/Zulu/zulu-25/bin')
subprocess.run([str(jdk/'javac.exe'),'-encoding','UTF-8','-cp',cp,'-d',str(classes),str(source)],check=True)
with zipfile.ZipFile(test/'plugins/AltarProbe.jar','w') as z:
 for f in classes.rglob('*.class'):z.write(f,f.relative_to(classes).as_posix())
 z.writestr('plugin.yml',"name: AltarProbe\nversion: '1'\nmain: AltarProbe\napi-version: '1.21.11'\ndepend:\n  - Dungeon\n  - MythicMobs\n  - CraftEngine\n")
print('Isolated test prepared',test)
