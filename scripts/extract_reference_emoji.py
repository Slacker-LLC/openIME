#!/usr/bin/env python3
"""Extract the emoji artwork supplied on PDF page 28 at its original 3x scale.
Usage: python3 scripts/extract_reference_emoji.py /absolute/path/to/design.pdf
This reads the PDF image directly; no replacement artwork is generated.
"""
import subprocess
import sys
import tempfile
from pathlib import Path
from PIL import Image

emojis = [
 '😀','😃','😄','😁','😆','😅','😂','🤣',
 '🥹','😊','😇','🙂','🙃','😉','😌','😍',
 '🥰','😘','😗','😙','😚','☺️','😛','😝',
 '😜','🤪','😳','🥺','🤓','😎','🥸','🤩',
 '🥳','😏','😒','😞','😔','😟','😕','🙁',
]
output=Path(__file__).resolve().parents[1]/'app/src/main/assets/emoji/reference'
output.mkdir(parents=True,exist_ok=True)
with tempfile.TemporaryDirectory() as folder:
 prefix=Path(folder)/'page'
 subprocess.run(['pdfimages','-f','28','-l','28','-j',sys.argv[1],str(prefix)],check=True)
 page=Image.open(sorted(Path(folder).glob('page-*.jpg'))[0]).convert('RGB')
 if page.size!=(1170,906): raise ValueError('Expected the original 390x302 reference at 3x resolution')
 background=(213,216,223)
 for index,emoji in enumerate(emojis):
  cx=(28+48*(index%8))*3; cy=(113+42*(index//8))*3
  icon=page.crop((cx-42,cy-42,cx+42,cy+42)).convert('RGBA')
  pixels=icon.load()
  for y in range(icon.height):
   for x in range(icon.width):
    rgb=pixels[x,y][:3]
    distance=max(abs(rgb[k]-background[k]) for k in range(3))
    alpha=max(0.,min(1.,(distance-12)/48))
    if alpha==0: pixels[x,y]=(0,0,0,0)
    else:
     foreground=tuple(max(0,min(255,round((rgb[k]-(1-alpha)*background[k])/alpha))) for k in range(3))
     pixels[x,y]=(*foreground,round(alpha*255))
  name='_'.join(format(ord(c),'x') for c in emoji)+'.png'
  icon.save(output/name,optimize=True)
print('Extracted',len(emojis),'reference emoji assets')
