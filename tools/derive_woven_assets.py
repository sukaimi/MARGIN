"""Deterministic Pillow derivations of approved originals; no generation."""
from pathlib import Path
from PIL import Image, ImageDraw
import hashlib, json
ROOT=Path(__file__).resolve().parents[1]
SRC=ROOT/'approved-ui-assets'; RES=ROOT/'app/src/main/res'
(RES/'drawable-nodpi').mkdir(exist_ok=True)
for source,target in [('jotterbook-warm-woven-texture.png','woven_texture.png'),('jotterbook-cream-paper-grain.png','paper_grain.png')]:
    # Preserve the supplied source for cached inSampleSize=4 native decode.
    (RES/'drawable-nodpi'/target).write_bytes((SRC/source).read_bytes())
book=Image.open(SRC/'jotterbook-adaptive-foreground.png').convert('RGBA')
# Full approved canvas, 11% inset. Never trim to stray nonzero alpha pixels.
fg=Image.new('RGBA',(1080,1080)); fg.alpha_composite(book.resize((842,842),Image.Resampling.LANCZOS),(119,119))
fg.resize((432,432),Image.Resampling.LANCZOS).save(RES/'drawable-nodpi/ic_launcher_book.png')
background=Image.new('RGBA',(1080,1080),'#F5EFE0'); background.alpha_composite(fg)
viewport=background.crop((180,180,900,900)) # Adaptive 108 -> 72 apparent viewport.
for qualifier,size in [('mdpi',48),('hdpi',72),('xhdpi',96),('xxhdpi',144),('xxxhdpi',192)]:
    folder=RES/('mipmap-'+qualifier); folder.mkdir(exist_ok=True)
    viewport.resize((size,size),Image.Resampling.LANCZOS).convert('RGB').save(folder/'ic_launcher.png')
proof=ROOT/'tests/woven-ui/proof'; proof.mkdir(exist_ok=True)
board=Image.new('RGB',(576,224),'#D8D1C3')
for i,kind in enumerate(['circle','squircle','legacy']):
    icon=viewport.resize((192,192),Image.Resampling.LANCZOS)
    mask=Image.new('L',(192,192),0); d=ImageDraw.Draw(mask)
    if kind=='circle': d.ellipse((0,0,191,191),fill=255)
    elif kind=='squircle': d.rounded_rectangle((0,0,191,191),radius=44,fill=255)
    else: d.rectangle((0,0,191,191),fill=255)
    board.paste(icon,(i*192,0),mask)
    small=icon.resize((48,48),Image.Resampling.LANCZOS); board.paste(small,(i*192+72,176),mask.resize((48,48)))
board.save(proof/'launcher-masks-and-48px.png')
metadata={'credit':'Original generated production assets; source metadata retained in approved-ui-assets/asset-audit.json. No external artwork.', 'pillow':Image.__version__,'foreground':'Full 1254 canvas -> 842 within 1080 (11% inset), then 432. No alpha trim or outer mask.','legacy':'Same composed foreground, ivory background, central 72/108 viewport; no baked mask.', 'sources':{p.name:hashlib.sha256(p.read_bytes()).hexdigest() for p in SRC.glob('*.png')}}
metadata['derived']={str(p.relative_to(ROOT)):hashlib.sha256(p.read_bytes()).hexdigest() for p in sorted([*RES.glob('drawable-nodpi/*.png'),*RES.glob('mipmap-*/ic_launcher.png'),*proof.glob('*.png')])}
(ROOT/'tests/woven-ui/asset-provenance.json').write_text(json.dumps(metadata,indent=2)+'\n')
