"""Author the Dungeon boss through the user's live Blockbench MCP, including native undo history."""
from pathlib import Path
import base64, json, math, sys
from blockbench_rpc import Blockbench

ROOT = Path(__file__).resolve().parents[1]
OUT = ROOT / 'src/main/resources/boss/models'
ART = ROOT / 'assets/stone-site-warden'
OUT.mkdir(parents=True, exist_ok=True)
ART.mkdir(parents=True, exist_ok=True)
bb = Blockbench()
log = []

def call(tool_name, **args):
    r = bb.call(tool_name, args)
    log.append(dict(tool=tool_name, arguments=args if tool_name != 'risky_eval' else {'code':args['code'][:150]}, ok=True))
    return r

def evaljs(code):
    r=call('risky_eval',code=code)
    return json.loads(next(c['text'] for c in r['content'] if c['type']=='text'))

def material(name, color, kind):
    call('create_texture',name=name,width=32,height=32,uv_width=32,uv_height=32,fill_color='#'+''.join(f'{c:02x}' for c in color))
    # Painter.edit owns a scoped native texture Undo entry. Never write a bbmodel behind Blockbench.
    evaljs("""(()=>{const t=Texture.all.find(t=>t.name===%s);t.edit(canvas=>{
const ctx=canvas.getContext('2d'),base=%s,kind=%s;let seed=71;
const rnd=()=>{seed=(seed*1664525+1013904223)>>>0;return seed/4294967296};
for(let y=0;y<32;y++)for(let x=0;x<32;x++){
 let n=Math.floor(rnd()*19)-9;
 if(kind==='stone' && (y===0||x===0))n+=13;
 if(kind==='stone' && ((x===9&&y>8&&y<25)||(y===23&&x>9&&x<20)))n-=23;
 if(kind==='moss' && rnd()<.27)n-=18;
 if(kind==='rune')n=(x>2&&x<29&&y>2&&y<29)?9:-12;
 ctx.fillStyle='rgb('+base.map(c=>Math.max(0,Math.min(255,c+n))).join(',')+')';ctx.fillRect(x,y,1,1);
}},{edit_name:'Paint '+t.name});return {name:t.name,size:[t.width,t.height]}})()""" % (json.dumps(name),json.dumps(color),json.dumps(kind)))

def group(name,origin,parent='root'):
    call('add_group',name=name,origin=origin,parent=parent)

def cubes(bone,mat,items):
    elements=[]
    for item in items:
        n,a,b,*rest=item
        e=dict(name=n,**{'from':a,'to':b})
        if rest: e.update(rest[0])
        elements.append(e)
    call('place_cube',group=bone,texture=mat,faces=True,elements=elements)

def key(t,r=None,p=None,s=None):
    v={'time':t}
    if r is not None:v['rotation']=r
    if p is not None:v['position']=p
    if s is not None:v['scale']=s
    return v

def anim(name,length,bones,loop=False):
    if 'root' in bones:bones['body' if name not in ('burst','telegraph') else 'fx_root']=bones.pop('root')
    call('create_animation',name=name,animation_length=length,bones=bones,loop=loop)

def save(name):
    # BetterModel consumes plain animation names, not Bedrock's optional prefix.
    evaljs("(()=>{Undo.initEdit({animations:Animation.all});Animation.all.forEach(a=>a.name=a.name.replace(/^animation\\./,''));Undo.finishEdit('Name BetterModel animations');Modes.options.edit.select();Canvas.updateAll();return Animation.all.map(a=>a.name)})()")
    r=call('export_model',codec_id='project',result_format='text',max_content_length=2000000)
    data=r.get('structuredContent')
    if not data:
        data=json.loads(next(c['text'] for c in r['content'] if c['type']=='text'))
    # export_model reports metadata; inspect the actual content, do not reimplement the exporter.
    content=data.get('content') or data.get('metadata',{}).get('content')
    if not content: raise RuntimeError('Missing complete exported model: '+str(list(data)))
    if isinstance(content,dict):content=json.dumps(content,ensure_ascii=False)
    parsed=json.loads(content)
    assert parsed.get('elements') and parsed.get('animations') and parsed.get('textures')
    (OUT/(name+'.bbmodel')).write_text(content,encoding='utf-8')
    print('Exported',name,'cubes',len(parsed['elements']),'animations',[a['name'] for a in parsed['animations']],flush=True)

def preview(name,position,target,zoom):
    call('create_offscreen_view',id='dungeon_preview',width=1100,height=1000,copy_view='none')
    try:
        r=call('set_camera_angle',view='dungeon_preview',position=position,target=target,projection='orthographic',zoom=zoom)
        images=[c for c in r.get('content',[]) if c['type']=='image']
        if not images:
            r=call('capture_screenshot',view='dungeon_preview')
            images=[c for c in r['content'] if c['type']=='image']
        (ART/name).write_bytes(base64.b64decode(images[0]['data']))
    finally:
        try:call('delete_offscreen_view',view='dungeon_preview')
        except RuntimeError as e:
            # BB MCP 1.10.1 disposes the view, then hits a missing onContextMenu binding.
            if 'onContextMenu is not defined' not in str(e):raise
            if evaljs("Preview.all.some(p=>p.id==='dungeon_preview')"):raise
            print('MCP preview view disposed; ignored known post-dispose UI error',flush=True)

call('create_project',name='stone_site_wave',format='free')
material('warning',[238,175,70],'rune')
material('pulse',[94,232,198],'rune')
group('fx_root',[0,0,0])
group('ring',[0,0,0],'fx_root')
group('runes',[0,0,0],'fx_root')
for i in range(16):
    angle=2*math.pi*i/16
    x,z=math.sin(angle)*16,math.cos(angle)*16
    cubes('ring','warning',[(f'arc_{i}',[x-3.2,.3,z-.4],[x+3.2,.65,z+.4],{'origin':[x,.45,z],'rotation':[0,math.degrees(angle),0],'shade':False})])
for i in range(8):
    angle=2*math.pi*i/8
    x,z=math.sin(angle)*13,math.cos(angle)*13
    cubes('runes','pulse',[(f'rune_{i}',[x-.5,.5,z-1.7],[x+.5,.85,z+1.7],{'origin':[x,.6,z],'rotation':[0,math.degrees(angle)+35,0],'shade':False})])
anim('telegraph',1.4,{'ring':[key(0,s=.98),key(.7,s=1.03),key(1.4,s=.98)],'runes':[key(0,r=[0,0,0],s=.85),key(.7,r=[0,22.5,0],s=1),key(1.4,r=[0,45,0],s=.85)]},True)
anim('burst',.65,{'root':[key(0,s=.12),key(.25,s=.7),key(.55,s=1),key(.65,s=1.05)],'ring':[key(0,s=[1,4,1]),key(.55,s=[1,.5,1]),key(.65,s=[1,.01,1])],'runes':[key(0,r=[0,0,0],p=[0,0,0]),key(.5,r=[0,65,0],p=[0,8,0]),key(.65,r=[0,80,0],p=[0,10,0],s=.01)]})
save('stone_site_wave')
preview('wave-preview.png',[75,100,-90],[0,0,0],.48)
(ART/'mcp-build-log.json').write_text(json.dumps(log,ensure_ascii=False,indent=2),encoding='utf-8')
print('Blockbench MCP build complete',flush=True)
