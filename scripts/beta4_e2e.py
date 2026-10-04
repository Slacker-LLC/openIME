#!/usr/bin/env python3
"""Beta4 real Android IME E2E: native audio final, capture release, app UI.

Install the debug APK, select an explicitly named emulator, then:
  python3 scripts/beta4_e2e.py --serial emulator-5554 --out .local/test-runs/beta4
The emulator needs a heap class >= 256 MiB for the large native ASR model.
Fixture replay goes through native ASR/punctuation and a live InputConnection;
its host microphone is bypassed because injectAudio crashes this SDK emulator.
Real space-key touch -> AudioRecord capture stop is verified independently.
JSON, editor XML, screenshots, and logs are saved; no simulated ASR result.
"""
import argparse
import base64
import json
import re
import subprocess
import time
import traceback
import xml.etree.ElementTree as ET
from pathlib import Path
from beta3_e2e import Device, show_keyboard, ensure_ime, PKG, ACTION, RECEIVER, field_text

class BoundedDevice(Device):
    def run(self, *args, binary=False):
        r = subprocess.run(self.base + list(args), capture_output=True, check=True, timeout=180 if 'instrument' in args else 25)
        return r.stdout if binary else r.stdout.decode('utf-8', 'replace')

def send(dev, cmd):
    dev.shell('am', 'broadcast', '-n', RECEIVER, '-a', ACTION, '--es', 'cmd', cmd)

def log(dev):
    return dev.run('logcat', '-d', '-v', 'epoch', '-s', 'OpenImeVoicePerf:I', 'OpenImeVoiceLifecycle:I', 'OpenImeVoiceMedia:I', 'OpenImeE2E:I', 'OpenIme:I', 'AndroidRuntime:E')

def wait(probe, timeout=45):
    until = time.monotonic() + timeout
    while time.monotonic() < until:
        result = probe()
        if result: return result
        time.sleep(.1)
    raise AssertionError('Timed out waiting for Android state')

def capture(dev, name):
    dev.shot(name)
    nodes = dev.dump()
    (dev.out / (name+'.xml')).write_text(dev.shell('cat','/sdcard/beta3.xml'))
    return nodes

def verify_layout(dev, name, home):
    """Measure the rendered Android UI, including display overrides."""
    sizes=re.findall(r'(?:Physical|Override) size: (\d+)x(\d+)',dev.shell('wm','size'))
    width=int(sizes[-1][0])
    density=int(re.findall(r'(?:Physical|Override) density: (\d+)',dev.shell('wm','density'))[-1])/160
    left=(width-min(width,600*density))/2+16*density
    tree=ET.fromstring((dev.out/(name+'.xml')).read_text())
    bounds=lambda n:list(map(int,re.findall(r'-?\d+',n.get('bounds'))))
    if home:
        # The IME is ready during this run, so home shows the ready layout:
        # the ready card, the try field and the shortcuts card share page edges.
        by_id={n.get('resource-id','').split('/')[-1]:n for n in tree.iter('node')}
        # Large fonts push the lower cards below the fold; check the ones on screen.
        assert 'ready_card' in by_id, f'{name}: ready card missing'
        cards=[bounds(by_id[k]) for k in ('ready_card','test_input','shortcuts_card') if k in by_id]
        for b in cards:
            assert abs(b[0]-left)<=3 and abs(b[2]-(width-left))<=3, f'{name}: inconsistent page edges {b}'
        assert all(b[3]-b[1]>=52*density-3 for b in cards), f'{name}: card height'
    else:
        # Appearance options sit under the label: card padding 16 + icon 32 + gap 12, track inset 2.
        options=[bounds(n) for n in tree.iter('node') if n.get('content-desc','').split('，')[0] in ('跟随系统','浅色','深色')]
        assert options, f'{name}: appearance options missing'
        assert abs(min(b[0] for b in options)-(left+62*density))<=4, f'{name}: options start {options}'
        assert abs(max(b[2] for b in options)-(width-left-18*density))<=4, f'{name}: options end {options}'
    return {'screenWidthDp':round(width/density,2),'contentMarginDp':16,'passed':True}

def toggle_space(dev, enabled):
    dev.shell('input','keyevent','4')
    dev.shell('am','start','-n',PKG+'/.MainActivity','-f','0x10008000');time.sleep(1)
    nodes=dev.dump();button=next(n for n in nodes if n.desc=='偏好设置')
    dev.tap(button.cx,button.cy);time.sleep(.6)
    for _ in range(8):
        nodes=dev.dump()
        rows=[n for n in nodes if n.desc.startswith('标点用空格代替')]
        if rows:
            row=rows[0]
            if ('已开启' in row.desc) != enabled:
                dev.tap(row.cx,row.cy);time.sleep(.3)
            wait(lambda: any(n.desc.startswith('标点用空格代替') and ('已开启' in n.desc)==enabled for n in dev.dump()),10)
            name='voice-switch-on' if enabled else 'voice-switch-off'
            capture(dev,name)
            xml=ET.fromstring((dev.out/(name+'.xml')).read_text())
            switch=next(n for n in xml.iter('node') if n.get('content-desc','').startswith('标点用空格代替'))
            assert switch.get('class')=='android.widget.Switch' and switch.get('checkable')=='true'
            assert switch.get('checked')==str(enabled).lower(), 'Switch accessibility state differs from saved preference'
            return
        w,h=dev.size();dev.shell('input','swipe',str(w//2),str(int(h*.8)),str(w//2),str(int(h*.3)),'250')
    raise AssertionError('voice setting not reachable in real app UI')

def replay(dev, punctuation_only, name):
    assert show_keyboard(dev)
    send(dev,'voice-punctuation-e2e' if punctuation_only else 'voice-audio-e2e')
    payload=dev.shell('cat','/sdcard/Android/data/'+PKG+'/files/beta4-audio-e2e.json')
    report=json.loads(payload)
    assert report.get('passed'),payload
    time.sleep(.3)
    actual=field_text(dev).strip()
    report['editor']=actual
    assert actual==report['final'], f"real editor mismatch: {actual!r} vs {report['final']!r}"
    (dev.out/(name+'.json')).write_text(json.dumps(report,ensure_ascii=False,indent=2))
    capture(dev,name)
    return report

def main():
    p=argparse.ArgumentParser(description=__doc__)
    p.add_argument('--serial',required=True); p.add_argument('--out',type=Path,required=True)
    p.add_argument('--skip-native',action='store_true')
    a=p.parse_args()
    if not re.fullmatch('emulator-[0-9]+',a.serial): p.error('explicit emulator required')
    d=BoundedDevice(a.serial,a.out); records=[]
    saved={k:d.shell('settings','get',table,k).strip() for table,k in [('system','font_scale'),('system','accelerometer_rotation'),('system','user_rotation'),('secure','default_input_method'),('secure','show_ime_with_hard_keyboard')]}
    backup=d.shell('run-as',PKG,'cat','shared_prefs/ime_settings.xml')
    locale=re.search(r'are \[(.*)\]',d.shell('cmd','locale','get-app-locales',PKG,'--user','0')).group(1)
    size=d.shell('wm','size'); density=d.shell('wm','density')
    try:
        d.shell('cmd','locale','set-app-locales',PKG,'--user','0','--locales','zh-CN')
        d.shell('settings','put','secure','show_ime_with_hard_keyboard','1')
        d.shell('settings','put','system','accelerometer_rotation','0'); d.shell('settings','put','system','user_rotation','0')
        ensure_ime(d)
        if not a.skip_native:
            toggle_space(d,False)
            native=replay(d,False,'native-audio-editor')
            assert native['rawFinal'].endswith('星期三'),native
            assert native['partialBeforeFinish'],native
            assert native['question'].endswith('？') and '，' in native['clauses'],native
            toggle_space(d,True)
            spaced=replay(d,True,'native-space-editor')
            assert spaced['final']==native['clauses'].replace('，',' ').replace('。','').strip(),spaced
            assert spaced['asciiSpace']=='你好 今天很好 明天见',spaced
            assert spaced['literalSpace']=='价格3.5 访问https://a.b',spaced
            records.append({'case':'native audio -> final -> editor + actual UI space preference','passed':True})
        assert show_keyboard(d)
        send(d,'mode:PINYIN_26')
        d.run('logcat','-c');send(d,'bounds')
        bounds=log(d)
        x,y,w,h=map(int,re.search(r'window=(\d+),(\d+),(\d+),(\d+)',bounds).groups())
        left,top,width,height=map(float,re.search(r'tag=key-space\|[^\n]*?\|([\d.eE,+-]+)',bounds).group(1).split(','))
        cx,cy=str(int(x+(left+width/2)*w)),str(int(y+(top+height/2)*h))
        d.run('logcat','-c')
        d.shell('input','motionevent','DOWN',cx,cy)
        try:
            wait(lambda: 'recordStartMs=' in log(d),20)
            time.sleep(1)
        finally: d.shell('input','motionevent','UP',cx,cy)
        data=wait(lambda: (txt if 'inputFinished end' in txt else '') if (txt:=log(d)) else '',90)
        (a.out/'capture-stop.log').write_text(data)
        delay=int(re.search(r'captureFinished releaseToCaptureMs=(\d+)',data).group(1))
        sample=re.search(r'ringRemainingSamples=(\d+) capturedSamples=(\d+) decodedSamples=(\d+) droppedSamples=(\d+)',data)
        remaining,captured,decoded,dropped=map(int,sample.groups())
        assert delay < 150, f'capture delayed {delay} ms'
        assert remaining==0 and captured==decoded and captured>0 and dropped==0, sample.group(0)
        assert 'mediaRestored' in data, 'media volume not restored'
        records.append({'case':'real touch release -> AudioRecord -> queue drain','captureStopMs':delay,'captured':captured,'decoded':decoded,'dropped':dropped,'passed':True})
        capture(d,'capture-stopped')
        d.shell('input','keyevent','4')
        d.shell('am','start','-n',PKG+'/.MainActivity','-f','0x10008000');time.sleep(1)
        nodes=capture(d,'app-home-light')
        records.append({'case':'rendered home size/spacing/alignment',**verify_layout(d,'app-home-light',True)})
        home_xml=ET.fromstring((a.out/'app-home-light.xml').read_text())
        assert any(n.get('text')=='openIME 已就绪' for n in home_xml.iter('node'))
        button=next(n for n in nodes if n.desc=='偏好设置')
        d.tap(button.cx,button.cy);time.sleep(1)
        nodes=capture(d,'app-settings-light')
        records.append({'case':'rendered settings alignment',**verify_layout(d,'app-settings-light',False)})
        assert any(n.text=='偏好设置' for n in nodes)
        assert not any('皮肤' in n.text or '单手' in n.text for n in nodes), 'Removed skin/one-hand settings resurfaced'
        # Set dark mode through its real setting segment.
        nodes=d.dump();dark=next(n for n in nodes if n.desc.startswith('深色，'))
        d.tap(dark.cx,dark.cy);time.sleep(1);capture(d,'app-settings-dark')
        d.shell('input','keyevent','4');time.sleep(.5);capture(d,'app-home-dark')
        d.shell('wm','size','840x1800');d.shell('wm','density','420');d.shell('settings','put','system','font_scale','1.3')
        # Recreate the Activity through its task, without force-stopping the
        # selected IME (Android can asynchronously switch to a fallback IME).
        d.shell('am','start','-n',PKG+'/.MainActivity','-f','0x10008000');time.sleep(2)
        ensure_ime(d)
        d.shell('am','start','-n',PKG+'/.MainActivity','-f','0x10008000')
        wait(lambda: any(n.text=='openIME 已就绪' for n in d.dump()),15)
        time.sleep(1)
        nodes=capture(d,'app-home-320dp-large-font')
        verify_layout(d,'app-home-320dp-large-font',True)
        button=next(n for n in nodes if n.desc=='偏好设置');d.tap(button.cx,button.cy);time.sleep(1)
        nodes=capture(d,'app-settings-320dp-large-font')
        verify_layout(d,'app-settings-320dp-large-font',False)
        assert any(n.text=='偏好设置' for n in nodes), 'Narrow settings page did not actually open'
        assert any(n.desc.startswith('跟随系统，') for n in nodes)
        height_value=next(n for n in nodes if n.text=='100%')
        assert height_value.text=='100%' and height_value.y1-height_value.y0 < 32*d.density(), 'Narrow slider value wraps'
        records.append({'case':'app home/settings light/dark/320dp font 1.3','passed':True})
        d.shell('settings','put','system','font_scale','2.0');time.sleep(1)
        d.shell('am','start','-n',PKG+'/.MainActivity','-f','0x10008000');time.sleep(1)
        nodes=capture(d,'app-home-320dp-font-2')
        verify_layout(d,'app-home-320dp-font-2',True)
        button=next(n for n in nodes if n.desc=='偏好设置');d.tap(button.cx,button.cy);time.sleep(1)
        nodes=capture(d,'app-settings-320dp-font-2')
        assert all(any(n.desc.startswith(v+'，') and n.y1-n.y0>=48*2.625-3 for n in nodes) for v in ('跟随系统','浅色','深色')), 'Large-font options clipped'
        records.append({'case':'320dp font 2.0, adaptive options and actual button geometry','passed':True})
        d.shell('settings','put','system','font_scale','1.0');d.shell('wm','size','1400x840');d.shell('wm','density','160');time.sleep(2)
        d.shell('am','start','-n',PKG+'/.MainActivity','-f','0x10008000');time.sleep(1)
        nodes=capture(d,'app-home-wide')
        verify_layout(d,'app-home-wide',True)
        button=next(n for n in nodes if n.desc=='偏好设置');d.tap(button.cx,button.cy);time.sleep(1)
        capture(d,'app-settings-wide');verify_layout(d,'app-settings-wide',False)
        records.append({'case':'wide display, centered 600dp content and 16dp margins','passed':True})
    except Exception as error:
        records.append({'case':'run','passed':False,'error':str(error)})
        print('FAIL',str(error),flush=True)
        (a.out/'failure-traceback.txt').write_text(traceback.format_exc())
        try:(a.out/'failure.log').write_text(log(d));capture(d,'failure')
        except Exception:pass
    finally:
        d.shell('input','keyevent','4');d.shell('am','force-stop',PKG)
        payload=base64.b64encode(backup.encode()).decode()
        d.shell(f"run-as {PKG} sh -c 'echo {payload} | base64 -d > shared_prefs/ime_settings.xml'")
        for table,k in [('system','font_scale'),('system','accelerometer_rotation'),('system','user_rotation'),('secure','default_input_method'),('secure','show_ime_with_hard_keyboard')]:
            if saved[k]=='null': d.shell('settings','delete',table,k)
            else:d.shell('settings','put',table,k,saved[k])
        d.shell('cmd','locale','set-app-locales',PKG,'--user','0',*(['--locales',locale] if locale else []))
        for command,original in [('size',size),('density',density)]:
            override=re.search(r'Override (?:size|density): (\S+)',original)
            d.shell('wm',command,override.group(1) if override else 'reset')
        report={'serial':a.serial,'results':records,'passed':all(r['passed'] for r in records)}
        (a.out/'results.json').write_text(json.dumps(report,ensure_ascii=False,indent=2))
        print(json.dumps(report,ensure_ascii=False,indent=2),flush=True)
    return 0 if report['passed'] else 1

if __name__=='__main__': raise SystemExit(main())
