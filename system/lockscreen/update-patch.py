#!/usr/bin/env python3
"""Regenerate image/lockscreen from owned source and the pinned upstream SystemUI bytes.

Every upstream hook is an anchored edit that must match exactly once in the pinned file, so the patch
is reproducible and fails loudly when upstream changes. Upstream bytes come from --upstream-root (a
frameworks/base checkout at the pinned revision) or, with --fetch, from the pinned LineageOS commit.
"""
import argparse
import difflib
import hashlib
import json
from pathlib import Path
import tempfile
import urllib.request

ROOT = Path(__file__).resolve().parents[2]
OUTPUT = ROOT / 'image/lockscreen'
SOURCES = ROOT / 'system/lockscreen/src'
SYSTEMUI = 'packages/SystemUI/src/'
PATCH = 'systemui-lockscreen.patch'
REPOSITORY = 'frameworks/base'
FETCH_URL = 'https://raw.githubusercontent.com/LineageOS/android_frameworks_base/{revision}/{path}'

PKG = 'com.android.systemui.taku.lockscreen'
HOOKS = {
    SYSTEMUI + 'com/android/systemui/keyguard/ui/view/layout/sections/transitions/ClockSizeTransition.kt': [
        ('import android.animation.Animator\n',
         'import android.animation.Animator\n'
         f'import {PKG}.TakuClockController\n'
         f'import {PKG}.TakuClockSizeTransition\n'),
        ('            addTransition(ClockFaceOutTransition(config, clockViewModel, logBuffer))\n'
         '            addTransition(ClockFaceInTransition(config, clockViewModel, logBuffer))\n',
         '            // Taku interpolates native glyph outlines instead of crossfading two clocks.\n'
         '            if (clockViewModel.currentClock.value is TakuClockController) {\n'
         '                addTransition(TakuClockSizeTransition())\n'
         '            } else {\n'
         '                addTransition(ClockFaceOutTransition(config, clockViewModel, logBuffer))\n'
         '                addTransition(ClockFaceInTransition(config, clockViewModel, logBuffer))\n'
         '            }\n'),
    ],
    SYSTEMUI + 'com/android/systemui/keyguard/ui/view/layout/blueprints/KeyguardBlueprintModule.kt': [
        ('import com.android.systemui.keyguard.shared.model.KeyguardBlueprint\n',
         'import com.android.systemui.keyguard.shared.model.KeyguardBlueprint\n'
         f'import {PKG}.TakuKeyguardBlueprint\n'),
        ('    abstract fun bindSplitShadeBlueprint(\n'
         '        splitShadeBlueprint: SplitShadeKeyguardBlueprint\n'
         '    ): KeyguardBlueprint\n',
         '    abstract fun bindSplitShadeBlueprint(\n'
         '        splitShadeBlueprint: SplitShadeKeyguardBlueprint\n'
         '    ): KeyguardBlueprint\n'
         '\n'
         '    // Taku: the lock screen layout (system/lockscreen, docs/lock-screen.md).\n'
         '    @Binds\n'
         '    @IntoSet\n'
         '    abstract fun bindTakuBlueprint(takuBlueprint: TakuKeyguardBlueprint): KeyguardBlueprint\n'),
    ],
    SYSTEMUI + 'com/android/systemui/keyguard/domain/interactor/KeyguardBlueprintInteractor.kt': [
        ('import com.android.systemui.dagger.SysUISingleton\n',
         'import com.android.systemui.dagger.SysUISingleton\n'
         f'import {PKG}.TakuKeyguardBlueprint\n'),
        ('                else -> DefaultKeyguardBlueprint.DEFAULT\n',
         '                // Taku: single-shade lock screens use the Taku layout.\n'
         '                else -> TakuKeyguardBlueprint.ID\n'),
    ],
    SYSTEMUI + 'com/android/systemui/keyguard/data/repository/KeyguardBlueprintRepository.kt': [
        ('import com.android.systemui.keyguard.ui.view.layout.blueprints.DefaultKeyguardBlueprint.Companion.DEFAULT\n',
         'import com.android.systemui.keyguard.ui.view.layout.blueprints.DefaultKeyguardBlueprint.Companion.DEFAULT\n'
         f'import {PKG}.TakuKeyguardBlueprint\n'),
        ('MutableStateFlow(blueprintIdMap[DEFAULT]!!)',
         'MutableStateFlow(blueprintIdMap[TakuKeyguardBlueprint.ID] ?: blueprintIdMap[DEFAULT]!!)'),
    ],
    SYSTEMUI + 'com/android/keyguard/dagger/ClockRegistryModule.java': [
        ('import com.android.systemui.dagger.SysUISingleton;\n',
         'import com.android.systemui.dagger.SysUISingleton;\n'
         f'import {PKG}.TakuClockProvider;\n'),
        ('                new DefaultClockProvider(\n'
         '                        layoutInflater,\n'
         '                        resources,\n'
         '                        vibrator\n'
         '                ),\n'
         '                context.getString(R.string.lockscreen_clock_id_fallback),\n',
         '                // Taku: the stock clocks plus the Taku clock, which is the default.\n'
         '                new TakuClockProvider(context, new DefaultClockProvider(\n'
         '                        layoutInflater,\n'
         '                        resources,\n'
         '                        vibrator\n'
         '                )),\n'
         '                TakuClockProvider.TAKU_CLOCK_ID,\n'),
    ],
    SYSTEMUI + 'com/android/systemui/statusbar/notification/interruption/KeyguardNotificationVisibilityProvider.kt': [
        ('import com.android.systemui.CoreStartable\n',
         'import com.android.systemui.CoreStartable\n'
         f'import {PKG}.TakuLockscreenPolicy\n'),
        ('            !isLockedOrLocking -> SHOW\n',
         '            !isLockedOrLocking -> SHOW\n'
         '            // Taku: nothing shows on the lock screen until the user authenticates.\n'
         '            TakuLockscreenPolicy.HIDE_NOTIFICATIONS_UNTIL_AUTHENTICATED &&\n'
         '                !keyguardStateController.isUnlocked -> HIDE\n'),
    ],
}


def digest(data):
    return hashlib.sha256(data).hexdigest()


def pinned_revision():
    import xml.etree.ElementTree as ET
    pins = {project.attrib['path']: project.attrib['revision']
            for project in ET.parse(ROOT / 'image/manifests/platform.xml').iter('project')}
    return pins[REPOSITORY]


def upstream_bytes(path, upstream, revision):
    if upstream is not None:
        return (upstream / path).read_bytes()
    with urllib.request.urlopen(FETCH_URL.format(revision=revision, path=path), timeout=60) as response:
        return response.read()


def apply_hooks(path, text):
    for anchor, replacement in HOOKS[path]:
        count = text.count(anchor)
        if count != 1:
            raise ValueError(f'{path}: anchor must occur once, found {count}: {anchor[:60]!r}')
        text = text.replace(anchor, replacement)
    return text


def generate(upstream=None):
    revision = pinned_revision()
    before, after, sources = {}, {}, {}
    for path in HOOKS:
        original = upstream_bytes(path, upstream, revision).decode()
        before[path] = original
        after[path] = apply_hooks(path, original)
    for source in sorted(SOURCES.rglob('*')):
        if source.is_file():
            name = SYSTEMUI + str(source.relative_to(SOURCES))
            after[name] = source.read_text()
            sources[name] = str(source.relative_to(ROOT))
    output, files = [], []
    for name in sorted(after):
        original = before.get(name)
        output.extend(difflib.unified_diff((original or '').splitlines(True), after[name].splitlines(True),
                                           fromfile='a/' + name if original is not None else '/dev/null',
                                           tofile='b/' + name))
        entry = {'path': name, 'beforeSha256': digest(original.encode()) if original is not None else None,
                 'afterSha256': digest(after[name].encode())}
        if name in sources:
            entry['source'] = sources[name]
        files.append(entry)
    content = ''.join(output).encode()
    OUTPUT.mkdir(parents=True, exist_ok=True)
    (OUTPUT / PATCH).write_bytes(content)
    manifest = {'schema': 'taku.lockscreen/1', 'repository': REPOSITORY, 'revision': revision,
                'patch': PATCH, 'patchSha256': digest(content), 'files': files}
    (OUTPUT / 'manifest.json').write_text(json.dumps(manifest, indent=2) + '\n')
    return manifest


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    group = parser.add_mutually_exclusive_group(required=True)
    group.add_argument('--upstream-root', type=Path, help='frameworks/base checkout at the pinned revision')
    group.add_argument('--fetch', action='store_true', help='read the pinned upstream files from LineageOS on GitHub')
    args = parser.parse_args()
    manifest = generate(None if args.fetch else args.upstream_root)
    print(json.dumps({'files': len(manifest['files']), 'patchSha256': manifest['patchSha256']}))
