package ai.codeandcraft.secondbrain;

import android.content.Context;
import android.graphics.*;
import android.view.View;

/** Canvas orb with visible-only, reduced-motion-aware breathing, capped at 30 fps. */
public final class RecordingOrbView extends View {
    private final Paint paint=new Paint(3);private boolean active;private long epoch;
    public RecordingOrbView(Context c){super(c);setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);}
    public void setActive(boolean value){if(active==value)return;active=value;epoch=android.os.SystemClock.uptimeMillis();removeCallbacks(frame);if(active&&isShown()&&android.animation.ValueAnimator.areAnimatorsEnabled())post(frame);invalidate();}
    private final Runnable frame=new Runnable(){public void run(){if(active&&isShown()&&android.animation.ValueAnimator.areAnimatorsEnabled()){invalidate();postDelayed(this,34);}}};
    @Override protected void onDraw(Canvas c){super.onDraw(c);float x=getWidth()/2f,y=getHeight()/2f,r=Math.min(getWidth()*.23f,getHeight()*.34f);float scale=active&&android.animation.ValueAnimator.areAnimatorsEnabled()?1f+.027f*(float)Math.sin((android.os.SystemClock.uptimeMillis()-epoch)/1800.0):1f;c.save();c.scale(scale,scale,x,y);paint.setStyle(Paint.Style.FILL);paint.setShader(new RadialGradient(x-r*.4f,y-r*.5f,r*2,new int[]{0xFFD7E8FF,0xFFA8C6FF,0xFF5B88F1,0xFF3157C8,0xFF192E81},new float[]{0,.24f,.48f,.73f,1},Shader.TileMode.CLAMP));c.drawCircle(x,y,r,paint);paint.setShader(null);paint.setColor(0xAADCA32E);paint.setStyle(Paint.Style.STROKE);paint.setStrokeWidth(PopUi.dp(getContext(),2));c.rotate(-24,x,y);c.drawOval(x-r*1.55f,y-r*.95f,x+r*1.55f,y+r*.95f,paint);c.restore();}
    @Override protected void onWindowVisibilityChanged(int visibility){super.onWindowVisibilityChanged(visibility);removeCallbacks(frame);if(visibility==VISIBLE&&active&&android.animation.ValueAnimator.areAnimatorsEnabled())post(frame);}
    @Override protected void onDetachedFromWindow(){removeCallbacks(frame);super.onDetachedFromWindow();}
}
