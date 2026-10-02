#!/usr/bin/env python3
"""Real IME visual/interaction E2E. Explicit emulator serial; screenshots + replay log.
Usage: python3 scripts/design_reference_e2e.py emulator-5554 [output-directory]
Append --panels-only after the output directory to refresh only affected panels.
Requires a built/installed debug APK and Pillow for cropped reference comparisons.
Backs up and restores affected preferences; uses deterministic visual fixtures.
Restores emulator display, locale and font scale. No unit tests or fake InputConnection.
"""
import base64
import json
import re
import subprocess
import sys
import time
from pathlib import Path
import xml.etree.ElementTree as ET
from PIL import Image

serial = sys.argv[1] if len(sys.argv) > 1 else ''
if not serial.startswith('emulator-'):
    raise SystemExit('Please pass an explicit emulator serial; this suite changes display configuration.')
out = Path(sys.argv[2] if len(sys.argv) > 2 else 'output/design-reference').resolve()
out.mkdir(parents=True, exist_ok=True)
pkg = 'llc.slacker.openime'
panels_only = '--panels-only' in sys.argv
records = json.loads((out / 'results.json').read_text()) if panels_only and (out / 'results.json').exists() else []

def adb(*args, binary=False):
    result = subprocess.run(['adb', '-s', serial, *args], check=True, capture_output=True)
    return result.stdout if binary else result.stdout.decode('utf-8', errors='replace').strip()

def command(value):
    adb('logcat', '-c')
    adb('shell', 'am', 'broadcast', '-n', pkg + '/.E2ETestReceiver', '-a', pkg + '.TEST_COMMAND', '--es', 'cmd', value)
    time.sleep(0.3)
    log = adb('logcat', '-d', '-s', 'OpenImeE2E:I', 'OpenIme:I')
    if 'ok=true' not in log:
        raise AssertionError(f'Command failed: {value}\n{log}')
    return log

def tap(value):
    return command('tap:' + value)

def tree():
    adb('shell', 'uiautomator', 'dump', '/sdcard/openime-design.xml')
    return adb('shell', 'cat', '/sdcard/openime-design.xml')

def editor():
    for node in ET.fromstring(tree()).iter('node'):
        if node.get('resource-id') == pkg + ':id/test_input':
            return node.get('text', '') if node.get('text', '') != node.get('hint') else ''
    raise AssertionError('Real editor missing')

def launch():
    adb('shell', 'am', 'force-stop', pkg)
    adb('shell', 'am', 'start', '-n', pkg + '/.MainActivity')
    time.sleep(1)
    for attempt in range(5):
        for node in ET.fromstring(tree()).iter('node'):
            if node.get('resource-id') == pkg + ':id/test_input':
                x1, y1, x2, y2 = map(int, re.findall(r'\d+', node.get('bounds')))
                if y2 <= y1: continue
                adb('shell', 'input', 'tap', str((x1+x2)//2), str((y1+y2)//2))
                time.sleep(1)
                for _ in range(5):
                    if 'window=' in command('bounds'):
                        command('state')
                        return
                    adb('shell', 'input', 'tap', str((x1+x2)//2), str((y1+y2)//2))
                    time.sleep(.5)
        sizes = re.findall(r'(\d+)x(\d+)', adb('shell', 'wm', 'size'))
        w, h = map(int, sizes[-1])
        adb('shell', 'input', 'swipe', str(w//2), str(int(h*.6)), str(w//2), str(int(h*.3)), '250')
    raise AssertionError('Cannot focus real editor')

def capture(name, required=()):
    time.sleep(0.3)
    log = command('bounds')
    match = re.search(r'window=(\d+),(\d+),(\d+),(\d+)', log)
    if not match:
        raise AssertionError('No measured real IME window')
    x, y, w, h = map(int, match.groups())
    for label in required:
        if label not in log:
            raise AssertionError(f'{name}: missing {label}')
    # Every actual keyboard key must fit its measured window. Scrollable panel
    # content may intentionally extend beyond its viewport and is excluded.
    for tag, bounds in re.findall(r'tag=(key:[^|]+|key-9:[^|]+|key-enter|key-space)\|[^\n]*?\|([\d.eE,+-]+)', log):
        left, top, width, height = map(float, bounds.split(','))
        scrolling_symbols = 'tag=symbols-panel' in log
        if left < -0.002 or left+width > 1.002 or (not scrolling_symbols and (top < -0.002 or top+height > 1.002)):
            raise AssertionError(f'{name}: key outside window {tag}: {bounds}')
    raw = out / (name + '-screen.png')
    raw.write_bytes(adb('exec-out', 'screencap', '-p', binary=True))
    Image.open(raw).crop((x, y, x+w, y+h)).save(out / (name + '.png'))
    (out / (name + '-bounds.txt')).write_text(log)
    records[:] = [record for record in records if record['case'] != name]
    records.append({'case': name, 'window': [x,y,w,h], 'required': list(required), 'passed': True})
    (out / 'results.json').write_text(json.dumps(records, ensure_ascii=False, indent=2))
    print('PASS', name, flush=True)

def panel(label, name, required=()):
    command('mode:PINYIN_9')
    tap('更多')
    tap(label)
    capture(name, required)

def capture_voice_hold(name):
    log = command('bounds')
    x,y,w,h = map(int,re.search(r'window=(\d+),(\d+),(\d+),(\d+)',log).groups())
    match = re.search(r'tag=key-space\|[^\n]*?\|([\d.eE,+-]+)',log)
    left,top,width,height = map(float,match.group(1).split(','))
    cx,cy = str(int(x+(left+width/2)*w)),str(int(y+(top+height/2)*h))
    hold = subprocess.Popen(['adb','-s',serial,'shell','input','swipe',cx,cy,cx,cy,'3000'],stdout=subprocess.DEVNULL,stderr=subprocess.DEVNULL)
    try:
        time.sleep(.9)
        capture(name, ('voice-inline-zone','voice-inline-status'))
    finally:
        hold.wait(timeout=15)
    command('mode:PINYIN_26'); command('mode:PINYIN_9')

def ui_tap(selector):
    for attempt in range(5):
        for node in ET.fromstring(tree()).iter('node'):
            if selector in (node.get('resource-id'), node.get('text'), node.get('content-desc')):
                x1,y1,x2,y2 = map(int, re.findall(r'\d+', node.get('bounds')))
                if x2 > x1 and y2 > y1:
                    adb('shell','input','tap',str((x1+x2)//2),str((y1+y2)//2))
                    time.sleep(.7)
                    return
        sizes = re.findall(r'(\d+)x(\d+)', adb('shell','wm','size'))
        w,h = map(int,sizes[-1])
        adb('shell','input','swipe',str(w//2),str(int(h*.8)),str(w//2),str(int(h*.3)),'250')
    raise AssertionError('Missing visible UI target: ' + selector)

def capture_app(name, required=()):
    time.sleep(.5)
    xml = tree()
    for label in required:
        if label not in xml: raise AssertionError(name + ': missing ' + label)
    (out / (name + '.png')).write_bytes(adb('exec-out','screencap','-p',binary=True))
    (out / (name + '-ui.xml')).write_text(xml)
    records[:] = [record for record in records if record['case'] != name]
    records.append({'case': name, 'required': list(required), 'passed': True})
    (out / 'results.json').write_text(json.dumps(records,ensure_ascii=False,indent=2))
    print('PASS', name, flush=True)

def appearance(label):
    command('mode:PINYIN_9')
    tap('更多'); tap('设置'); tap(label)
    command('mode:PINYIN_9')

def write_pref(name, raw):
    path = 'shared_prefs/' + name + '.xml'
    if raw is None:
        adb('shell','run-as',pkg,'rm','-f',path)
    else:
        subprocess.run(['adb','-s',serial,'shell',f"run-as {pkg} sh -c 'mkdir -p shared_prefs; cat > {path}'"],input=raw,check=True,capture_output=True)

def pref_items(items):
    root = ET.Element('map')
    ET.SubElement(root,'string',{'name':'items'}).text = json.dumps(items,ensure_ascii=False)
    return ET.tostring(root,encoding='utf-8',xml_declaration=True)

pref_names = ['ime_settings','ime_quick_phrases','ime_custom_symbols','ime_clipboard_history']
old_prefs = {}
for name in pref_names:
    result = subprocess.run(['adb','-s',serial,'shell','run-as',pkg,'cat','shared_prefs/'+name+'.xml'],capture_output=True)
    old_prefs[name] = result.stdout if result.returncode == 0 else None

old_size = adb('shell', 'wm', 'size')
old_density = adb('shell', 'wm', 'density')
old_font = adb('shell', 'settings', 'get', 'system', 'font_scale')
old_locale = adb('shell', 'cmd', 'locale', 'get-app-locales', pkg)
try:
    adb('shell','am','force-stop',pkg)
    settings = ET.fromstring(old_prefs['ime_settings'] or b'<map/>')
    fixtures = {'skin_font': ('int','21'), 'skin_opacity': ('int','100'), 'skin_radius': ('int','8'), 'keyboard_height_percent': ('int','100'), 'floating_width_percent': ('int','100'), 'floating_opacity_percent': ('int','100'), 'sound': ('boolean','false'), 'haptic': ('boolean','true'), 'popup': ('boolean','true'), 'handedness': ('string','STANDARD'), 'skin_color': ('string','#1D9BF0')}
    for name,(kind,value) in fixtures.items():
        for node in list(settings):
            if node.get('name') == name: settings.remove(node)
        node = ET.SubElement(settings,kind,{'name':name})
        if kind == 'string': node.text = value
        else: node.set('value',value)
    write_pref('ime_settings',ET.tostring(settings,encoding='utf-8',xml_declaration=True))
    write_pref('ime_quick_phrases',pref_items([{'id':i+1,'category':'常用','text':text,'input_code':''} for i,text in enumerate(['会议室 B，下午三点','我到家了，不用担心','example@mail.com'])]))
    write_pref('ime_custom_symbols',pref_items([{'id':i+1,'group':'常用箭头','symbol':text,'pinned':i==0} for i,text in enumerate(['→','★','¯\\_(ツ)_/¯'])]))
    write_pref('ime_clipboard_history',pref_items([{'text':text,'timestamp':int(time.time()*1000),'pinned':i==0} for i,text in enumerate(['会议室 B，下午三点','example@mail.com','https://example.com/docs/getting-started'])]))
    adb('shell', 'cmd', 'locale' , 'set-app-locales', pkg, '--locales', 'zh-CN')
    adb('shell', 'ime', 'enable', pkg + '/.LocalVoiceImeService')
    adb('shell', 'ime', 'set', pkg + '/.LocalVoiceImeService')
    adb('shell', 'pm', 'grant', pkg, 'android.permission.RECORD_AUDIO')
    launch()
    for theme in ('浅色', '深色'):
        appearance(theme)
        stem = 'light' if theme == '浅色' else 'dark'
        if panels_only:
            command('clear-swipe'); command('mode:PINYIN_9')
            capture(stem + '-nine-idle', ('key-9:9',))
            command('mode:ENGLISH_26'); capture(stem + '-english26', ('key:q',))
            for label,target,required in [('工具','tools',('tool:设置',)),('切换键盘','keyboard-select',('keyboard-choice-selected',)),('符号','symbols',('symbol-categories',)),('表情','emoji',('emoji-cell',)),('文本编辑','text-editor',('textedit-cross',)),('剪贴板','clipboard',('clip-card',)),('语音输入','voice',('voice-mic',)),('设置','preferences',('settings-slider:键盘高度',))]:
                if label == '工具':
                    command('mode:PINYIN_9'); tap('更多'); capture(stem + '-' + target, required)
                else: panel(label,stem + '-' + target,required)
            continue
        command('clear-swipe'); command('mode:PINYIN_9')
        capture(stem + '-nine-idle', ('key-9:1', 'key-9:9', 'key-space'))
        capture_voice_hold(stem + '-voice-hold')
        command('nine-sequence:64426')
        capture(stem + '-nine-composing', ('candidate-first', 'nine-pinyin-path-selected'))
        tap('candidate-expand')
        capture(stem + '-candidates-expanded', ('candidate-grid-first',))
        tap('candidate-expand'); tap('key-space')
        assert '你好' in editor(), 'Nine-key selection did not commit to the real editor'
        command('clear-swipe'); command('mode:PINYIN_26')
        for letter in 'nihao': tap('key:' + letter)
        capture(stem + '-pinyin26', ('key:q', 'candidate-first'))
        tap('key-space')
        assert '你好' in editor(), '26-key selection did not commit to the real editor'
        command('clear-swipe'); command('mode:ENGLISH_26')
        capture(stem + '-english26', ('key:q', 'key:mode'))
        before = editor()
        tap('key:a'); tap('key:b'); tap('key:c')
        assert editor() == before + 'abc', 'English input did not commit'
        command('clear-swipe'); command('mode:DIGITS')
        capture(stem + '-numeric', ('key:0', 'key:.', 'key:@'))
        before = editor()
        tap('key:1'); tap('key:0'); tap('key:.'); tap('key:5')
        assert editor() == before + '10.5', 'Numeric layout commits wrong literals'
        command('clear-swipe'); command('mode:PINYIN_9'); tap('更多')
        capture(stem + '-tools', ('tool:语音输入', 'tool:设置', 'tool:文本编辑', 'tool:浮动键盘'))
        for label, target, required in [
            ('切换键盘','keyboard-select',('keyboard-choice-selected',)),
            ('符号','symbols',('symbol-categories',)),
            ('表情','emoji',('emoji-cell',)),
            ('文本编辑','text-editor',('textedit-cross', 'textedit-action:paste')),
            ('语音输入','voice',('voice-mic', 'segment-selected')),
            ('剪贴板','clipboard',('segmented-track',)),
            ('设置','preferences',('segmented-track-tall', 'settings-slider:键盘高度')),
        ]:
            panel(label, stem + '-' + target, required)
        tap('强调色与按键皮肤')
        capture(stem + '-skin', ('accent-swatch', 'accent-custom'))
        tap('accent-custom')
        capture_app(stem + '-accent-dialog', ('自定义强调色', '应用'))
        adb('shell','input','text','5B6B7A')
        capture_app(stem + '-accent-editor', ('5B6B7A',))
        ui_tap('应用')
        ui_tap('强调色蓝色，未选中')
        launch()
        command('mode:PINYIN_26'); tap('更多'); tap('浮动键盘')
        capture(stem + '-floating', ('floating-drag-handle', 'key:q'))
        tap('floating-drag-handle'); command('mode:PINYIN_9')
        panel('设置', stem + '-preferences-return')
        tap('模糊音与智能纠错')
        capture(stem + '-fuzzy', ('fuzzy-rules',))
        command('mode:PINYIN_9')
        launch(); adb('shell','input','keyevent','4'); time.sleep(.5)
        capture_app(stem + '-setup', ('open_app_settings',))
        ui_tap(pkg + ':id/open_app_settings')
        capture_app(stem + '-preferences-full', ('偏好设置', '强调色'))
        ui_tap('关于与数据')
        capture_app(stem + '-about', ('隐私','用户数据','导出','导入'))
        launch()
        panel('符号', stem + '-symbols-return')
        tap('自定义'); tap('管理自定义符号')
        capture_app(stem + '-symbol-manager', ('custom_symbol_text_editor', '完成'))
        ui_tap('符号菜单')
        capture_app(stem + '-symbol-menu', ('编辑','上移','下移','删除'))
        adb('shell','input','keyevent','4'); ui_tap('完成'); launch()
        panel('剪贴板', stem + '-clipboard-return')
        tap('常用语')
        capture(stem + '-quick-phrases', ('quick-phrase-add', 'phrase-card'))
        tap('phrase-delete:2')
        capture_app(stem + '-phrase-delete-dialog', ('删除常用语','删除'))
        ui_tap('取消')
        tap('quick-phrase-add')
        command('mode:ENGLISH_26'); tap('key:a')
        adb('shell','input','keyevent','4'); time.sleep(.5)
        capture_app(stem + '-quick-phrase-editor', ('quick_phrase_text_editor','保存'))
        ui_tap('取消')
        capture_app(stem + '-discard-dialog', ('放弃未保存内容','继续编辑'))
        ui_tap('放弃'); launch()
    # Narrow phone, wide portrait, landscape, and large fonts use the same view.
    for name, size, font in ([] if panels_only else [('narrow','840x1860','1.0'),('wide','1440x2560','1.0'),('landscape','2400x1080','1.0'),('large-font','1080x2400','1.3')]):
        adb('shell', 'wm', 'size', size)
        adb('shell', 'settings', 'put', 'system', 'font_scale', font)
        time.sleep(0.7); launch(); command('clear-swipe'); appearance('浅色')
        command('mode:PINYIN_9'); capture(name + '-nine', ('key-9:9',))
        command('mode:PINYIN_26'); capture(name + '-pinyin26', ('key:q',))
    print('All visual and real input scenarios passed.', flush=True)
finally:
    adb('shell','am','force-stop',pkg)
    for name,raw in old_prefs.items(): write_pref(name,raw)
    override = re.search(r'Override size: (\d+x\d+)', old_size)
    adb('shell', 'wm', 'size', override.group(1) if override else 'reset')
    override = re.search(r'Override density: (\d+)', old_density)
    adb('shell', 'wm', 'density', override.group(1) if override else 'reset')
    adb('shell', 'settings', 'put', 'system', 'font_scale', old_font if old_font != 'null' else '1.0')
    match = re.search(r'\[(.*?)\]', old_locale)
    adb('shell', 'cmd', 'locale', 'set-app-locales', pkg, '--locales', match.group(1) if match else '')
