package ai.codeandcraft.secondbrain;

import android.content.Context;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.view.View;
import android.widget.*;

/** Native authored-reference tokens. No audio is generated during capture. */
public final class PopUi {
    public static final int PAPER=0xFFF8F6F0, INK=0xFF17233D, BLUE=0xFF244ACB,
        SECONDARY=0xFF4A5871, LINE=0xFFD3D9E6, SOFT=0xFFE9EDFF;
    private PopUi() {}
    public static int dp(Context c,float n){return Math.round(n*c.getResources().getDisplayMetrics().density);}
    public static LinearLayout column(Context c){LinearLayout v=new LinearLayout(c);v.setOrientation(1);return v;}
    public static GradientDrawable shape(Context c,int fill,int stroke,int radius){GradientDrawable d=new GradientDrawable();d.setColor(fill);d.setCornerRadius(dp(c,radius));d.setStroke(dp(c,1.5f),stroke);return d;}
    public static TextView text(Context c,String s,float size,boolean bold){TextView v=new TextView(c);v.setText(s);v.setTextSize(size);v.setTextColor(INK);v.setTypeface(Typeface.SANS_SERIF,bold?Typeface.BOLD:Typeface.NORMAL);v.setLineSpacing(0,size>=21&&size<30?1.5f:1.25f);if(size>=36){v.setLetterSpacing(-.045f);if(android.os.Build.VERSION.SDK_INT>=28)v.setTypeface(Typeface.create(Typeface.SANS_SERIF,750,false));}if(bold&&size>=26)v.setAccessibilityHeading(true);return v;}
    public static View brandMark(Context c){return new View(c){private final android.graphics.Paint p=new android.graphics.Paint(3);@Override protected void onDraw(android.graphics.Canvas canvas){float d=getResources().getDisplayMetrics().density;canvas.save();canvas.translate(3*d,7*d);canvas.rotate(-13,8*d,8*d);p.setColor(0xFFFA6C57);canvas.drawRoundRect(5*d,3*d,20*d,18*d,4*d,4*d,p);p.setColor(0xFFFFBD29);canvas.drawRoundRect(0,0,15*d,15*d,4*d,4*d,p);canvas.restore();}};}
    public static View homeTitle(Context c,String label){FrameLayout frame=new FrameLayout(c);View circle=new View(c);GradientDrawable d=new GradientDrawable();d.setShape(GradientDrawable.OVAL);d.setColor(0x9EFFD254);circle.setBackground(d);FrameLayout.LayoutParams spot=new FrameLayout.LayoutParams(dp(c,70),dp(c,70),android.view.Gravity.RIGHT|android.view.Gravity.TOP);spot.rightMargin=-dp(c,20);frame.addView(circle,spot);frame.addView(text(c,label,38.7f,true),new FrameLayout.LayoutParams(-1,-2));return frame;}
    public static Button button(Context c,String s,boolean primary){Button v=new Button(c);v.setText(s);v.setSoundEffectsEnabled(false);v.setTextSize(18);v.setAllCaps(false);v.setSingleLine(false);v.setMinHeight(dp(c,56));v.setMinimumHeight(dp(c,56));v.setPadding(dp(c,14),dp(c,10),dp(c,14),dp(c,10));v.setTextColor(primary?0xFFFFFFFF:BLUE);v.setBackgroundTintList(null);v.setBackground(new android.graphics.drawable.RippleDrawable(android.content.res.ColorStateList.valueOf(0x22244ACB),shape(c,primary?BLUE:0xFFFFFFFF,primary?0xFF193698:LINE,15),null));return v;}
    public static void add(LinearLayout p,View v,int gap){LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(-1,-2);lp.topMargin=dp(p.getContext(),gap);p.addView(v,lp);}
    public static LinearLayout card(Context c,int color){LinearLayout v=column(c);v.setPadding(dp(c,16),dp(c,13),dp(c,16),dp(c,13));v.setBackground(shape(c,color,0xFFA5B2D1,18));return v;}
    public static void cue(View v){v.performHapticFeedback(android.view.HapticFeedbackConstants.CONTEXT_CLICK);}
    public static Button sound(Context c){boolean enabled=c.getSharedPreferences("pop-ui",0).getBoolean("sound",true);Button b=button(c,enabled?"Sound on":"Sound off",false);b.setSoundEffectsEnabled(false);b.setContentDescription(enabled?"Mute sound effects":"Enable sound effects");b.setOnClickListener(v->{boolean next=!c.getSharedPreferences("pop-ui",0).getBoolean("sound",true);c.getSharedPreferences("pop-ui",0).edit().putBoolean("sound",next).apply();b.setText(next?"Sound on":"Sound off");b.setContentDescription(next?"Mute sound effects":"Enable sound effects");cue(b);});return b;}
    private static android.media.AudioTrack cueTrack;
    private static final android.os.Handler cueHandler=new android.os.Handler(android.os.Looper.getMainLooper());
    public static boolean capturePending;
    public static void stopSound(){cueHandler.removeCallbacksAndMessages(null);if(cueTrack!=null){try{cueTrack.stop();}catch(Exception ignored){}cueTrack.release();cueTrack=null;}}
    public static void feedback(View view,String kind){cue(view);Context c=view.getContext();String status=RecorderService.getStatus();if(capturePending||RecorderService.isRecording()||"STARTING".equals(status)||"RECORDING".equals(status)||!c.getSharedPreferences("pop-ui",0).getBoolean("sound",true))return;
        android.media.AudioManager manager=(android.media.AudioManager)c.getSystemService(Context.AUDIO_SERVICE);if(manager==null||manager.getRingerMode()!=android.media.AudioManager.RINGER_MODE_NORMAL||android.provider.Settings.System.getInt(c.getContentResolver(),android.provider.Settings.System.SOUND_EFFECTS_ENABLED,0)==0)return;
        stopSound();try{int rate=22050,n=rate/20;short[] data=new short[n];double from="finish".equals(kind)?410:660,to="finish".equals(kind)?280:600,phase=0;for(int i=0;i<n;i++){double t=(double)i/n,env=Math.min(1,Math.min(i/(rate*.003),(n-1-i)/(rate*.003)));phase+=2*Math.PI*(from+(to-from)*t)/rate;data[i]=(short)(32767*.025*env*Math.sin(phase));}
            android.media.AudioTrack track=new android.media.AudioTrack.Builder().setAudioAttributes(new android.media.AudioAttributes.Builder().setUsage(android.media.AudioAttributes.USAGE_ASSISTANCE_SONIFICATION).setContentType(android.media.AudioAttributes.CONTENT_TYPE_SONIFICATION).build()).setAudioFormat(new android.media.AudioFormat.Builder().setSampleRate(rate).setEncoding(android.media.AudioFormat.ENCODING_PCM_16BIT).setChannelMask(android.media.AudioFormat.CHANNEL_OUT_MONO).build()).setTransferMode(android.media.AudioTrack.MODE_STATIC).setBufferSizeInBytes(n*2).build();cueTrack=track;if(track.write(data,0,n)!=n){stopSound();return;}track.play();cueHandler.postDelayed(PopUi::stopSound,100);
        }catch(Exception ignored){stopSound();}
    }
    public static EditText input(Context c,String label,String value,int max){EditText e=new EditText(c);e.setText(value);e.setHint(label);e.setContentDescription(label);e.setTextSize(18);e.setTextColor(INK);e.setMinHeight(dp(c,56));e.setPadding(dp(c,13),dp(c,14),dp(c,13),dp(c,14));e.setFilters(new android.text.InputFilter[]{new android.text.InputFilter.LengthFilter(max)});e.setBackground(shape(c,0xFFFFFFFF,0xFF9EAFD6,10));return e;}
}
