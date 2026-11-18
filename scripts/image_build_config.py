"""Shared identity for the current image builder; historical runs stay separate."""
import json
import os
import pathlib
import re

ROOT = pathlib.Path(__file__).resolve().parents[1]
CONFIG = json.loads((ROOT / 'image/build-config.json').read_text())
SOURCE_LOCK = json.loads((ROOT / 'image/source-lock.json').read_text())


def validate_go_runtime(config):
    for key in ('soongGoMemoryLimitGiB', 'soongGoMaxProcs'):
        if type(config.get(key)) is not int or config[key] <= 0:
            raise ValueError('Expected a positive integer for ' + key)
    if config['soongGoMemoryLimitGiB'] >= config['memoryGiB']:
        raise ValueError('Soong Go memory limit must leave room within container memory')
    if config['soongGoMaxProcs'] > config['cpuQuota']:
        raise ValueError('Soong Go parallelism must not exceed the container CPU quota')


validate_go_runtime(CONFIG)


def project_path(key, prefix):
    path = pathlib.PurePosixPath(CONFIG[key])
    if path.is_absolute() or '..' in path.parts or path.parts[0] != prefix:
        raise ValueError('Invalid builder path: ' + key)
    return ROOT / path


OUT = project_path('outputDirectory', 'out')
REPORT = project_path('statusReport', 'docs')
NAME = CONFIG['container']
VOLUME = CONFIG['sourceVolume']
REFERENCE = CONFIG['referenceVolume']
PREVIOUS = CONFIG['previousSourceVolume']
IMAGE = os.environ.get('TAKU_BUILDER_IMAGE', CONFIG['builderImage'])
for value in (NAME, VOLUME, REFERENCE, PREVIOUS):
    if not re.fullmatch(r'[a-zA-Z0-9][a-zA-Z0-9_.-]*', value):
        raise ValueError('Invalid Docker resource name')
if VOLUME in (REFERENCE, PREVIOUS) or REFERENCE == PREVIOUS:
    raise ValueError('Build volume must differ from the read-only reference')
if not re.fullmatch(r'sha256:[a-f0-9]{64}', IMAGE):
    raise ValueError('Builder image must be pinned by digest')
