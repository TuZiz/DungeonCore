from pathlib import Path
import sys,io,zlib,struct,zipfile,hashlib,shutil,datetime,yaml,nbtlib
from map_blocks import root as source,block
base=Path('C:/Users/32633/Desktop/leaf');mm=base/'plugins/MythicMobs';dungeon=base/'plugins/Dungeon'
if (dungeon/'maps/lost_altar/manifest.yml').exists():
 raise SystemExit('Lost Altar already installed; edit existing configuration instead of reinstalling')
backup=base/'backups'/('lost-altar-'+datetime.datetime.now().strftime('%Y%m%d-%H%M%S'));backup.mkdir(parents=True)
shutil.copytree(mm,backup/'MythicMobs')
# SQLite online consistent backup instead of copying live WAL files.
import sqlite3
with sqlite3.connect(dungeon/'dungeon.db') as db,sqlite3.connect(backup/'dungeon.db') as dst:db.backup(dst)
shutil.copytree(dungeon/'dungeons',backup/'Dungeon-dungeons')
for f in ('config.yml','lobby.yml'):
 shutil.copy2(dungeon/f,backup/f)
for folder in ('mobs','skills','droptables','items','randomspawns','dialogs'):
 for f in (mm/folder).glob('*.yml'):
  if f.name.startswith('Example') or f.name=='VanillaMobs.yml':f.write_text('# 默认示例已停用；保留空文件防止重建。\n',encoding='utf-8')
# Archive unused stage-based example; explicit marker prevents reinstallation.
example=dungeon/'dungeons/example'
if example.exists():shutil.move(str(example),str(backup/'retired-example'))
(dungeon/'.no-example').write_text('Example archived for permanent farming setup\n',encoding='utf-8')
(folder:=dungeon/'maps/lost_altar').mkdir(parents=True,exist_ok=True)
files={};stats={'chunks':0,'disabled_blocks':0,'emptied_containers':0}
def clean_region(raw):
 out=bytearray(8192);out[4096:8192]=raw[4096:8192]
 for i in range(1024):
  o=int.from_bytes(raw[i*4:i*4+3],'big')*4096
  if not o:continue
  n=int.from_bytes(raw[o:o+4],'big');assert raw[o+4]==2
  c=nbtlib.File.parse(io.BytesIO(zlib.decompress(raw[o+5:o+4+n])));stats['chunks']+=1
  for sec in c.get('sections',[]):
   for p in sec.get('block_states',{}).get('palette',[]):
    if str(p['Name']) in ('minecraft:spawner','minecraft:trial_spawner','minecraft:command_block','minecraft:chain_command_block','minecraft:repeating_command_block'):
     p.clear();p['Name']=nbtlib.String('minecraft:mossy_stone_bricks');stats['disabled_blocks']+=1
  entities=[]
  for e in c.get('block_entities',[]):
   if str(e.get('id','')) in ('minecraft:command_block','minecraft:mob_spawner','minecraft:trial_spawner'):continue
   if 'Items' in e or 'LootTable' in e:
    e.pop('LootTable',None);e.pop('LootTableSeed',None);e['Items']=nbtlib.List[nbtlib.Compound]();stats['emptied_containers']+=1
   entities.append(e)
  c['block_entities']=nbtlib.List[nbtlib.Compound](entities)
  stream=io.BytesIO();c.write(stream);payload=zlib.compress(stream.getvalue());packet=struct.pack('>I',len(payload)+1)+b'\x02'+payload
  size=(len(packet)+4095)//4096;offset=len(out)//4096;out[i*4:i*4+4]=offset.to_bytes(3,'big')+bytes([size]);out.extend(packet);out.extend(bytes(size*4096-len(packet)))
 return bytes(out)
with zipfile.ZipFile(folder/'world.zip','w',zipfile.ZIP_DEFLATED) as archive:
 dat=(source/'level.dat').read_bytes();archive.writestr('level.dat',dat);files['level.dat']=hashlib.sha256(dat).hexdigest()
 for f in (source/'dimensions/minecraft/overworld/region').glob('*.mca'):
  if f.stat().st_size==0:continue
  data=clean_region(f.read_bytes());name='dimensions/minecraft/overworld/region/'+f.name
  archive.writestr(name,data);files[name]=hashlib.sha256(data).hexdigest()
print('Map sanitized:',stats,flush=True)
(folder/'manifest.yml').write_text(yaml.safe_dump({'schema-version':1,'data-version':4903,'files':[{'path':k,'sha256':v} for k,v in files.items()]},sort_keys=False,allow_unicode=True),encoding='utf-8')
# Mob names and CE IDs are independently stable.
mobs=[('AltarMossWalker','苔痕行尸','ZOMBIE',28,3,'moss'),('AltarTideRemnant','潮蚀遗民','DROWNED',32,3,'slow'),('AltarBoneArcher','遗坛弓卫','SKELETON',24,3,'archer'),('AltarVineSpider','藤影猎蛛','SPIDER',22,3,'slow'),('AltarStoneGuard','石誓守卫','HUSK',70,5,'slam'),('AltarRuneKeeper','符文守祭者','WITHER_SKELETON',90,6,'slam'),('AltarKillerRabbit','杀手兔','RABBIT',60,6,'rabbit')]
lines=['# 遗落圣坛怪物配置 v1；生命/伤害为基础值，掉落引用独立掉落表。']
for ident,name,kind,hp,dmg,skill in mobs:
 elite=ident in ('AltarStoneGuard','AltarRuneKeeper','AltarKillerRabbit')
 lines += [f'{ident}:',f'  Type: {kind}',f"  Display: '<#A8D6AA>{name}'",f'  Health: {hp}',f'  Damage: {dmg}','  Options:','    PreventOtherDrops: true','    PreventRandomEquipment: true','    PreventSunburn: true','    PreventItemPickup: true','    PreventTransformation: true','    Despawn: true','    AlwaysShowName: false',f'    MovementSpeed: {0.23 if elite else 0.25}','    FollowRange: 20','    Silent: false','  Drops:',f'    - Altar{"Elite" if elite else "Common"}Drops']
 if kind=='RABBIT':
  lines.insert(len(lines)-3,'    IsKillerBunny: true')
 if kind=='SKELETON':lines+=['  Equipment:','    - BOW HAND']
 if skill in ('slow','slam'):
  lines+=['  Skills:',f'    - skill{{s=Altar{"Slam" if skill=="slam" else "Chill"}}} @target ~onAttack 0.25']
(mm/'mobs/遗落圣坛.yml').write_text('\n'.join(lines)+'\n',encoding='utf-8')
(mm/'skills/遗落圣坛.yml').write_text('''# 遗落圣坛技能 v1；无地形破坏、无召唤、无模型。
AltarChill:
  Cooldown: 8
  Skills:
    - effect:particles{p=spore_blossom_air;amount=8;hS=0.3;vS=0.5} @self
    - potion{type=SLOW;duration=40;level=0} @target
AltarSlam:
  Cooldown: 12
  Skills:
    - effect:particles{p=crit;amount=20;hS=1.2;vS=0.1} @self
    - sound{s=block.stone.break;v=0.6;p=0.7} @self
    - delay 16
    - damage{amount=3} @PlayersInRadius{r=2.5}
''',encoding='utf-8')
(mm/'droptables/遗落圣坛.yml').write_text('''# 遗落圣坛掉落 v1；每项独立概率，数量在概率前。
# dungeonce由Dungeon桥接CraftEngine真实物品；非玩家击杀不产出。
# 玩家击杀必掉 1-3 个铜币；精英额外 10% 概率掉 1 个银币。
# 初级升级单元：普通7%，精英30%掉1-2个；本地图不产出机器和高阶材料。
AltarCommonDrops:
  Drops:
    - dungeonce{id=genlite:basic_upgrade_unit} 1 0.07
    - dungeonce{id=genlite:mechanical_parts} 1 0.04
    - dungeonce{id=genlite:copper_coil} 1 0.04
    - 'dungeonce{id=currency:copper_coin} 1to3 1.0'
AltarEliteDrops:
  Drops:
    - dungeonce{id=genlite:basic_upgrade_unit} 1to2 0.30
    - dungeonce{id=genlite:gear} 1 0.08
    - dungeonce{id=genlite:energy_cell} 1 0.04
    - 'dungeonce{id=currency:copper_coin} 1to3 1.0'
    - 'dungeonce{id=currency:silver_coin} 1 0.10'
''',encoding='utf-8')
# Dungeon owns all nearby spawning; no fixed MythicMobs spawners are created.
(dungeon/'dungeons/lost_altar').mkdir(parents=True,exist_ok=True)
(dungeon/'dungeons/lost_altar/farming.yml').write_text("# 常驻共享刷怪地图；修改后完整重启。出生点已核对实心地面和两格净空。\nschema-version: 1\nenabled: true\nid: 'lost_altar'\nworld: 'lost_altar'\nkeep-inventory: true\nspawn:\n  x: 181.771\n  y: 8.0\n  z: 11.174\n  yaw: -173.7\n  pitch: -7.2\n",encoding='utf-8')
shutil.copy2(Path(__file__).resolve().parents[1]/'src/main/resources/lang/farming_zh_CN.yml',dungeon/'lang/farming_zh_CN.yml')
print('BACKUP',backup,flush=True)
(backup/'install-report.yml').write_text(yaml.safe_dump({'backup':str(backup),'map':stats,'files':len(files)},allow_unicode=True),encoding='utf-8')

for name in ('spawning.yml','elite-hud.yml','README.md'):
 shutil.copy2(Path(__file__).resolve().parents[1]/'src/main/resources/dungeons/lost_altar'/name,dungeon/'dungeons/lost_altar'/name)
