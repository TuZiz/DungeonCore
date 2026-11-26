"""Audit the chosen source and actual BetterModel output; never claim client rendering."""
from pathlib import Path
import hashlib
import json
import zipfile

root = Path(__file__).resolve().parents[1]
source = root / 'assets/stone-site-warden/runestone-warden-golem.original.bbmodel'
export = root / 'src/main/resources/boss/models/stone_site_warden.bbmodel'
pack = root / 'target/coin-smoke263/plugins/BetterModel/build.zip'
original = json.loads(source.read_text(encoding='utf-8-sig'))
converted = json.loads(export.read_text(encoding='utf-8-sig'))


def bones(nodes):
    return sum(1 + bones(n.get('children', [])) for n in nodes if isinstance(n, dict))


def frames(model):
    return sum(len(a.get('keyframes', [])) for anim in model['animations']
               for a in anim.get('animators', {}).values())


def equivalent(left, right):
    if isinstance(left, (int, float)) and isinstance(right, (int, float)):
        return abs(left - right) < 1e-10
    if isinstance(left, dict) and isinstance(right, dict):
        return left.keys() == right.keys() and all(equivalent(v, right[k]) for k, v in left.items())
    if isinstance(left, list) and isinstance(right, list):
        return len(left) == len(right) and all(equivalent(a, b) for a, b in zip(left, right))
    return left == right


by_id = {e['uuid']: e for e in converted['elements']}
for cube in original['elements']:
    candidate = by_id[cube['uuid']]
    for field in ('from', 'to', 'rotation', 'origin', 'faces'):
        assert equivalent(cube.get(field), candidate.get(field)), (cube['uuid'], field)
assert all(e.get('type') == 'cube' for e in original['elements'])
assert set(a['name'] for a in original['animations']) <= set(a['name'] for a in converted['animations'])
with zipfile.ZipFile(pack) as archive:
    names = [n for n in archive.namelist()
             if n.startswith('assets/bettermodel/models/') and 'stone_site_warden' in n and n.endswith('.json')]
    models = [json.loads(archive.read(n)) for n in names]
    elements = sum(len(m.get('elements', [])) for m in models)
    assert elements == len(original['elements']), (elements, len(original['elements']))
    report = {
        'source_sha256': hashlib.sha256(source.read_bytes()).hexdigest(),
        'source_format': original['meta']['model_format'],
        'cubes': len(original['elements']),
        'bones': bones(original['outliner']),
        'resolution': original['resolution'],
        'multiaxis_cubes': sum(sum(abs(v) > 1e-8 for v in e.get('rotation', [])) > 1 for e in original['elements']),
        'original_keyframes': frames(original),
        'geometry_uv_preserved': True,
        'original_animations': {a['name']: a['length'] for a in original['animations']},
        'exported_animations': [a['name'] for a in converted['animations']],
        'generated_submodels': len(models),
        'generated_cubes': elements,
        'fixed_rotation_models': sum(any(abs(v) > 1e-8 for v in m.get('display', {}).get('fixed', {}).get('rotation', [])) for m in models),
        'pack_metadata': json.loads(archive.read('pack.mcmeta')),
        'boundary': 'Static source comparison and real BetterModel pack output; no client render/FPS/Folia assertion',
    }
output = root / 'target/boss-compatibility.json'
output.write_text(json.dumps(report, ensure_ascii=False, indent=2), encoding='utf-8')
print(json.dumps(report, ensure_ascii=False, indent=2))
