#!/usr/bin/env python3
"""Host source qualification for an actual direct-image installation.

Never flashes, changes slots, initializes a probe, or repairs snapshot state.
Reference preparation authenticates the real archive; device qualification reads
exact source image byte ranges, never hashes an entire oversized firmware block.
"""
import datetime
import hashlib
import importlib.util
import json
import os
from pathlib import Path
import re
import selectors
import shlex
import shutil
import struct
import subprocess
import sys
import time
import zipfile

import ota_device
from ota_release import ReleaseError, digest, file_under, load_baseline, read_json, require, sha

PARTITIONS = tuple(sorted('abl bl1 bl2 bl31 boot dtbo gcf gsa gsa_bl1 init_boot ldfw modem pbl product pvmfw system system_dlkm system_ext tzsw vbmeta vbmeta_system vbmeta_vendor vendor vendor_boot vendor_dlkm vendor_kernel_boot'.split()))
DYNAMIC = ('product', 'system', 'system_dlkm', 'system_ext', 'vendor', 'vendor_dlkm')
BOOT = ('boot', 'init_boot', 'dtbo', 'vendor_kernel_boot', 'vendor_boot', 'vbmeta_system', 'vbmeta_vendor', 'vbmeta')
FLASH_ORDER = ('super', *(name + '_a' for name in BOOT))
# Fixed Android 17 system/update_engine/update_metadata.proto, independently
# read from the pinned source. PartitionUpdate field 2 is run_postinstall;
# operations is field 8. REPLACE_ZSTD (14) does not read the source partition.
PAYLOAD_PROTO_SHA256 = '5b1ed6abb452996971be3dc4c4db949bb1d82a87221cc63b6867fb95451cef3a'
SOURCE_SCHEMA = 'taku.direct-image-source-installation/1'
REFERENCE_SCHEMA = 'taku.ota-source-partition-reference/1'
QUALIFICATION_SCHEMA = 'taku.ota-source-partition-observation/1'


def now():
    return datetime.datetime.now(datetime.timezone.utc).isoformat()


def blob_sha(data):
    return hashlib.sha256(data).hexdigest()


def sync_directory(path):
    descriptor = os.open(path, os.O_RDONLY)
    try: os.fsync(descriptor)
    finally: os.close(descriptor)


def save(path, value):
    path = Path(path)
    with path.open('x') as stream:
        json.dump(value, stream, indent=2, ensure_ascii=False)
        stream.write('\n'); stream.flush(); os.fsync(stream.fileno())
    sync_directory(path.parent)
    return {'path': str(path), 'sha256': digest(path)}


def copy_file(source, destination):
    source, destination = Path(source), Path(destination)
    require(source.is_file() and not source.is_symlink(), 'Evidence must be a regular non-symlink file')
    wanted = digest(source)
    destination.parent.mkdir(parents=True, exist_ok=True)
    with source.open('rb') as incoming, destination.open('xb') as output:
        shutil.copyfileobj(incoming, output, 8 * 1024 * 1024)
        output.flush(); os.fsync(output.fileno())
    require(digest(destination) == wanted == digest(source), 'Evidence changed while copying')
    sync_directory(destination.parent)
    return {'path': str(destination), 'bytes': destination.stat().st_size, 'sha256': wanted}


def small(path, expected=None):
    path = Path(path)
    require(path.is_file() and not path.is_symlink() and path.stat().st_size <= 8 * 1024 * 1024,
            'Missing/oversized/symlink evidence')
    if expected is not None:
        require(sha(expected) and digest(path) == expected, 'Evidence SHA-256 differs: ' + str(path))
    return read_json(path)


def relative_path(value):
    require(isinstance(value, str) and value and not Path(value).is_absolute()
            and Path(value).parts and ".." not in Path(value).parts and str(Path(value)) == value,
            'Evidence path must be canonical and relative')
    return value


def checked_ref(root, ref):
    require(isinstance(ref, dict) and sha(ref.get('sha256')) and type(ref.get('bytes')) is int and ref['bytes'] > 0,
            'Invalid portable evidence reference')
    path = file_under(Path(root).resolve(), relative_path(ref['path']))
    require(path.stat().st_size == ref['bytes'] and digest(path) == ref['sha256'], 'Portable evidence changed')
    return path


def protobuf_fields(data):
    """Bounded protobuf wire reader, rejecting malformed and duplicate core fields."""
    offset = 0
    def varint():
        nonlocal offset
        result = 0
        for shift in range(0, 70, 7):
            require(offset < len(data), 'Truncated protobuf')
            byte = data[offset]; offset += 1; result |= (byte & 127) << shift
            if not byte & 128:
                return result
        raise ReleaseError('Oversized protobuf varint')
    while offset < len(data):
        tag = varint(); number, wire = tag >> 3, tag & 7
        require(number > 0, 'Invalid protobuf field')
        if wire == 0:
            value = varint()
        else:
            require(wire in (1, 2, 5), 'Unsupported protobuf wire type')
            length = varint() if wire == 2 else (8 if wire == 1 else 4)
            require(offset + length <= len(data), 'Truncated protobuf field')
            value = data[offset:offset + length]; offset += length
        yield number, wire, value


def one(fields, number, wire, optional=False):
    matches = [(w, v) for n, w, v in fields if n == number]
    require(len(matches) == (0 if optional and not matches else 1) and all(w == wire for w, _ in matches),
            'Missing/duplicate/wrong-type payload field ' + str(number))
    return matches[0][1] if matches else None


def partition_info(raw):
    fields = list(protobuf_fields(raw))
    size, checksum = one(fields, 1, 0), one(fields, 2, 2)
    require(size > 0 and len(checksum) == 32, 'Invalid payload partition size/digest')
    return {'bytes': size, 'sha256': checksum.hex()}


def manifest_parts(raw):
    top = list(protobuf_fields(raw)); parts = {}
    partial = one(top, 16, 0, optional=True)
    require(partial in (None, 0), 'Partial payload not allowed')
    for number, wire, value in top:
        if number != 13:
            continue
        require(wire == 2, 'Invalid payload partition')
        fields = list(protobuf_fields(value))
        name = one(fields, 1, 2).decode('ascii')
        require(name in PARTITIONS and name not in parts, 'Unknown/duplicate payload partition')
        old = one(fields, 6, 2, optional=True)
        new = one(fields, 7, 2)
        operations = []
        for field, opwire, opraw in fields:
            if field == 8:
                require(opwire == 2, 'Invalid payload operation')
                op = list(protobuf_fields(opraw)); kind = one(op, 1, 0)
                require(kind in range(15), 'Unknown payload operation type')
                operations.append({'type': kind, 'readsSource': kind in (2, 3, 4, 5, 9, 10, 11, 12, 13)
                                   or any(n == 4 for n, _, _ in op)})
        parts[name] = {'old': partition_info(old) if old is not None else None,
                       'new': partition_info(new), 'operations': operations}
    require(tuple(sorted(parts)) == PARTITIONS, 'Expected exact complete tokay 26-partition inventory')
    return parts


def payload_manifest(package):
    with zipfile.ZipFile(package) as archive:
        require(len(archive.namelist()) == len(set(archive.namelist())), 'Duplicate OTA entries')
        with archive.open('payload.bin') as stream:
            header = stream.read(24)
            require(len(header) == 24, 'Missing payload header')
            magic, version, length, signatures = struct.unpack('>4sQQI', header)
            require(magic == b'CrAU' and version == 2 and 0 < length <= 32 * 1024 * 1024 and signatures > 0,
                    'Expected signed v2 payload manifest')
            raw = stream.read(length)
            require(len(raw) == length, 'Truncated payload manifest')
    return raw, manifest_parts(raw)


def prepare_reference(baseline_dir, avbtool, output, *, payload_proto):
    """Must run where full immutable artifacts exist, not the metadata mirror."""
    baseline = load_baseline(baseline_dir)
    require(tuple(sorted(baseline['abPartitions'])) == PARTITIONS, 'Unexpected source partition contract')
    output = Path(output).resolve()
    require(not output.is_relative_to(Path(baseline['directory']).resolve()), 'Reference output must not alter the immutable archive')
    output.mkdir(parents=True, exist_ok=False)
    proto = Path(payload_proto)
    require(proto.is_file() and not proto.is_symlink() and 0 < proto.stat().st_size <= 1024 * 1024
            and digest(proto) == PAYLOAD_PROTO_SHA256, 'Payload protobuf source differs from the reviewed pinned schema')
    proto_ref = copy_file(proto, output / 'update_metadata.proto'); proto_ref['path'] = 'update_metadata.proto'
    tool = Path(avbtool).resolve()
    proof = small(Path(baseline['directory']) / 'signature-verification/signature-verification.json')
    matches = [r['sha256'] for r in proof['toolSources'] if r['path'] == 'external/avb/avbtool.py']
    require(len(matches) == 1 and tool.is_file() and digest(tool) == matches[0], 'Image reader differs from authenticated AVB source')
    sys.dont_write_bytecode = True
    spec = importlib.util.spec_from_file_location('reviewed_ota_raw_image_reader', tool)
    reader = importlib.util.module_from_spec(spec); spec.loader.exec_module(reader)
    full = baseline['fullOtas'][0]
    raw, parts = payload_manifest(full['path'])
    require(all(p['old'] is None and not any(op['readsSource'] for op in p['operations']) for p in parts.values()), 'Source reference must use authenticated full OTA')
    with (output / 'source-payload-manifest.bin').open('xb') as stream:
        stream.write(raw); stream.flush(); os.fsync(stream.fileno())
    records = {}
    temp = output / 'derived-images'; temp.mkdir()
    with zipfile.ZipFile(baseline['targetFiles']['path']) as archive:
        names = archive.namelist(); require(len(names) == len(set(names)), 'Duplicate target-files entries')
        for name in PARTITIONS:
            entry = 'IMAGES/' + name + '.img'
            if entry not in names: entry = 'RADIO/' + name + '.img'
            require(entry in names, 'Missing actual canonical partition image: ' + name)
            path = temp / (name + '.img')
            with archive.open(entry) as incoming, path.open('xb') as target:
                shutil.copyfileobj(incoming, target, 8 * 1024 * 1024)
            stored = {'bytes': path.stat().st_size, 'sha256': digest(path)}
            image = reader.ImageHandler(str(path), read_only=True)
            try:
                expected = parts[name]['new']
                require(image.image_size == expected['bytes'], 'Raw image size differs from signed payload: ' + name)
                checksum = hashlib.sha256(); count = 0
                image.seek(0)
                while count < expected['bytes']:
                    block = image.read(min(8 * 1024 * 1024, expected['bytes'] - count))
                    require(block and len(block) <= expected['bytes'] - count, 'Truncated/overlong raw image')
                    checksum.update(block); count += len(block)
                require(checksum.hexdigest() == expected['sha256'], 'Raw image digest differs from signed payload: ' + name)
                records[name] = {'targetFilesEntry': entry, 'storedImage': stored, 'sparse': image.is_sparse, **expected}
            finally:
                image._image.close()  # Fixed, hash-bound ImageHandler has no public close().
            path.unlink()  # Only successful new derived copies; original archive is untouched.
    require(digest(tool) == matches[0], 'Image reader changed during reference preparation')
    result = {'schema': REFERENCE_SCHEMA, 'createdAt': now(), 'baseline': ota_device.baseline_binding(baseline),
              'fullOta': {k: full[k] for k in ('bytes', 'sha256')}, 'targetFiles': {k: baseline['targetFiles'][k] for k in ('bytes', 'sha256')},
              'manifest': {'path': 'source-payload-manifest.bin', 'bytes': len(raw), 'sha256': blob_sha(raw)},
              'partitions': records, 'payloadSchema': proto_ref, 'imageReaderSha256': matches[0], 'referenceGeneratorSha256': digest(__file__),
              'archiveFullyRevalidated': True, 'allRawImagesMatchSignedManifest': True, 'phoneCommandsRun': False}
    return save(output / 'reference.json', result)


def validate_reference(path, expected_hash, source):
    path = Path(path); value = small(path, expected_hash)
    require(value.get('schema') == REFERENCE_SCHEMA and value.get('baseline') == ota_device.baseline_binding(source),
            'Source reference belongs to another authenticated baseline')
    require(value.get('targetFiles') == {k: source['targetFiles'][k] for k in ('bytes', 'sha256')}
            and value.get('fullOta') in [{k: p[k] for k in ('bytes', 'sha256')} for p in source['fullOtas']]
            and value.get('archiveFullyRevalidated') is True and value.get('allRawImagesMatchSignedManifest') is True
            and value.get('phoneCommandsRun') is False and sha(value.get('imageReaderSha256')),
            'Incomplete actual archive reference')
    proto = checked_ref(path.parent, value.get('payloadSchema'))
    require(proto.name == 'update_metadata.proto' and digest(proto) == PAYLOAD_PROTO_SHA256,
            'Reference payload schema differs from the reviewed fixed source')
    manifest = checked_ref(path.parent, value['manifest'])
    parts = manifest_parts(manifest.read_bytes())
    require(set(value.get('partitions', {})) == set(PARTITIONS), 'Source reference coverage incomplete')
    for name, part in parts.items():
        record = value['partitions'][name]
        require(part['old'] is None and not any(op['readsSource'] for op in part['operations']) and {k: record.get(k) for k in ('bytes', 'sha256')} == part['new']
                and record.get('targetFilesEntry') in ('IMAGES/' + name + '.img', 'RADIO/' + name + '.img')
                and type(record.get('sparse')) is bool and sha(record.get('storedImage', {}).get('sha256')),
                'Source raw image / signed metadata binding differs: ' + name)
    return value


def read_only_state(adb):
    """Record all snapshot state; stale CREATED metadata is not silently removed."""
    values = {}
    for key, command in {
        'bootId': ('cat', '/proc/sys/kernel/random/boot_id'), 'uid': ('id', '-u'),
        'slot': ('bootctl', 'get-current-slot'), 'merge': ('bootctl', 'get-snapshot-merge-status'),
        'snapshots': ('snapshotctl', 'dump'), 'dmDevices': ('dmctl', 'list', 'devices'),
        'withoutSlotSwitch': ('sh', '-c', shlex.quote('if [ -e /metadata/ota/snapshot-boot-without-slot-switch ]; then echo present; else echo absent; fi'))}.items():
        values[key] = adb.shell(*command)
    require(values['uid'] == '0', 'Raw partition qualification needs existing root read access; never invokes adb root')
    require(values['slot'] in ('0', '1'), 'Invalid current slot')
    values['slotSuccessful'] = adb.shell('bootctl', 'is-slot-marked-successful', values['slot'])
    validate_read_only_state(values)
    return values


def validate_read_only_state(values):
    require(values.get('uid') == '0' and values.get('slot') in ('0', '1') and values.get('slotSuccessful') == '1'
            and values.get('merge') == 'none' and values.get('withoutSlotSwitch') == 'absent', 'Source slot/snapshot state is not qualified')
    require(re.fullmatch(r'[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}', values.get('bootId', '')) is not None,
            'Invalid qualification boot ID')
    lines = values.get('snapshots', '').splitlines()
    require('Update state: none' in lines and 'Using snapuserd: 0' in lines and 'Using userspace snapshots: 0' in lines,
            'Active snapshot update is not supported for direct-source qualification')
    require('snapshot' not in values.get('dmDevices', '').lower() and 'cow' not in values.get('dmDevices', '').lower(),
            'Active COW/snapshot mapping requires separate review')
    targets = re.findall(r'^Snapshot: ([a-z0-9_]+)$', values['snapshots'], re.M)
    states = re.findall(r'^\s+state: ([A-Z_]+)$', values['snapshots'], re.M)
    target_suffix = '_b' if values['slot'] == '0' else '_a'
    require(len(targets) == len(states) and len(targets) == len(set(targets))
            and set(targets) <= {p + target_suffix for p in DYNAMIC} and all(s == 'CREATED' for s in states),
            'Unexpected stale snapshot metadata; do not reset or guess')
    return targets


def hash_device_prefix(adb, path, count, stderr_path, *, timeout=600):
    """Stream exactly the authenticated image bytes, count them, never persist raw data."""
    command = [adb.executable, '-s', adb.serial, 'exec-out', '/system/bin/head', '-c', str(count), path]
    checksum = hashlib.sha256(); seen = 0; started = time.monotonic()
    with Path(stderr_path).open('xb') as errors:
        process = subprocess.Popen(command, stdout=subprocess.PIPE, stderr=errors)
        try:
            with selectors.DefaultSelector() as selector:
                selector.register(process.stdout, selectors.EVENT_READ)
                while selector.get_map():
                    require(time.monotonic() - started < timeout, 'Raw source read timed out')
                    for key, _ in selector.select(0.5):
                        block = key.fileobj.read1(1024 * 1024)
                        if not block: selector.unregister(key.fileobj); continue
                        seen += len(block); require(seen <= count, 'Raw source read exceeded requested byte count')
                        checksum.update(block)
            status = process.wait(timeout=10)
        except BaseException:
            process.terminate()
            try: process.wait(timeout=5)
            except subprocess.TimeoutExpired: process.kill(); process.wait()
            raise
        finally:
            process.stdout.close()
        errors.flush(); os.fsync(errors.fileno())
    require(status == 0 and seen == count, 'Raw source read failed or was short')
    return {'command': command, 'exitCode': status, 'bytesRead': seen, 'sha256': checksum.hexdigest(),
            'offset': 0, 'stderrSha256': digest(stderr_path), 'stderr': Path(stderr_path).name,
            'rawBytesSaved': False, 'readOnly': True}


def linear_table(table, expected_bytes, name):
    """dmctl prints the libdm half-open sector range and target type."""
    require(isinstance(table, str) and table.strip(), 'Missing linear mapping table')
    lines = table.splitlines()
    require(lines[0] == 'Targets in the device-mapper table for ' + name + ':', 'Unexpected dmctl table header')
    next_sector = 0
    for line in lines[1:]:
        # Fixed Android dmctl: "0-<end>: linear, <major>:<minor> <physical-sector>".
        match = re.fullmatch(r'\s*(\d+)-(\d+):\s+linear,\s+(\d+):(\d+)\s+(\d+)\s*', line)
        require(match is not None, 'Source logical mapping is not a supported linear target')
        start, end = int(match[1]), int(match[2])
        require(start == next_sector and end > start, 'Linear mapping has a gap/overlap')
        next_sector = end
    require(next_sector * 512 == expected_bytes, 'Linear mapping size differs from source image')


def block_mapping(adb, name, suffix, image_bytes):
    path = '/dev/block/' + ('mapper/' if name in DYNAMIC else 'by-name/') + name + suffix
    resolved = adb.shell('/system/bin/readlink', '-f', '--', path)
    require(re.fullmatch(r'/dev/block/[A-Za-z0-9_./-]+', resolved) is not None
            and '..' not in resolved.split('/'), 'Unexpected raw block path')
    # Follow the canonical by-name/mapper symlink, then inspect POSIX type bits.
    # Toybox calls %F a "block device", while GNU calls it a "block special file".
    mode = adb.shell('/system/bin/stat', '-L', '-c', '%f', path)
    require(re.fullmatch(r'[0-9a-fA-F]{1,4}', mode) is not None and (int(mode, 16) & 0xf000) == 0x6000,
            'Source path mode is not a block device')
    capacity = adb.shell('/system/bin/blockdev', '--getsize64', path)
    require(capacity.isdigit() and int(capacity) >= image_bytes, 'Raw block is shorter than signed image')
    table = None
    if name in DYNAMIC:
        require(re.fullmatch(r'/dev/block/dm-[0-9]+', resolved) is not None and int(capacity) == image_bytes,
                'Dynamic image does not use its exact raw logical mapping')
        table = adb.shell('dmctl', 'table', name + suffix)
        linear_table(table, image_bytes, name + suffix)
    return {'devicePath': path, 'resolvedPath': resolved, 'modeHex': mode, 'blockBytes': int(capacity), 'dmTable': table}


def replay_observation(value, source, before, reference):
    """Re-run the read-only parser against recorded actual stdout, never a device."""
    rows = value.get('commands'); require(isinstance(rows, list) and rows, 'Missing actual source read commands')
    class Replay:
        index = 0
        def call(self, *args, **kwargs):
            require(self.index < len(rows), 'Source read transcript truncated')
            row = rows[self.index]; self.index += 1
            argv = row.get('argv', [])
            require(isinstance(argv, list) and len(argv) >= 4 and argv[1] == '-s'
                    and ota_device.sha_text(argv[2]) == before['device']['adbSerialSha256']
                    and argv[3:] == list(args) and type(row.get('exitCode')) is int and row['exitCode'] == 0
                    and isinstance(row.get('stdout'), str), 'Source read transcript command/result differs')
            self.serial = argv[2]
            return row['stdout']
        def shell(self, *args): return self.call('shell', *args)
    connection = Replay()
    binding = ota_device.baseline_binding(source)
    require(ota_device.inspect_device(connection, binding) == before['device']
            and ota_device.probe_state(connection) == before['probe'], 'Initial source raw identity/probe differs')
    state = read_only_state(connection)
    require(state == value['initialState'] and state['bootId'] == before.get('captureBootId'),
            'Source qualification is not the same boot as read-only before')
    for name in PARTITIONS:
        actual = block_mapping(connection, name, before['device']['slotSuffix'], reference['partitions'][name]['bytes'])
        require(all(value['partitions'][name].get(k) == v for k, v in actual.items()),
                'Source block/mapping summary differs from recorded stdout: ' + name)
    require(ota_device.inspect_device(connection, binding) == before['device']
            and ota_device.probe_state(connection) == before['probe']
            and read_only_state(connection) == value['finalState'] and connection.index == len(rows),
            'Final source read transcript identity/state differs')


def observe_partitions(serial, source, target, before_path, before_hash, reference_path, reference_hash, output,
                       *, adb='adb', runner=None, prefix_reader=None):
    baselines = ota_device.transition_bindings(source, target)
    before = ota_device.validate_before(before_path, before_hash, baselines)
    reference = validate_reference(reference_path, reference_hash, source)
    output = Path(output).resolve(); output.mkdir(parents=True, exist_ok=False)
    records = []; connection = ota_device.Adb(serial, adb, runner)
    class Logged:
        executable, serial = connection.executable, connection.serial
        def call(self, *args, **kw):
            value = connection.call(*args, **kw)
            records.append({'argv': [self.executable, '-s', self.serial, *map(str, args)], 'exitCode': 0, 'stdout': value})
            save(output / ('read-' + str(len(records)).zfill(3) + '.json'), records[-1])
            return value
        def shell(self, *args): return self.call('shell', *args)
    observed = Logged()
    identity = ota_device.inspect_device(observed, baselines['from'])
    require(identity == before['device'] and ota_device.probe_state(observed) == before['probe'], 'Source phone/probe differs from before')
    state = read_only_state(observed)
    require(before.get('schema') == 'taku.ota-device-before/2' and state['bootId'] == before.get('captureBootId'),
            'Direct-image source requires same-boot read-only reuse-probe before/2')
    suffix = identity['slotSuffix']; require(suffix == ('_a' if state['slot'] == '0' else '_b'), 'Slot APIs disagree')
    parts = {}
    reader = prefix_reader or hash_device_prefix
    for name in PARTITIONS:
        record = reference['partitions'][name]
        mapping = block_mapping(observed, name, suffix, record['bytes'])
        path = mapping['devicePath']
        result = reader(observed, path, record['bytes'], output / (name + '.stderr'))
        require(result['bytesRead'] == record['bytes'] and result['sha256'] == record['sha256']
                and result['exitCode'] == 0, 'Actual source image bytes differ: ' + name)
        parts[name] = {**result, **mapping}
        save(output / (name + '.json'), parts[name])
    final_identity = ota_device.inspect_device(observed, baselines['from'])
    final_probe = ota_device.probe_state(observed); final_state = read_only_state(observed)
    require(final_identity == identity and final_probe == before['probe'] and final_state == state,
            'Source identity/probe/boot/snapshot state changed while hashing')
    report = {'schema': QUALIFICATION_SCHEMA, 'capturedAt': now(), 'baseline': baselines['from'],
              'beforeSha256': before_hash, 'referenceSha256': reference_hash, 'collectorScriptSha256': digest(__file__), 'device': identity, 'probe': final_probe,
              'initialState': state, 'finalState': final_state, 'partitions': parts,
              'commands': records, 'phoneDataWritten': False, 'result': 'verified-exact-source-image-prefixes',
              'wholePhysicalPartitionTailsVerified': False, 'staleSnapshotRecords': validate_read_only_state(state)}
    return save(output / 'qualification.json', report)


def validate_qualification(path, expected_hash, reference, reference_hash, source, before, before_hash):
    value = small(path, expected_hash)
    require(value.get('schema') == QUALIFICATION_SCHEMA and value.get('result') == 'verified-exact-source-image-prefixes'
            and value.get('baseline') == ota_device.baseline_binding(source) and value.get('beforeSha256') == before_hash
            and value.get('referenceSha256') == reference_hash and value.get('device') == before['device']
            and value.get('probe') == before['probe'] and value.get('phoneDataWritten') is False
            and sha(value.get('collectorScriptSha256')) and value.get('initialState') == value.get('finalState'), 'Source qualification binding differs')
    states = validate_read_only_state(value['initialState'])
    require(value.get('staleSnapshotRecords') == states and set(value.get('partitions', {})) == set(PARTITIONS),
            'Source qualification coverage differs')
    suffix = before['device']['slotSuffix']
    require(suffix == ('_a' if value['initialState']['slot'] == '0' else '_b'), 'Qualification current slot differs')
    for name in PARTITIONS:
        expected = reference['partitions'][name]; actual = value['partitions'][name]
        path_name = '/dev/block/' + ('mapper/' if name in DYNAMIC else 'by-name/') + name + suffix
        command = actual.get('command')
        require(isinstance(command, list) and len(command) == 8 and isinstance(command[0], str)
                and isinstance(command[2], str) and command[1:] == ['-s', command[2],
                    'exec-out', '/system/bin/head', '-c', str(expected['bytes']), path_name]
                and ota_device.sha_text(command[2]) == before['device']['adbSerialSha256']
                and actual.get('devicePath') == path_name and type(actual.get('exitCode')) is int and actual['exitCode'] == 0
                and actual.get('offset') == 0 and actual.get('bytesRead') == expected['bytes']
                and actual.get('sha256') == expected['sha256'] and actual.get('readOnly') is True
                and actual.get('rawBytesSaved') is False and type(actual.get('blockBytes')) is int
                and actual['blockBytes'] >= expected['bytes'], 'Invalid exact source image read: ' + name)
        require(digest(file_under(Path(path).parent.resolve(), relative_path(actual['stderr']))) == actual['stderrSha256'],
                'Raw source read diagnostic changed')
    replay_observation(value, source, before, reference)
    return value


def validate_delta_source(package, reference, qualification):
    _, parts = payload_manifest(package)
    for name, part in parts.items():
        old = part['old']
        require(not any(op['readsSource'] for op in part['operations']) or old is not None,
                'Delta reads source without old_partition_info: ' + name)
        if old is not None:
            require(old == {k: reference['partitions'][name][k] for k in ('bytes', 'sha256')},
                    'Delta old_partition_info differs from qualified source: ' + name)
    stale = qualification.get('staleSnapshotRecords', [])
    require({name[:-2] for name in stale} <= set(parts), 'Delta does not cover existing stale target snapshot records')
    return {'allOldPartitionInfosMatchQualifiedSource': True, 'sourceReadingPartitions': sorted(
        n for n, p in parts.items() if any(o['readsSource'] for o in p['operations'])),
        'staleTargetSnapshotCoverageVerified': True, 'offlinePayloadApplyVerified': False}


def evidence_input(path, expected_hash):
    value = small(path, expected_hash)
    require(value.get('schema') == 'taku.direct-image-source-input/1'
            and set(value.get('files', {})) == {'installationPlan', 'flashResult', 'flashCommands', 'postbootReport'},
            'Expected four explicit original direct-image evidence references')
    paths = {}
    for name, ref in value['files'].items():
        require(isinstance(ref, dict) and isinstance(ref.get('path'), str) and Path(ref['path']).is_absolute()
                and sha(ref.get('sha256')), 'Explicit original path/SHA is required')
        source = Path(ref['path']); small_file = source.is_file() and not source.is_symlink() and source.stat().st_size <= 8 * 1024 * 1024
        require(small_file and digest(source) == ref['sha256'], 'Original direct evidence changed: ' + name)
        paths[name] = source
    return value, paths


def bind_direct_source(plan, before_path, before_hash, reference_path, reference_hash,
                       qualification_path, qualification_hash, input_path, input_hash, output):
    before = ota_device.validate_before(before_path, before_hash, ota_device.transition_bindings(plan['source'], plan['target']))
    reference = validate_reference(reference_path, reference_hash, plan['source'])
    validate_qualification(qualification_path, qualification_hash, reference, reference_hash, plan['source'], before, before_hash)
    original, paths = evidence_input(input_path, input_hash)
    output = Path(output).resolve(); output.mkdir(parents=True, exist_ok=False)
    historical = {}
    def copy_ref(source, relative):
        destination = output / relative
        result = copy_file(source, destination); result['path'] = relative
        return result
    for name in ('installationPlan', 'flashResult', 'flashCommands'):
        historical[name] = copy_ref(paths[name], 'historical/' + paths[name].name)
    # Keep the original report and its real siblings; never rewrite absolute fields inside it.
    for name, filename in [('postbootReport','report.json'), ('postbootCommands','commands.jsonl'), ('postbootInputs','input-bindings.json')]:
        source = paths['postbootReport'] if name == 'postbootReport' else paths['postbootReport'].with_name(filename)
        historical[name] = copy_ref(source, 'postboot/' + filename)
    direct_plan = small(paths['installationPlan'])
    flash_logs = {}
    for line in paths['flashCommands'].read_text().splitlines():
        row = json.loads(line); command = row.get('argv', [])
        if len(command) == 6 and command[3] == 'flash' and 'exitCode' in row:
            name = command[4]
            require(name in FLASH_ORDER and name not in flash_logs and sha(row.get('logSha256')),
                    'Missing/duplicate actual flash log')
            source = Path(row.get('log', ''))
            require(source.is_absolute() and source.is_file() and not source.is_symlink()
                    and source.stat().st_size <= 8 * 1024 * 1024 and digest(source) == row['logSha256'],
                    'Original flash log changed')
            flash_logs[name] = copy_ref(source, 'historical/flash-logs/' + name + '.log')
    require(set(flash_logs) == set(FLASH_ORDER), 'Historical flash logs incomplete')
    proof_index = {}
    for name in ('baselineReceipt','runInput','signatureReport','superVerification','superProducer','superVerificationInput','superVerifyController','superProducerController'):
        item = direct_plan.get('proofs', {}).get(name, {})
        require(isinstance(item.get('path'), str) and Path(item['path']).is_absolute() and sha(item.get('sha256')),
                'Missing original direct installation proof: ' + name)
        source = Path(item['path']); small(source, item['sha256'])
        proof_index[name] = copy_ref(source, 'historical/proofs/' + name + '.json')
        require(proof_index[name]['sha256'] == item['sha256'], 'Original proof changed')
    reference_ref = copy_ref(reference_path, 'reference/reference.json')
    for role in ('manifest', 'payloadSchema'):
        item = reference[role]
        copy_ref(checked_ref(Path(reference_path).parent, item), 'reference/' + relative_path(item['path']))
    qualification_ref = copy_ref(qualification_path, 'qualification/qualification.json')
    qualified = small(qualification_path)
    for name in PARTITIONS:
        item = qualified['partitions'][name]
        copy_ref(file_under(Path(qualification_path).parent.resolve(), relative_path(item['stderr'])), 'qualification/' + item['stderr'])
    result = {'schema': SOURCE_SCHEMA, 'method': 'direct-fastboot-images', 'reviewedAt': now(),
              'provenanceScope': 'Current revalidation of unchanged historical direct-image installation evidence, plus a new exact source-byte observation. Not a sideload or OTA installation.',
              'baseline': ota_device.baseline_binding(plan['source']), 'beforeSha256': before_hash,
              'inputSha256': input_hash, 'historicalEvidence': historical, 'flashLogs': flash_logs, 'proofs': proof_index,
              'reference': reference_ref, 'qualification': qualification_ref,
              'sourceInstallationWasOta': False, 'phoneDataWrittenByAdapter': False, 'allUserDataVerified': False}
    record = save(output / 'installation.json', result)
    validate_direct_source(Path(record['path']), record['sha256'], plan['source'], before, before_hash)
    return record


def validate_direct_source(path, expected_hash, source, before, before_hash):
    path = Path(path); value = small(path, expected_hash); root = path.parent
    require(value.get('schema') == SOURCE_SCHEMA and value.get('method') == 'direct-fastboot-images'
            and value.get('baseline') == ota_device.baseline_binding(source) and value.get('beforeSha256') == before_hash
            and value.get('sourceInstallationWasOta') is False and value.get('phoneDataWrittenByAdapter') is False
            and value.get('allUserDataVerified') is False, 'Direct-image source binding differs')
    historical = value.get('historicalEvidence', {})
    require(set(historical) == {'installationPlan','flashResult','flashCommands','postbootReport','postbootCommands','postbootInputs'},
            'Historical source evidence incomplete')
    files = {name: checked_ref(root, ref) for name, ref in historical.items()}
    plan, result = small(files['installationPlan']), small(files['flashResult'])
    require(plan.get('schema') == 'taku.pixel9-branded-runtime-install-plan/1' and plan.get('candidate') == 'I'
            and plan.get('buildNumber') == source['identity']['buildNumber'] and plan.get('snapshotDigest') == source['snapshotDigest']
            and plan.get('sourceTargetFilesSha256') == source['targetFiles']['sha256'] and plan.get('finalized') is True
            and plan.get('templateOnly') is False and plan.get('userdataAndMetadataExcluded') is True
            and plan.get('writeOrder') == list(FLASH_ORDER), 'Direct installation plan differs from source archive')
    serial = plan.get('selectedSerial')
    require(isinstance(serial, str) and ota_device.sha_text(serial) == before['device']['hardwareSerialSha256']
            and result.get('selectedSerial') == serial and result.get('planSha256') == historical['installationPlan']['sha256']
            and result.get('mode') == 'execute' and result.get('flashCommandsCompleted') is True
            and result.get('completedFlashSteps') == list(FLASH_ORDER)
            and result.get('status') == 'flash-complete-await-boot-validation', 'Historical flash completion or device differs')
    require(result.get('deviceBootVerified') is False and result.get('dataPreservationVerified') is False,
            'Original flash-only result must keep its unverified boot/data claims')
    rows = [json.loads(line) for line in files['flashCommands'].read_text().splitlines()]
    flash = []
    for row in rows:
        command = row.get('argv', [])
        require(len(command) >= 5 and command[1:3] == ['-s', serial]
                and Path(command[0]).name == 'fastboot', 'Unrecognized historical device command')
        if command[3] == 'getvar':
            require(len(command) == 5 and row.get('exitCode') == 0 and row.get('phase') is None,
                    'Historical preflight failed')
        else:
            require(command[3] == 'flash' and len(command) == 6 and command[4] in FLASH_ORDER,
                    'Unapproved write/erase/slot/AVB operation in historical record')
            flash.append(row)
    require(len(flash) == 2 * len(FLASH_ORDER), 'Missing/duplicate actual flash command pairs')
    require(set(value.get('flashLogs', {})) == set(FLASH_ORDER), 'Missing exact historical flash logs')
    for i, partition in enumerate(FLASH_ORDER):
        start, end = flash[2*i:2*i+2]
        expected_command = [start['argv'][0], '-s', serial, 'flash', partition, plan['images'][partition]['path']]
        require(start['argv'] == end['argv'] == expected_command and start.get('phase') == 'starting-flash'
                and 'exitCode' not in start and type(end.get('exitCode')) is int and end['exitCode'] == 0
                and end.get('phase') is None and end.get('imageSha256') == plan['images'][partition]['sha256'],
                'Historical exact flash failed or differs: ' + partition)
        log_ref = value['flashLogs'][partition]
        log = checked_ref(root, log_ref).read_text()
        require(log_ref['sha256'] == end.get('logSha256') and 'OKAY' in log and 'FAILED' not in log,
                'Historical flash log did not verify success: ' + partition)
    require(set(value.get('proofs', {})) == {'baselineReceipt','runInput','signatureReport','superVerification',
            'superProducer','superVerificationInput','superVerifyController','superProducerController'},
            'Missing exact direct-install proof roles')
    proofs = {name: small(checked_ref(root, record)) for name, record in value.get('proofs', {}).items()}
    for name, record in value['proofs'].items():
        require(record['sha256'] == plan.get('proofs', {}).get(name, {}).get('sha256'), 'Original direct proof binding differs')
    require(proofs['baselineReceipt']['buildIdentity'] == source['identity']
            and value['proofs']['baselineReceipt']['sha256'] == source['receiptSha256']
            and value['proofs']['runInput']['sha256'] == source['runInputSha256']
            and value['proofs']['signatureReport']['sha256'] == source['signatureReportSha256'],
            'Historical direct installation is not the accepted source archive')
    verified, produced = proofs['superVerification'], proofs['superProducer']
    require(verified.get('schema') == 'taku.I-super-verify/1' and verified.get('result') == 'verified'
            and produced.get('result') == 'generated' and verified.get('buildNumber') == source['identity']['buildNumber']
            and verified.get('snapshotDigest') == source['snapshotDigest'] and verified.get('receiptSha256') == source['receiptSha256']
            and verified.get('producer', {}).get('reportSha256') == value['proofs']['superProducer']['sha256']
            and verified.get('inputSha256') == produced.get('inputSha256') == value['proofs']['superVerificationInput']['sha256']
            and verified.get('userdataIncluded') is False and verified.get('metadataPhysicalPartitionIncluded') is False
            and verified.get('pvmfwOrFirmwareIncluded') is False, 'Historical super verification differs')
    for controller, report in [('superProducerController', 'superProducer'), ('superVerifyController', 'superVerification')]:
        require(type(proofs[controller].get('actualDockerExitCode')) is int and proofs[controller]['actualDockerExitCode'] == 0
                and proofs[controller].get('reportSha256') == value['proofs'][report]['sha256'],
                'Historical producer/verification controller did not complete')
    require(verified['producer'].get('containerResultSha256') == value['proofs']['superProducerController']['sha256']
            and verified['producer'].get('actualDockerExitCode') == 0
            and plan['proofs'].get('superProducerInput', {}).get('sha256') == value['proofs']['superVerificationInput']['sha256']
            and all(type(c.get('actualExitCode')) is int and c['actualExitCode'] == 0
                    for report in (verified, produced) for c in report.get('commands', []))
            and verified.get('commands') and produced.get('commands'), 'Historical super execution evidence differs')
    super_input = proofs['superVerificationInput']
    require(super_input.get('receiptSha256') == source['receiptSha256']
            and super_input.get('runInputSha256') == source['runInputSha256']
            and super_input.get('signatureReportSha256') == source['signatureReportSha256']
            and super_input.get('vbmetaDigest') == source['vbmetaDigest']
            and super_input.get('targetFiles', {}).get('sha256') == source['targetFiles']['sha256'],
            'Historical super input belongs to another source archive')
    reference_path = checked_ref(root, value['reference'])
    reference = validate_reference(reference_path, value['reference']['sha256'], source)
    for name in DYNAMIC:
        require(verified.get('logicalPartitions', {}).get(name + '_a') == {k: reference['partitions'][name][k] for k in ('bytes','sha256')},
                'Flashed super contents do not match source reference: ' + name)
    require(set(plan['images']) == set(FLASH_ORDER) and {k: plan['images']['super'][k] for k in ('bytes','sha256')} ==
            {k: verified['superImage'][k] for k in ('bytes','sha256')}, 'Flashed super byte identity differs')
    for name in BOOT:
        expected = {k: reference['partitions'][name][k] for k in ('bytes','sha256')}
        require(verified.get('bootImages', {}).get(name) == expected
                and {k: plan['images'][name + '_a'][k] for k in ('bytes','sha256')} == expected,
                'Flashed startup image differs: ' + name)
    require(files['postbootCommands'] == files['postbootReport'].with_name('commands.jsonl')
            and files['postbootInputs'] == files['postbootReport'].with_name('input-bindings.json'), 'Postboot sibling paths differ')
    anchor = ota_device.load_probe_anchor(files['postbootReport'], historical['postbootReport']['sha256'], ota_device.baseline_binding(source))
    require(anchor['probe'] == before['probe'] and anchor['hardwareSerialSha256'] == before['device']['hardwareSerialSha256'],
            'Historical source boot/probe differs from fresh before')
    qualification_path = checked_ref(root, value['qualification'])
    qualification = validate_qualification(qualification_path, value['qualification']['sha256'], reference,
                                          value['reference']['sha256'], source, before, before_hash)
    return value, reference, qualification


def copy_direct_source(path, expected_hash, destination, source, before, before_hash):
    value, _, _ = validate_direct_source(path, expected_hash, source, before, before_hash)
    path, destination = Path(path), Path(destination)
    destination.mkdir(parents=True, exist_ok=False)
    copy_file(path, destination / 'installation.json')
    refs = [*value['historicalEvidence'].values(), *value['proofs'].values(), *value['flashLogs'].values(), value['reference'], value['qualification']]
    reference = small(checked_ref(path.parent, value['reference']))
    for role in ('manifest', 'payloadSchema'):
        item = reference[role]
        refs.append({**item, 'path': str(Path(value['reference']['path']).parent / relative_path(item['path']))})
    qualification = small(checked_ref(path.parent, value['qualification']))
    for part in qualification['partitions'].values():
        relative = str(Path(value['qualification']['path']).parent / part['stderr'])
        original = file_under(path.parent.resolve(), relative_path(relative))
        refs.append({'path': relative, 'bytes': original.stat().st_size, 'sha256': part['stderrSha256']})
    for item in refs:
        original = file_under(path.parent.resolve(), relative_path(item['path']))
        require(digest(original) == item['sha256'], 'Source portable evidence changed')
        copy_file(original, destination / item['path'])
    validate_direct_source(destination / 'installation.json', expected_hash, source, before, before_hash)
