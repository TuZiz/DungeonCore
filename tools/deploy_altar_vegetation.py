"""Apply a verified staging result only while holding an exclusive world session handle."""
from pathlib import Path
import datetime
import hashlib
import json
import os
import shutil
import sys
import zipfile
import yaml
import ctypes
from ctypes import wintypes


def digest(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def atomic_copy(source, destination):
    pending = destination.with_name(destination.name + '.vegetation-pending')
    try:
        shutil.copy2(source, pending)
        with pending.open('r+b') as handle:
            os.fsync(handle.fileno())
        assert digest(pending) == digest(source)
        os.replace(pending, destination)
    finally:
        if pending.exists():
            pending.unlink()


def main():
    live = Path('C:/Users/32633/Desktop/leaf')
    staging = Path(sys.argv[1]).resolve(strict=True)
    report = json.loads((staging / 'report.json').read_text(encoding='utf-8'))
    world = live / 'world/dimensions/minecraft/lost_altar'
    maps = live / 'plugins/Dungeon/maps/lost_altar'
    marker = live / 'plugins/Dungeon/farming/lost_altar.permanent'
    for name, expected in report['source_hashes'].items():
        assert digest(world / 'region' / name) == expected, f'World changed during preparation: {name}'
    assert digest(maps / 'world.zip') == report['template_source_hash'], 'Template changed during preparation'
    manifest = yaml.safe_load((staging / 'manifest.yml').read_text(encoding='utf-8'))
    expected = {entry['path']: entry['sha256'] for entry in manifest['files']}
    with zipfile.ZipFile(staging / 'world.zip') as archive:
        assert set(archive.namelist()) == set(expected)
        for name, sha in expected.items():
            assert hashlib.sha256(archive.read(name)).hexdigest() == sha, name
    ownership = hashlib.sha256('\n'.join(f'{k}={v}' for k, v in sorted(expected.items())).encode()).hexdigest()
    assert (staging / 'lost_altar.permanent').read_bytes() == f'world=lost_altar\ndata-version={manifest["data-version"]}\nmanifest-sha256={ownership}\n'.encode('utf-8')
    assert marker.is_file(), 'Existing Dungeon ownership marker required'
    backup = live / '.codex-backups' / ('StoneSite-vegetation-' + datetime.datetime.now().strftime('%Y%m%d-%H%M%S'))
    backup.mkdir(parents=True, exist_ok=False)
    shutil.copytree(world, backup / 'lost_altar')
    shutil.copytree(maps, backup / 'map-template')
    shutil.copy2(marker, backup / 'lost_altar.permanent')
    pairs = [(p, world / 'region' / p.name, backup / 'lost_altar/region' / p.name) for p in sorted((staging / 'region').glob('*.mca'))]
    pairs += [(staging / 'world.zip', maps / 'world.zip', backup / 'map-template/world.zip'),
              (staging / 'manifest.yml', maps / 'manifest.yml', backup / 'map-template/manifest.yml'),
              (staging / 'lost_altar.permanent', marker, backup / 'lost_altar.permanent')]
    written = []
    try:
        for source, destination, original in pairs:
            written.append((destination, original))
            atomic_copy(source, destination)
        assert all(digest(source) == digest(destination) for source, destination, _ in pairs)
    except BaseException:
        for destination, original in reversed(written):
            atomic_copy(original, destination)
        raise
    result = {'backup': str(backup), 'changed_region_files': len(pairs) - 3, 'live': report['live'], 'template': report['template'], 'verified': True}
    (staging / 'deployment.json').write_text(json.dumps(result, indent=2), encoding='utf-8')
    print(json.dumps(result, indent=2), flush=True)


if __name__ == '__main__':
    kernel = ctypes.WinDLL('kernel32', use_last_error=True)
    kernel.CreateFileW.argtypes = [wintypes.LPCWSTR, wintypes.DWORD, wintypes.DWORD, ctypes.c_void_p, wintypes.DWORD, wintypes.DWORD, wintypes.HANDLE]
    kernel.CreateFileW.restype = wintypes.HANDLE
    kernel.CloseHandle.argtypes = [wintypes.HANDLE]
    handle = kernel.CreateFileW('C:/Users/32633/Desktop/leaf/world/session.lock', 0xC0000000, 0, None, 3, 0x80, None)
    if handle == ctypes.c_void_p(-1).value:
        raise RuntimeError(f'World session is open; stop the server before deployment (Windows error {ctypes.get_last_error()})')
    try:
        main()
    finally:
        kernel.CloseHandle(handle)
