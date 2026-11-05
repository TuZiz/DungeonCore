from pathlib import Path
import subprocess,time,threading,queue
root=Path('D:/codex/DungeonCore/target/altar-smoke262');q=queue.Queue()
p=subprocess.Popen(['C:/Program Files/Zulu/zulu-25/bin/java.exe','@java.args'],cwd=root,stdin=subprocess.PIPE,stdout=subprocess.PIPE,stderr=subprocess.STDOUT,text=True,encoding='utf-8',errors='replace',creationflags=subprocess.CREATE_NO_WINDOW)
def read():
 for line in p.stdout:q.put(line)
threading.Thread(target=read,daemon=True).start();start=time.monotonic();stopped=False;passed=False
with (root/'probe.log').open('w',encoding='utf-8') as log:
 while p.poll() is None or not q.empty():
  try:
   line=q.get(timeout=.2);log.write(line);log.flush()
   if any(s in line for s in ('Dungeon','Mythic','CraftEngine','ERROR','Exception','WARN','ALTAR_','Done (')):print(line.rstrip(),flush=True)
   if 'ALTAR_PROBE_PASS' in line:passed=True
   if not stopped and (passed or 'ALTAR_PROBE_FAILED' in line or time.monotonic()-start>130):
    stopped=True;p.stdin.write('stop\n');p.stdin.flush()
  except queue.Empty:pass
  if not stopped and time.monotonic()-start>130:stopped=True;p.stdin.write('stop\n');p.stdin.flush()
  if time.monotonic()-start>165:p.kill();break
 print('PASSED',passed,'exit',p.wait(),flush=True)
 if not passed:raise SystemExit(1)
