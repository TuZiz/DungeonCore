"""Isolated event/coordinate verification with real Bukkit registries; no live player mutations."""
from pathlib import Path
import queue,shutil,subprocess,threading,time,zipfile,sys,json
sys.stdout.reconfigure(encoding='utf-8',errors='replace')
project=Path(__file__).resolve().parents[1];live=Path('C:/Users/32633/Desktop/leaf')
root=project/'target/death-smoke';root.mkdir(parents=True,exist_ok=True)
java=Path('C:/Program Files/Zulu/zulu-25/bin')
with zipfile.ZipFile(live/'leaf-26.3-4.jar') as jar:
    libs=[str(live/'libraries'/line.split('\t')[2]) for line in jar.read('META-INF/libraries.list').decode().splitlines()]
cp=';'.join([str(live/'versions/26.3/leaf-26.3.jar')]+libs).replace('\\','/')
(root/'java.args').write_text('-Xms512M\n-Xmx1536M\n-Dfile.encoding=UTF-8\n-cp\n"'+cp+'"\norg.bukkit.craftbukkit.Main\n--nogui\n',encoding='utf-8')
shutil.copy2(live/'eula.txt',root/'eula.txt')
(root/'server.properties').write_text('server-ip=127.0.0.1\nserver-port=0\nonline-mode=false\nenable-query=false\nenable-rcon=false\nlevel-name=smoke-base\nlevel-type=minecraft:flat\ngenerator-settings={"layers":[],"biome":"minecraft:the_void"}\nallow-nether=false\nview-distance=2\nsimulation-distance=2\n',encoding='utf-8')
(root/'bukkit.yml').write_text('settings:\n  allow-end: false\n',encoding='utf-8')
plugins=root/'plugins';plugins.mkdir(exist_ok=True)
shutil.copy2(project/'target/Dungeon-0.1.0.jar',plugins/'Dungeon.jar')
data=plugins/'Dungeon';data.mkdir(exist_ok=True)
shutil.copytree(live/'plugins/Dungeon/maps/lost_altar',data/'maps/lost_altar',dirs_exist_ok=True)
folder=data/'dungeons/lost_altar';folder.mkdir(parents=True,exist_ok=True)
shutil.copy2(project/'src/main/resources/dungeons/lost_altar/farming.yml',folder/'farming.yml')
text=(folder/'farming.yml').read_text(encoding='utf-8').replace('enabled: false','enabled: true')
(folder/'farming.yml').write_text(text,encoding='utf-8')
(data/'.no-example').touch()
(data/'lobby.yml').write_text("schema-version: 1\nenabled: false\nworld: 'Starfall_Dungeon_Hub'\nimport-bundled-map: false\nauto-load: false\nspawn:\n  x: 0.5\n  y: 101.0\n  z: 0.5\n  yaw: 0\n  pitch: 0\n",encoding='utf-8')
classes=root/'probe-classes';classes.mkdir(exist_ok=True)
subprocess.run([str(java/'javac.exe'),'-encoding','UTF-8','-cp',cp+';'+str(plugins/'Dungeon.jar'),'-d',str(classes),str(project/'tools/DeathProbe.java')],check=True)
with zipfile.ZipFile(plugins/'DeathProbe.jar','w') as jar:
    jar.write(classes/'DeathProbe.class','DeathProbe.class')
    jar.writestr('plugin.yml',"name: DeathProbe\nversion: '1'\nmain: DeathProbe\napi-version: '1.21.11'\ndepend:\n  - Dungeon\n")
p=subprocess.Popen([str(java/'java.exe'),'@java.args'],cwd=root,stdin=subprocess.PIPE,stdout=subprocess.PIPE,stderr=subprocess.STDOUT,text=True,encoding='utf-8',errors='replace',creationflags=subprocess.CREATE_NO_WINDOW)
q=queue.Queue();threading.Thread(target=lambda:[q.put(line) for line in p.stdout],daemon=True).start()
start=time.monotonic();stopping=False;passed=False
try:
    with (root/'server.log').open('w',encoding='utf-8') as log:
        while p.poll() is None or not q.empty():
            try:
                line=q.get(timeout=.2);log.write(line);log.flush()
                if any(s in line for s in ('Dungeon','DEATH_PROBE','Exception','ERROR','DeathProbe')):print(line.rstrip(),flush=True)
                if 'DEATH_PROBE_PASS' in line:passed=True
                if not stopping and (passed or 'DEATH_PROBE_FAIL' in line or time.monotonic()-start>90):
                    stopping=True;p.stdin.write('stop\n');p.stdin.flush()
            except queue.Empty:pass
            if time.monotonic()-start>120:p.kill();break
finally:
    if p.poll() is None:p.kill()
code=p.wait();result={'passed':passed,'exit_code':code}
(root/'result.json').write_text(json.dumps(result),encoding='utf-8');print(result,flush=True)
if not passed or code!=0:raise SystemExit(1)
