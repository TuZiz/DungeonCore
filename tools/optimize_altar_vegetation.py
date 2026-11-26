"""Prepare offline Lost Altar vegetation edits; never mutates the input world or archive.

Supports padded modern MCA block-state storage and old/new palette key formats.
Every changed chunk is decoded again and compared at block level before publication.
"""
from pathlib import Path
from collections import Counter
import argparse
import copy
import gzip
import hashlib
import io
import json
import math
import struct
import zipfile
import zlib

import nbtlib
import numpy as np
import yaml

LOW = {'short_grass', 'grass', 'fern'}
HIGH = {'tall_grass', 'large_fern'}
TALL_FLOWERS = {'lilac', 'peony', 'rose_bush', 'sunflower'}
FLOWERS = ('azure_bluet', 'oxeye_daisy', 'cornflower', 'dandelion')
ALLOWED = LOW | HIGH | TALL_FLOWERS


def name(entry):
    if isinstance(entry, dict):
        return str(entry.get('Name', entry.get('id', entry.get('', '')))).removeprefix('minecraft:')
    return str(entry).removeprefix('minecraft:')


def indices(states):
    if 'data' not in states:
        return np.zeros(4096, dtype=np.uint64)
    bits = max(4, (len(states['palette']) - 1).bit_length())
    per = 64 // bits
    i = np.arange(4096, dtype=np.uint64)
    words = np.asarray(states['data'], dtype=np.int64).view(np.uint64)
    return (words[i // per] >> ((i % per) * bits)) & ((1 << bits) - 1)


def encode(states, values):
    bits = max(4, (len(states['palette']) - 1).bit_length())
    per = 64 // bits
    words = np.zeros(math.ceil(4096 / per), dtype=np.uint64)
    for slot in range(per):
        part = values[slot::per]
        words[:len(part)] |= part << (slot * bits)
    states['data'] = nbtlib.LongArray(words.view(np.int64))


def palette_id(states, block):
    palette = states['palette']
    for i, item in enumerate(palette):
        if name(item) == block and not (isinstance(item, dict) and ('Properties' in item or 'properties' in item)):
            return i
    if palette.subtype == nbtlib.String:
        entry = nbtlib.String('minecraft:' + block)
    else:
        key = 'Name' if any('Name' in p for p in palette) else ''
        entry = nbtlib.Compound({key: nbtlib.String('minecraft:' + block)})
    palette.append(entry)
    return len(palette) - 1


def roll(x, y, z):
    value = (x * 73428767 ^ y * 19349663 ^ z * 912931 ^ 0x531709) & 0xFFFFFFFF
    value ^= value >> 16
    value = value * 0x7FEB352D & 0xFFFFFFFF
    value ^= value >> 15
    return value % 100


def replacement(block, entry, x, y, z):
    if block in HIGH:
        return 'air'
    if block in LOW:
        r = roll(x, y, z)
        return block if r < 20 else FLOWERS[r % len(FLOWERS)] if r < 25 else 'air'
    props = entry.get('Properties', entry.get('properties', {})) if isinstance(entry, dict) else {}
    upper = str(props.get('half', 'lower')) == 'upper'
    r = roll(x, y - int(upper), z)
    if r < 25:
        return block
    return 'air' if upper else FLOWERS[r % len(FLOWERS)]


def transform_chunk(chunk, counts):
    changed = False
    for section in chunk.get('sections', []):
        states = section.get('block_states')
        if states is None or not any(name(p) in ALLOWED for p in states['palette']):
            continue
        before = indices(states)
        after = before.copy()
        original = list(states['palette'])
        for index, entry in enumerate(original):
            block = name(entry)
            if block not in ALLOWED:
                continue
            for local in np.flatnonzero(before == index):
                i = int(local)
                x = int(chunk['xPos']) * 16 + i % 16
                y = int(section['Y']) * 16 + i // 256
                z = int(chunk['zPos']) * 16 + i // 16 % 16
                target = replacement(block, entry, x, y, z)
                if target == block:
                    counts['kept:' + block] += 1
                    continue
                after[i] = palette_id(states, target)
                counts[block + '->' + target] += 1
        if np.any(before != after):
            changed = True
            encode(states, after)
            # Repacking must retain all unaffected positions exactly, including palette growth.
            assert np.array_equal(indices(states), after)
    if changed:
        refresh_surface(chunk)
    return changed


def refresh_surface(chunk):
    # Only WORLD_SURFACE includes non-colliding grass/flowers. Other heightmap
    # predicates are unchanged because every replacement has no collision/fluid.
    sections = {int(s['Y']): s['block_states'] for s in chunk.get('sections', []) if 'block_states' in s}
    decoded = {}
    minimum = int(chunk.get('yPos', -4)) * 16
    for key in ('WORLD_SURFACE', 'WORLD_SURFACE_WG'):
        maps = chunk.get('Heightmaps', {})
        if key not in maps:
            continue
        words = np.asarray(maps[key], dtype=np.int64).view(np.uint64)
        assert len(words) == 37, 'This map must use the verified 384-block overworld height'
        for column in range(256):
            slot, shift = column // 7, column % 7 * 9
            height = int((int(words[slot]) >> shift) & 511)
            y = minimum + height - 1
            while y >= minimum:
                section = sections.get(y // 16)
                if section is not None:
                    if y // 16 not in decoded:
                        decoded[y // 16] = indices(section)
                    i = y % 16 * 256 + column
                    block = name(section['palette'][int(decoded[y // 16][i])])
                    if block not in {'air', 'cave_air', 'void_air'}:
                        break
                y -= 1
            updated = y - minimum + 1
            words[slot] = np.uint64((int(words[slot]) & ~(511 << shift)) | (updated << shift))
        maps[key] = nbtlib.LongArray(words.view(np.int64))


def verify_chunk(before, after):
    old = copy.deepcopy(before)
    new = copy.deepcopy(after)
    assert len(old.get('sections', [])) == len(new.get('sections', []))
    for a, b in zip(old.get('sections', []), new.get('sections', [])):
        if 'block_states' not in a:
            continue
        av, bv = indices(a['block_states']), indices(b['block_states'])
        ap, bp = a['block_states']['palette'], b['block_states']['palette']
        # Existing palette entries are never renumbered or edited. Compare every index
        # with vector operations, then check only the distinct changed palette entries.
        assert ap == bp[:len(ap)]
        mask = av != bv
        for index in np.unique(av[mask]):
            assert name(ap[int(index)]) in ALLOWED
        for index in np.unique(bv[mask]):
            assert name(bp[int(index)]) in {'air'} | set(FLOWERS)
        a.pop('block_states')
        b.pop('block_states')
    # Preserve every other metadata field; only surface heightmaps track the removal.
    for key in ('WORLD_SURFACE', 'WORLD_SURFACE_WG'):
        old.get('Heightmaps', {}).pop(key, None)
        new.get('Heightmaps', {}).pop(key, None)
    assert old == new


def region(raw, totals):
    result = bytearray(8192)
    result[4096:8192] = raw[4096:8192]
    dirty = False
    for i in range(1024):
        offset = int.from_bytes(raw[i * 4:i * 4 + 3], 'big') * 4096
        if not offset:
            continue
        length = int.from_bytes(raw[offset:offset + 4], 'big')
        compression = raw[offset + 4]
        assert compression in (1, 2, 3), f'Unsupported compression/external chunk: {compression}'
        body = raw[offset + 5:offset + 4 + length]
        data = gzip.decompress(body) if compression == 1 else zlib.decompress(body) if compression == 2 else body
        chunk = nbtlib.File.parse(io.BytesIO(data))
        counts = Counter()
        totals['chunks_scanned'] += 1
        candidate = any(any(name(p) in ALLOWED for p in s.get('block_states', {}).get('palette', [])) for s in chunk.get('sections', []))
        original = copy.deepcopy(chunk) if candidate else None
        if candidate and transform_chunk(chunk, counts):
            buffer = io.BytesIO()
            chunk.write(buffer)
            encoded = buffer.getvalue()
            verify_chunk(original, nbtlib.File.parse(io.BytesIO(encoded)))
            body = zlib.compress(encoded)
            payload = struct.pack('>I', len(body) + 1) + b'\x02' + body
            dirty = True
            totals['chunks_changed'] += 1
        else:
            payload = raw[offset:offset + length + 4]
        totals.update(counts)
        sectors = math.ceil(len(payload) / 4096)
        assert sectors < 256
        result[i * 4:i * 4 + 3] = (len(result) // 4096).to_bytes(3, 'big')
        result[i * 4 + 3] = sectors
        result.extend(payload)
        result.extend(bytes(sectors * 4096 - len(payload)))
    return bytes(result) if dirty else raw


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--live', type=Path, required=True)
    parser.add_argument('--output', type=Path, required=True)
    args = parser.parse_args()
    args.output.mkdir(parents=True, exist_ok=False)
    report = {}
    region_folder = args.live / 'world/dimensions/minecraft/lost_altar/region'
    sources = {}
    for p in sorted(region_folder.glob('*.mca')):
        raw = p.read_bytes()
        sources[p.name] = hashlib.sha256(raw).hexdigest()
        totals = Counter()
        optimized = region(raw, totals)
        if optimized != raw:
            out = args.output / 'region' / p.name
            out.parent.mkdir(exist_ok=True)
            out.write_bytes(optimized)
        report.setdefault('live', Counter()).update(totals)
        print('LIVE', p.name, dict(totals), flush=True)
    template = args.live / 'plugins/Dungeon/maps/lost_altar'
    manifest = yaml.safe_load((template / 'manifest.yml').read_text(encoding='utf-8-sig'))
    expected = {entry['path']: entry['sha256'] for entry in manifest['files']}
    with zipfile.ZipFile(template / 'world.zip') as source, zipfile.ZipFile(args.output / 'world.zip', 'w', zipfile.ZIP_DEFLATED) as target:
        assert set(source.namelist()) == set(expected)
        for info in source.infolist():
            raw = source.read(info.filename)
            assert hashlib.sha256(raw).hexdigest() == expected[info.filename]
            if info.filename.endswith('.mca') and '/region/' in info.filename:
                totals = Counter()
                raw = region(raw, totals)
                report.setdefault('template', Counter()).update(totals)
                print('TEMPLATE', info.filename, dict(totals), flush=True)
            target.writestr(info, raw)
            expected[info.filename] = hashlib.sha256(raw).hexdigest()
    for entry in manifest['files']:
        entry['sha256'] = expected[entry['path']]
    (args.output / 'manifest.yml').write_text(yaml.safe_dump(manifest, sort_keys=False), encoding='utf-8')
    digest = hashlib.sha256('\n'.join(f'{k}={v}' for k, v in sorted(expected.items())).encode()).hexdigest()
    (args.output / 'lost_altar.permanent').write_bytes(f'world=lost_altar\ndata-version={manifest["data-version"]}\nmanifest-sha256={digest}\n'.encode('utf-8'))
    report['source_hashes'] = sources
    report['template_source_hash'] = hashlib.sha256((template / 'world.zip').read_bytes()).hexdigest()
    (args.output / 'report.json').write_text(json.dumps(report, indent=2), encoding='utf-8')
    print('COMPLETE', args.output, flush=True)


if __name__ == '__main__':
    main()
