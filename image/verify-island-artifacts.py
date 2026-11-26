#!/usr/bin/env python3
"""Check island components in built APKs and preinstalled Agent docs; no device acceptance claim."""
import argparse
import hashlib
import json
from pathlib import Path
import subprocess
import zipfile

SYSTEM_COMPONENTS = (
    'com.android.systemui.taku.island.IslandProvider',
    'com.android.systemui.taku.island.IslandSettingsActivity',
)
AGENT_COMPONENTS = (
    'org.agentos.agent.home.island.IslandPublisherService',
    'org.agentos.agent.home.island.IslandActionReceiver',
)
DOC = 'assets/skills/taku-island/SKILL.md'


def require(ok, message):
    if not ok:
        raise ValueError(message)


def verify_apk(apk, aapt2, components, markers):
    manifest = subprocess.check_output([str(aapt2), 'dump', 'xmltree', str(apk), '--file', 'AndroidManifest.xml'], text=True)
    for value in (*components, *markers):
        require(value in manifest, 'Missing island manifest entry: ' + value)
    with zipfile.ZipFile(apk) as archive:
        dex = [archive.read(name) for name in archive.namelist()
               if name.startswith('classes') and name.endswith('.dex') and '/' not in name]
        for name in components:
            descriptor = ('L' + name.replace('.', '/') + ';').encode()
            require(any(descriptor in value for value in dex), 'Missing island implementation: ' + name)
    return {'path': str(apk), 'sha256': hashlib.sha256(apk.read_bytes()).hexdigest(), 'components': list(components)}


def verify(product, aapt2):
    def one(name):
        matches = [p for partition in ('system', 'system_ext', 'product')
                   for p in (product / partition).rglob(name)]
        require(len(matches) == 1, 'Expected one built ' + name)
        return matches[0]
    system = verify_apk(one('SystemUI.apk'), aapt2, SYSTEM_COMPONENTS,
                        ('com.android.systemui.island', 'org.agentos.permission.BIND_ISLAND_CONTENT'))
    agent = verify_apk(one('TakuAgent.apk'), aapt2, AGENT_COMPONENTS,
                       ('com.android.systemui.island', 'org.agentos.permission.BIND_ISLAND_CONTENT'))
    with zipfile.ZipFile(one('TakuAssistant.apk')) as archive:
        require(DOC in archive.namelist(), 'Missing preinstalled island Agent documentation')
        source = Path(__file__).resolve().parents[1] / 'system/assistant/assets/skills/taku-island/SKILL.md'
        require(archive.read(DOC) == source.read_bytes(), 'Stale preinstalled island Agent documentation')
    return {'schema': 'taku.island-artifacts/1', 'artifactContentsVerified': True,
            'systemUi': system, 'agent': agent, 'agentDocSha256': hashlib.sha256(source.read_bytes()).hexdigest(),
            'daggerRuntimeVerified': False, 'deviceVerified': False}


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--product', type=Path, required=True)
    parser.add_argument('--aapt2', type=Path, required=True)
    parser.add_argument('--output', type=Path, required=True)
    args = parser.parse_args()
    result = verify(args.product, args.aapt2)
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(json.dumps(result, indent=2) + '\n')
    print(args.output)
