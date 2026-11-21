#!/usr/bin/env python3
# SPDX-License-Identifier: Apache-2.0
"""Manifest/source boundary regression, not an Android permission/runtime acceptance test."""
from pathlib import Path
import xml.etree.ElementTree as ET
root=Path(__file__).resolve().parents[1]
manifest=ET.parse(root/'app/AndroidManifest.xml').getroot()
ns='{http://schemas.android.com/apk/res/android}'
app=manifest.find('application')
components={node.get(ns+'name'):node for node in app}
assert components['.realtime.TryOnRetryActivity'].get(ns+'exported')=='false'
assert components['.realtime.CallTryOnService'].get(ns+'exported')=='false'
assert components['.realtime.TryOnActionReceiver'].get(ns+'exported')=='false'
assert '.realtime.DirectLiveTryOnActivity' not in components
assert components['.realtime.DirectCosplayActivity'].get(ns+'theme')=='@android:style/Theme.Translucent.NoTitleBar'
assert components['.realtime.DirectCosplayActivity'].get(ns+'excludeFromRecents')=='true'
assert components['.realtime.DirectCosplayActivity'].get(ns+'taskAffinity')=='org.taku.tryon.character'
setup_affinity=components['.realtime.TryOnActivity'].get(ns+'taskAffinity',app.get(ns+'taskAffinity',manifest.get('package')))
assert components['.realtime.DirectCosplayActivity'].get(ns+'taskAffinity')!=setup_affinity
assert manifest.get(ns+'sharedUserId') is None
for source in (root/'app/src').rglob('*.java'):
    text=source.read_text()
    assert 'content://org.agentos.assistant.camera-tryon' not in text,source
    assert 'org.agentos.proactivity.api' not in text,source
    assert 'LiveTryOnContract' not in text,source
    assert 'content://org.agentos.shopping' not in text,source
    assert 'SystemIslandHost' not in text,source
activity=(root/'app/src/org/taku/tryon/realtime/TryOnActivity.java').read_text()
assert 'this instanceof TryOnRetryActivity&&"retry".equals' in activity
assert 'DictationSettingsActivity' not in activity or 'SPEECH_SETTINGS_ACTIVITY' in activity
assert 'saveVoiceSetting("enabled"' in activity and 'saveVoiceSetting("voiceOnly"' in activity
gateway=(root/'app/src/org/taku/tryon/realtime/LegacyCosplayGateway.java').read_text()
assert 'putString("speechLanguage"' not in gateway
assert 'org.agentos.shopping' not in (root/'app/AndroidManifest.xml').read_text()
print('Try-on manifest boundaries: passed (static only; no Android/runtime/model)')
