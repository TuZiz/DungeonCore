"""Run only the existing isolated Leaf 26.3 fixture with actual MM/CE/BetterModel and the supplied model."""
from pathlib import Path
import json,queue,shutil,sqlite3,subprocess,threading,time,zipfile,sys
sys.stdout.reconfigure(encoding='utf-8',errors='replace')
project=Path(__file__).resolve().parents[1]
root=project/'target/coin-smoke263'
live=Path('C:/Users/32633/Desktop/leaf')
java=Path('C:/Program Files/Zulu/zulu-25/bin')
assert (root/'java.args').exists() and root.is_relative_to(project/'target')
plugins=root/'plugins'
# The rose probe injects a combat snapshot. Keep the public boss on manual spawn
# during the probe, even if an earlier fixture run left an expired schedule.
with sqlite3.connect(plugins/'Dungeon/boss-rewards.db') as db:
 db.execute("CREATE TABLE IF NOT EXISTS schedule(id TEXT PRIMARY KEY,next_at INTEGER)")
 db.execute("INSERT OR REPLACE INTO schedule VALUES('lost_altar',?)",(int(time.time()*1000)+900000,))
rose='--rose' in sys.argv
probes=['BossProbe','RoseProbe'] if rose else ['BossProbe']
shutil.copy2(project/'target/Dungeon-0.1.0.jar',plugins/'Dungeon.jar')
shutil.copy2(live/'plugins/bettermodel-3.6.0-SNAPSHOT-534-paper.jar',plugins/'BetterModel.jar')
shutil.copytree(project/'src/main/resources/boss/models',plugins/'BetterModel/models/dungeon',dirs_exist_ok=True)
if rose:shutil.copytree(project/'src/main/resources/elites/models',plugins/'BetterModel/models/dungeon',dirs_exist_ok=True)
for n in ('LaunchProbe.jar','AltarProbe.jar'):
 if (plugins/n).exists():shutil.move(plugins/n,root/(n+'.previous'))
for name in ('boss.yml','spawning.yml','elites.yml'):
 shutil.copy2(project/'src/main/resources/dungeons/lost_altar'/name,plugins/'Dungeon/dungeons/lost_altar'/name)
shutil.copy2(project/'src/main/resources/boss-hologram.yml',plugins/'Dungeon/boss-hologram.yml')
lang=plugins/'Dungeon/lang'
lang.mkdir(exist_ok=True)
if '--migrate-lang' in sys.argv:
 for name in ('zh_CN.yml','lobby_zh_CN.yml','farming_zh_CN.yml','elite_zh_CN.yml','boss_zh_CN.yml'):
  shutil.copy2(live/'plugins/Dungeon/lang'/name,lang/name)
else:
 shutil.copy2(project/'src/main/resources/lang/zh_CN.yml',lang/'zh_CN.yml')
if rose:
 # Keep automatic population out of this deterministic spawn/attack probe.
 path=plugins/'Dungeon/dungeons/lost_altar/spawning.yml'
 path.write_text(path.read_text(encoding='utf-8').replace('enabled: true','enabled: false',1),encoding='utf-8')
 shutil.copy2(project/'src/main/resources/integrations/mythicmobs/BriarqueenRose.yml',plugins/'MythicMobs/mobs/DungeonBriarqueenRose.yml')
else:
 if (plugins/'RoseProbe.jar').exists():shutil.move(plugins/'RoseProbe.jar',root/'RoseProbe.jar.previous')
shutil.copy2(project/'src/main/resources/integrations/mythicmobs/StoneSiteWarden.yml',plugins/'MythicMobs/mobs/DungeonStoneSiteWarden.yml')
shutil.copytree(live/'plugins/CraftEngine/resources/luckblock',plugins/'CraftEngine/resources/luckblock',dirs_exist_ok=True)
shutil.copytree(project/'deploy/warden-spoils/CraftEngine/resources/dungeon_spoils',plugins/'CraftEngine/resources/dungeon_spoils',dirs_exist_ok=True)
(plugins/'CraftEngine/resources/dungeon_spoils/configuration/warden_spoils.yml').unlink(missing_ok=True)
shutil.copytree(live/'plugins/CraftEngine/resources/星芽装备',plugins/'CraftEngine/resources/星芽装备',dirs_exist_ok=True)
shutil.copy2(live/'plugins/ItemHub-1.0-SNAPSHOT.jar',plugins/'ItemHub.jar')
shutil.copy2(live/'plugins/asterstats-1.0.0.jar',plugins/'AsterStats.jar')
(plugins/'ItemHub/items').mkdir(parents=True,exist_ok=True)
shutil.copy2(live/'plugins/ItemHub/items/星芽短剑.yml',plugins/'ItemHub/items/星芽短剑.yml')
(plugins/'AsterStats').mkdir(exist_ok=True)
shutil.copy2(live/'plugins/AsterStats/qualities.yml',plugins/'AsterStats/qualities.yml')
pack_config=plugins/'CraftEngine/config.yml'
pack_archive=plugins/'CraftEngine/generated/dungeon_spoils_test.zip'
pack_archive.unlink(missing_ok=True)
pack_source=pack_config.read_text(encoding='utf-8')
if '    dungeon_spoils_verify:' not in pack_source:
 pack_source=pack_source.replace('  workflows:\n','''  workflows:
    dungeon_spoils_verify:
      steps:
        - type: generate
        - type: zip
          path: "./generated/dungeon_spoils_test.zip"
''',1)
 pack_config.write_text(pack_source,encoding='utf-8')
cp=(root/'java.args').read_text(encoding='utf-8').splitlines()[4].strip(chr(34))
cp+=';'+str(project/'target/Dungeon-0.1.0.jar')+';'+str(plugins/'BetterModel.jar')
for probe in probes:
 classes=root/(probe.lower()+'-classes');classes.mkdir(exist_ok=True)
 subprocess.run([str(java/'javac.exe'),'-encoding','UTF-8','-cp',cp,'-d',str(classes),str(project/('tools/'+probe+'.java'))],check=True)
 with zipfile.ZipFile(plugins/(probe+'.jar'),'w') as z:
  for f in classes.rglob('*.class'):z.write(f,f.relative_to(classes).as_posix())
  z.writestr('plugin.yml',f"name: {probe}\nversion: '1'\nmain: {probe}\napi-version: '1.21.11'\ndepend:\n  - Dungeon\n  - BetterModel\n  - MythicMobs\n  - CraftEngine\n")
p=subprocess.Popen([str(java/'java.exe'),'@java.args'],cwd=root,stdin=subprocess.PIPE,stdout=subprocess.PIPE,stderr=subprocess.STDOUT,text=True,encoding='utf-8',errors='replace',creationflags=subprocess.CREATE_NO_WINDOW)
lines=queue.Queue()
threading.Thread(target=lambda:[lines.put(line) for line in p.stdout],daemon=True).start()
start=time.monotonic();passed=stopping=False;markers=set()
try:
 with (root/'boss-probe.log').open('w',encoding='utf-8') as log:
  while p.poll() is None or not lines.empty():
   try:
    line=lines.get(timeout=.2);log.write(line);log.flush()
    if any(k in line for k in ('Dungeon','BetterModel','BOSS_PROBE','ROSE_PROBE','Exception','ERROR','Done (')):print(line.rstrip(),flush=True)
    if 'Done (' in line:
     p.stdin.write('ce workflow dungeon_spoils_verify\n');p.stdin.flush()
    if 'BOSS_PROBE_PASS' in line:markers.add('BossProbe')
    if 'ROSE_PROBE_PASS' in line:markers.add('RoseProbe')
    passed=all(probe in markers for probe in probes)
    if not stopping and (passed or '_PROBE_FAIL' in line or time.monotonic()-start>180):
     stopping=True;p.stdin.write('stop\n');p.stdin.flush()
   except queue.Empty:pass
   if time.monotonic()-start>215:p.kill();break
finally:
 if p.poll() is None:p.kill()
result={'passed':passed,'probes':sorted(markers),'exit_code':p.wait(),'boundary':'isolated server APIs/model generation plus explicit Player proxy; no network client or Folia runtime test'}
if passed and pack_archive.is_file():
 files=('assets/dungeon/textures/item/warden_spoils.png','assets/dungeon/items/warden_spoils.json','assets/dungeon/models/item/warden_spoils.json')
 with zipfile.ZipFile(pack_archive) as pack:
  result['pack_texture']=all(pack.read(name)==(plugins/'CraftEngine/resources/dungeon_spoils/resourcepack'/name).read_bytes() for name in files)
else:result['pack_texture']=False
(root/'boss-probe-result.json').write_text(json.dumps(result),encoding='utf-8');print(result,flush=True)
if '--migrate-lang' in sys.argv:
 assert {p.name for p in lang.glob('*.yml')}=={'zh_CN.yml'}
 saved=plugins/'Dungeon/backups/language-v2'
 for p in (live/'plugins/Dungeon/lang').glob('*zh_CN.yml'):
  assert (saved/p.name).read_bytes()==p.read_bytes(),f'Backup mismatch for {p.name}'
if not passed or result['exit_code']!=0 or not result['pack_texture']:raise SystemExit(1)
