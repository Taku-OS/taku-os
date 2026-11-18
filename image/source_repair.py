"""Shared, source-only repair provenance checks; no build or device operations."""
import hashlib
import json
import pathlib
import re
import struct
import zlib

CAPSULE = 'image/source-repair.json'
HELPER = 'image/source_repair.py'
CONTROLS = {CAPSULE, HELPER, 'image/verify-system-ai.py'}
PREFIXES = ('system/assistant/', 'system/memory/', 'system/ai/', 'system/display/',
            'system/inputmethod/', 'system/surfaces/')
FILES = {'Android.bp', 'adapters/magpie-runtime/magpie', 'adapters/magpie-runtime/taku-magpie.rc',
         'image/runtime/taku-runtime.rc', 'image/runtime/start', 'image/taku.mk'}
PUBLIC_RESOURCES = {
    **{'system/assistant/res/font/' + name + '.ttf': 'font/ttf' for name in (
        'doto_black_rounded', 'hanken_grotesk_medium', 'hanken_grotesk_regular',
        'noto_sans_sc_medium', 'noto_sans_sc_regular', 'space_grotesk_medium', 'space_grotesk_regular')},
    **{'system/assistant/res/mipmap-' + density + '/' + name + '.png': 'image/png'
       for density in ('mdpi', 'hdpi', 'xhdpi', 'xxhdpi', 'xxxhdpi')
       for name in ('ic_launcher', 'ic_launcher_round')},
}


def require(condition, message):
    if not condition:
        raise ValueError(message)


def checksum(raw):
    return hashlib.sha256(raw).hexdigest()


def versions(value):
    require(isinstance(value, dict) and set(value) == {'ime', 'assistant', 'memory'}
            and all(type(n) is int and n > 0 for n in value.values()), 'Invalid runtime version constraints')
    return value


def source_path(name):
    p = pathlib.PurePosixPath(name)
    require(name and not p.is_absolute() and '..' not in p.parts and str(p) == name
            and (name in FILES or name.startswith(PREFIXES))
            and not any(part.startswith('.') or part in ('out', 'state', 'data', 'backups', 'prebuilt')
                        for part in p.parts)
            and p.name not in ('auth.json', 'credentials.json')
            and not p.name.startswith(('credentials', 'userdata'))
            and (p.suffix not in ('.ttf', '.png') or name in PUBLIC_RESOURCES)
            and p.suffix not in ('.apk', '.so', '.jar', '.zip', '.pk8', '.pem', '.key', '.jks', '.keystore'),
            'Repair path is not a public source/lock input: ' + name)


def source_content(name, raw, allow_resources=False):
    """Only named public UI resources may bypass the source-text boundary."""
    source_path(name)
    kind = PUBLIC_RESOURCES.get(name)
    if kind is None or not allow_resources:
        require(b'\x00' not in raw, 'Repair input must be UTF-8 source text: ' + name)
        raw.decode('utf-8')
        return 'source-text'
    require(0 < len(raw) <= 8 * 1024 * 1024, 'Public resource size differs: ' + name)
    if kind == 'font/ttf':
        require(len(raw) >= 12 and raw[:4] == b'\x00\x01\x00\x00', 'Invalid TrueType header: ' + name)
        tables = struct.unpack_from('>H', raw, 4)[0]
        require(0 < tables <= 128 and 12 + tables * 16 <= len(raw), 'Invalid TrueType directory: ' + name)
        for index in range(tables):
            offset, length = struct.unpack_from('>II', raw, 12 + index * 16 + 8)
            require(offset >= 12 + tables * 16 and offset + length <= len(raw),
                    'TrueType table escapes resource: ' + name)
    else:
        require(raw.startswith(b'\x89PNG\r\n\x1a\n'), 'Invalid PNG signature: ' + name)
        position, chunks, ended, has_pixels = 8, 0, False, False
        while position + 12 <= len(raw):
            length = struct.unpack_from('>I', raw, position)[0]
            tag = raw[position + 4:position + 8]
            end = position + 12 + length
            require(end <= len(raw), 'PNG chunk escapes resource: ' + name)
            payload = raw[position + 8:position + 8 + length]
            require(zlib.crc32(tag + payload) & 0xffffffff == struct.unpack_from('>I', raw, end - 4)[0],
                    'PNG chunk checksum differs: ' + name)
            if chunks == 0:
                require(tag == b'IHDR' and length == 13, 'Missing PNG dimensions: ' + name)
                width, height = struct.unpack_from('>II', payload)
                require(0 < width <= 4096 and 0 < height <= 4096, 'Invalid PNG dimensions: ' + name)
            else:
                require(tag != b'IHDR', 'Duplicate PNG dimensions: ' + name)
            has_pixels = has_pixels or (tag == b'IDAT' and length > 0)
            position, chunks = end, chunks + 1
            if tag == b'IEND':
                require(length == 0 and position == len(raw), 'PNG trailing data differs: ' + name)
                ended = True
                break
        require(ended and has_pixels and chunks >= 3, 'Incomplete PNG resource: ' + name)
    return kind


def validate(raw, proof, entries):
    """Validate archived or newly frozen bytes against their complete parent input set."""
    require(isinstance(proof, dict) and checksum(raw) == proof.get('capsuleSha256'), 'Repair capsule hash differs')
    capsule = json.loads(raw)
    require(capsule.get('schema') == 'taku.source-repair-capsule/1', 'Invalid repair capsule')
    manifest_raw = capsule['manifestJson'].encode()
    parent_raw = capsule['parentManifestJson'].encode()
    require(checksum(manifest_raw) == proof.get('manifestSha256')
            and checksum(parent_raw) == proof.get('parentManifestSha256')
            and capsule.get('packetSha256') == proof.get('packetSha256')
            and re.fullmatch('[0-9a-f]{64}', proof.get('packetSha256', '')) is not None,
            'Repair packet/manifest binding differs')
    manifest, parent = json.loads(manifest_raw), json.loads(parent_raw)
    schema = manifest.get('schema')
    require(schema in ('taku.system-ai-source-repair/1', 'taku.system-ai-source-repair/2')
            and manifest.get('credentialsIncluded') is False and manifest.get('physicalVerified') is False
            and manifest.get('deletions') == []
            and manifest.get('baseSnapshotDigest') == parent.get('digest') == proof.get('parentSnapshotDigest')
            and manifest.get('baseManifestSha256') == checksum(parent_raw)
            and re.fullmatch('[0-9a-f]{40}', manifest.get('sourceCommit', '')) is not None
            and manifest['sourceCommit'] == proof.get('sourceCommit'), 'Repair identity/scope differs')
    previous = {item['path']: item['sha256'] for item in parent['files']}
    require(len(previous) == len(parent['files'])
            and checksum(json.dumps(parent['files'], sort_keys=True).encode()) == parent['digest'],
            'Repair parent input digest differs')
    original = parent.get('runtimeIntegration', {})
    require(original.get('readyForBuild') is True
            and manifest.get('baseComponentCommit') == original.get('sourceCommit'), 'Repair component base differs')
    actual, floor = versions(manifest.get('versions')), versions(proof.get('minimumVersions'))
    old = versions(original.get('sourceRepair', {}).get('versions', original.get('versions')))
    require(actual == proof.get('versions') and all(actual[k] >= max(old[k], floor[k]) for k in actual),
            'Repair version is below the reviewed or parent floor')
    records = manifest.get('files')
    require(isinstance(records, list) and records, 'Repair has no source files')
    changed = {}
    for item in records:
        name = item.get('path', ''); source_path(name)
        kind = PUBLIC_RESOURCES.get(name, 'source-text')
        require((schema == 'taku.system-ai-source-repair/1' and kind == 'source-text')
                or (schema == 'taku.system-ai-source-repair/2' and item.get('contentType') == kind),
                'Repair resource type is missing or differs: ' + name)
        require(kind == 'source-text' or item.get('mode') == '0o644', 'Public resources must not be executable')
        require(name not in changed and item.get('beforeSha256') == previous.get(name)
                and re.fullmatch('[0-9a-f]{64}', item.get('sha256', '')) is not None
                and type(item.get('bytes')) is int and item['bytes'] > 0
                and item.get('mode') in ('0o644', '0o755')
                and entries.get(name) == item['sha256'], 'Repair source preimage/output differs: ' + name)
        changed[name] = item
    require(set(entries) == set(previous) | set(changed) | CONTROLS,
            'Repair contains missing or unlisted frozen inputs')
    require(all(entries.get(name) == value for name, value in previous.items()
                if name not in changed and name not in CONTROLS), 'Repair changed an inherited input')
    require(entries.get(CAPSULE) == checksum(raw) and all(name in entries for name in CONTROLS),
            'Repair gate/capsule input is missing')
    return manifest
