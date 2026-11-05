"""Back up and stage the verified compass fix for the user-specified server."""
from pathlib import Path
from datetime import datetime
import hashlib
import json
import os
import shutil
import zipfile

project = Path(__file__).resolve().parents[1]
server = Path('C:/Users/32633/Desktop/leaf')
result = json.loads((project/'target/compass-smoke/result.json').read_text(encoding='utf-8'))
assert result['passed'] and result['pack_generated'] and result['bitmap_frames_at_height_20'] == 32
jar = project/'target/Dungeon-0.1.0.jar'
with zipfile.ZipFile(jar) as archive:
    assert not any(name.startswith(('org/bukkit/', 'io/papermc/', 'org/junit/')) for name in archive.namelist())
    assert 'dev/dungeon/farming/SpawnGeometry.class' in archive.namelist()
installed = list((server/'plugins').glob('Dungeon*.jar'))
assert len(installed) == 1, installed
backup = server/'backups'/('dungeon-compass-'+datetime.now().strftime('%Y%m%d-%H%M%S'))
backup.mkdir(parents=True)
shutil.copy2(installed[0], backup/installed[0].name)

# Bukkit consumes this update folder at the next startup; leave the running JAR intact.
staged = server/'plugins/update'/installed[0].name
copies = [(jar, staged), (project/'deploy/xerr/dungeon_elite.yml', server/'plugins/Xerr/core/layout/dungeon_elite.yml')]
assets = project/'deploy/xerr/assets'
copies += [(source, server/'plugins/Xerr/core/assets'/source.relative_to(assets)) for source in assets.rglob('*') if source.is_file()]
copies += [(project/'src/main/resources/dungeons/lost_altar/README.md', server/'plugins/Dungeon/dungeons/lost_altar/README.md')]
for source, target in copies:
    if target.exists():
        saved = backup/'previous'/target.relative_to(server)
        saved.parent.mkdir(parents=True, exist_ok=True)
        shutil.copy2(target, saved)

manifest = []
for source, target in copies:
    target.parent.mkdir(parents=True, exist_ok=True)
    temporary = target.with_name(target.name+'.compass-tmp')
    shutil.copy2(source, temporary)
    os.replace(temporary, target)
    digest = hashlib.sha256(source.read_bytes()).hexdigest()
    assert hashlib.sha256(target.read_bytes()).hexdigest() == digest
    manifest.append({'path': str(target.relative_to(server)), 'sha256': digest})
(backup/'installation.json').write_text(json.dumps({'state':'staged-awaiting-restart', 'files':manifest}, indent=2), encoding='utf-8')
print(json.dumps({'backup':str(backup), 'staged_jar':str(staged), 'files':len(copies), 'state':'staged-awaiting-restart'}))
