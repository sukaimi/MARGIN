package ai.codeandcraft.secondbrain;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.widget.Button;

/** Local drawn icons with native Button focus, click and accessibility semantics. */
public final class MarginIconButton extends Button {
    public enum Icon { PLAY, PAUSE, EXPORT, INFO, SOUND_ON, SOUND_OFF }
    private Icon icon;
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path path = new Path();
    private final int ink;

    public MarginIconButton(Context context, Icon icon, String label, boolean primary) {
        super(context);
        this.icon = icon;
        ink = primary ? 0xFFFFFFFF : PopUi.INK;
        setText("");
        setContentDescription(label);
        setTooltipText(label);
        setSoundEffectsEnabled(false);
        setMinWidth(PopUi.dp(context,48));
        setMinimumWidth(PopUi.dp(context,48));
        setMinHeight(PopUi.dp(context,48));
        setMinimumHeight(PopUi.dp(context,48));
        setPadding(0,0,0,0);
        setBackgroundTintList(null);
        setBackground(new android.graphics.drawable.RippleDrawable(
            android.content.res.ColorStateList.valueOf(0x22244ACB),
            NotebookSurface.control(context,primary ? PopUi.BLUE : icon == Icon.PAUSE ? PopUi.CORAL : icon == Icon.EXPORT ? PopUi.YELLOW : 0xFFFFFAEF,
                primary ? 0xFF193698 : PopUi.LINE),null));
    }

    public void setIcon(Icon value) { icon = value; invalidate(); }

    @Override protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        canvas.save();
        float scale = PopUi.dp(getContext(),24)/24f;
        canvas.translate((getWidth()-24*scale)/2f,(getHeight()-24*scale)/2f);
        canvas.scale(scale,scale);
        paint.setColor(ink);
        paint.setAlpha(isEnabled() ? 255 : 96);
        paint.setStrokeWidth(2);
        paint.setStrokeCap(Paint.Cap.ROUND);
        paint.setStrokeJoin(Paint.Join.ROUND);
        paint.setStyle(Paint.Style.FILL);
        path.reset();
        switch (icon) {
            case PLAY:
                path.moveTo(7,4); path.lineTo(20,12); path.lineTo(7,20); path.close();
                canvas.drawPath(path,paint); break;
            case PAUSE:
                canvas.drawRoundRect(5,4,10,20,1,1,paint);
                canvas.drawRoundRect(14,4,19,20,1,1,paint); break;
            case EXPORT:
                paint.setStyle(Paint.Style.STROKE);
                path.moveTo(4,14); path.lineTo(4,20); path.lineTo(20,20); path.lineTo(20,14);
                path.moveTo(12,15); path.lineTo(12,3);
                path.moveTo(7,8); path.lineTo(12,3); path.lineTo(17,8);
                canvas.drawPath(path,paint); break;
            case INFO:
                paint.setStyle(Paint.Style.STROKE); canvas.drawCircle(12,12,9,paint);
                canvas.drawLine(12,11,12,17,paint);
                paint.setStyle(Paint.Style.FILL); canvas.drawCircle(12,7,1.2f,paint); break;
            case SOUND_ON:
            case SOUND_OFF:
                path.moveTo(3,9); path.lineTo(7,9); path.lineTo(12,5);
                path.lineTo(12,19); path.lineTo(7,15); path.lineTo(3,15); path.close();
                canvas.drawPath(path,paint); paint.setStyle(Paint.Style.STROKE);
                if (icon == Icon.SOUND_ON) {
                    canvas.drawArc(10,7,20,17,-55,110,false,paint);
                    canvas.drawArc(8,3,24,21,-55,110,false,paint);
                } else {
                    canvas.drawLine(16,9,22,15,paint);
                    canvas.drawLine(22,9,16,15,paint);
                }
                break;
        }
        canvas.restore();
    }
}
