"""Preserve the user's plant model and export it through the real Blockbench MCP."""
from pathlib import Path
import base64,hashlib,json
from blockbench_rpc import Blockbench

root=Path(__file__).resolve().parents[1]
source=Path('C:/Users/32633/Downloads/briarqueen-rose.bbmodel')
output=root/'src/main/resources/elites/models/briarqueen_rose.bbmodel'
art=root/'assets/briarqueen-rose'
output.parent.mkdir(parents=True,exist_ok=True)
original=source.read_bytes()
(art/'briarqueen-rose.original.bbmodel').write_bytes(original)
bb=Blockbench()
bb.call('create_project',{'name':'briarqueen_rose','format':'free'})
result=bb.call('risky_eval',{'code':"(()=>{const data=JSON.parse(require('fs').readFileSync("+json.dumps(str(source))+",'utf8').replace(/^\\uFEFF/,''));const elements=[],textures=[],animations=[];Undo.initEdit({elements,textures,animations,outliner:true});Codecs.project.parse(data);elements.push(...Cube.all);textures.push(...Texture.all);animations.push(...Animation.all);Project.name='briarqueen_rose';Undo.finishEdit('Import user briarqueen model');const original=Animation.all.find(a=>a.name==='wilt');const added=[];Undo.initEdit({animations:added});const dataCopy=original.getUndoCopy();dataCopy.uuid=guid();dataCopy.name='death';dataCopy.loop='once';const death=new Animation(dataCopy).add();added.push(death);Undo.finishEdit('Map wilt to BetterModel death');Modes.options.edit.select();Canvas.updateAll();return {cubes:Cube.all.length,bones:Group.all.length,animations:Animation.all.map(a=>a.name)}})()"})
print(next(c['text'] for c in result['content'] if c['type']=='text'))
bb.call('export_model',{'codec_id':'project','path':str(output),'overwrite':True,'max_content_length':0})
converted=json.loads(output.read_text(encoding='utf-8'))
assert len(converted['elements'])==52
bb.call('create_offscreen_view',{'id':'rose_preview','width':950,'height':950,'copy_view':'none'})
try:
 result=bb.call('set_camera_angle',{'view':'rose_preview','position':[68,48,-85],'target':[0,17,-3],'projection':'orthographic','zoom':.43})
 (art/'preview.png').write_bytes(base64.b64decode(next(c['data'] for c in result['content'] if c['type']=='image')))
finally:
 try:bb.call('delete_offscreen_view',{'view':'rose_preview'})
 except RuntimeError as error:
  if 'onContextMenu is not defined' not in str(error):raise
  check=bb.call('risky_eval',{'code':"Preview.all.some(p=>p.id==='rose_preview')"})
  print('Known MCP post-dispose UI error; viewport inventory:',check)
assert original==source.read_bytes()
(art/'source-record.json').write_text(json.dumps({'source':str(source),'source_sha256':hashlib.sha256(original).hexdigest(),'original_unchanged':True,'cubes':52,'bones':17,'preserved_animations':['idle','hiss','lunge_bite','whip_crack','wilt'],'added_alias':'death -> wilt','imported_via':'Blockbench MCP'},ensure_ascii=False,indent=2),encoding='utf-8')
print('Exported',output)
