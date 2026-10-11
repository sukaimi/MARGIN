package ai.codeandcraft.secondbrain;

import android.content.Context;
import android.content.res.Resources;
import android.graphics.*;
import android.graphics.drawable.Drawable;

/** Cached, density-scaled textures and contained bevels; no platform shadow dependency.
 * Optional textures get one bounded decode attempt; flat paper remains usable.
 */
public final class NotebookSurface extends Drawable {
    private static Bitmap woven, paper;
    private static boolean decodeAttempted;
    private final Paint paint=new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF rect=new RectF(); private final Path clip=new Path();
    private final PorterDuffColorFilter spineTint=new PorterDuffColorFilter(0xFF176079,PorterDuff.Mode.MULTIPLY);
    // Center each channel on the approved paper asset's mean so contrast does not
    // bleach the paper. The old 22/255 unfiltered overlay quantized almost flat.
    private static final ColorMatrixColorFilter PAPER_CONTRAST=new ColorMatrixColorFilter(new float[]{
        6,0,0,0,-5*244, 0,6,0,0,-5*237, 0,0,6,0,-5*220, 0,0,0,1,0});
    private final BitmapShader cloth, grain;
    private final float density; private final int kind, fill, strip;
    private static Bitmap decode(Context c,int resource){
        try{BitmapFactory.Options o=new BitmapFactory.Options();o.inSampleSize=4;o.inScaled=false;return BitmapFactory.decodeResource(c.getResources(),resource,o);}
        catch(Resources.NotFoundException | IllegalArgumentException e){return null;}
        catch(OutOfMemoryError e){return null;}
    }
    private static BitmapShader shader(Bitmap bitmap){
        if(bitmap==null)return null;
        try{return new BitmapShader(bitmap,Shader.TileMode.MIRROR,Shader.TileMode.MIRROR);}
        catch(OutOfMemoryError e){return null;}
    }
    private NotebookSurface(Context c,int kind,int fill,int strip){
        synchronized(NotebookSurface.class){if(!decodeAttempted){decodeAttempted=true;woven=decode(c,R.drawable.woven_texture);paper=decode(c,R.drawable.paper_grain);}}
        density=c.getResources().getDisplayMetrics().density;this.kind=kind;this.fill=fill;this.strip=strip;
        cloth=kind==3?null:shader(woven);grain=kind==1||kind==3?null:shader(paper);
        // The nodpi bitmap must not become sub-dp weave on high-density screens.
        Matrix textureScale=new Matrix();textureScale.setScale(density,density);
        if(cloth!=null)cloth.setLocalMatrix(textureScale);
        if(grain!=null)grain.setLocalMatrix(textureScale);
    }
    public static Drawable page(Context c){return new NotebookSurface(c,0,PopUi.PAPER,0);}
    public static Drawable cloth(Context c){return new NotebookSurface(c,1,PopUi.PAPER,0);}
    public static Drawable card(Context c,int fill,int strip){return new NotebookSurface(c,2,fill,strip);}
    public static Drawable control(Context c,int fill,int stroke){return new NotebookSurface(c,3,fill,stroke);}
    @Override public void draw(Canvas c){
        rect.set(getBounds());float radius=kind==0?0:(kind==3?15:18)*density;
        clip.reset();clip.addRoundRect(rect,radius,radius,Path.Direction.CW);c.save();c.clipPath(clip);
        paint.setShader(null);paint.setColor(fill);paint.setColorFilter(null);paint.setAlpha(255);c.drawRect(rect,paint);
        BitmapShader texture=kind==3?null:kind==1?cloth:grain;
        if(texture!=null){paint.setShader(texture);paint.setColorFilter(kind==1?null:PAPER_CONTRAST);paint.setAlpha(kind==1?190:72);c.drawRect(rect,paint);}
        paint.setShader(null);paint.setColorFilter(null);paint.setAlpha(255);
        if(kind==0){
            paint.setColor(0xFF176079);paint.setShader(cloth);paint.setColorFilter(cloth==null?null:spineTint);
            c.drawRect(rect.left,rect.top,rect.left+12*density,rect.bottom,paint);
            c.drawRect(rect.right-4*density,rect.top,rect.right,rect.bottom,paint);
            paint.setShader(null);paint.setColorFilter(null);paint.setColor(0xFFD9B64C);paint.setStrokeWidth(density);
            for(float y=rect.top+10*density;y<rect.bottom;y+=18*density)c.drawLine(rect.left+7*density,y,rect.left+7*density,Math.min(y+6*density,rect.bottom),paint);
        }else if(kind==2){paint.setColor(strip);c.drawRect(rect.left,rect.top,rect.left+7*density,rect.bottom,paint);}
        if(kind!=0){
            // Draw both edges INSIDE the clipped drawable. Parent clipping, OEM
            // elevation and a missing bitmap cannot erase this tactile depth.
            float lip=Math.min(3*density,(rect.bottom-rect.top)/4);
            paint.setColor(kind==3?0x4017233D:0x38715C3E);
            c.drawRect(rect.left,rect.bottom-lip,rect.right,rect.bottom,paint);
            paint.setColor(0xB3FFFFFF);
            c.drawRect(rect.left,rect.top,rect.right,Math.min(rect.top+1.5f*density,rect.bottom),paint);
        }
        c.restore();paint.setStyle(Paint.Style.STROKE);paint.setStrokeWidth(density);paint.setColor(kind==3?strip:PopUi.LINE);
        if(kind!=0){rect.inset(density/2,density/2);c.drawRoundRect(rect,Math.max(0,radius-density/2),Math.max(0,radius-density/2),paint);}paint.setStyle(Paint.Style.FILL);
    }
    @Override public void getOutline(Outline outline){
        outline.setRoundRect(getBounds(),kind==0?0:(kind==3?15:18)*density);
        outline.setAlpha(1f);
    }
    @Override public void setAlpha(int alpha){} @Override public void setColorFilter(ColorFilter f){} @Override public int getOpacity(){return PixelFormat.TRANSLUCENT;}
}
