#!/usr/bin/env python3
"""Run feedback E2E on the sole visible emulator and retain verifiable artifacts."""
import argparse
import hashlib
import json
import os
import re
import shutil
import subprocess
import time
from pathlib import Path

parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument('--phase', required=True)
parser.add_argument('--serial')
parser.add_argument('--font-scale', type=float)
parser.add_argument('--out', type=Path)
parser.add_argument('--package', default='llc.slacker.openime.dev')
parser.add_argument('--digits', default='7426936,7426,64,426,64426,6,26')
parser.add_argument('--classes', default=','.join('llc.slacker.openime.' + name for name in
    ['FeedbackAuditE2E', 'OptimizationAuditE2E', 'NineKeyRailE2E', 'KeyboardPresentationE2E']))
args = parser.parse_args()
assert re.fullmatch(r'[A-Za-z0-9_-]+', args.phase), 'phase must be a simple directory name'
assert re.fullmatch(r'[A-Za-z0-9_.]+', args.package)
repo = Path(__file__).resolve().parent.parent
out = args.out or repo / 'output' / 'feedback-audit' / args.phase
out.mkdir(parents=True, exist_ok=True)
adb_bin = shutil.which('adb') or str(Path(os.environ.get('ANDROID_HOME', Path.home() / 'Android/Sdk')) / 'platform-tools/adb')
devices = subprocess.check_output([adb_bin, 'devices'], text=True)
emulators = [line.split()[0] for line in devices.splitlines() if line.startswith('emulator-') and '\tdevice' in line]
assert len(emulators) == 1, '必须只运行一台 Android 模拟器'
serial = args.serial or emulators[0]
assert serial == emulators[0], '本驱动仅用于模拟器'
windows = subprocess.check_output(['xdotool', 'search', '--onlyvisible', '--name', '^Android Emulator - '], text=True).splitlines()
assert windows, '模拟器必须有桌面可见窗口'
subprocess.run(['xdotool', 'windowactivate', '--sync', windows[-1]], check=True)
adb = [adb_bin, '-s', serial]

def call(*parts, check=False):
    return subprocess.run(adb + list(parts), capture_output=True, text=True, timeout=30, check=check)

local_apk = repo / 'app/build/outputs/apk/debug/app-debug.apk'
installed = call('shell', 'pm', 'path', args.package, check=True).stdout.strip().removeprefix('package:')
assert installed.startswith('/'), '请先安装本次构建的 APK'
installed_sha = call('shell', 'sha256sum', installed, check=True).stdout.split()[0]
with local_apk.open('rb') as stream:
    local_sha = hashlib.file_digest(stream, 'sha256').hexdigest()
assert installed_sha == local_sha, '请先重新安装当前 app-debug.apk'
(out / 'installed-apk.json').write_text(json.dumps({'sha256': local_sha, 'serial': serial, 'package': args.package}, indent=2))
keys = [('system', 'font_scale'), ('secure', 'default_input_method'), ('system', 'accelerometer_rotation'),
        ('system', 'user_rotation'), ('secure', 'show_ime_with_hard_keyboard')]
settings = {(namespace, key): call('shell', 'settings', 'get', namespace, key, check=True).stdout.strip() for namespace, key in keys}
remote = f'/sdcard/Android/data/{args.package}/files/optimization-audit/{args.phase}'
process = None
events = []
log = out / 'instrumentation.txt'
try:
    call('shell', 'wm', 'dismiss-keyguard', check=True)
    call('shell', 'settings', 'put', 'secure', 'show_ime_with_hard_keyboard', '1', check=True)
    if args.font_scale is not None:
        call('shell', 'settings', 'put', 'system', 'font_scale', str(args.font_scale), check=True)
    call('shell', 'ime', 'enable', f'{args.package}/llc.slacker.openime.LocalVoiceImeService', check=True)
    call('shell', 'am', 'force-stop', args.package + '.test', check=True)
    call('shell', 'mkdir', '-p', remote, check=True)
    call('shell', 'rm', '-f', remote + '/sms-fixtures.json', remote + '/sms-ack-*', check=True)
    with log.open('w') as stream:
        process = subprocess.Popen(adb + ['shell', 'am', 'instrument', '-w', '-e', 'class', args.classes,
            '-e', 'phase', args.phase, '-e', 'digits', args.digits,
            args.package + '.test/androidx.test.runner.AndroidJUnitRunner'], stdout=stream, stderr=subprocess.STDOUT)
        while process.poll() is None:
            try:
                messages = json.loads(call('exec-out', 'cat', remote + '/sms-fixtures.json').stdout)
            except json.JSONDecodeError:
                messages = []
            for number, body in enumerate(messages):
                if number < len(events):
                    continue
                modem = call('emu', 'sms', 'send', '10690000', body, check=True)
                assert 'OK' in modem.stdout, 'Emulator SMS delivery failed'
                events.append({'sequence': number, 'body': body, 'modem': modem.stdout.strip()})
                call('shell', 'touch', remote + f'/sms-ack-{number}', check=True)
            time.sleep(.5)
    call('pull', remote, str(out / 'device-artifacts'), check=True)
finally:
    if process is not None and process.poll() is None:
        process.terminate()
        process.wait(timeout=10)
        call('shell', 'am', 'force-stop', args.package + '.test')
    restored = {}
    for (namespace, key), value in settings.items():
        call('shell', 'settings', 'delete' if value == 'null' else 'put', namespace, key,
             *([] if value == 'null' else [value]), check=True)
        restored[namespace + '/' + key] = call('shell', 'settings', 'get', namespace, key, check=True).stdout.strip() == value
    (out / 'restoration.json').write_text(json.dumps(restored, indent=2))
    (out / 'modem-delivery.json').write_text(json.dumps(events, ensure_ascii=False, indent=2))
    manifest = {}
    for path in out.rglob('*'):
        if path.is_file() and path.name != 'SHA256SUMS.json':
            with path.open('rb') as stream:
                manifest[str(path.relative_to(out))] = hashlib.file_digest(stream, 'sha256').hexdigest()
    (out / 'SHA256SUMS.json').write_text(json.dumps(manifest, ensure_ascii=False, indent=2))
print(log.read_text())
raise SystemExit(0 if process is not None and process.returncode == 0 and
    re.search(r'^OK \(\d+ tests?\)', log.read_text(), re.MULTILINE) and all(restored.values()) else 1)
