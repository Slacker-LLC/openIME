#!/usr/bin/env python3
"""Capture the real phone UI after installing the debug APK.
Usage: python3 scripts/reference_phone_capture.py SERIAL OUTPUT [--apply-reference-skin]
Only the explicit skin flag updates visual settings. It preserves other preferences
and all dictionaries, clipboard entries, phrases and custom symbols.
"""
import json
import re
import subprocess
import sys
import time
from pathlib import Path
import xml.etree.ElementTree as ET
from PIL import Image

serial, output = sys.argv[1:3]
out = Path(output).resolve(); out.mkdir(parents=True, exist_ok=True)
pkg = 'llc.slacker.openime'
def adb(*args, binary=False):
    data = subprocess.run(['adb', '-s', serial, *args], check=True, capture_output=True).stdout
    return data if binary else data.decode('utf-8', errors='replace')
def cmd(value):
    adb('logcat','-c')
    adb('shell','am','broadcast','-n',pkg+'/.E2ETestReceiver','-a',pkg+'.TEST_COMMAND','--es','cmd',value)
    time.sleep(.4)
    log = adb('logcat','-d','-s','OpenImeE2E:I','OpenIme:I')
    if 'ok=true' not in log: raise AssertionError(value + ': ' + log)
    return log

def tree():
    adb('shell','uiautomator','dump','/sdcard/openime-reference.xml')
    return adb('shell','cat','/sdcard/openime-reference.xml')

def tap_ui(target):
    for node in ET.fromstring(tree()).iter('node'):
        if node.get('resource-id') == pkg+':id/'+target:
            x1,y1,x2,y2 = map(int,re.findall(r'\d+',node.get('bounds')))
            if y2>y1:
                adb('shell','input','tap',str((x1+x2)//2),str((y1+y2)//2)); return
    raise AssertionError('Missing visible ' + target)

raw=adb('shell','run-as',pkg,'cat','shared_prefs/ime_settings.xml',binary=True)
prefs=ET.fromstring(raw)
old_mode=next((n.text for n in prefs if n.get('name')=='preferred_chinese_mode'),'PINYIN_26')
if '--apply-reference-skin' in sys.argv:
    for name,kind,value in [('skin_font','int','21'),('skin_opacity','int','100'),('skin_radius','int','8'),('skin_color','string','#1D9BF0'),('floating_width_percent','int','100')]:
        for n in list(prefs):
            if n.get('name')==name: prefs.remove(n)
        n=ET.SubElement(prefs,kind,{'name':name})
        if kind=='string': n.text=value
        else: n.set('value',value)
    adb('shell','am','force-stop',pkg)
    subprocess.run(['adb','-s',serial,'shell',f"run-as {pkg} sh -c 'cat > shared_prefs/ime_settings.xml'"],input=ET.tostring(prefs,encoding='utf-8',xml_declaration=True),check=True,capture_output=True)

old_ime = adb('shell','settings','get','secure','default_input_method').strip()
adb('shell','am','force-stop',pkg)
adb('shell','ime','set',pkg+'/.LocalVoiceImeService')
time.sleep(.7)
records=[]
def capture(name, keyboard=True):
    time.sleep(.6)
    screen=Image.open(__import__('io').BytesIO(adb('exec-out','screencap','-p',binary=True)))
    screen.save(out/(name+'-screen.png'))
    if keyboard:
        log=cmd('bounds'); box=re.search(r'window=(\d+),(\d+),(\d+),(\d+)',log)
        if not box: raise AssertionError('Missing real IME window')
        x,y,w,h=map(int,box.groups());screen.crop((x,y,x+w,y+h)).save(out/(name+'.png'))
        (out/(name+'-bounds.txt')).write_text(log)
    else:
        screen.save(out/(name+'.png'));(out/(name+'-ui.xml')).write_text(tree())
    records.append({'case':name,'captured':True})
    print('CAPTURE',name,flush=True)
try:
    adb('shell','am','start','-n',pkg+'/.MainActivity');time.sleep(1)
    adb('shell','input','keyevent','111');capture('phone-setup',False)
    focused = False
    for _ in range(5):
        tap_ui('test_step');time.sleep(.7)
        try:
            if 'window=' in cmd('bounds'):
                focused = True
                break
        except AssertionError:
            time.sleep(.5)
    if not focused: raise AssertionError('Cannot focus the real phone editor')
    cmd('mode:PINYIN_9');capture('phone-nine-idle')
    cmd('nine-sequence:64426');capture('phone-nine-composing')
    cmd('tap:key-space')
    # Verify a genuine commit into the Activity's actual EditText.
    ui=tree()
    if not any(n.get('resource-id')==pkg+':id/test_input' and '你好' in n.get('text','') for n in ET.fromstring(ui).iter('node')):
        raise AssertionError('Chinese input did not commit to the real phone editor')
    for label,name in [('工具','tools'),('符号','symbols'),('表情','emoji'),('语音输入','voice'),('设置','preferences')]:
        cmd('mode:PINYIN_9');cmd('tap:更多')
        if label!='工具': cmd('tap:'+label)
        capture('phone-'+name)
    records.append({'case':'phone-chinese-commit','passed':True})
finally:
    try: cmd('mode:'+old_mode)
    except AssertionError: pass
    if old_ime and old_ime != pkg+'/.LocalVoiceImeService': adb('shell','ime','set',old_ime)
    (out/'results.json').write_text(json.dumps(records,ensure_ascii=False,indent=2))
