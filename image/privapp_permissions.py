"""Check the actual Memory policy installed by Soong beside its privileged APK."""
import hashlib
import re
import stat
import xml.etree.ElementTree as ET

SOURCE = 'system/memory/org.agentos.memory.xml'
TARGET = 'SYSTEM_EXT/etc/permissions/TakuMemory.xml'
PACKAGE = 'org.agentos.memory'
PERMISSION = 'android.permission.DUMP'
APK = 'SYSTEM_EXT/priv-app/TakuMemory/TakuMemory.apk'
FRAMEWORK = 'SYSTEM/framework/framework-res.apk'


def permission_definitions(xmltree):
    """Read actual aapt2 permission definitions, retaining compact source proof."""
    result, stanza = {}, []
    def finish():
        if not stanza:
            return
        text = '\n'.join(stanza)
        name = re.search(r':name\(0x[0-9a-f]+\)="([^"]+)"', text)
        level = re.search(r':protectionLevel\(0x[0-9a-f]+\)=0x([0-9a-f]+)', text)
        if not name or name[1] in result:
            raise ValueError('Missing or duplicate framework permission definition')
        result[name[1]] = {'protectionLevel': int(level[1], 16) if level else 0, 'aapt2Proof': text}
    for line in xmltree.splitlines():
        if re.match(r'\s*E:', line):
            finish();stanza = []
            if re.match(r'\s*E: permission \(line=', line):
                stanza = [line]
        elif stanza and re.match(r'\s*A:', line):
            stanza.append(line)
    finish()
    if not result:
        raise ValueError('No framework permission definitions')
    return result


def requested_permissions(badging):
    return sorted(set(re.findall(r"^uses-permission(?::|-[^:]+:) name='([^']+)'", badging, re.M)))


def audit(archive, definitions, apk_badging):
    """Every actual Taku privileged APK must have all requested privileged grants."""
    grants, denies = {}, {}
    for info in archive.infolist():
        if not re.fullmatch(r'SYSTEM_EXT/etc/permissions/[^/]+\.xml', info.filename):
            continue
        if archive.namelist().count(info.filename) != 1 or stat.S_ISLNK(info.external_attr >> 16):
            raise ValueError('Duplicate or linked system_ext permission policy')
        for node in ET.fromstring(archive.read(info)).iter('privapp-permissions'):
            package = node.get('package')
            grants.setdefault(package, set()).update(x.get('name') for x in node.findall('permission'))
            denies.setdefault(package, set()).update(x.get('name') for x in node.findall('deny-permission'))
    records, used = {}, set()
    paths = sorted(n for n in archive.namelist() if re.fullmatch(r'SYSTEM_EXT/priv-app/Taku[^/]+/[^/]+\.apk', n))
    if not {APK, 'SYSTEM_EXT/priv-app/TakuAssistant/TakuAssistant.apk'} <= set(paths):
        raise ValueError('Missing Taku privileged application in permission audit')
    for path in paths:
        if archive.namelist().count(path) != 1 or stat.S_ISLNK(archive.getinfo(path).external_attr >> 16):
            raise ValueError('Duplicate or linked Taku privileged APK')
        raw = archive.read(path);text = apk_badging(raw)
        package = re.search(r"^package: name='([^']+)'", text, re.M)
        if not package or package[1] in records:
            raise ValueError('Missing or duplicate Taku package identity')
        package = package[1];requested = requested_permissions(text)
        platform = set(requested) & definitions.keys()
        if any(p.startswith('android.permission.') and p not in definitions for p in requested):
            raise ValueError('Unknown requested Android permission: ' + package)
        used.update(platform)
        privileged = sorted(p for p in platform if definitions[p]['protectionLevel'] & 0x10)
        if not set(privileged) <= grants.get(package, set()) or set(privileged) & denies.get(package, set()):
            raise ValueError('Taku privileged permissions not fully allowed: ' + package)
        records[package] = {'path': path, 'apkSha256': sha(raw), 'requestedPermissions': requested,
                            'aapt2BadgingProof': text, 'aapt2BadgingSha256': sha(text.encode()),
                            'requiredPrivilegedPermissions': privileged, 'allRequestedPrivilegedPermissionsAllowed': True}
    return {'schema': 'taku.privapp-request-closure/1', 'frameworkApkSha256': sha(archive.read(FRAMEWORK)),
            'frameworkDefinitions': {p: definitions[p] for p in sorted(used)}, 'applications': records,
            'savedValidationScope': 'Recheck bound extraction proof and actual archive hashes/policy; APK manifest extraction is performed at build time.'}


def validate_audit(archive, report):
    definitions = report.get('frameworkDefinitions', {})
    parsed = permission_definitions('\n'.join(x['aapt2Proof'] for x in definitions.values()))
    if parsed != definitions:
        raise ValueError('Saved framework permission definitions differ')
    by_hash = {item['apkSha256']: (package, item) for package, item in report.get('applications', {}).items()}
    def badging(raw):
        package, record = by_hash[sha(raw)]
        text = record['aapt2BadgingProof']
        if sha(text.encode()) != record['aapt2BadgingSha256']:
            raise ValueError('Saved APK permission extraction proof changed')
        return text
    if audit(archive, definitions, badging) != report:
        raise ValueError('Saved Taku permission closure differs from actual archive')


def sha(raw):
    return hashlib.sha256(raw).hexdigest()


def verify(archive, source_sha, requested_permissions):
    if not re.fullmatch(r'[0-9a-f]{64}', source_sha or ''):
        raise ValueError('Missing frozen Memory permission policy input')
    if (not isinstance(requested_permissions, list)
            or any(not isinstance(p, str) for p in requested_permissions)
            or requested_permissions != sorted(set(requested_permissions))
            or PERMISSION not in requested_permissions):
        raise ValueError('Actual Memory APK permission request differs')
    for path in (TARGET, APK):
        if archive.namelist().count(path) != 1 or stat.S_ISLNK(archive.getinfo(path).external_attr >> 16):
            raise ValueError('Missing, duplicate or linked Memory policy/APK: ' + path)
    raw = archive.read(TARGET)
    if sha(raw) != source_sha:
        raise ValueError('Installed Memory permission policy differs from frozen input')
    fs_path = 'META/system_ext_filesystem_config.txt'
    fs = {parts[0]: parts[1:4] for line in archive.read(fs_path).decode().splitlines()
          if len(parts := line.split()) >= 4}
    metadata = fs.get('system_ext/etc/permissions/TakuMemory.xml')
    if not metadata or metadata[:2] != ['0', '0'] or int(metadata[2], 8) != 0o644:
        raise ValueError('Memory permission policy ownership/mode differs')
    root = ET.fromstring(raw)
    if (root.tag != 'permissions' or root.attrib or len(root) != 1
            or root[0].tag != 'privapp-permissions' or root[0].attrib != {'package': PACKAGE}
            or len(root[0]) != 1 or root[0][0].tag != 'permission'
            or root[0][0].attrib != {'name': PERMISSION} or len(root[0][0])):
        raise ValueError('Memory permission policy must grant only its reviewed DUMP permission')
    # A second stanza (including a deny) could change the effective permission.
    for info in archive.infolist():
        if info.filename == TARGET or not re.fullmatch(r'SYSTEM_EXT/etc/permissions/[^/]+\.xml', info.filename):
            continue
        if stat.S_ISLNK(info.external_attr >> 16):
            raise ValueError('Linked system_ext permission policy')
        policy = ET.fromstring(archive.read(info))
        if any(node.get('package') == PACKAGE for node in policy.iter('privapp-permissions')):
            raise ValueError('Conflicting additional Memory permission policy')
    return {'schema': 'taku.memory-privapp-policy/1', 'package': PACKAGE,
            'targetFile': TARGET, 'sourceFile': SOURCE, 'sourceSha256': source_sha,
            'sha256': sha(raw), 'bytes': len(raw), 'grantedPermissions': [PERMISSION],
            'requestedPermissions': requested_permissions, 'apkSha256': sha(archive.read(APK))}
