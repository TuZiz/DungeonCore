"""Reuse the isolated MM/CE Leaf fixture, never the live server process or world."""
from pathlib import Path
import json, queue, shutil, subprocess, threading, time, zipfile, sys
sys.stdout.reconfigure(encoding="utf-8", errors="replace")

project = Path(__file__).resolve().parents[1]
root = project / 'target/coin-smoke263'
live = Path('C:/Users/32633/Desktop/leaf')
java = Path('C:/Program Files/Zulu/zulu-25/bin')
if not (root / 'java.args').exists():
    raise SystemExit('Existing isolated coin-smoke263 fixture required; do not run against a live server')
plugins = root / 'plugins'
shutil.copy2(project / 'target/Dungeon-0.1.0.jar', plugins / 'Dungeon.jar')
old = plugins / 'AltarProbe.jar'
if old.exists():
    old.rename(root / 'AltarProbe.previous.jar')
for name in ('farming.yml', 'spawning.yml'):
    source = project / 'src/main/resources/dungeons/lost_altar' / name
    text = source.read_text(encoding='utf-8')
    if name == 'farming.yml':
        text = text.replace('enabled: false', 'enabled: true')
    (plugins / 'Dungeon/dungeons/lost_altar' / name).write_text(text, encoding='utf-8')
shutil.copy2(project / 'src/main/resources/drop-protection.yml', plugins / 'Dungeon/drop-protection.yml')
for category in ('droptables', 'mobs', 'skills'):
    shutil.copytree(project / 'deploy/lost_altar/MythicMobs' / category,
                    plugins / 'MythicMobs' / category, dirs_exist_ok=True)
for pack in ('genlite', '货币'):
    shutil.copytree(live / 'plugins/CraftEngine/resources' / pack,
                    plugins / 'CraftEngine/resources' / pack, dirs_exist_ok=True)
cp = (root / 'java.args').read_text(encoding='utf-8').splitlines()[4].strip(chr(34))
cp += ';' + str(project / 'target/Dungeon-0.1.0.jar')
cp += ';' + str(plugins / 'MythicMobs-5.14.0.jar')
cp += ';' + str(plugins / 'craft-engine-paper-plugin-26.9.2.jar')
classes = root / 'launch-probe-classes'
classes.mkdir(exist_ok=True)
subprocess.run([str(java / 'javac.exe'), '-encoding', 'UTF-8', '-cp', cp, '-d', str(classes),
                str(project / 'tools/LaunchProbe.java')], check=True)
with zipfile.ZipFile(plugins / 'LaunchProbe.jar', 'w') as jar:
    for path in classes.rglob('*.class'):
        jar.write(path, path.relative_to(classes).as_posix())
    jar.writestr('plugin.yml', "name: LaunchProbe\nversion: '1'\nmain: LaunchProbe\napi-version: '1.21.11'\ndepend:\n  - Dungeon\n  - MythicMobs\n  - CraftEngine\n")
p = subprocess.Popen([str(java / 'java.exe'), '@java.args'], cwd=root, stdin=subprocess.PIPE,
                     stdout=subprocess.PIPE, stderr=subprocess.STDOUT, text=True,
                     encoding='utf-8', errors='replace', creationflags=subprocess.CREATE_NO_WINDOW)
lines = queue.Queue()
threading.Thread(target=lambda: [lines.put(line) for line in p.stdout], daemon=True).start()
start = time.monotonic()
passed = stopping = False
try:
    with (root / 'launch-probe.log').open('w', encoding='utf-8') as log:
        while p.poll() is None or not lines.empty():
            try:
                line = lines.get(timeout=.2)
                log.write(line); log.flush()
                if any(key in line for key in ('Dungeon', 'LAUNCH_PROBE', 'Exception', 'ERROR', 'Done (')):
                    print(line.rstrip(), flush=True)
                if 'LAUNCH_PROBE_PASS' in line:
                    passed = True
                if not stopping and (passed or 'LAUNCH_PROBE_FAIL' in line or time.monotonic()-start > 130):
                    stopping = True; p.stdin.write('stop\n'); p.stdin.flush()
            except queue.Empty:
                pass
            if time.monotonic()-start > 165:
                p.kill(); break
finally:
    if p.poll() is None:
        p.kill()
result = dict(passed=passed, exit_code=p.wait())
(root / 'launch-result.json').write_text(json.dumps(result), encoding='utf-8')
print(result, flush=True)
if not passed or result['exit_code'] != 0:
    raise SystemExit(1)
