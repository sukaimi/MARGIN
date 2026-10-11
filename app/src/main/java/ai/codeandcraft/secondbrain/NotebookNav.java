package ai.codeandcraft.secondbrain;

import android.content.Context;
import android.graphics.*;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.RippleDrawable;
import android.content.res.ColorStateList;
import android.widget.Button;

/** Native text buttons with decorative drawn icons; Record is always primary. */
final class NotebookNav {
    static Button button(Context c,String name,String label,boolean selected){
        boolean primary="Record".equals(name);
        Button b=PopUi.button(c,label,primary);
        b.setTextSize(16);b.setGravity(android.view.Gravity.CENTER);
        b.setMinWidth(PopUi.dp(c,48));b.setMinimumWidth(PopUi.dp(c,48));
        b.setMinHeight(PopUi.dp(c,76));b.setMinimumHeight(PopUi.dp(c,76));
        b.setPadding(PopUi.dp(c,4),PopUi.dp(c,8),PopUi.dp(c,4),PopUi.dp(c,8));
        b.setElevation(primary?PopUi.dp(c,3):0);b.setStateListAnimator(null);
        if(!primary)b.setBackground(new RippleDrawable(ColorStateList.valueOf(0x22244ACB),null,null));
        NavIcon icon=new NavIcon(name,primary?Color.WHITE:PopUi.INK);
        icon.setBounds(0,0,PopUi.dp(c,24),PopUi.dp(c,24));
        b.setCompoundDrawables(null,icon,null,null);b.setCompoundDrawablePadding(PopUi.dp(c,4));
        b.setTooltipText(label);select(b,selected);return b;
    }
    static void select(Button b,boolean selected){
        b.setSelected(selected);b.setTypeface(Typeface.SANS_SERIF,selected?Typeface.BOLD:Typeface.NORMAL);
        b.setPaintFlags(selected?b.getPaintFlags()|Paint.UNDERLINE_TEXT_FLAG:b.getPaintFlags()&~Paint.UNDERLINE_TEXT_FLAG);
        b.setContentDescription(b.getText()+(selected?", selected":""));
    }
    private static final class NavIcon extends Drawable {
        private final Paint p=new Paint(Paint.ANTI_ALIAS_FLAG);private final String name;
        NavIcon(String name,int color){this.name=name;p.setColor(color);p.setStyle(Paint.Style.STROKE);p.setStrokeWidth(1.8f);p.setStrokeCap(Paint.Cap.ROUND);p.setStrokeJoin(Paint.Join.ROUND);}
        @Override public void draw(Canvas c){c.save();Rect b=getBounds();c.translate(b.left,b.top);c.scale(b.width()/24f,b.height()/24f);
            if("Record".equals(name)){c.drawRoundRect(9,3,15,14,3,3,p);c.drawArc(6,7,18,18,0,180,false,p);c.drawLine(12,18,12,21,p);c.drawLine(8,21,16,21,p);}
            else if("Meetings".equals(name)){c.drawRoundRect(3,5,21,21,2,2,p);c.drawLine(3,10,21,10,p);c.drawLine(8,3,8,7,p);c.drawLine(16,3,16,7,p);c.drawLine(7,15,10,15,p);c.drawLine(14,15,17,15,p);}
            else {c.drawLine(4,12,10,18,p);c.drawLine(10,18,20,6,p);}c.restore();}
        @Override public void setAlpha(int alpha){p.setAlpha(alpha);}
        @Override public void setColorFilter(ColorFilter filter){p.setColorFilter(filter);}
        @Override public int getOpacity(){return PixelFormat.TRANSLUCENT;}
    }
}
