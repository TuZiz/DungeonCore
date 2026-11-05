"""Isolated Leaf startup test; reads live libraries/map only, writes under target/."""
from pathlib import Path
import json, queue, shutil, subprocess, threading, time, zipfile, sys

sys.stdout.reconfigure(encoding='utf-8', errors='replace')

project = Path(__file__).resolve().parents[1]
live = Path('C:/Users/32633/Desktop/leaf')
root = project / 'target/farm-layout-smoke'
root.mkdir(parents=True, exist_ok=True)
with zipfile.ZipFile(live / 'leaf-26.3-4.jar') as jar:
    libs = [str(live / 'libraries' / line.split('\t')[2]) for line in jar.read('META-INF/libraries.list').decode().splitlines()]
classpath = ';'.join([str(live / 'versions/26.3/leaf-26.3.jar')] + libs).replace('\\', '/')
(root / 'java.args').write_text('-Xms512M\n-Xmx1536M\n-Dfile.encoding=UTF-8\n-cp\n"' + classpath + '"\norg.bukkit.craftbukkit.Main\n--nogui\n', encoding='utf-8')
shutil.copy2(live / 'eula.txt', root / 'eula.txt')
(root / 'server.properties').write_text('server-ip=127.0.0.1\nserver-port=0\nonline-mode=false\nenable-query=false\nenable-rcon=false\nlevel-name=smoke-base\nlevel-type=minecraft:flat\ngenerator-settings={"layers":[],"biome":"minecraft:the_void"}\nallow-nether=false\nview-distance=2\nsimulation-distance=2\n', encoding='utf-8')
(root / 'bukkit.yml').write_text('settings:\n  allow-end: false\n', encoding='utf-8')
plugins = root / 'plugins'
plugins.mkdir(exist_ok=True)
shutil.copy2(project / 'target/Dungeon-0.1.0.jar', plugins / 'Dungeon.jar')
data = plugins / 'Dungeon'
data.mkdir(exist_ok=True)
shutil.copytree(live / 'plugins/Dungeon/maps/lost_altar', data / 'maps/lost_altar', dirs_exist_ok=True)
legacy = live / 'plugins/Dungeon/farming.yml'
source = legacy if legacy.exists() else live / 'plugins/Dungeon/dungeons/lost_altar/farming.yml'
original = source.read_bytes()
if not (data / 'dungeons/lost_altar/farming.yml').exists():
    (data / 'farming.yml').write_bytes(original)
(data / '.no-example').touch()
(data / 'lobby.yml').write_text("schema-version: 1\nenabled: false\nworld: 'Starfall_Dungeon_Hub'\nimport-bundled-map: false\nauto-load: false\nspawn:\n  x: 0.5\n  y: 101.0\n  z: 0.5\n  yaw: 0\n  pitch: 0\n", encoding='utf-8')
process = subprocess.Popen(['C:/Program Files/Zulu/zulu-25/bin/java.exe', '@java.args'], cwd=root,
    stdin=subprocess.PIPE, stdout=subprocess.PIPE, stderr=subprocess.STDOUT,
    text=True, encoding='utf-8', errors='replace', creationflags=subprocess.CREATE_NO_WINDOW)
events = queue.Queue()
threading.Thread(target=lambda: [events.put(line) for line in process.stdout], daemon=True).start()
started = time.monotonic()
ready = False
stopping = False
listed = False
with (root / 'server.log').open('w', encoding='utf-8') as log:
    while process.poll() is None or not events.empty():
        try:
            line = events.get(timeout=0.2)
            log.write(line)
            log.flush()
            if any(word in line for word in ('Dungeon', 'ERROR', 'Exception', 'Permanent farming', '副本', 'lost_altar')):
                print(line.rstrip(), flush=True)
            if 'Permanent farming ready: id=lost_altar' in line:
                ready = True
                process.stdin.write('dungeon list\ndungeon reload\n')
                process.stdin.flush()
            if ready and 'lost_altar' in line and 'Permanent farming ready' not in line:
                listed = True
        except queue.Empty:
            pass
        elapsed = time.monotonic() - started
        if not stopping and (listed or elapsed > 100):
            stopping = True
            process.stdin.write('stop\n')
            process.stdin.flush()
        if elapsed > 140:
            process.kill()
            break
code = process.wait(timeout=10)
result = {'world_ready': ready, 'listed': listed, 'exit_code': code,
          'migration_preserved_bytes': (data / 'dungeons/lost_altar/farming.yml').read_bytes() == original,
          'legacy_removed': not (data / 'farming.yml').exists()}
(root / 'result.json').write_text(json.dumps(result, indent=2), encoding='utf-8')
print(result, flush=True)
if not all((ready, listed, code == 0, result['migration_preserved_bytes'], result['legacy_removed'])):
    raise SystemExit(1)
