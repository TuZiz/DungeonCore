"""Install MM-owned boss model skill and impact settings with guarded, backed-up patches."""
from pathlib import Path
import base64, copy, hashlib, json, os, re, shutil, subprocess, uuid
import yaml

root=Path(__file__).resolve().parents[1]
live=Path('C:/Users/32633/Desktop/leaf')
identifier='boss-mm-impact-'+uuid.uuid4().hex
stage='D:/Purpur-deploy-staging/'+identifier
host='LaoZong@119.188.247.168'
opts=['-o','BatchMode=yes','-o','IdentitiesOnly=yes','-o','ConnectTimeout=15','-i','C:/Users/32633/.ssh/id_ed25519_fishbot_deploy']
def sha(data):return hashlib.sha256(data).hexdigest()
def remote(script):
    encoded=base64.b64encode(("$ErrorActionPreference='Stop';[Console]::OutputEncoding=[Text.UTF8Encoding]::new($false);"+script).encode('utf-16le')).decode()
    result=subprocess.run(['ssh','-p','2022',*opts,host,'powershell.exe','-NoProfile','-NonInteractive','-EncodedCommand',encoded],capture_output=True,text=True,encoding='utf-8',timeout=60)
    if result.returncode:raise RuntimeError(result.stdout+result.stderr)
    return result.stdout.strip()
def patch(data,name):
    text=data.decode('utf-8-sig');before=yaml.safe_load(text);expected=copy.deepcopy(before)
    nl='\r\n' if '\r\n' in text else '\n'
    if name.startswith('MythicMobs/'):
        defaults=yaml.safe_load((root/'src/main/resources/integrations/mythicmobs/StoneSiteWarden.yml').read_text(encoding='utf-8'))['石址守望者']
        boss=expected['石址守望者'];boss['Options']['KnockbackResistance']=defaults['Options']['KnockbackResistance']
        text,count=re.subn(r'(?m)^    KnockbackResistance:[^\r\n]*',lambda _: '    KnockbackResistance: 0.75',text)
        if count!=1:raise RuntimeError('Missing or ambiguous KnockbackResistance')
        skill=defaults['Skills'][0]
        existing=boss.setdefault('Skills',[])
        if skill not in existing:
            if any('bm:model' in value or 'model{' in value for value in existing):raise RuntimeError('Existing model skill differs; retained for manual review')
            existing.append(skill)
            line="    - '"+skill.replace("'","''")+"'"+nl
            if 'Skills' in before['石址守望者']:
                text,count=re.subn(r'(?ms)^  Skills:\r?\n.*?(?=^\S|^  [^\s#]|\Z)',lambda m:m[0].rstrip('\r\n')+nl+line,text)
                if count!=1:raise RuntimeError('Ambiguous Skills')
            else:text=text.rstrip('\r\n')+nl+'  # MM 创建模型，Dungeon 复用同一个模型控制公共首领战斗。'+nl+'  Skills:'+nl+line
    else:
        defaults=yaml.safe_load((root/'src/main/resources/dungeons/lost_altar/boss.yml').read_text(encoding='utf-8'))['impact']
        if 'impact' not in expected:
            expected['impact']=defaults
            text=text.rstrip('\r\n')+nl+'# 受击击退：高抗性、短暂保留冲量；施法期间不击退。'+nl+'impact:'+nl
            text+=''.join('  '+key+': '+str(value)+nl for key,value in defaults.items())
    if yaml.safe_load(text)!=expected:raise RuntimeError('Unexpected semantic change: '+name)
    return text.encode('utf-8')

records=[];prepared=[]
for name in ['MythicMobs/mobs/DungeonStoneSiteWarden.yml','Dungeon/dungeons/lost_altar/boss.yml']:
    target='D:/Purpur-server/plugins/'+name
    old=base64.b64decode(remote("[Convert]::ToBase64String([IO.File]::ReadAllBytes('"+target+"'))"))
    local=live/'plugins'/name;local_old=local.read_bytes()
    prepared.append((name,target,old,patch(old,name),local,local_old,patch(local_old,name)))
remote("New-Item -ItemType Directory -Path '"+stage+"' | Out-Null")
for index,(name,target,old,data,local,local_old,local_data) in enumerate(prepared):
    out=root/f'target/boss-mm-impact-{index}.yml';out.write_bytes(data)
    subprocess.run(['scp','-P','2022',*opts,str(out),host+':'+stage+f'/{index}.yml'],check=True,timeout=60)
    backup='D:/Purpur-deploy-backups/'+identifier+'/'+name
    script=f"""$ErrorActionPreference='Stop'
$target='{target}'
$source='{stage}/{index}.yml'
$backup='{backup}'
if((Get-FileHash -LiteralPath $source -Algorithm SHA256).Hash.ToLower() -ne '{sha(data)}'){{throw 'Upload hash mismatch'}}
if((Get-FileHash -LiteralPath $target -Algorithm SHA256).Hash.ToLower() -ne '{sha(old)}'){{throw 'Configuration changed since snapshot; retained'}}
New-Item -ItemType Directory -Path (Split-Path -Parent $backup) -Force | Out-Null
[IO.File]::Copy($target,$backup,$false)
$temporary=$target+'.{identifier}.tmp'
[IO.File]::Copy($source,$temporary,$false)
[IO.File]::Replace($temporary,$target,$backup+'.atomic')
if((Get-FileHash -LiteralPath $target -Algorithm SHA256).Hash.ToLower() -ne '{sha(data)}'){{throw 'Installed hash mismatch'}}
"""
    install=root/f'target/boss-mm-impact-{index}.ps1';install.write_text(script,encoding='utf-8-sig')
    subprocess.run(['scp','-P','2022',*opts,str(install),host+':'+stage+f'/{index}.ps1'],check=True,timeout=60)
    subprocess.run(['ssh','-p','2022',*opts,host,'powershell.exe','-NoProfile','-NonInteractive','-File',stage+f'/{index}.ps1'],check=True,timeout=60)
    if local.read_bytes()!=local_old:raise RuntimeError('Local config changed since snapshot; retained')
    saved=live/'backups'/identifier/name;saved.parent.mkdir(parents=True,exist_ok=True);shutil.copy2(local,saved)
    temporary=local.with_name(local.name+'.'+identifier+'.tmp');temporary.write_bytes(local_data);os.replace(temporary,local)
    records.append({'cloud':target,'backup':backup,'sha256':sha(data),'local':str(local)})
jar=root/'target/Dungeon-0.1.0.jar';pending=live/'plugins/update/Dungeon-0.1.0.jar'
pending.parent.mkdir(exist_ok=True)
if pending.exists():
    saved=live/'backups'/identifier/'Dungeon-0.1.0.jar';saved.parent.mkdir(parents=True,exist_ok=True);shutil.copy2(pending,saved)
temporary=pending.with_name(pending.name+'.'+identifier+'.tmp');shutil.copy2(jar,temporary);os.replace(temporary,pending)
assert sha(pending.read_bytes())==sha(jar.read_bytes())
record={'resources':records,'jar_sha256':sha(jar.read_bytes()),'runtime_verified':False}
(root/'target/boss-mm-impact-delivery.json').write_text(json.dumps(record,ensure_ascii=False,indent=2),encoding='utf-8')
print(json.dumps(record,ensure_ascii=False,indent=2))
