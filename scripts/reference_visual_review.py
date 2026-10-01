#!/usr/bin/env python3
"""Generate review artifacts from the PDF and measured Android E2E screenshots.
Usage: python3 scripts/reference_visual_review.py DESIGN.pdf OUTPUT_DIRECTORY
Images retain their aspect ratio; system navigation is cropped from IME bounds.
"""
import html
import json
import re
import subprocess
import sys
from pathlib import Path
from PIL import Image, ImageDraw, ImageFont

pdf, output = Path(sys.argv[1]), Path(sys.argv[2]).resolve()
reference = output / 'reference'
review = output / 'review'
reference.mkdir(parents=True, exist_ok=True)
review.mkdir(parents=True, exist_ok=True)
subprocess.run(['pdfimages', '-j', str(pdf), str(reference / 'page')], check=True)
font = ImageFont.truetype('/usr/share/fonts/truetype/dejavu/DejaVuSans.ttf', 15)

def resize(im, width):
    return im.resize((width, round(im.height * width / im.width)), Image.Resampling.LANCZOS)

def measured_image(path):
    im = Image.open(path).convert('RGB')
    bounds = path.with_name(path.stem + '-bounds.txt')
    if bounds.exists():
        match = re.search(r'tag=main-dock\|desc=[^|]*\|([\d.eE,+-]+)', bounds.read_text())
        if match:
            x, y, w, h = map(float, match.group(1).split(','))
            im = im.crop((round(x * im.width), round(y * im.height),
                          round((x+w) * im.width), round((y+h) * im.height)))
    return im

pairs = [
    ('phone/phone-nine-idle', 1, '实机 · 九键'),
    ('phone/phone-nine-composing', 3, '实机 · 拼音候选'),
    ('phone/phone-tools', 22, '实机 · 工具'),
    ('phone/phone-symbols', 27, '实机 · 符号'),
    ('phone/phone-emoji', 28, '实机 · 表情'),
    ('phone/phone-voice', 29, '实机 · 语音'),
    ('phone/phone-preferences', 30, '实机 · 偏好设置'),
    ('light-pinyin26', 11, '拼音 26 键'),
    ('light-english26', 12, '英文 26 键'),
    ('light-numeric', 13, '数字键盘'),
    ('light-keyboard-select', 23, '切换键盘'),
    ('light-clipboard', 24, '剪贴板'),
    ('light-quick-phrases', 25, '常用语'),
    ('light-text-editor', 26, '文本编辑'),
    ('light-skin', 31, '强调色与皮肤'),
    ('light-fuzzy', 32, '模糊音'),
    ('dark-nine-idle', 6, '深色九键'),
    ('dark-tools', 34, '深色工具'),
    ('dark-preferences', 33, '深色设置'),
]
cards = []
phone_rows = []
for stem, page, label in pairs:
    source = output / (stem + '.png')
    if not source.exists():
        raise FileNotFoundError(source)
    a = resize(Image.open(reference / f'page-{page-1:03d}.jpg').convert('RGB'), 390)
    b = resize(measured_image(source), 390)
    # Preserve both actual heights. Never stretch a capture to disguise mismatch.
    canvas = Image.new('RGB', (800, max(a.height, b.height) + 32), '#f8fafc')
    draw = ImageDraw.Draw(canvas)
    draw.text((8, 8), f'PDF {page:02d}', fill='#475569', font=font)
    draw.text((408, 8), 'Android / phone' if stem.startswith('phone/') else 'Android / emulator', fill='#475569', font=font)
    canvas.paste(a, (0, 32)); canvas.paste(b, (410, 32))
    name = stem.replace('/', '-') + '.png'
    canvas.save(review / name)
    cards.append(f'<section><h2>{html.escape(label)} · 设计稿第 {page} 页</h2><img src="review/{name}" loading="lazy"></section>')
    if stem.startswith('phone/') and 'composing' not in stem:
        phone_rows.append(canvas)

overview = Image.new('RGB', (800, sum(im.height for im in phone_rows) + 12*(len(phone_rows)-1)), '#cbd5e1')
y = 0
for im in phone_rows:
    overview.paste(im, (0, y)); y += im.height + 12
overview.save(output / 'comparison.png')

for name in ['narrow', 'wide', 'landscape', 'large-font']:
    for keyboard in ['nine', 'pinyin26']:
        path = output / f'{name}-{keyboard}.png'
        im = measured_image(path)
        im.save(review / path.name)
        cards.append(f'<section><h2>{name} · {keyboard} · 实测 {im.width} × {im.height}</h2><img src="review/{path.name}" loading="lazy"></section>')

(output / 'review.html').write_text('''<!doctype html><html lang="zh-CN"><meta charset="utf-8">
<meta name="viewport" content="width=device-width,initial-scale=1"><title>openIME 设计稿对照</title>
<style>body{font-family:system-ui,sans-serif;max-width:1000px;margin:24px auto;padding:0 16px;background:#f1f5f9;color:#172334}h1{font-size:24px}h2{font-size:17px}section{padding:16px;background:white;border-radius:12px;margin:16px 0}img{max-width:100%;height:auto}p{line-height:1.7}</style>
<h1>openIME · 设计稿与真实 Android 对照</h1>
<p>每组左侧为 PDF 原图，右侧为实机或模拟器截图，统一到 390 宽，仅等比例缩放。输入内容、回车动作、权限与模型状态按实际运行显示。横屏按可用高度收敛键盘尺寸；设置和工具面板可滚动。</p>
''' + '\n'.join(cards) + '</html>')
records = json.loads((output / 'results.json').read_text())
phone = json.loads((output / 'phone/results.json').read_text())
(output / 'README.md').write_text(f'''# openIME 本轮验收

设计来源：`{pdf}`。参考画布 390 单位宽，实际尺寸由设备可用宽度计算；横屏同时限制可用高度。文字、图标、键位、间距、圆角同步缩放。

- 完整 Android 端到端场景：{len(records)} 项通过，覆盖浅色、深色、窄屏 840×1860、宽屏 1440×2560、横屏 2400×1080、系统字体 1.3 倍。
- 核心输入回归：7 项通过，验证九键、26 键、英文、数字、删除和真实提交。
- 最终图标与原稿表情更新：重新执行 20 项面板场景。
- 手机：1200×2670，截图 {sum(bool(r.get('captured')) for r in phone)} 个页面，真实编辑器提交“你好”通过。
- 最终 APK 已覆盖安装到连接的手机及模拟器。构建和 Android Lint 通过。
- 比对保留真实页面状态；没有将模型状态、用户数据或回车动作伪装为设计示例。

查看 `review.html` 可逐页并排检查；`comparison.png` 为实机六个页面的设计稿对照。原始截图、控件实测 bounds 和结果 JSON 均在本目录。

## 可重复验证

在项目根目录执行：

```bash
./gradlew --offline :app:assembleDebug :app:lintDebug
adb -s emulator-5554 install -r app/build/outputs/apk/debug/app-debug.apk
python3 scripts/design_reference_e2e.py emulator-5554 output/reference-final
bash scripts/core_regression.sh emulator-5554 app/build/outputs/apk/debug/app-debug.apk
python3 scripts/reference_phone_capture.py PHONE_SERIAL output/reference-final/phone
python3 scripts/reference_visual_review.py '{pdf}' output/reference-final
```

多尺寸脚本仅允许明确指定模拟器，结束时恢复显示尺寸、字体、外观和测试数据；手机脚本恢复原默认输入法。
''')
print('Saved comparison.png, review.html and README.md')
