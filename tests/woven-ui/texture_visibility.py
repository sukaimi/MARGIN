"""Quantitative/synthetic host proof, NOT an Android render or phone screenshot.
Uses approved PNGs and production filter/opacity constants; does not edit assets.
"""
from pathlib import Path
from PIL import Image, ImageDraw, ImageStat, ImageFont
import json, math, re
ROOT=Path(__file__).resolve().parents[2]
HERE=Path(__file__).resolve().parent
source=(ROOT/'app/src/main/java/ai/codeandcraft/secondbrain/NotebookSurface.java').read_text()
assert '6,0,0,0,-5*244, 0,6,0,0,-5*237, 0,0,6,0,-5*220' in source
assert 'kind==1?190:72' in source and 'textureScale.setScale(density,density)' in source
paper=Image.open(ROOT/'app/src/main/res/drawable-nodpi/paper_grain.png').convert('RGB')
# BOX approximates quarter-resolution resource decode; Android decode can differ.
paper=paper.resize((paper.width//4,paper.height//4),Image.Resampling.BOX)
base=Image.new('RGB',paper.size,(245,239,224))
old=Image.blend(base,paper,22/255)
channels=[c.point(lambda x,m=m: max(0,min(255,6*x-5*m))) for c,m in zip(paper.split(),(244,237,220))]
new=Image.blend(base,Image.merge('RGB',channels),72/255)
old_std=ImageStat.Stat(old).stddev;new_std=ImageStat.Stat(new).stddev
assert min(new_std)>1.5, new_std
assert all(b>3.5*a for a,b in zip(old_std,new_std)),(old_std,new_std)
# Bound dark/light texture extrema and test worst texture under secondary ink.
def luminance(rgb):
 v=[c/255 for c in rgb]; v=[c/12.92 if c<=.04045 else ((c+.055)/1.055)**2.4 for c in v]
 return sum(c*w for c,w in zip(v,(.2126,.7152,.0722)))
ink=luminance((74,88,113))
worst=min((luminance(rgb)+.05)/(ink+.05) for rgb in new.getdata())
assert worst>=4.5,worst
board=Image.new('RGB',(840,650),'#D8D1C3');d=ImageDraw.Draw(board)
font=ImageFont.truetype('/usr/share/fonts/truetype/dejavu/DejaVuSans.ttf',16)
small=ImageFont.truetype('/usr/share/fonts/truetype/dejavu/DejaVuSans.ttf',13)
d.text((20,14),'SYNTHETIC HOST COMPOSITE — not Android/device verification',fill='#17233D',font=font)
for i,(label,texture,raised) in enumerate([('Build 66: 22/255 grain',old,False),('Candidate: contrasted, dp-scaled grain',new,True)]):
 x=20+i*410;d.text((x,48),label,fill='#17233D',font=small)
 # 1dp sample shown at 1.2x to expose texture, identical scale for comparison.
 tile=texture.resize((380,380),Image.Resampling.BILINEAR);board.paste(tile,(x,78))
 d.text((x+20,100),'Meeting notes',fill='#17233D',font=font)
 d.text((x+20,138),'Readable paper, subtle visible grain.',fill='#4A5871',font=small)
 for y,fill in [(200,'#FFFAEF'),(490,'#244ACB')]:
  box=(x+20,y,x+360,y+88);d.rounded_rectangle(box,18,fill=fill,outline='#C9BFA8',width=1)
  if raised:
   mask=Image.new('L',board.size);md=ImageDraw.Draw(mask);md.rounded_rectangle(box,18,fill=255)
   edge=Image.new('RGBA',board.size);ed=ImageDraw.Draw(edge);ed.rectangle((x+20,y+84,x+360,y+88),fill=(23,35,61,64));ed.rectangle((x+20,y,x+360,y+2),fill=(255,255,255,179))
   alpha=edge.getchannel('A');from PIL import ImageChops
   edge.putalpha(ImageChops.multiply(alpha,mask));board=Image.alpha_composite(board.convert('RGBA'),edge).convert('RGB');d=ImageDraw.Draw(board)
  d.text((x+40,y+31),'Saved meeting' if y==200 else 'Record',fill='#17233D' if y==200 else 'white',font=font)
d.text((20,615),'Drawn edges are inside each rounded surface; existing approved PNGs are unchanged.',fill='#17233D',font=small)
board.save(HERE/'texture-visibility-proof.png')
report={'scope':'Host pixel simulation, not Android or A70 acceptance','quarter_decode':'Pillow BOX approximation','old_rgb_stddev':old_std,'new_rgb_stddev':new_std,'worst_secondary_text_contrast':worst,'density_contract':'Bitmap shader unit scaled to dp in production constructor','edge_contract':'3dp lower lip, 1.5dp upper highlight within existing clip'}
(HERE/'texture-visibility-metrics.json').write_text(json.dumps(report,indent=2)+'\n')
print(json.dumps(report,indent=2));print('PASS: texture variation >3.5x old and >=4.5 secondary-text contrast in host composite')
