"""Import the user's chosen source through Blockbench MCP, preserve its geometry/UVs, add two missing animations."""
from pathlib import Path
import base64,json,sys,hashlib
from blockbench_rpc import Blockbench

root=Path(__file__).resolve().parents[1]
source=Path('C:/Users/32633/Downloads/runestone-warden-golem.bbmodel')
out=root/'src/main/resources/boss/models/stone_site_warden.bbmodel'
art=root/'assets/stone-site-warden'
original=source.read_bytes()
(art/'runestone-warden-golem.original.bbmodel').write_bytes(original)
data=json.loads(original.decode('utf-8-sig'))
bb=Blockbench()
bb.call('create_project',{'name':'stone_site_warden','format':'free'})
result=bb.call('risky_eval',{'code':"(()=>{const input=JSON.parse(require('fs').readFileSync("+json.dumps(str(source))+",'utf8').replace(/^\\uFEFF/,''));const elements=[],textures=[],animations=[];Undo.initEdit({elements,textures,animations,outliner:true});Codecs.project.parse(input);elements.push(...Cube.all);textures.push(...Texture.all);animations.push(...Animation.all);Project.name='stone_site_warden';Undo.finishEdit('Import supplied runestone golem');Modes.options.edit.select();return {elements:Cube.all.length,bones:Group.all.map(g=>g.name),animations:Animation.all.map(a=>a.name)}})()"})
print(next(c['text'] for c in result['content'] if c['type']=='text'))
def key(t,r=None,p=None,s=None):
 v={'time':t}
 if r is not None:v['rotation']=r
 if p is not None:v['position']=p
 if s is not None:v['scale']=s
 return v
bb.call('create_animation',{'name':'spawn','loop':False,'animation_length':2.4,'bones':{
 'root':[key(0,p=[0,-25,0],s=.6),key(1.7,p=[0,1,0],s=1),key(2.4,p=[0,0,0],s=1)],
 'chest':[key(0,r=[24,0,0]),key(1.5,r=[-8,0,0]),key(2.4,r=[0,0,0])],
 'armR0':[key(0,r=[-18,0,24]),key(1.5,r=[-40,0,25]),key(2.4,r=[0,0,0])],
 'armL0':[key(0,r=[-18,0,-24]),key(1.5,r=[-40,0,-25]),key(2.4,r=[0,0,0])],
 'core':[key(0,s=.1),key(1.6,s=1.25),key(2.4,s=1)]}})
bb.call('create_animation',{'name':'hurt','loop':False,'animation_length':.35,'bones':{
 'chest':[key(0,r=[0,0,0]),key(.08,r=[-5,0,3]),key(.2,r=[3,0,-2]),key(.35,r=[0,0,0])],
 'head':[key(0,r=[0,0,0]),key(.1,r=[-6,0,0]),key(.35,r=[0,0,0])]}})
bb.call('risky_eval',{'code':"(()=>{Undo.initEdit({animations:Animation.all});Animation.all.forEach(a=>a.name=a.name.replace(/^animation\\./,''));Undo.finishEdit('Name supplemental animations');Modes.options.edit.select();Canvas.updateAll();return Animation.all.map(a=>({name:a.name,length:a.length}))})()"})
bb.call('export_model',{'codec_id':'project','path':str(out),'overwrite':True,'max_content_length':0})
content=out.read_text(encoding='utf-8')
final=json.loads(content)
assert len(final['elements'])==len(data['elements'])
out.write_text(content if isinstance(content,str) else json.dumps(content,ensure_ascii=False),encoding='utf-8')
bb.call('create_offscreen_view',{'id':'user_warden','width':1100,'height':1000,'copy_view':'none'})
try:
 r=bb.call('set_camera_angle',{'view':'user_warden','position':[115,80,-150],'target':[0,29,0],'projection':'orthographic','zoom':.24})
 (art/'preview.png').write_bytes(base64.b64decode(next(c['data'] for c in r['content'] if c['type']=='image')))
finally:
 try:bb.call('delete_offscreen_view',{'view':'user_warden'})
 except RuntimeError as e:
  if 'onContextMenu is not defined' not in str(e):raise
  print('Known MCP post-dispose UI error; view was removed')
(art/'source-record.json').write_text(json.dumps({'source':str(source),'source_sha256':hashlib.sha256(original).hexdigest(),'geometry':len(final['elements']),'preserved_animations':[a['name'] for a in data['animations']],'added_animations':['spawn','hurt'],'imported_via':'Blockbench MCP 1.10.1','original_unchanged':source.read_bytes()==original},ensure_ascii=False,indent=2),encoding='utf-8')
print('Imported supplied model and exported',out)
