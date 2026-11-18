#!/usr/bin/env python3
"""Send a bounded acceptance request through the installed Android chat UI."""
import argparse
import json
import os
from pathlib import Path
import subprocess

ROOT = Path(__file__).resolve().parents[1]


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--id', required=True)
    parser.add_argument('--prompt-file', type=Path, required=True)
    parser.add_argument('--output', type=Path, required=True)
    parser.add_argument('--thread-id', default='')
    parser.add_argument('--timeout', type=int, default=480)
    parser.add_argument('--response-style', choices=['concise','detailed'])
    args = parser.parse_args()
    if not args.id.replace('-', '').isalnum() or not 30 <= args.timeout <= 600:
        parser.error('Invalid acceptance ID or timeout')
    prompt = args.prompt_file.read_text()
    if not prompt.strip():
        parser.error('Acceptance request must not be empty')
    sdk = Path(os.environ.get('ANDROID_SDK_ROOT', str(Path.home() / 'Library/Android/sdk')))
    adb = str(sdk / 'platform-tools/adb')
    prefix = [adb, '-s', 'emulator-5634']
    def run(command):
        return subprocess.check_output(command, text=True).strip()
    if run(prefix + ['emu', 'avd', 'name']).splitlines()[0] != 'Taku_ImageIntegration_20261010':
        raise RuntimeError('Dedicated emulator mismatch')
    account = json.loads(run(prefix + ['shell', 'CLASSPATH=/data/local/tmp/taku-account-tests.jar app_process /system/bin org.agentos.tests.AccountBridgeProbe']))
    if not account.get('connected') or account.get('challengePresent'):
        raise RuntimeError('Existing connected account required')
    args.output.mkdir(parents=True, exist_ok=True)
    plan = {'id': args.id, 'prompt': prompt, 'threadId': args.thread_id,
            'timeoutSeconds': args.timeout}
    if args.response_style:
        plan['responseStyle'] = args.response_style
    plan_file = args.output / (args.id + '.plan.json')
    plan_file.write_text(json.dumps(plan, ensure_ascii=False))
    run(prefix + ['push', str(plan_file), '/data/local/tmp/taku-capability/plan.json'])
    test = subprocess.run(prefix + ['shell', 'timeout', str(args.timeout + 50), 'uiautomator', 'runtest',
                                    '/system/framework/android.test.base.jar', '/data/local/tmp/taku-capability/test.jar',
                                    '-c', 'org.agentos.tests.AgentCapabilityTest'], capture_output=True, text=True,
                          timeout=args.timeout + 80)
    (args.output / (args.id + '.test.txt')).write_text(test.stdout + test.stderr)
    if 'OK (1 test)' not in test.stdout:
        raise RuntimeError('Chat UI acceptance failed; diagnostic log preserved')
    for extension in ['json', 'png']:
        run(prefix + ['pull', '/data/local/tmp/taku-capability/' + args.id + '.' + extension,
                      str(args.output / (args.id + '.' + extension))])
    result = json.loads((args.output / (args.id + '.json')).read_text())
    print(json.dumps({key: result.get(key) for key in ['state', 'seconds', 'threadId', 'turnId', 'model']}, ensure_ascii=False))
    raise SystemExit(0 if result.get('state') == 'completed' else 1)


if __name__ == '__main__':
    os.chdir(ROOT)
    main()
