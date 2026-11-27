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
import subprocess
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
ISLAND = ROOT / 'image/island'
# Files another Taku patch also edits. The image builds apply the island patch first, so the lock
# screen's edit starts from the island's final bytes (`layeredOn` in the manifest), and both apply
# scripts accept the layered result.
LAYERED = {'packages/SystemUI/AndroidManifest.xml': 'island'}
HOOKS = {
    SYSTEMUI + 'com/android/systemui/keyguard/ui/view/layout/sections/ClockSection.kt': [
        ('import com.android.systemui.res.R\n',
         'import com.android.systemui.res.R\n'
         f'import {PKG}.TakuLockscreenPolicy\n'),
        ('this.setScaleX(view.id, scaleX)',
         'this.setScaleX(view.id, TakuLockscreenPolicy.clockScale(view, scaleX))'),
        ('this.setScaleY(view.id, scaleY)',
         'this.setScaleY(view.id, TakuLockscreenPolicy.clockScale(view, scaleY))'),
    ],
    SYSTEMUI + 'com/android/systemui/keyguard/ui/binder/KeyguardRootViewBinder.kt': [
        ('import com.android.systemui.res.R\n',
         'import com.android.systemui.res.R\n'
         f'import {PKG}.TakuLockscreenPolicy\n'),
        ('                                    it.scaleX = scaleViewModel.scale\n'
         '                                    it.scaleY = scaleViewModel.scale\n',
         '                                    // Taku uses final-size outlines; preserve relative AOD scaling.\n'
         '                                    val scale = TakuLockscreenPolicy.clockScale(it, scaleViewModel.scale)\n'
         '                                    it.scaleX = scale\n'
         '                                    it.scaleY = scale\n'),
    ],
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
    SYSTEMUI + 'com/android/systemui/keyguard/data/quickaffordance/KeyguardDataQuickAffordanceModule.kt': [
        ('import dagger.multibindings.ElementsIntoSet\n',
         'import dagger.multibindings.ElementsIntoSet\n'
         f'import {PKG}.TakuOpenAppQuickAffordanceConfig\n'),
        ('            videoCamera: VideoCameraQuickAffordanceConfig,\n'
         '        ): Set<KeyguardQuickAffordanceConfig> {\n',
         '            videoCamera: VideoCameraQuickAffordanceConfig,\n'
         '            // Taku: "Open app" shortcut (system/lockscreen, docs/lock-screen.md).\n'
         '            takuOpenApp: TakuOpenAppQuickAffordanceConfig,\n'
         '        ): Set<KeyguardQuickAffordanceConfig> {\n'),
        ('                videoCamera,\n'
         '            )\n',
         '                videoCamera,\n'
         '                takuOpenApp, // Taku\n'
         '            )\n'),
    ],
    'packages/SystemUI/AndroidManifest.xml': [
        ('            android:name="com.android.systemui.keyguard.CustomizationProvider"\n'
         '            android:exported="true"\n'
         '            android:permission="android.permission.CUSTOMIZE_SYSTEM_UI"\n'
         '            />\n',
         '            android:name="com.android.systemui.keyguard.CustomizationProvider"\n'
         '            android:exported="true"\n'
         '            android:permission="android.permission.CUSTOMIZE_SYSTEM_UI"\n'
         '            />\n'
         '\n'
         '        <!-- Taku: app chooser for the "Open app" lock-screen shortcut, opened by the\n'
         '             wallpaper picker as the shortcut\'s configure action. -->\n'
         '        <activity\n'
         '            android:name="com.android.systemui.taku.lockscreen.TakuShortcutAppActivity"\n'
         '            android:exported="true"\n'
         '            android:permission="android.permission.CUSTOMIZE_SYSTEM_UI"\n'
         '            android:label="@string/controls_open_app"\n'
         '            android:excludeFromRecents="true"\n'
         '            android:theme="@android:style/Theme.DeviceDefault.DayNight" />\n'),
    ],
    SYSTEMUI + 'com/android/systemui/keyguard/ui/binder/KeyguardQuickAffordanceViewBinder.kt': [
        ('import com.android.systemui.res.R\n',
         'import com.android.systemui.res.R\n'
         f'import {PKG}.TakuLockscreenPolicy\n'),
        ('        view.drawable.setTint(\n'
         '            view.context.getColor(\n'
         '                if (viewModel.isActivated) {\n'
         '                    com.android.internal.R.color.materialColorOnPrimaryFixed\n'
         '                } else {\n'
         '                    com.android.internal.R.color.materialColorOnSurface\n'
         '                }\n'
         '            )\n'
         '        )\n',
         '        // Taku: dark icon on the white shortcut button (Figma 979:455); activated keeps stock.\n'
         '        view.drawable.setTint(\n'
         '            TakuLockscreenPolicy.shortcutIconColor(\n'
         '                viewModel.isActivated,\n'
         '                view.context.getColor(\n'
         '                    if (viewModel.isActivated) {\n'
         '                        com.android.internal.R.color.materialColorOnPrimaryFixed\n'
         '                    } else {\n'
         '                        com.android.internal.R.color.materialColorOnSurface\n'
         '                    }\n'
         '                ),\n'
         '            )\n'
         '        )\n'),
        ('            ColorStateList.valueOf(\n'
         '                view.context.getColor(\n'
         '                    if (viewModel.isActivated) {\n'
         '                        com.android.internal.R.color.materialColorPrimaryFixed\n'
         '                    } else if (enableLockscreenBlur() && isBlurSupported) {\n'
         '                        com.android.internal.R.color.customColorSurfaceEffect1\n'
         '                    } else {\n'
         '                        com.android.internal.R.color.materialColorSurfaceContainerHigh\n'
         '                    }\n'
         '                )\n'
         '            )\n',
         '            // Taku: white at 92 % at rest (Figma 979:455); activated keeps stock.\n'
         '            ColorStateList.valueOf(\n'
         '                TakuLockscreenPolicy.shortcutBackgroundColor(\n'
         '                    viewModel.isActivated,\n'
         '                    view.context.getColor(\n'
         '                        if (viewModel.isActivated) {\n'
         '                            com.android.internal.R.color.materialColorPrimaryFixed\n'
         '                        } else if (enableLockscreenBlur() && isBlurSupported) {\n'
         '                            com.android.internal.R.color.customColorSurfaceEffect1\n'
         '                        } else {\n'
         '                            com.android.internal.R.color.materialColorSurfaceContainerHigh\n'
         '                        }\n'
         '                    ),\n'
         '                )\n'
         '            )\n'),
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
    SYSTEMUI + 'com/android/systemui/shade/NotificationPanelViewController.java': [
        ('import com.android.systemui.statusbar.policy.KeyguardStateController;\n',
         'import com.android.systemui.statusbar.policy.KeyguardStateController;\n'
         f'import {PKG}.TakuLockscreenPolicy;\n'),
        ('    private ClockSize computeDesiredClockSizeForSingleShade() {\n'
         '        if (hasVisibleNotifications()) {\n',
         '    private ClockSize computeDesiredClockSizeForSingleShade() {\n'
         '        // Taku: tall until the user drags the notifications open, whatever the lock state.\n'
         '        if (isKeyguardShowing()) {\n'
         '            return TakuLockscreenPolicy.clockSize();\n'
         '        }\n'
         '        if (hasVisibleNotifications()) {\n'),
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


def island_layer(path, original):
    """The island's final bytes for [path]: its own patch applied to the pinned upstream bytes."""
    contract = json.loads((ISLAND / 'manifest.json').read_text())
    entry = next(item for item in contract['files'] if item['path'] == path)
    if digest(original.encode()) != entry['beforeSha256']:
        raise ValueError(f'{path}: upstream bytes differ from the island contract')
    with tempfile.TemporaryDirectory(prefix='taku-lockscreen-layer-') as temp:
        target = Path(temp) / path
        target.parent.mkdir(parents=True)
        target.write_bytes(original.encode())
        subprocess.run(['git', 'init', '-q', temp], check=True)
        subprocess.run(['git', '-C', temp, 'apply', '--whitespace=error', f'--include={path}',
                        str(ISLAND / contract['patch'])], check=True)
        layered = target.read_bytes()
    if digest(layered) != entry['afterSha256']:
        raise ValueError(f'{path}: island patch did not reproduce its final bytes')
    return layered.decode()


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
        if LAYERED.get(path) == 'island':
            original = island_layer(path, original)
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
        if name in LAYERED:
            entry['layeredOn'] = LAYERED[name]
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
