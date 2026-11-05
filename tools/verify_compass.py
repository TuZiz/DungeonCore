from pathlib import Path
import json,queue,shutil,subprocess,threading,time,zipfile,sys
sys.stdout.reconfigure(encoding='utf-8',errors='replace')
project=Path(__file__).resolve().parents[1]
live=Path('C:/Users/32633/Desktop/leaf')
root=project/'target/compass-smoke';root.mkdir(parents=True,exist_ok=True)
shutil.copytree(live/'assets',root/'assets',dirs_exist_ok=True)
java=Path('C:/Program Files/Zulu/zulu-25/bin')
with zipfile.ZipFile(live/'leaf-26.3-4.jar') as jar:
    libs=[str(live/'libraries'/line.split('\t')[2]) for line in jar.read('META-INF/libraries.list').decode().splitlines()]
cp=';'.join([str(live/'versions/26.3/leaf-26.3.jar')]+libs).replace('\\','/')
(root/'java.args').write_text('-Xms512M\n-Xmx1536M\n-Dfile.encoding=UTF-8\n-Dsun.stdout.encoding=UTF-8\n-cp\n"'+cp+'"\norg.bukkit.craftbukkit.Main\n--nogui\n',encoding='utf-8')
shutil.copy2(live/'eula.txt',root/'eula.txt')
(root/'server.properties').write_text('server-ip=127.0.0.1\nserver-port=0\nonline-mode=false\nenable-query=false\nenable-rcon=false\nlevel-name=smoke-base\nlevel-type=minecraft:flat\ngenerator-settings={"layers":[],"biome":"minecraft:the_void"}\nallow-nether=false\nview-distance=2\nsimulation-distance=2\n',encoding='utf-8')
(root/'bukkit.yml').write_text('settings:\n  allow-end: false\n',encoding='utf-8')
plugins=root/'plugins';plugins.mkdir(exist_ok=True)
mm=list((live/'plugins').glob('MythicMobs-*.jar'))
assert len(mm)==1, 'Expected exactly one installed MythicMobs JAR'
for path in (live/'plugins/Xerr-2.3.0.jar',mm[0],live/'plugins/packetevents-spigot-2.14.0.jar'):shutil.copy2(path,plugins/path.name)
shutil.copy2(project/'target/Dungeon-0.1.0.jar',plugins/'Dungeon.jar')
shutil.copytree(live/'plugins/Xerr',plugins/'Xerr',dirs_exist_ok=True)
shutil.copytree(live/'plugins/MythicMobs',plugins/'MythicMobs',dirs_exist_ok=True)
shutil.copy2(project/'deploy/xerr/dungeon_elite.yml',plugins/'Xerr/core/layout/dungeon_elite.yml')
shutil.copytree(project/'deploy/xerr/assets',plugins/'Xerr/core/assets',dirs_exist_ok=True)
data=plugins/'Dungeon';data.mkdir(exist_ok=True)
folder=data/'dungeons/lost_altar';folder.mkdir(parents=True,exist_ok=True)
for name in ('elite-hud.yml',):shutil.copy2(project/'src/main/resources/dungeons/lost_altar'/name,folder/name)
(data/'.no-example').touch()
(data/'lobby.yml').write_text("schema-version: 1\nenabled: false\nworld: 'Starfall_Dungeon_Hub'\nimport-bundled-map: false\nauto-load: false\nspawn:\n  x: 0.5\n  y: 101.0\n  z: 0.5\n  yaw: 0\n  pitch: 0\n",encoding='utf-8')
classes=root/'probe-classes';classes.mkdir(exist_ok=True)
compilecp=cp+';'+str(plugins/'Dungeon.jar')+';'+str(plugins/'Xerr-2.3.0.jar')+';'+str(plugins/mm[0].name)
subprocess.run([str(java/'javac.exe'),'-encoding','UTF-8','-cp',compilecp,'-d',str(classes),str(project/'tools/CompassProbe.java')],check=True)
with zipfile.ZipFile(plugins/'CompassProbe.jar','w') as jar:
    jar.write(classes/'CompassProbe.class','CompassProbe.class')
    jar.writestr('plugin.yml',"name: CompassProbe\nversion: '1'\nmain: CompassProbe\napi-version: '1.21.11'\ndepend:\n  - Dungeon\n  - MythicMobs\n  - Xerr\n")
p=subprocess.Popen([str(java/'java.exe'),'@java.args'],cwd=root,stdin=subprocess.PIPE,stdout=subprocess.PIPE,stderr=subprocess.STDOUT,text=True,encoding='utf-8',errors='replace',creationflags=subprocess.CREATE_NO_WINDOW)
q=queue.Queue()
threading.Thread(target=lambda:[q.put(line) for line in p.stdout],daemon=True).start()
start=time.monotonic();stopping=False;passed=False;pack_done=False;pack_requested=False
try:
    with (root/'server.log').open('w',encoding='utf-8') as log:
        while p.poll() is None or not q.empty():
            try:
                line=q.get(timeout=.2);log.write(line);log.flush()
                if any(s in line for s in ('Dungeon','Xerr','COMPASS_','Exception','ERROR','MythicMobs','CompassProbe')): print(line.rstrip(),flush=True)
                if 'COMPASS_PROBE_PASS' in line:passed=True
                if passed and not pack_requested:
                    pack_requested=True;p.stdin.write('xerr pack\n');p.stdin.flush()
                if pack_requested and ('资源包已生成' in line or 'Resource pack generated:' in line):pack_done=True
                if not stopping and (pack_done or 'COMPASS_PROBE_FAIL' in line or '资源包生成失败' in line or time.monotonic()-start>150):
                    stopping=True;p.stdin.write('stop\n');p.stdin.flush()
            except queue.Empty:pass
            if time.monotonic()-start>190:p.kill();break
finally:
    if p.poll() is None:p.kill()
code=p.wait()
result={'passed':passed,'pack_generated':pack_done,'exit_code':code}
if passed and pack_done:
    components=json.loads((plugins/'CompassProbe/hud-components.json').read_text(encoding='utf-8'))
    fonts=set();used_images=set()
    def collect(value):
        if isinstance(value,dict):
            if 'font' in value:
                fonts.add(value['font'])
                if value['font'].startswith('xerr_img:'):used_images.add((value['font'],value['text']))
            for item in value.values():collect(item)
        elif isinstance(value,list):
            for item in value:collect(item)
    collect(components)
    with zipfile.ZipFile(plugins/'Xerr/generated/resource_pack.zip') as pack:
        for font in fonts:
            ns,path=font.split(':',1)
            assert f'assets/{ns}/font/{path}.json' in pack.namelist(), f'Missing generated HUD font: {font}'
        images=[name for name in pack.namelist() if '/font/' in name and 'compass_' in name]
        # Inspect the actual emitted bitmap providers, not just the YAML input.
        bitmap_count=0
        for font in fonts:
            ns,path=font.split(':',1)
            providers=json.loads(pack.read(f'assets/{ns}/font/{path}.json'))['providers']
            for provider in providers:
                if provider.get('type')!='bitmap':continue
                if not any((font,char) in used_images for row in provider['chars'] for char in row):continue
                assert provider['height']==20, provider
                texns,texpath=provider['file'].split(':',1)
                assert f'assets/{texns}/textures/{texpath}' in pack.namelist()
                bitmap_count+=1
        assert bitmap_count==32, bitmap_count
    result['bitmap_frames_at_height_20']=bitmap_count
    result['hud_font_assets_verified']=sorted(fonts)
(root/'result.json').write_text(json.dumps(result),encoding='utf-8');print(result,flush=True)
if not passed or not pack_done or code!=0:raise SystemExit(1)
