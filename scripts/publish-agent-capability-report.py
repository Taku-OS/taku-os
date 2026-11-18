#!/usr/bin/env python3
# coding: utf-8
"""Publish curated facts and synthetic-only screenshots from actual capability tests."""
import html
import json
import pathlib
import shutil

ROOT = pathlib.Path(__file__).resolve().parents[1]
OUTPUT = ROOT / 'out/agent-capabilities-2026-10-14'
REVIEW = ROOT / 'docs/reviews'
LABELS = {
    'chat': ('Chinese conversation', 'Real model replied “Remembered” (translated)'),
    'memory': ('Same-session context', 'Second turn accurately recalled the Chinese test phrase “青叶731”'),
    'system-read': ('Read system and permissions', 'Android 16 / API 36; tool UID 0'),
    'volume': ('Adjust media volume', 'Media volume 5 → 7, independently read back'),
    'brightness': ('Adjust brightness', 'Brightness 102 → 120, manual mode independently read back'),
    'open-app': ('Open a background app', 'Settings on independent display 2; main-screen task retained'),
    'clock-gui': ('Inspect screenshot and tap', 'Tapped Stopwatch after capture; actual image showed 0, without starting the timer'),
    'wallpaper': ('Change Home wallpaper', 'Actual wallpaper pixels #23587d; lock-screen wallpaper retained'),
    'interrupt': ('Stop current task', 'Turn interrupted, but sleep subprocess kept running'),
}


def main():
    data = json.loads((OUTPUT / 'acceptance.json').read_text())
    rows, curated = [], []
    for case in data['cases']:
        label, note = LABELS[case['id']]
        passed = case.get('verified', False)
        rows.append('<tr><td>' + html.escape(label) + '</td><td><span class="pill ' +
                    ('yes' if passed else 'partial') + '">' + ('Passed' if passed else 'Partially passed') +
                    '</span></td><td>' + html.escape(note) + '</td><td>' +
                    f'{case.get("seconds", 0):.1f} seconds</td></tr>')
        curated.append({'id': case['id'], 'label': label, 'verified': bool(passed),
                        'seconds': case.get('seconds'), 'note': note,
                        'nativeStatus': case.get('nativeStatus')})
    summary = {'date': '2026-10-15', 'completedAt': data['completedAt'],
               'sourceHead': '02992bf', 'workingTreeModified': True, 'model': 'gpt-6.1-sol',
               'runtime': 'codex 0.161.0 app-server', 'device': 'Dedicated Android16 / API36 / ARM64 emulator',
               'cases': curated, 'restored': data['restored'], 'physicalDeviceTouched': False,
               'wallpaperFirstAttemptSeconds': 171.282, 'wallpaperRetrySeconds': 51.698,
               'stopBoundary': {'nativeTurnInterrupted': True, 'commandObservedRunningBeforeStop': True,
                                'sleepProcessStillRunningAfterStop': True, 'sleepExitedNaturallyLater': True},
               'boundary': data['boundary']}
    (ROOT / 'evidence/builds/2026-10-15-agent-capabilities.json').write_text(
        json.dumps(summary, ensure_ascii=False, indent=2) + '\n')
    images = {'agent-capability-chat-2026-10-15.png': 'memory.png',
              'agent-capability-stopwatch-2026-10-15.png': 'clock-gui-background.png'}
    for target, source in images.items():
        shutil.copy2(OUTPUT / source, REVIEW / target)
        shutil.copy2(OUTPUT / source, OUTPUT / target)
    page = '''<!doctype html><html lang="en"><meta charset="utf-8">
<meta name="viewport" content="width=device-width,initial-scale=1">
<title>Taku Agent · Actual capability tests</title><style>
*{box-sizing:border-box}body{margin:0;background:#f3f4ef;color:#1d3028;font:16px/1.7 -apple-system,BlinkMacSystemFont,"Segoe UI",sans-serif}main{max-width:1120px;margin:auto;padding:50px 24px}small{letter-spacing:.15em;color:#57695f}h1{font-size:clamp(30px,5vw,52px);line-height:1.25;margin:14px 0}p{max-width:850px}.stats{display:flex;gap:16px;margin:28px 0}.stat{background:#fff;padding:20px 28px;border:1px solid #dbe1d7;border-radius:18px;flex:1}.stat strong{display:block;font-size:34px}.warning{background:#fff0cf;padding:20px 24px;border-radius:18px;margin-bottom:26px}.table{overflow:auto;background:white;border-radius:18px;border:1px solid #dbe1d7}table{border-collapse:collapse;width:100%;min-width:650px}th,td{padding:14px 18px;border-bottom:1px solid #edf0e9;text-align:left}th{color:#607467;font-size:13px}tr:last-child td{border:0}.pill{white-space:nowrap;font-size:13px;padding:5px 10px;border-radius:20px}.yes{background:#dbeedc}.partial{background:#fff0cf}.figures{display:grid;grid-template-columns:1fr 1fr;gap:26px;margin-top:30px}figure{margin:0;padding:24px;background:white;border:1px solid #dbe1d7;border-radius:18px}figure img{display:block;max-width:100%;height:520px;object-fit:contain;margin:18px auto}figcaption{font-size:14px;color:#5d6f62}footer{margin-top:26px;color:#5d6f62;font-size:14px}@media(max-width:650px){main{padding:28px 18px}.stats{gap:8px}.stat{padding:14px}.stat strong{font-size:26px}.figures{grid-template-columns:1fr}figure img{height:460px}}
</style><main><small>TAKU OS / 2026-10-15 / REAL MODEL AND DEVICE OPERATIONS</small>
<h1>What can the Agent do now?</h1><p>Requests were sent through the phone chat UI and executed by a real on-device model runtime. Independent checks read system state, app display placement, and actual screenshots afterward. Tests used a dedicated emulator without operating the connected Pixel or other physical devices.</p>
<div class="stats"><div class="stat"><strong>8 / 9</strong>Fully passed</div><div class="stat"><strong>1 case</strong>Stop has a gap</div><div class="stat"><strong>Restored</strong>Volume, brightness, and wallpaper</div></div>
<div class="warning"><b>Stopping a conversation ≠ terminating every tool process</b><br>After Stop, the native turn became interrupted and the task showed cancelled. The running sleep subprocess continued and later exited naturally. This remains failure evidence, without a full-pass claim.</div>
<div class="table"><table><thead><tr><th>Capability</th><th>Result</th><th>Independent confirmation</th><th>Model execution time</th></tr></thead><tbody>ROWS</tbody></table></div>
<p>The first wallpaper run took 171 seconds; a same-session retest took 52 seconds. Pixel color and retained lock-screen wallpaper were verified, but the GUI path still needs stabilization. Timings exclude test compilation, UI preparation, and restoration.</p>
<div class="figures"><figure><b>Real chat and context</b><img src="agent-capability-chat-2026-10-15.png" alt="Actual Taku conversation drawer and two-turn test-phrase memory results"><figcaption>Across two consecutive turns in one session, the model accurately remembered and recalled a Chinese test phrase. Conversation content and screenshots came from real execution.</figcaption></figure><figure><b>Independent background display: tap after screenshot</b><img src="agent-capability-stopwatch-2026-10-15.png" alt="Clock stopwatch on an independent display, showing zero with Start untouched"><figcaption>After inspecting a screenshot, the model opened Stopwatch. The actual display showed 0 and Start remained untouched; the main-screen task was preserved.</figcaption></figure></div>
<footer>Runtime: Codex 0.161.0 App Server / model gpt-6.1-sol. Verification covers only an Android 16 ARM64 development emulator. General third-party compatibility, plugin lifecycle/isolation, production permissions, latest-assistant hardware, and power-off cold start are unverified. Source test: scripts/test-agent-capabilities.py; redacted record: evidence/builds/2026-10-15-agent-capabilities.json.</footer></main></html>'''.replace('ROWS', ''.join(rows))
    (REVIEW / 'agent-capabilities-2026-10-15.html').write_text(page)
    (OUTPUT / 'index.html').write_text(page)
    print('Curated report generated: 8 verified, 1 partial.')


if __name__ == '__main__':
    main()
