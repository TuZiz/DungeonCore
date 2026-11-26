"""Back up and patch only the boss hologram text, then stage the already-built local JAR.

Does not control/reload either server, change models or replace generated resource packs.
"""
from pathlib import Path
import base64,copy,hashlib,json,os,re,shutil,subprocess,uuid
import yaml

root=Path(__file__).resolve().parents[1]
live=Path('C:/Users/32633/Desktop/leaf')
relative='Dungeon/lang/boss_zh_CN.yml'
identifier='boss-visual-'+uuid.uuid4().hex
stage='D:/Purpur-deploy-staging/'+identifier
host='LaoZong@119.188.247.168'
opts=['-o','BatchMode=yes','-o','IdentitiesOnly=yes','-o','ConnectTimeout=15','-i','C:/Users/32633/.ssh/id_ed25519_fishbot_deploy']
def sha(data):return hashlib.sha256(data).hexdigest()
def remote(script):
    encoded=base64.b64encode(("$ErrorActionPreference='Stop';[Console]::OutputEncoding=[Text.UTF8Encoding]::new($false);"+script).encode('utf-16le')).decode()
    r=subprocess.run(['ssh','-p','2022',*opts,host,'powershell.exe','-NoProfile','-NonInteractive','-EncodedCommand',encoded],capture_output=True,text=True,encoding='utf-8',timeout=60)
    if r.returncode:raise RuntimeError(r.stdout+r.stderr)
    return r.stdout.strip()
def patch(data):
    text=data.decode('utf-8-sig');before=yaml.safe_load(text);expected=copy.deepcopy(before)
    desired=yaml.safe_load((root/'src/main/resources/lang/boss_zh_CN.yml').read_text(encoding='utf-8'))['hologram']
    nl='\r\n' if '\r\n' in text else '\n'
    match=re.search(r'(?m)^hologram:\r?\n',text)
    if not match:raise RuntimeError('Existing hologram section missing; no automatic overwrite')
    prefix=text[:match.end()];section=text[match.end():]
    lines=re.search(r'(?ms)^  lines:\r?\n(?P<body>.*?)(?=^  [^ #\r\n]|\Z)',section)
    if not lines:raise RuntimeError('Existing hologram lines missing')
    comments=''.join(line+nl for line in lines['body'].splitlines() if line.lstrip().startswith('#'))
    replacement='  lines:'+nl+comments+''.join("    - '"+value.replace("'","''")+"'"+nl for value in desired['lines'])
    section=section[:lines.start()]+replacement+section[lines.end():]
    expected['hologram']['lines']=desired['lines']
    for key,value in desired['status'].items():
        section,count=re.subn(r'(?m)^    '+re.escape(key)+r':[^\r\n]*',lambda _:"    "+key+": '"+value.replace("'","''")+"'",section)
        if count!=1:raise RuntimeError('Missing/ambiguous hologram status: '+key)
        expected['hologram']['status'][key]=value
    text=prefix+section
    if yaml.safe_load(text)!=expected:raise RuntimeError('Unexpected semantic language changes')
    return text.encode('utf-8')

target='D:/Purpur-server/plugins/'+relative
cloud=remote("[Convert]::ToBase64String([IO.File]::ReadAllBytes('"+target+"'))")
old=base64.b64decode(cloud);data=patch(old)
local_path=live/'plugins'/relative
local_old=local_path.read_bytes();local_data=patch(local_old)
out=root/'target/boss-visual-language.yml';out.write_bytes(data)
remote("New-Item -ItemType Directory -Path '"+stage+"' | Out-Null")
subprocess.run(['scp','-P','2022',*opts,str(out),host+':'+stage+'/language.yml'],check=True,timeout=60)
script=r'''
$ErrorActionPreference='Stop'
[Console]::OutputEncoding=[Text.UTF8Encoding]::new($false)
$target='TARGET'
$source='STAGE/language.yml'
$backup='D:/Purpur-deploy-backups/IDENTIFIER/Dungeon/lang/boss_zh_CN.yml'
if((Get-FileHash -LiteralPath $source -Algorithm SHA256).Hash.ToLower() -ne 'NEW'){throw 'Upload hash mismatch'}
if((Get-FileHash -LiteralPath $target -Algorithm SHA256).Hash.ToLower() -ne 'OLD'){throw 'Language changed since snapshot; retained'}
New-Item -ItemType Directory -Path (Split-Path -Parent $backup) -Force | Out-Null
[IO.File]::Copy($target,$backup,$false)
$temporary=$target+'.IDENTIFIER.tmp'
[IO.File]::Copy($source,$temporary,$false)
[IO.File]::Replace($temporary,$target,$backup+'.atomic')
if((Get-FileHash -LiteralPath $target -Algorithm SHA256).Hash.ToLower() -ne 'NEW'){throw 'Installed language hash mismatch'}
[pscustomobject]@{path=$target;sha256='NEW';backup=$backup;runtime_verified=$false} | ConvertTo-Json -Compress
'''.replace('TARGET',target).replace('STAGE',stage).replace('IDENTIFIER',identifier).replace('NEW',sha(data)).replace('OLD',sha(old))
install=root/'target/boss-visual-install.ps1';install.write_text(script,encoding='utf-8-sig')
subprocess.run(['scp','-P','2022',*opts,str(install),host+':'+stage+'/install.ps1'],check=True,timeout=60)
r=subprocess.run(['ssh','-p','2022',*opts,host,'powershell.exe','-NoProfile','-NonInteractive','-File',stage+'/install.ps1'],capture_output=True,text=True,encoding='utf-8',timeout=60)
if r.returncode:raise RuntimeError(r.stdout+r.stderr)
cloud_result=json.loads(r.stdout)
backup=live/'backups'/identifier
def replace_local(path,data,relative):
    if path.exists():
        saved=backup/relative;saved.parent.mkdir(parents=True,exist_ok=True);shutil.copy2(path,saved)
    temporary=path.with_name(path.name+'.'+identifier+'.tmp');path.parent.mkdir(parents=True,exist_ok=True)
    temporary.write_bytes(data);os.replace(temporary,path)
    assert sha(path.read_bytes())==sha(data)
if local_path.read_bytes()!=local_old:raise RuntimeError('Local language changed since snapshot; retained')
replace_local(local_path,local_data,relative)
jar=root/'target/Dungeon-0.1.0.jar';pending=live/'plugins/update/Dungeon-0.1.0.jar'
replace_local(pending,jar.read_bytes(),'update/Dungeon-0.1.0.jar')
record={'cloud_language':cloud_result,'local_language':str(local_path),'local_backup':str(backup),'local_pending_jar':str(pending),'jar_sha256':sha(jar.read_bytes()),'runtime_verified':False}
(root/'target/boss-visual-resources.json').write_text(json.dumps(record,ensure_ascii=False,indent=2),encoding='utf-8')
print(json.dumps(record,ensure_ascii=False,indent=2))
