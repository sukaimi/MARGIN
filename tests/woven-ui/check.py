"""Host asset/contrast/source contracts; not Android runtime geometry acceptance."""
from pathlib import Path
from PIL import Image, ImageChops
import hashlib,json,re,xml.etree.ElementTree as ET
ROOT=Path(__file__).resolve().parents[2]; RES=ROOT/'app/src/main/res'; BASE=ROOT/'app/src/main/java/ai/codeandcraft/secondbrain'
audit=json.loads((ROOT/'approved-ui-assets/asset-audit.json').read_text())
for name,expected in audit['images'].items():
    p=ROOT/'approved-ui-assets'/name; im=Image.open(p)
    assert list(im.size)==expected['size'] and im.mode==expected['mode']
    assert hashlib.sha256(p.read_bytes()).hexdigest()==expected['sha256'],name
source=Image.open(ROOT/'approved-ui-assets/jotterbook-adaptive-foreground.png')
assert source.getchannel('A').point(lambda a:255 if a>=8 else 0).getbbox()==(340,245,926,1012)
fg=Image.open(RES/'drawable-nodpi/ic_launcher_book.png'); assert fg.size==(432,432) and fg.mode=='RGBA'
assert fg.getpixel((0,0))[3]==0
box=fg.getchannel('A').point(lambda a:255 if a>=8 else 0).getbbox()
assert all(72<=v<=360 for v in box),box # Inside 72/108 viewport; substantial book is not clipped.
for qualifier,size in [('mdpi',48),('hdpi',72),('xhdpi',96),('xxhdpi',144),('xxxhdpi',192)]:
    im=Image.open(RES/('mipmap-'+qualifier)/'ic_launcher.png')
    assert im.size==(size,size) and im.mode=='RGB'
    assert im.getpixel((0,0))==(245,239,224) # No baked outer mask.
    cobalt=sum(1 for r,g,b in im.getdata() if b>r*1.5 and b>g*1.2)
    assert cobalt>size*size*.045,(size,cobalt) # Cobalt binding survives 48px.
for resource in ['mipmap-anydpi-v26/ic_launcher.xml','mipmap-anydpi-v33/ic_launcher.xml','drawable/ic_launcher_monochrome.xml','values/icon-colors.xml']:
    ET.parse(RES/resource)
xml=(RES/'mipmap-anydpi-v33/ic_launcher.xml').read_text()
assert '@drawable/ic_launcher_book' in xml and '@drawable/ic_launcher_monochrome' in xml
assert '#F5EFE0' in (RES/'values/icon-colors.xml').read_text()
manifest=(ROOT/'app/src/main/AndroidManifest.xml').read_text(); assert 'android:icon="@mipmap/ic_launcher"' in manifest and 'android:roundIcon="@mipmap/ic_launcher"' in manifest
# Text/controls meet WCAG normal-text contrast on their opaque base colors.
def lum(color):
    rgb=[int(color[i:i+2],16)/255 for i in (0,2,4)]
    rgb=[v/12.92 if v<=.04045 else ((v+.055)/1.055)**2.4 for v in rgb]
    return sum(v*w for v,w in zip(rgb,[.2126,.7152,.0722]))
for ink,bg in [('17233D','F5EFE0'),('4A5871','F5EFE0'),('FFFFFF','244ACB'),('17233D','FF806B'),('17233D','FFD45A'),('17233D','FFFAEF')]:
    a,b=sorted([lum(ink),lum(bg)]); ratio=(b+.05)/(a+.05); assert ratio>=4.5,(ink,bg,ratio)
helper=(BASE/'NotebookSurface.java').read_text();assert 'inSampleSize=4' in helper and 'static Bitmap woven, paper' in helper and 'Shader.TileMode.MIRROR' in helper
assert 'paint.setAlpha(kind==1?190:72)' in helper
assert 'getOutline(Outline outline)' in helper and 'outline.setRoundRect(getBounds()' in helper
assert 'PAPER_CONTRAST' in helper and 'textureScale.setScale(density,density)' in helper
assert 'rect.bottom-lip' in helper and 'rect.top+1.5f*density' in helper
assert 'NotebookSurface.control(c,' in (BASE/'PopUi.java').read_text()
assert 'NotebookSurface.control(context,' in (BASE/'MarginIconButton.java').read_text()
orb=(BASE/'RecordingOrbView.java').read_text(); assert 'postDelayed(this,34)' in orb and orb.count('ValueAnimator.areAnimatorsEnabled()')>=3
assert 'removeCallbacks(frame)' in orb and 'setActive(boolean value)' in orb and 'RadialGradient' not in orb
icon=(BASE/'MarginIconButton.java').read_text()
for dimension in ['MinWidth','MinimumWidth','MinHeight','MinimumHeight']: assert 'set'+dimension+'(PopUi.dp(context,48))' in icon
pop=(BASE/'PopUi.java').read_text();assert 'v.setMinHeight(dp(c,56))' in pop
main=(BASE/'MainActivity.java').read_text();assert main.index('header.addView(PopUi.sound(this)')<main.index('header.addView(about,infoParams)')
assert 'new String[]{"Meetings","Record","Tasks"}' in main
assert 'NotebookSurface.page(this)' in main and 'NotebookSurface.cloth(this)' in main
assert 'setOnClickListener(v->detail(m,"Summary",true))' in main
assert Image.open(ROOT/'tests/woven-ui/proof/launcher-masks-and-48px.png').size==(576,224)
print('PASS: unchanged approved source hashes, dimensions/alpha, launcher viewport/48px binding, XML links, contrast >=4.5, cached MIRROR textures, minimum-target and reduced-motion source contracts (host only)')

# A failed/null decode is cached as a completed attempt, never retried by draw.
constructor=helper.split('private NotebookSurface(',1)[1].split('public static Drawable page',1)[0]
draw=helper.split('@Override public void draw(',1)[1].split('@Override public void setAlpha',1)[0]
assert 'if(!decodeAttempted){decodeAttempted=true;' in constructor
assert constructor.count('=decode(c,')==2
assert 'decodeResource' not in draw and 'new BitmapShader' not in draw
assert 'if(bitmap==null)return null;' in helper
assert 'catch(OutOfMemoryError e){return null;}' in helper
assert 'Resources.NotFoundException | IllegalArgumentException' in helper
assert 'catch(Exception' not in helper and 'catch(Throwable' not in helper
assert 'if(texture!=null)' in draw and 'cloth==null?null:spineTint' in draw
assert 'paint.setColor(fill)' in draw and 'paint.setColor(0xFF176079)' in draw
assert helper.count('new PorterDuffColorFilter(')==1 and 'new PorterDuffColorFilter' not in draw
assert 'rect.inset(density/2,density/2)' in draw
assert 'rect.top+10*density' in draw and 'rect.left+7*density' in draw
assert 'Math.min(y+6*density,rect.bottom)' in draw
for callback in ['onAttachedToWindow','onVisibilityChanged','onWindowVisibilityChanged']:
    body=orb.split(callback+'(',1)[1].split('}',1)[0]
    assert 'restartFrames()' in body
assert 'private void restartFrames(){removeCallbacks(frame);if(isAttachedToWindow()&&active&&isShown()&&android.animation.ValueAnimator.areAnimatorsEnabled())postDelayed(frame,34);}' in orb
assert orb.count('postDelayed(this,34)')==1 and orb.count('postDelayed(frame,34)')==1
assert 'onDetachedFromWindow(){removeCallbacks(frame)' in orb
assert 'PopUi.dp(getContext(),7)' in orb and 'PopUi.dp(getContext(),8)' in orb
for forbidden in ['RecorderService','RECORD_AUDIO','AudioRecord','MediaRecorder']:
    assert forbidden not in orb
provenance=json.loads((ROOT/'tests/woven-ui/asset-provenance.json').read_text())
expected={str(p.relative_to(ROOT)) for p in [*RES.glob('drawable-nodpi/*.png'),*RES.glob('mipmap-*/ic_launcher.png'),*(ROOT/'tests/woven-ui/proof').glob('*.png')]}
assert set(provenance['derived'])==expected
for path,digest in provenance['derived'].items():
    assert hashlib.sha256((ROOT/path).read_bytes()).hexdigest()==digest,path
print('PASS: one-attempt optional decode/null fallback, cached tint, bounds, lifecycle decorative loop and all derived/proof hashes (source/host only)')
