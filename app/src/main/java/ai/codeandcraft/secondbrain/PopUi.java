package ai.codeandcraft.secondbrain;

import android.content.Context;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.view.View;
import android.widget.*;

/** Native authored-reference tokens. No audio is generated during capture. */
public final class PopUi {
    public static final int PAPER=0xFFF5EFE0, INK=0xFF17233D, BLUE=0xFF244ACB,
        SECONDARY=0xFF4A5871, LINE=0xFFC9BFA8, SOFT=0xFFF6E8C8, CORAL=0xFFFF806B, YELLOW=0xFFFFD45A;
    private PopUi() {}
    public static int dp(Context c,float n){return Math.round(n*c.getResources().getDisplayMetrics().density);}
    public static LinearLayout column(Context c){LinearLayout v=new LinearLayout(c);v.setOrientation(1);return v;}
    public static GradientDrawable shape(Context c,int fill,int stroke,int radius){GradientDrawable d=new GradientDrawable();d.setColor(fill);d.setCornerRadius(dp(c,radius));d.setStroke(dp(c,1.5f),stroke);return d;}
    public static TextView text(Context c,String s,float size,boolean bold){TextView v=new TextView(c);v.setText(s);v.setTextSize(size);v.setTextColor(INK);v.setTypeface(Typeface.SANS_SERIF,bold?Typeface.BOLD:Typeface.NORMAL);v.setLineSpacing(0,size>=21&&size<30?1.5f:1.25f);if(size>=36){v.setLetterSpacing(-.045f);if(android.os.Build.VERSION.SDK_INT>=28)v.setTypeface(Typeface.create(Typeface.SANS_SERIF,750,false));}if(bold&&size>=26)v.setAccessibilityHeading(true);return v;}
    public static View brandMark(Context c){return new View(c){private final android.graphics.Paint p=new android.graphics.Paint(3);@Override protected void onDraw(android.graphics.Canvas canvas){float d=getResources().getDisplayMetrics().density;canvas.save();canvas.translate(3*d,7*d);canvas.rotate(-13,8*d,8*d);p.setColor(0xFFFA6C57);canvas.drawRoundRect(5*d,3*d,20*d,18*d,4*d,4*d,p);p.setColor(0xFFFFBD29);canvas.drawRoundRect(0,0,15*d,15*d,4*d,4*d,p);canvas.restore();}};}
    public static View homeTitle(Context c,String label){FrameLayout frame=new FrameLayout(c);frame.addView(text(c,label,38.7f,true),new FrameLayout.LayoutParams(-1,-2));return frame;}
    public static Button button(Context c,String s,boolean primary){Button v=new Button(c);v.setText(s);v.setSoundEffectsEnabled(false);v.setTextSize(18);v.setAllCaps(false);v.setSingleLine(false);v.setMinHeight(dp(c,56));v.setMinimumHeight(dp(c,56));v.setPadding(dp(c,14),dp(c,10),dp(c,14),dp(c,10));v.setTextColor(primary?0xFFFFFFFF:INK);v.setBackgroundTintList(null);v.setBackground(new android.graphics.drawable.RippleDrawable(android.content.res.ColorStateList.valueOf(0x22244ACB),NotebookSurface.control(c,primary?BLUE:0xFFFFFAEF,primary?0xFF193698:LINE),null));return v;}
    public static void add(LinearLayout p,View v,int gap){LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(-1,-2);lp.topMargin=dp(p.getContext(),gap);p.addView(v,lp);}
    public static LinearLayout card(Context c,int color){LinearLayout v=column(c);v.setPadding(dp(c,20),dp(c,16),dp(c,16),dp(c,16));v.setBackground(NotebookSurface.card(c,color==0xFFFFFFFF?0xFFFFFAEF:color,color==0xFFFFFFFF?CORAL:YELLOW));return v;}
    public static void cue(View v){v.performHapticFeedback(android.view.HapticFeedbackConstants.CONTEXT_CLICK);}
    public static MarginIconButton iconButton(Context c,MarginIconButton.Icon icon,String label,boolean primary){return new MarginIconButton(c,icon,label,primary);}
    public static Button sound(Context c){boolean enabled=c.getSharedPreferences("pop-ui",0).getBoolean("sound",true);MarginIconButton b=iconButton(c,enabled?MarginIconButton.Icon.SOUND_ON:MarginIconButton.Icon.SOUND_OFF,enabled?"Sound effects on. Mute sound effects":"Sound effects off. Enable sound effects",false);b.setOnClickListener(v->{boolean next=!c.getSharedPreferences("pop-ui",0).getBoolean("sound",true);c.getSharedPreferences("pop-ui",0).edit().putBoolean("sound",next).apply();if(next)prepareSound(c);else UiSoundEngine.INSTANCE.release();b.setIcon(next?MarginIconButton.Icon.SOUND_ON:MarginIconButton.Icon.SOUND_OFF);String label=next?"Sound effects on. Mute sound effects":"Sound effects off. Enable sound effects";b.setContentDescription(label);b.setTooltipText(label);cue(b);});return b;}
    public static boolean capturePending;
    public static void stopSound(){UiSoundEngine.INSTANCE.stop();}
    public static long soundGeneration(){return UiSoundEngine.INSTANCE.generation();}
    public static boolean soundCurrent(long token){return UiSoundEngine.INSTANCE.current(token);}
    public static void prepareSound(Context c){UiSoundEngine.INSTANCE.prepare(c);}
    public static void resumeSound(Context c){UiSoundEngine.INSTANCE.resume(c);}
    public static void pauseSound(){UiSoundEngine.INSTANCE.pause();}
    public static void playbackStarted(Object owner){UiSoundEngine.INSTANCE.playbackStarted(owner);}
    public static void playbackClosed(Object owner){UiSoundEngine.INSTANCE.playbackClosed(owner);}
    public static void feedback(View view,String kind){cue(view);UiSoundEngine.INSTANCE.play(view.getContext(),kind);}
    public static EditText input(Context c,String label,String value,int max){EditText e=new EditText(c);e.setText(value);e.setHint(label);e.setContentDescription(label);e.setTextSize(18);e.setTextColor(INK);e.setMinHeight(dp(c,56));e.setPadding(dp(c,13),dp(c,14),dp(c,13),dp(c,14));e.setFilters(new android.text.InputFilter[]{new android.text.InputFilter.LengthFilter(max)});e.setBackground(shape(c,0xFFFFFFFF,0xFF9EAFD6,10));return e;}
}
