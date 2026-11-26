"""Patch only floor height and spawn titles; back up, hash-check and never reload servers."""
from pathlib import Path
import base64, copy, hashlib, json, os, re, shutil, subprocess, uuid
import yaml

root=Path(__file__).resolve().parents[1]
live=Path('C:/Users/32633/Desktop/leaf')
identifier='boss-spawn-notice-'+uuid.uuid4().hex
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
    defaults=yaml.safe_load((root/'src/main/resources'/name).read_text(encoding='utf-8'))
    keys=['height-offset'] if name=='boss-hologram.yml' else ['spawn-title','spawn-subtitle']
    nl='\r\n' if '\r\n' in text else '\n'
    for key in keys:
        value=defaults[key];expected[key]=value
        scalar=str(value) if isinstance(value,(int,float)) else "'"+value.replace("'","''")+"'"
        line=key+': '+scalar
        pattern=r'(?m)^'+re.escape(key)+r':[^\r\n]*'
        if key in before:
            text,count=re.subn(pattern,lambda _:line,text)
            if count!=1:raise RuntimeError('Ambiguous key: '+key)
        else:text=text.rstrip('\r\n')+nl+line+nl
    if name=='boss-hologram.yml':
        text=text.replace('# 相对首领脚部的高度（格），避免遮住约 3.8 格高的模型。','# 相对首领脚部的高度（格，0–16）；默认贴近圆台地面。')
    if yaml.safe_load(text)!=expected:raise RuntimeError('Unexpected semantic change: '+name)
    return text.encode('utf-8')

records=[];prepared=[]
for name in ['boss-hologram.yml','lang/boss_zh_CN.yml']:
    target='D:/Purpur-server/plugins/Dungeon/'+name
    old=base64.b64decode(remote("[Convert]::ToBase64String([IO.File]::ReadAllBytes('"+target+"'))"))
    local=live/'plugins/Dungeon'/name;local_old=local.read_bytes()
    prepared.append((name,target,old,patch(old,name),local,local_old,patch(local_old,name)))
remote("New-Item -ItemType Directory -Path '"+stage+"' | Out-Null")
for index,(name,target,old,data,local,local_old,local_data) in enumerate(prepared):
    out=root/f'target/boss-notice-{index}.yml';out.write_bytes(data)
    subprocess.run(['scp','-P','2022',*opts,str(out),host+':'+stage+f'/{index}.yml'],check=True,timeout=60)
    backup='D:/Purpur-deploy-backups/'+identifier+'/Dungeon/'+name
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
    install=root/f'target/boss-notice-{index}.ps1';install.write_text(script,encoding='utf-8-sig')
    subprocess.run(['scp','-P','2022',*opts,str(install),host+':'+stage+f'/{index}.ps1'],check=True,timeout=60)
    subprocess.run(['ssh','-p','2022',*opts,host,'powershell.exe','-NoProfile','-NonInteractive','-File',stage+f'/{index}.ps1'],check=True,timeout=60)
    if local.read_bytes()!=local_old:raise RuntimeError('Local config changed since snapshot; retained')
    saved=live/'backups'/identifier/'Dungeon'/name;saved.parent.mkdir(parents=True,exist_ok=True);shutil.copy2(local,saved)
    temporary=local.with_name(local.name+'.'+identifier+'.tmp');temporary.write_bytes(local_data);os.replace(temporary,local)
    records.append({'cloud':target,'backup':backup,'sha256':sha(data),'local':str(local)})
jar=root/'target/Dungeon-0.1.0.jar';pending=live/'plugins/update/Dungeon-0.1.0.jar'
pending.parent.mkdir(exist_ok=True)
if pending.exists():
    saved=live/'backups'/identifier/'Dungeon-0.1.0.jar';saved.parent.mkdir(parents=True,exist_ok=True);shutil.copy2(pending,saved)
temporary=pending.with_name(pending.name+'.'+identifier+'.tmp');shutil.copy2(jar,temporary);os.replace(temporary,pending)
assert sha(pending.read_bytes())==sha(jar.read_bytes())
record={'resources':records,'jar_sha256':sha(jar.read_bytes()),'runtime_verified':False}
(root/'target/boss-notice-delivery.json').write_text(json.dumps(record,ensure_ascii=False,indent=2),encoding='utf-8')
print(json.dumps(record,ensure_ascii=False,indent=2))
