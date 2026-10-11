package ai.codeandcraft.secondbrain;

import android.content.Context;
import android.graphics.*;
import android.view.View;

/** Ceramic indicator with visible-only, reduced-motion-aware breathing, capped at 30 fps. */
public final class RecordingOrbView extends View {
    private final Paint paint=new Paint(3);private boolean active;private long epoch;
    public RecordingOrbView(Context c){super(c);setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);}
    public void setActive(boolean value){if(active==value)return;active=value;epoch=android.os.SystemClock.uptimeMillis();restartFrames();invalidate();}
    private final Runnable frame=new Runnable(){public void run(){if(isAttachedToWindow()&&active&&isShown()&&android.animation.ValueAnimator.areAnimatorsEnabled()){invalidate();postDelayed(this,34);}}};
    @Override protected void onDraw(Canvas c){super.onDraw(c);float x=getWidth()/2f,y=getHeight()/2f,r=Math.min(getWidth()*.23f,getHeight()*.34f);float scale=active&&android.animation.ValueAnimator.areAnimatorsEnabled()?1f+.012f*(float)Math.sin((android.os.SystemClock.uptimeMillis()-epoch)/1800.0):1f;c.save();c.scale(scale,scale,x,y);paint.setShader(null);paint.setStyle(Paint.Style.FILL);paint.setColor(0xFFE4D7BE);c.drawCircle(x,y+PopUi.dp(getContext(),3),r,paint);paint.setColor(PopUi.BLUE);c.drawCircle(x,y,r,paint);paint.setStyle(Paint.Style.STROKE);paint.setStrokeWidth(PopUi.dp(getContext(),2));paint.setColor(0xFF193698);c.drawCircle(x,y,r-PopUi.dp(getContext(),1),paint);paint.setColor(0xFF91A7E8);float inset=PopUi.dp(getContext(),7);c.drawArc(x-r+inset,y-r+inset,x+r-inset,y+r-inset,205,65,false,paint);paint.setStyle(Paint.Style.FILL);paint.setColor(PopUi.PAPER);c.drawRoundRect(x-r*.20f,y-r*.30f,x+r*.20f,y+r*.30f,PopUi.dp(getContext(),8),PopUi.dp(getContext(),8),paint);c.restore();}
    private void restartFrames(){removeCallbacks(frame);if(isAttachedToWindow()&&active&&isShown()&&android.animation.ValueAnimator.areAnimatorsEnabled())postDelayed(frame,34);}
    @Override protected void onAttachedToWindow(){super.onAttachedToWindow();restartFrames();}
    @Override protected void onVisibilityChanged(View changedView,int visibility){super.onVisibilityChanged(changedView,visibility);if(frame!=null)restartFrames();}
    @Override protected void onWindowVisibilityChanged(int visibility){super.onWindowVisibilityChanged(visibility);if(frame!=null)restartFrames();}
    @Override protected void onDetachedFromWindow(){removeCallbacks(frame);super.onDetachedFromWindow();}
}
