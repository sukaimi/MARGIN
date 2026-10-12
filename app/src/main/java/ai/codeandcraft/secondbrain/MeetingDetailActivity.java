package ai.codeandcraft.secondbrain;

import android.app.Activity;
import android.media.AudioAttributes;
import android.media.MediaPlayer;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.widget.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.json.*;

public class MeetingDetailActivity extends Activity {
    private static final int PAPER=0xFFF5EFE0, INK=0xFF17233D, SECONDARY=0xFF4A5871, PINE=0xFF244ACB, RULE=0xFFC9BFA8;
    private LinearLayout content;
    private LinearLayout tasksPane;private ScrollView tasksScroll;
    LinearLayout transcriptPane,notesPane,optionsPane,briefPane;
    ScrollView transcriptScroll,notesScroll,optionsScroll,briefScroll;
    private Button bottomMeetings,bottomTasks;
    Button transcriptTab,notesTab,briefTab;private android.view.View briefLine;private android.view.View transcriptLine,notesLine;
    String selectedTab="Brief";boolean optionsOpen,excerptsOpen;
    private Button excerptsToggle;private Clip selectedClip;private long pausedPosition;
    private String restoreClipName;private int restoreTranscriptY,restoreNotesY,restoreOptionsY;
    private boolean restoredTranscript,restoredNotes,restoredOptions;
    private String stateKey(){return getIntent().getStringExtra("sessionId")+":"+getIntent().getStringExtra("qaMeetingId")+":";}

    private TextView meetingHeading, meetingMetadata;
    int transcriptPage;Button previousTranscriptPage,nextTranscriptPage;
    volatile String exportState="NOT_STARTED",exportError;volatile JSONObject exportResult;private TextView exportStatus;Button exportButton,saveBundleButton;private static final int SAVE_BUNDLE=410;
    private TextView playerStatus;
    private SeekBar seek;
    static final String MANUAL_FINISH = "manualFinishCue";
    private UiSoundGate.FinishMarker finishMarker;
    private final Object playbackOwner = new Object();
    private MediaPlayer player;
    private long briefSourceEndMs=-1;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private static final java.util.concurrent.CopyOnWriteArrayList<ExecutorService> detailWorkers = new java.util.concurrent.CopyOnWriteArrayList<>();
    private static ExecutorService newDetailWorker(){ExecutorService worker=Executors.newSingleThreadExecutor();detailWorkers.add(worker);return worker;}
    static boolean hasOpenWork(){detailWorkers.removeIf(ExecutorService::isTerminated);return !detailWorkers.isEmpty();}
    private final ExecutorService loader = newDetailWorker();
    private void submitWork(Runnable task){if(!deleting&&!AutoTranscription.cleanSlateRequested()&&!loader.isShutdown())loader.execute(task);}
    private boolean otherMeetingWork(){detailWorkers.removeIf(ExecutorService::isTerminated);for(ExecutorService worker:detailWorkers)if(worker!=loader)return true;return false;}
    private boolean visible,deleting;
    volatile boolean loaded;
    volatile String loadError;
    final ArrayList<Clip> clips = new ArrayList<>();
    int preparedDuration, observedSeek;
    static final class Clip {
        File audio; String name, transcript, truthError, sha;
        long duration, sessionStart, sourceStart, sourceEnd;
    }
    protected File sessionDirectory(String id) throws Exception {
        if (id == null || !UUID.fromString(id).toString().equals(id)) throw new IOException("Invalid meeting id");
        File root = new File(getFilesDir(), "sessions").getCanonicalFile();
        File result = new File(root, id).getCanonicalFile();
        if (!root.equals(result.getParentFile())) throw new IOException("Invalid meeting path");
        return result;
    }
    @Override public void onCreate(Bundle saved) {
        super.onCreate(saved);
        finishMarker = new UiSoundGate.FinishMarker(getIntent().getBooleanExtra(MANUAL_FINISH,false), saved != null, UiSoundEngine.INSTANCE.muteEpoch());
        getIntent().removeExtra(MANUAL_FINISH);
        if(AutoTranscription.cleanSlateRequested()){finish();return;}
        setTitle("JotterBook");
        getWindow().setStatusBarColor(PAPER); getWindow().setNavigationBarColor(PAPER);
        getWindow().getDecorView().setSystemUiVisibility(android.view.View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR | android.view.View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR);
        android.content.SharedPreferences prefs=getPreferences(MODE_PRIVATE);String key=stateKey();
        selectedTab=saved==null?"Brief":saved.getString("selectedTab","Brief");
        if(saved==null&&"Transcript".equals(getIntent().getStringExtra("popTab")))selectedTab="Transcript";
        if(saved==null&&"Tasks".equals(getIntent().getStringExtra("popTab")))selectedTab="Tasks";
        optionsOpen=saved!=null&&saved.getBoolean("optionsOpen");excerptsOpen=saved!=null&&saved.getBoolean("excerptsOpen");
        transcriptPage=saved==null?0:saved.getInt("transcriptPage");restoreTranscriptY=saved==null?prefs.getInt(key+"transcriptY",0):saved.getInt("transcriptY");restoreNotesY=saved==null?prefs.getInt(key+"notesY",0):saved.getInt("notesY");restoreOptionsY=saved==null?0:saved.getInt("optionsY");
        restoreClipName=saved==null?prefs.getString(key+"clip",null):saved.getString("clip");pausedPosition=saved==null?prefs.getLong(key+"position",0):saved.getLong("position");
        if(saved!=null&&saved.getString("preparedExport")!=null)try{exportResult=new JSONObject(saved.getString("preparedExport"));exportState="READY";}catch(Exception ignored){}
        android.widget.FrameLayout outer=new android.widget.FrameLayout(this);outer.setBackground(NotebookSurface.page(this));if(android.os.Build.VERSION.SDK_INT>=29)outer.setForceDarkAllowed(false);
        LinearLayout shell=column();int gutter=getResources().getConfiguration().screenWidthDp<=360?18:22;shell.setPadding(dp(gutter),dp(8),dp(gutter),0);
        outer.addView(shell,new android.widget.FrameLayout.LayoutParams(Math.min(dp(640),getResources().getDisplayMetrics().widthPixels),-1,android.view.Gravity.TOP|android.view.Gravity.CENTER_HORIZONTAL));setContentView(outer);
        ScrollView flowScroll=new ScrollView(this);flowScroll.setFillViewport(true);LinearLayout flow=column();flowScroll.addView(flow,new ScrollView.LayoutParams(-1,-2));shell.addView(flowScroll,new LinearLayout.LayoutParams(-1,0,1));content=column();flow.addView(content,new LinearLayout.LayoutParams(-1,-2));
        LinearLayout header=new LinearLayout(this);header.setGravity(android.view.Gravity.CENTER_VERTICAL);
        Button back=button("Meetings");back.setContentDescription("Back to meetings");back.setTextSize(16);back.setMinHeight(dp(48));header.addView(back,new LinearLayout.LayoutParams(0,-2,1));back.setOnClickListener(v->onBackPressed());
        TextView brand=text("JotterBook",18);brand.setSingleLine(true);brand.addOnLayoutChangeListener((v,l,t,r,bt,ol,ot,or,ob)->{if(brand.getPaint().measureText(brand.getText().toString())>r-l-brand.getPaddingLeft()-brand.getPaddingRight())brand.setVisibility(android.view.View.GONE);});brand.setTypeface(android.graphics.Typeface.SANS_SERIF,android.graphics.Typeface.BOLD);brand.setLetterSpacing(.12f);brand.setPadding(dp(4),0,dp(4),0);if(getResources().getConfiguration().fontScale<=1.5f)header.addView(brand,new LinearLayout.LayoutParams(0,-2,1));
        Button options=button("More");options.setTextSize(16);options.setMinHeight(dp(48));header.addView(options,new LinearLayout.LayoutParams(0,-2,2));options.setOnClickListener(v->{PopUi.feedback(v,"button");showOptions();});content.addView(header);
        meetingHeading=text("Meeting",38);meetingHeading.setTypeface(android.graphics.Typeface.SANS_SERIF,android.graphics.Typeface.BOLD);meetingHeading.setPadding(0,dp(8),0,dp(4));content.addView(meetingHeading);
        meetingMetadata=text("On this phone",16);meetingMetadata.setPadding(0,dp(4),0,dp(4));content.addView(meetingMetadata);TextView review=text("Notes need review · AI draft · Check before sharing",18);review.setBackground(controlShape(0xFFFFF0BC,0xFFFFF0BC,0));review.setTextColor(0xFF645019);content.addView(review);Button exportMeeting=PopUi.iconButton(this,MarginIconButton.Icon.EXPORT,"Export meeting",false);LinearLayout.LayoutParams exportParams=new LinearLayout.LayoutParams(dp(48),dp(48));exportParams.topMargin=dp(8);exportParams.bottomMargin=dp(8);exportMeeting.setLayoutParams(exportParams);exportMeeting.setOnClickListener(v->{PopUi.feedback(v,"button");showExportSheet();});content.addView(exportMeeting);
        LinearLayout playback=new LinearLayout(this);Button play=PopUi.iconButton(this,MarginIconButton.Icon.PLAY,"Play audio",true),pause=PopUi.iconButton(this,MarginIconButton.Icon.PAUSE,"Pause audio",false);playback.addView(play,new LinearLayout.LayoutParams(dp(48),dp(48)));LinearLayout.LayoutParams pauseParams=new LinearLayout.LayoutParams(dp(48),dp(48));pauseParams.leftMargin=dp(8);playback.addView(pause,pauseParams);content.addView(playback);
        play.setOnClickListener(v->{if(player!=null&&preparedDuration>0){PopUi.playbackStarted(playbackOwner);player.start();handler.post(progress);}else{Clip target=selectedClip;if(target==null&&!clips.isEmpty())target=clips.get(0);if(target!=null)playClip(target,false,pausedPosition<target.duration?pausedPosition:0);}});
        pause.setOnClickListener(v->{PopUi.stopSound();if(player!=null&&preparedDuration>0){player.pause();pausedPosition=player.getCurrentPosition();playerStatus.setText("Paused at "+clock(pausedPosition));}});
        seek=new SeekBar(this);seek.setContentDescription("Audio position");seek.setMinimumHeight(dp(48));seek.setLayoutParams(new LinearLayout.LayoutParams(-1,dp(48)));seek.setProgressTintList(android.content.res.ColorStateList.valueOf(PINE));seek.setThumbTintList(android.content.res.ColorStateList.valueOf(PINE));content.addView(seek);
        seek.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener(){public void onProgressChanged(SeekBar b,int position,boolean fromUser){}public void onStartTrackingTouch(SeekBar b){}public void onStopTrackingTouch(SeekBar b){if(player!=null&&preparedDuration>0)player.seekTo((long)b.getProgress(),MediaPlayer.SEEK_CLOSEST);}});
        playerStatus=text("Loading audio…",16);playerStatus.setPadding(0,0,0,dp(4));content.addView(playerStatus);
        LinearLayout tabs=new LinearLayout(this);tabs.setPadding(dp(4),dp(4),dp(4),dp(4));tabs.setBackground(NotebookSurface.cloth(this));
        briefTab=tab("Summary");notesTab=tab("Tasks");transcriptTab=tab("Transcript");briefLine=new android.view.View(this);notesLine=new android.view.View(this);transcriptLine=new android.view.View(this);
        for(Button t:new Button[]{briefTab,notesTab,transcriptTab})tabs.addView(t,new LinearLayout.LayoutParams(0,-2,1));flow.addView(tabs);
        briefTab.setOnClickListener(v->{PopUi.feedback(v,"tab");selectTab("Brief");});notesTab.setOnClickListener(v->{PopUi.feedback(v,"tab");selectTab("Tasks");});transcriptTab.setOnClickListener(v->{PopUi.feedback(v,"tab");selectTab("Transcript");});
        android.widget.FrameLayout body=new android.widget.FrameLayout(this);flow.addView(body,new LinearLayout.LayoutParams(-1,-2));transcriptPane=column();notesPane=column();optionsPane=column();briefPane=column();tasksPane=column();briefScroll=pane(body,briefPane);transcriptScroll=pane(body,transcriptPane);notesScroll=pane(body,notesPane);optionsScroll=pane(body,optionsPane);tasksScroll=pane(body,tasksPane);updatePanels();
        LinearLayout nav=new LinearLayout(this);nav.setBackground(NotebookSurface.cloth(this));for(String name:new String[]{"Meetings","Record","Tasks"}){Button n=NotebookNav.button(this,name,name,"Tasks".equals(name)?"Tasks".equals(selectedTab):"Meetings".equals(name)&&!"Tasks".equals(selectedTab));if("Meetings".equals(name))bottomMeetings=n;if("Tasks".equals(name))bottomTasks=n;n.setOnClickListener(v->{if("Tasks".equals(name)){PopUi.feedback(v,"tab");selectTab("Tasks");}else{android.content.Intent intent=new android.content.Intent(this,MainActivity.class);intent.putExtra("popPage",name);if("Record".equals(name))intent.putExtra(RecordEntryGuard.EXTRA_TOKEN,RecordEntryGuard.INSTANCE.issueFromDetailClick());intent.addFlags(android.content.Intent.FLAG_ACTIVITY_CLEAR_TOP);startActivity(intent);}});nav.addView(n,new LinearLayout.LayoutParams(0,-2,1));}shell.addView(nav);
        submitWork(() -> {
            try {
                File directory = sessionDirectory(getIntent().getStringExtra("sessionId"));
                meetingDirectory=directory;
                JSONObject session = readJson(new File(directory,"session.json"));
                JSONArray metadata = session.optJSONArray("chunks"); ArrayList<Clip> found = new ArrayList<>(); long offset=0;
                if(metadata!=null) for(int i=0;i<metadata.length();i++) {
                    JSONObject item=metadata.getJSONObject(i); String name=item.getString("filename");
                    if(!name.matches("chunk-[0-9]+\\.wav")) throw new IOException("Invalid audio filename");
                    Clip clip=new Clip(); clip.name=name; clip.audio=new File(directory,name).getCanonicalFile();
                    if(!directory.getCanonicalFile().equals(clip.audio.getParentFile())) throw new IOException("Invalid audio path");
                    clip.duration=item.getLong("durationMillis");
                    if(clip.duration<=0 || clip.duration>Integer.MAX_VALUE || offset>Long.MAX_VALUE-clip.duration) throw new IOException("Invalid audio duration");
                    clip.sessionStart=offset; offset+=clip.duration;
                    if(!clip.audio.isFile()) clip.truthError="Audio file is missing.";
                    else clip.sha=sha(clip.audio);
                    File truths=new File(directory,"transcripts"); File[] records=truths.listFiles();
                    if(records!=null) for(File record:records) {
                        if(!record.isDirectory() || !truths.getCanonicalFile().equals(record.getCanonicalFile().getParentFile())) continue;
                        try {
                            JSONObject truth=TranscriptTruthStore.load(record), source=truth.getJSONObject("source");
                            if(clip.sha!=null && clip.sha.equals(source.getString("sha256")) && source.getLong("durationMs")==clip.duration) {
                                clip.transcript=truth.getJSONObject("asr").getString("text");
                                clip.sourceStart=source.getLong("startMs");clip.sourceEnd=source.getLong("endMs");break;
                            }
                        } catch(Exception error) { clip.truthError="A transcript record could not be verified."; }
                    }
                    found.add(clip);
                }
                handler.post(() -> { if(isDestroyed()||deleting)return;clips.addAll(found); render(session);loaded=true; });
            } catch(Exception error) { handler.post(() -> { if(isDestroyed()||deleting)return;loadError=error.getMessage();playerStatus.setText("Meeting unavailable: "+loadError);Button retry=button("Reload meeting");retry.setOnClickListener(v->recreate());content.addView(retry);loaded=true; }); }
        });
    }
    private void render(JSONObject session) {
        meetingHeading.setText(session.optString("title","Local recording"));
        long duration=0;for(Clip clip:clips)duration+=clip.duration;
        long started=session.optLong("startUtc",0);String metadata=started>0?java.text.DateFormat.getDateTimeInstance(java.text.DateFormat.MEDIUM,java.text.DateFormat.SHORT).format(new java.util.Date(started))+" · ":"";
        if(duration>0)metadata+=duration/60000+":"+String.format(java.util.Locale.getDefault(),"%02d",duration/1000%60)+" audio · ";
        String status=session.optString("status","Unknown");metadata+="COMPLETED".equals(status)?"Audio saved":status.contains("RECOVER")||status.contains("INTERRUPT")?"Interrupted":status.contains("ERROR")?"Needs attention":"Recording session";meetingMetadata.setText(metadata);
        meetingHeading.setContentDescription(session.optString("title","Local recording"));selectedClip=findClip(restoreClipName);if(selectedClip==null&&!clips.isEmpty())selectedClip=clips.get(0);
        playerStatus.setText(clips.isEmpty()?"No saved audio.":pausedPosition>0?"Paused at "+clock(pausedPosition):"Ready to listen");
        optionsPane.addView(text("Meeting details",20));optionsPane.addView(text(session.optString("title","Local recording"),18));optionsPane.addView(text(metadata,16));
        optionsPane.addView(text("Audio parts",20));
        for(Clip clip:clips){Button part=button("Play part "+(clips.indexOf(clip)+1)+" · "+clock(clip.sessionStart)+"–"+clock(clip.sessionStart+clip.duration));part.setEnabled(clip.audio.isFile());part.setOnClickListener(v->playClip(clip,false,0));optionsPane.addView(part);
            disclosure(optionsPane,"Audio source details",text("Original saved audio · "+clock(clip.duration)+" · "+(clip.sha==null?"Unavailable":"Integrity checked"),18));
            if(clip.transcript!=null){transcriptPane.addView(text("Raw transcript",20));TextView raw=text(clip.transcript,18);raw.setTextIsSelectable(true);transcriptPane.addView(raw);Button source=button("Play transcript source");source.setOnClickListener(v->playClip(clip,false,clip.sourceStart));transcriptPane.addView(source);disclosure(transcriptPane,"Transcript source details",text("Source: full audio chunk "+clip.sourceStart+"–"+clip.sourceEnd+" ms. Word timings are unavailable.",16));}
            if(clip.truthError!=null)optionsPane.addView(text(clip.truthError,18));addManualSection(clip);
        }
        optionsPane.addView(text("Transcription information",20));optionsPane.addView(text("English audio. Source links identify audio windows; VAD boundaries are estimates. Word timestamps are unavailable. Raw recognition text and correction history remain saved. Experimental editable source-backed briefs are available in Brief. Automatic AI quality is not accepted: tested local models did not meet evidence-quality or completion checks. Endurance and word timings remain pending.",18));
        addSavedWorkSections();addExportSection();restorePanePosition();if(getIntent().getBooleanExtra("popExport",false)){getIntent().removeExtra("popExport");showExportSheet();}
    }
    volatile int manualSectionsLoaded;
    volatile String manualSaveError;
    private File meetingDirectory;
    final HashMap<String,LinearLayout> manualBoxes=new HashMap<>();

    private void addManualSection(Clip clip) {
        LinearLayout box=new LinearLayout(this);box.setOrientation(LinearLayout.VERTICAL);optionsPane.addView(box);manualBoxes.put(clip.name,box);
        if(clip.duration>120000 || !clip.audio.isFile()){box.addView(text("Manual labels need a verified local audio chunk of at most 120 seconds.",18));return;}
        refreshManualSection(clip,box);
    }
    private void refreshManualSection(Clip clip,LinearLayout box) {
        submitWork(()->{
            try {
                JSONObject overlay=ManualSpeakerCorrections.load(meetingDirectory);
                handler.post(()->{if(isDestroyed()||deleting)return;box.removeAllViews();box.addView(text("Manual speaker labels",20));
                    box.addView(text("Labels are supplied by you. They are not speaker recognition or word-aligned attribution.",16));
                    JSONArray heads=overlay.optJSONArray("effective"),history=overlay.optJSONArray("history");boolean any=false;
                    if(heads!=null)for(int i=0;i<heads.length();i++){
                        JSONObject event=heads.optJSONObject(i);if(event==null || !clip.name.equals(event.optString("chunkFilename")))continue;any=true;
                        boolean assigned="assignSpeaker".equals(event.optString("operation"));
                        box.addView(text((assigned?"Speaker label supplied by you: "+event.optString("displayLabel"):"Unknown / label cleared")+" · "+(event.optLong("startMs")==0&&event.optLong("endMs")==clip.duration?"full audio chunk ":"audio range ")+event.optLong("startMs")+"–"+event.optLong("endMs")+" ms",18));

                        Button revise=button("Revise this manual label");revise.setOnClickListener(v->manualDialog(clip,event,box));box.addView(revise);
                        if(assigned){Button clear=button("Clear this manual label");clear.setOnClickListener(v->saveManual(clip,event.optLong("startMs"),event.optLong("endMs"),null,event.optString("eventId"),event.optString("originalSpeakerId",null),box));box.addView(clear);}
                    }
                    if(!any)box.addView(text("Speaker unknown. No manual label has been supplied.",18));
                    Button add=button("Add a manual speaker label");add.setOnClickListener(v->manualDialog(clip,null,box));box.addView(add);
                    LinearLayout historyBox=column();disclosure(box,"Manual correction history",historyBox);
                    if(history!=null)for(int i=0;i<history.length();i++){
                        JSONObject event=history.optJSONObject(i);if(event==null || !clip.name.equals(event.optString("chunkFilename")))continue;
                        TextView audit=text(("assignSpeaker".equals(event.optString("operation"))?"Assigned: "+event.optString("displayLabel"):"Cleared")+" · "+event.optLong("startMs")+"–"+event.optLong("endMs")+" ms",16);audit.setTextIsSelectable(true);historyBox.addView(audit);
                    }
                    manualSectionsLoaded++;
                });
            }catch(Exception error){handler.post(()->{if(isDestroyed()||deleting)return;box.removeAllViews();box.addView(text("Manual labels unavailable: "+error.getMessage(),18));Button retry=button("Retry manual labels");retry.setOnClickListener(v->refreshManualSection(clip,box));box.addView(retry);manualSectionsLoaded++;});}
        });
    }
    private void manualDialog(Clip clip,JSONObject prior,LinearLayout box) {
        LinearLayout form=new LinearLayout(this);form.setOrientation(LinearLayout.VERTICAL);form.setPadding(dp(20),dp(8),dp(20),dp(8));
        form.addView(text("A label you supply for an exact audio range. No acoustic identity is inferred.",18));
        EditText label=new EditText(this);label.setTextSize(18);label.setMinHeight(dp(48));label.setContentDescription("Speaker label supplied by you");label.setHint("Your speaker label");if(prior!=null)label.setText(prior.optString("displayLabel",""));form.addView(label);
        form.addView(text("Start in milliseconds",16));EditText start=new EditText(this);start.setTextSize(18);start.setMinHeight(dp(48));start.setContentDescription("Audio range start in milliseconds");start.setInputType(android.text.InputType.TYPE_CLASS_NUMBER);start.setText(String.valueOf(prior==null?0:prior.optLong("startMs")));form.addView(start);
        form.addView(text("End in milliseconds",16));EditText end=new EditText(this);end.setTextSize(18);end.setMinHeight(dp(48));end.setContentDescription("Audio range end in milliseconds");end.setInputType(android.text.InputType.TYPE_CLASS_NUMBER);end.setText(String.valueOf(prior==null?clip.duration:prior.optLong("endMs")));form.addView(end);
        TextView error=text("",18);form.addView(error);
        android.app.AlertDialog dialog=new android.app.AlertDialog.Builder(this).setTitle("Manual speaker label").setView(wrapManualForm(form)).setNegativeButton("Cancel",null).setPositiveButton("Save user label",null).create();
        dialog.setOnShowListener(ignored->{dialog.getButton(android.app.AlertDialog.BUTTON_POSITIVE).setMinHeight(dp(48));dialog.getButton(android.app.AlertDialog.BUTTON_POSITIVE).setOnClickListener(v->{
            try{long from=Long.parseLong(start.getText().toString()),to=Long.parseLong(end.getText().toString());String display=label.getText().toString();
                if(display.trim().isEmpty() || display.codePointCount(0,display.length())>80)throw new IOException("Supply a label of 1–80 characters.");
                if(from<0||from>=to||to>clip.duration)throw new IOException("Range must stay within 0–"+clip.duration+" ms.");
                if(prior!=null && (from!=prior.optLong("startMs")||to!=prior.optLong("endMs")))throw new IOException("A revision must keep the original range. Add a separate label for a different range.");
                saveManual(clip,from,to,display,prior==null?null:prior.optString("eventId"),prior==null?null:prior.optString("originalSpeakerId",null),box);dialog.dismiss();
            }catch(Exception failure){error.setText(failure.getMessage());}
        });});dialog.show();
    }
    void saveManual(Clip clip,long start,long end,String label,String prior,String original,LinearLayout box) {
        manualSaveError=null;
        submitWork(()->{
            try{ManualSpeakerCorrections.append(meetingDirectory,UUID.randomUUID().toString(),clip.name,clip.sha,start,end,label,prior,original);
                handler.post(()->{if(!isDestroyed()&&!deleting)refreshManualSection(clip,box);});
            }catch(Exception error){manualSaveError=error.getMessage();handler.post(()->{if(!isDestroyed()&&!deleting)box.addView(text("Manual correction was not saved: "+error.getMessage(),18));});}
        });
    }
    private ScrollView wrapManualForm(LinearLayout form){ScrollView scroll=new ScrollView(this);scroll.addView(form);return scroll;}

    volatile String transcriptionState="LOADING", transcriptionError, noteSaveError;
    volatile JSONObject transcriptionResult;
    volatile int notesLoaded;
    private LinearLayout transcriptionBox, notesBox;
    Button transcribeButton, pauseTranscriptionButton;
    private final java.util.concurrent.atomic.AtomicBoolean pauseRequested=new java.util.concurrent.atomic.AtomicBoolean();

    private void addSavedWorkSections() {
        transcriptionBox=new LinearLayout(this);transcriptionBox.setOrientation(LinearLayout.VERTICAL);transcriptPane.addView(transcriptionBox);
        transcribeButton=primaryButton("Transcribe / resume locally");transcribeButton.setEnabled(false);transcribeButton.setOnClickListener(v->startTranscription(256));transcriptPane.addView(transcribeButton);
        pauseTranscriptionButton=button("Pause after current window");pauseTranscriptionButton.setEnabled(false);pauseTranscriptionButton.setOnClickListener(v->{try{AutoTranscription.pause(this,meetingDirectory.getName());lastQueueStatus="";refreshTranscription();}catch(Exception e){Toast.makeText(this,"Pause could not be saved",Toast.LENGTH_LONG).show();}});transcriptPane.addView(pauseTranscriptionButton);
        excerptBox=new LinearLayout(this);excerptBox.setOrientation(LinearLayout.VERTICAL);notesBox=new LinearLayout(this);notesBox.setOrientation(LinearLayout.VERTICAL);notesPane.addView(notesBox);
        excerptsToggle=button("Source excerpts · extractive");excerptsToggle.setContentDescription("Show exact transcript quotations, extractive, not a summary");notesPane.addView(excerptsToggle);notesPane.addView(excerptBox);excerptBox.setVisibility(excerptsOpen?android.view.View.VISIBLE:android.view.View.GONE);excerptsToggle.setOnClickListener(v->{excerptsOpen=!excerptsOpen;excerptBox.setVisibility(excerptsOpen?android.view.View.VISIBLE:android.view.View.GONE);excerptsToggle.setSelected(excerptsOpen);});
        refreshTranscription();refreshNotes();refreshBrief();
    }
    private boolean transcriptionRefreshBusy;
    private volatile String lastQueueStatus="";
    private final Runnable queuePoll=new Runnable(){public void run(){if(!visible)return;if(meetingDirectory!=null&&transcriptionBox!=null)refreshTranscription();handler.postDelayed(this,1000);}};
    private void refreshTranscription() {
        if(transcriptionRefreshBusy)return;transcriptionRefreshBusy=true;
        submitWork(()->{JSONObject result=null,queue=null;String failure=null;
            try{queue=AutoTranscription.status(this,meetingDirectory.getName());
                String state=queue==null?"":queue.optString("status");
                if(!state.isEmpty()&&state.equals(lastQueueStatus)){handler.post(()->transcriptionRefreshBusy=false);return;}
                // inspect shares the engine monitor: avoid blocking UI refresh behind decoding.
                if(!"RUNNING".equals(state)&&!"QUEUED".equals(state))result=WindowedTranscription.inspect(this,meetingDirectory);
            }catch(Exception e){failure=e.getMessage();}
            final JSONObject r=result,q=queue;final String e=failure;
            handler.post(()->{transcriptionRefreshBusy=false;if(isDestroyed()||deleting)return;
                String state=e!=null?"ERROR":q==null?(r==null?"NOT_STARTED":r.optString("status")):q.optString("status");
                if(state.equals(lastQueueStatus)&&q!=null)return;lastQueueStatus=state;
                transcriptionResult=r;transcriptionState=state;transcriptionError=e!=null?e:q==null?null:q.optString("error");
                renderTranscription(r);
                boolean running="RUNNING".equals(state)||"QUEUED".equals(state);
                transcribeButton.setVisibility(running||state.startsWith("COMPLETED")?8:0);
                transcribeButton.setText("ERROR".equals(state)?"Retry transcription":"PAUSED".equals(state)||"INTERRUPTED".equals(state)?"Resume transcription":"Transcribe locally");
                transcribeButton.setEnabled(!RecorderService.isRecording()&&!AutoTranscription.blocked());
                pauseTranscriptionButton.setVisibility(running?0:8);pauseTranscriptionButton.setEnabled(running);
                if(running){transcriptionBox.removeAllViews();transcriptionBox.addView(text("RUNNING".equals(state)?"Transcribing offline…":"Audio saved · queued for offline transcription…",20));ProgressBar progress=new ProgressBar(this);progress.setIndeterminate(true);transcriptionBox.addView(progress);}
                else if("ERROR".equals(state)||"INTERRUPTED".equals(state)||"PAUSED".equals(state))transcriptionBox.addView(text(state+" · "+(transcriptionError==null?"Accepted windows retained.":transcriptionError),18));
                else if(q!=null&&state.startsWith("COMPLETED")&&r==null)transcriptionBox.addView(text("Saved transcript unavailable for inspection. Original evidence retained.",18));
                renderBrief();restorePanePosition();
            });
        });
    }
    void startTranscription(int budget) {
        if(meetingDirectory==null||AutoTranscription.blocked())return;
        submitWork(()->{try{AutoTranscription.manual(this,meetingDirectory.getName());AutoTranscription.kick(this);handler.post(()->{lastQueueStatus="";refreshTranscription();});}catch(Exception e){handler.post(()->{transcriptionError=e.getMessage();transcriptionState="ERROR";renderTranscriptionError();});}});
    }
    private void renderTranscriptionError(){transcriptionBox.removeAllViews();transcriptionBox.addView(text("Local transcription unavailable: "+transcriptionError+". Accepted windows remain saved; retry after resolving the error.",18));}
    private void renderTranscription(JSONObject result) {
        transcribeButton.setVisibility(result!=null&&!result.optBoolean("historical")&&result.optString("status").startsWith("COMPLETED")?8:0);pauseTranscriptionButton.setVisibility(("RUNNING".equals(transcriptionState)||"PAUSING".equals(transcriptionState))?0:8);
        transcriptionBox.removeAllViews();transcriptionBox.addView(text("Local transcript",24));
        transcriptionBox.addView(text("English audio · source windows, not word timings",16));

        if(result==null){transcriptionBox.addView(text("No saved transcription job. Start when recording has finished.",18));return;}
        transcriptionBox.addView(text(result.optString("status")+" · "+result.optInt("completedWindows")+" / "+result.optInt("windowCount")+" windows",18));
        if(result.optBoolean("historical"))transcriptionBox.addView(text("Earlier saved transcription · read-only. Transcribing again creates a separate saved result; this transcription is kept.",16));
        if(result.optBoolean("boundaryReviewRequired"))transcriptionBox.addView(text("Overlapping speech needs boundary review. No merged transcript is presented.",18));
        JSONArray windows=result.optJSONArray("windows");if(windows==null)return;
        int pageCount=Math.max(1,(windows.length()+19)/20);transcriptPage=Math.min(Math.max(0,transcriptPage),pageCount-1);transcriptionBox.addView(text("Transcript page "+(transcriptPage+1)+" / "+pageCount+" · up to 20 windows per page",16));previousTranscriptPage=button("Previous transcript page");previousTranscriptPage.setEnabled(transcriptPage>0);previousTranscriptPage.setOnClickListener(v->{transcriptPage--;renderTranscription(result);});if(pageCount>1)transcriptionBox.addView(previousTranscriptPage);nextTranscriptPage=button("Next transcript page");nextTranscriptPage.setEnabled(transcriptPage+1<pageCount);nextTranscriptPage.setOnClickListener(v->{transcriptPage++;renderTranscription(result);});if(pageCount>1)transcriptionBox.addView(nextTranscriptPage);
        for(int i=transcriptPage*20;i<Math.min(windows.length(),(transcriptPage+1)*20);i++){
            JSONObject output=windows.optJSONObject(i),w=output.optJSONObject("window");if(w==null)continue;
            transcriptionBox.addView(text("Speaker unknown · Source audio "+clock(w.optLong("meetingStartSample")/16)+"–"+clock(w.optLong("meetingEndSample")/16),16));
            TextView raw=text(output.optBoolean("asrSkipped")?"No speech detected in this window.":output.optString("text"),18);raw.setTextIsSelectable(true);transcriptionBox.addView(raw);
            JSONArray parts=w.optJSONArray("sourceParts");if(parts==null)continue;
            for(int j=0;j<parts.length();j++){JSONObject part=parts.optJSONObject(j);Clip clip=findClip(part.optString("chunkFilename"));if(clip==null)continue;
                long from=part.optLong("startSample")/16,to=part.optLong("endSample")/16;
                disclosure(transcriptionBox,"Source details",text("Original saved audio · "+clock(from)+"–"+clock(to)+" · Integrity checked",16));
                Button play=button("Play source · "+clock(from));play.setOnClickListener(v->{playClip(clip,false,from);briefSourceEndMs=to;});transcriptionBox.addView(play);
            }
        }
    }
    private Clip findClip(String name){for(Clip clip:clips)if(clip.name.equals(name))return clip;return null;}
    private final java.util.concurrent.atomic.AtomicBoolean briefCancel=new java.util.concurrent.atomic.AtomicBoolean();
    private JSONArray briefVersions=new JSONArray();
    private JSONObject currentBrief;
    private boolean briefBusy,briefLoaded;
    private String briefNotice="Loading saved revisions…";

    private String briefParent(){return briefVersions.length()==0?null:briefVersions.optJSONObject(briefVersions.length()-1).optString("versionId");}
    private void refreshBrief(){
        briefBusy=true;renderBrief();
        submitWork(()->{long begin=android.os.SystemClock.elapsedRealtime();try{
            JSONArray versions=MeetingBriefStore.load(this,meetingDirectory);
            BriefDiagnostics.record(this,"LOAD",android.os.SystemClock.elapsedRealtime()-begin,"OK");
            handler.post(()->{if(isDestroyed()||deleting)return;briefVersions=versions;currentBrief=versions.length()==0?null:versions.optJSONObject(versions.length()-1);briefLoaded=true;briefBusy=false;briefNotice="";renderBrief();});
        }catch(Exception error){BriefDiagnostics.record(this,"LOAD",android.os.SystemClock.elapsedRealtime()-begin,"INVALID");handler.post(()->{if(isDestroyed()||deleting)return;briefLoaded=false;briefBusy=false;briefNotice="Saved brief could not be validated. Previous files are retained. Reload after resolving evidence or storage issues.";renderBrief();});}});
    }
    private void renderBrief(){
        if(briefPane==null)return;briefPane.removeAllViews();briefPane.addView(text("At a glance",26));briefPane.addView(text("Unverified draft · Check against the source before sharing. AI uses partial snippets and may be wrong.",18));if(!briefNotice.isEmpty())briefPane.addView(text(briefNotice,18));
        JSONArray items=currentBrief==null?new JSONArray():currentBrief.optJSONArray("items");boolean overview=false,decisions=false;
        for(int i=0;i<items.length();i++){JSONObject item=items.optJSONObject(i);if(!"summary".equals(item.optString("section")))continue;overview=true;summaryItem(briefPane,item);}
        if(!overview)briefPane.addView(text(briefLoaded?"No saved overview yet. Add your own notes or create a source draft below.":"Loading validated saved notes…",21));
        briefPane.addView(text("Decisions",24));for(int i=0;i<items.length();i++){JSONObject item=items.optJSONObject(i);if(!"decisions".equals(item.optString("section")))continue;decisions=true;summaryItem(briefPane,item);}if(!decisions)briefPane.addView(text("No saved decisions.",18));
        LinearLayout summary=briefPane,advanced=column();briefPane=advanced;renderBriefAdvanced();briefPane=summary;disclosure(summary,"Edit notes and draft tools",advanced);Button notes=button("Your source evidence notes");notes.setOnClickListener(v->selectTab("Notes"));summary.addView(notes);renderTasks();
    }
    private void summaryItem(LinearLayout pane,JSONObject item){TextView body=text(item.optString("text"),21);body.setTextIsSelectable(true);pane.addView(body);JSONArray cites=item.optJSONArray("citations");if(cites!=null)for(int i=0;i<cites.length();i++){JSONObject cite=cites.optJSONObject(i);Button source=button(sourceLabel(cite));source.setOnClickListener(v->showBriefSource(cite));pane.addView(source);}}
    private String sourceLabel(JSONObject cite){try{JSONObject part=cite.getJSONObject("anchor").getJSONObject("window").getJSONArray("sourceParts").getJSONObject(0);Clip clip=findClip(part.optString("chunkFilename"));long offset=clip==null?0:clip.sessionStart;return "Source · "+clock(offset+part.optLong("startSample")/16);}catch(Exception e){return "Source excerpt";}}
    private void showBriefHistory(){if(briefVersions.length()==0){Toast.makeText(this,"No saved draft history",Toast.LENGTH_SHORT).show();return;}String[] labels=new String[briefVersions.length()];for(int i=0;i<labels.length;i++)labels[i]="Version "+(i+1)+(i==labels.length-1?" · latest":" · read only");new android.app.AlertDialog.Builder(this).setTitle("Previous draft history").setItems(labels,(d,w)->{currentBrief=briefVersions.optJSONObject(w);briefNotice=w==briefVersions.length()-1?"":"Previous revision · read only";selectTab("Brief");renderBrief();}).setNegativeButton("Close",null).show();}
    private void renderTasks(){if(tasksPane==null)return;tasksPane.removeAllViews();JSONArray items=currentBrief==null?new JSONArray():currentBrief.optJSONArray("items");int open=0,total=0;for(int i=0;i<items.length();i++){JSONObject item=items.optJSONObject(i);if("actions".equals(item.optString("section"))){total++;if(!item.optBoolean("done"))open++;}}notesTab.setText("Tasks ("+open+")");tasksPane.addView(text("Your tasks",26));tasksPane.addView(text(briefLoaded?open+" open tasks":"Loading validated tasks…",21));if(!briefNotice.isEmpty())tasksPane.addView(text(briefNotice,18));boolean editable=briefLoaded&&!briefBusy&&currentBrief==briefVersions.optJSONObject(briefVersions.length()-1);
        for(int i=0;i<items.length();i++){JSONObject item=items.optJSONObject(i);if(!"actions".equals(item.optString("section")))continue;final int index=i;LinearLayout card=PopUi.card(this,0xFFFFFFFF);PopUi.add(tasksPane,card,12);CheckBox done=new CheckBox(this);done.setSoundEffectsEnabled(false);done.setText(item.optString("text"));done.setTextSize(20.3f);done.setTextColor(INK);done.setMinHeight(dp(48));done.setChecked(item.optBoolean("done"));done.setEnabled(editable);if(done.isChecked())done.setPaintFlags(done.getPaintFlags()|android.graphics.Paint.STRIKE_THRU_TEXT_FLAG);card.addView(done);done.setOnClickListener(v->{done.setChecked(item.optBoolean("done"));try{JSONObject draft=new JSONObject(currentBrief.toString());JSONObject changed=draft.getJSONArray("items").getJSONObject(index);changed.put("done",!item.optBoolean("done")).put("provenance","USER_ENTERED").put("ownerDateProvenance","USER_ENTERED_ONLY");draft.put("provenance","USER_ENTERED");saveBriefEdit(draft);}catch(Exception e){briefNotice="Task state could not be saved.";renderTasks();}});
            card.addView(text(item.optBoolean("done")?"Completed":"To do",18));if(!item.isNull("owner"))card.addView(text("Assigned to: "+item.optString("owner"),18));if(!item.isNull("date"))card.addView(text("Due: "+item.optString("date"),18));card.addView(text("Suggested from this meeting. Check the details before you act.",18));JSONArray cites=item.optJSONArray("citations");if(cites!=null)for(int j=0;j<cites.length();j++){JSONObject cite=cites.optJSONObject(j);Button source=button(sourceLabel(cite));source.setOnClickListener(v->showBriefSource(cite));card.addView(source);}Button edit=button("Edit");edit.setEnabled(editable);edit.setOnClickListener(v->inlineTaskEdit(card,index,item));card.addView(edit);
        }if(total==0)tasksPane.addView(text("No saved action items.",18));Button add=button("Add your task");add.setEnabled(briefLoaded&&!briefBusy&&(currentBrief==null||editable));add.setOnClickListener(v->briefEdit("actions",-1));tasksPane.addView(add);
    }
    private void inlineTaskEdit(LinearLayout card,int index,JSONObject item){card.removeAllViews();card.addView(text("Edit task",24));EditText value=PopUi.input(this,"Task",item.optString("text"),2000),owner=PopUi.input(this,"Assigned to",item.isNull("owner")?"":item.optString("owner"),120),date=PopUi.input(this,"Due",item.isNull("date")?"":item.optString("date"),120);value.setMinLines(3);PopUi.add(card,value,12);PopUi.add(card,owner,12);PopUi.add(card,date,12);Button cancel=button("Cancel"),save=primaryButton("Save changes");cancel.setOnClickListener(v->renderTasks());save.setOnClickListener(v->{if(value.getText().toString().trim().isEmpty()){value.setError("Enter task text");return;}try{JSONObject draft=new JSONObject(currentBrief.toString()),changed=draft.getJSONArray("items").getJSONObject(index);changed.put("text",value.getText().toString()).put("owner",owner.getText().toString().trim().isEmpty()?JSONObject.NULL:owner.getText().toString()).put("date",date.getText().toString().trim().isEmpty()?JSONObject.NULL:date.getText().toString()).put("provenance","USER_ENTERED").put("ownerDateProvenance","USER_ENTERED_ONLY");draft.put("provenance","USER_ENTERED");saveBriefEdit(draft);}catch(Exception e){value.setError("Save unavailable. Reload saved notes.");}});card.addView(cancel);card.addView(save);}

    private void renderBriefAdvanced(){
        if(briefPane==null)return;briefPane.removeAllViews();TextView heading=text("Draft tools",26);briefPane.addView(heading);
        briefPane.addView(text("EXPERIMENTAL · EDITABLE DRAFT",16));
        briefPane.addView(text("Drafts remain unverified. AI uses partial snippets; source drafts use selected quotations. Check the full transcript.",18));
        if(transcriptionResult!=null&&transcriptionResult.optBoolean("historical"))briefPane.addView(text("Using a saved older transcription job. New source drafts retain that job identity; they do not reuse current-job anchors.",16));
        if(!briefNotice.isEmpty())briefPane.addView(text(briefNotice,16));
        if(currentBrief!=null)briefPane.addView(text("Version "+currentBrief.optInt("sequence")+" · "+currentBrief.optString("engine")+" · "+currentBrief.optString("provenance")+" · unverified draft",16));
        if(currentBrief!=null&&SmolBriefEngine.ID.equals(currentBrief.optString("engine"))){
            briefPane.addView(text("EXPERIMENTAL AI · EDITABLE · UNVERIFIED",16));
            briefPane.addView(text(currentBrief.optString("warning","Partial transcript snippets only; does not cover the whole meeting. Check every item against the source."),18));
        }
        Button ai=primaryButton("Experimental AI draft");
        ai.setEnabled(briefLoaded&&!briefBusy&&!RecorderService.isRecording()&&"COMPLETED".equals(transcriptionState));
        ai.setOnClickListener(v->chooseBriefProvider(new SmolBriefEngine()));briefPane.addView(ai);
        Button generate=primaryButton(currentBrief==null?"Create source draft":"Create new source draft");
        generate.setEnabled(briefLoaded&&!briefBusy&&!RecorderService.isRecording()&&"COMPLETED".equals(transcriptionState));
        generate.setOnClickListener(v->chooseBriefProvider(new MeetingBriefEngine.SourceDraft()));briefPane.addView(generate);
        if(!"COMPLETED".equals(transcriptionState))briefPane.addView(text("Finish recording and complete a validated transcript before creating a source draft. Boundary review or incomplete evidence needs attention in Transcript.",16));
        if(briefBusy){Button cancel=button("Cancel brief operation");cancel.setOnClickListener(v->{synchronized(briefCancel){briefCancel.set(true);}cancel.setEnabled(false);});briefPane.addView(cancel);}
        JSONArray items=currentBrief==null?new JSONArray():currentBrief.optJSONArray("items");
        String[] sections={"summary","decisions","actions","questions","risks"};String[] labels={"Overview","Decisions","Actions","Open questions","Risks"};
        for(int section=0;section<sections.length;section++){
            final String key=sections[section];briefPane.addView(text(labels[section],22));boolean any=false;
            for(int i=0;i<items.length();i++){JSONObject item=items.optJSONObject(i);if(!key.equals(item.optString("section")))continue;any=true;final int index=i;
                LinearLayout card=column();card.setPadding(dp(16),dp(8),dp(16),dp(16));card.setBackground(NotebookSurface.card(this,0xFFFFFAEF,PopUi.YELLOW));LinearLayout.LayoutParams cp=new LinearLayout.LayoutParams(-1,-2);cp.bottomMargin=dp(12);briefPane.addView(card,cp);
                TextView body=text(item.optString("text"),18);body.setTextIsSelectable(true);card.addView(body);
                JSONArray citations=item.optJSONArray("citations");card.addView(text("USER_ENTERED".equals(item.optString("provenance"))?"User edited · unverified interpretation":"AI_DRAFT".equals(item.optString("provenance"))?"AI draft · unverified interpretation":"Exact quotation · selected evidence",16));
                if(citations.length()==0)card.addView(text("Not enough evidence · manual unsupported item",16));
                if(!item.isNull("owner"))card.addView(text("Owner (you entered): "+item.optString("owner"),16));
                if(!item.isNull("date"))card.addView(text("Date (you entered): "+item.optString("date"),16));
                if("actions".equals(key))card.addView(text(item.optBoolean("done")?"Done · user state":"Pending · user state",16));
                for(int j=0;j<citations.length();j++){final JSONObject citation=citations.optJSONObject(j);Button source=button("View quotation & audio window");source.setOnClickListener(v->showBriefSource(citation));card.addView(source);}
                Button edit=button("Edit "+labels[section].toLowerCase(Locale.ROOT));edit.setEnabled(briefLoaded&&!briefBusy&&currentBrief==briefVersions.optJSONObject(briefVersions.length()-1));edit.setOnClickListener(v->briefEdit(key,index));card.addView(edit);
            }
            if(!any)briefPane.addView(text("Insufficient evidence. Add an item in your own words.",18));
            Button add=button("Add "+labels[section].toLowerCase(Locale.ROOT)+" item");add.setEnabled(briefLoaded&&!briefBusy&&(currentBrief==null||currentBrief==briefVersions.optJSONObject(briefVersions.length()-1)));add.setOnClickListener(v->briefEdit(key,-1));briefPane.addView(add);
        }
        if(briefVersions.length()>0){Button history=button("Version history · "+briefVersions.length());history.setEnabled(!briefBusy);history.setOnClickListener(v->{String[] historyLabels=new String[briefVersions.length()];for(int i=0;i<historyLabels.length;i++)historyLabels[i]="Version "+(i+1)+(i==historyLabels.length-1?" · latest":" · read only");new android.app.AlertDialog.Builder(this).setTitle("Saved draft revisions").setItems(historyLabels,(d,w)->{currentBrief=briefVersions.optJSONObject(w);briefNotice=w==briefVersions.length()-1?"":"Older revision · return to latest to edit. Source links retain their original job identity.";renderBrief();}).show();});briefPane.addView(history);}
        Button reload=button("Reload saved brief");reload.setEnabled(!briefBusy);reload.setOnClickListener(v->refreshBrief());briefPane.addView(reload);
    }
    private void chooseBriefProvider(final MeetingBriefEngine provider){
        if(briefBusy)return;
        if(currentBrief==null){generateBrief(provider);return;}
        new android.app.AlertDialog.Builder(this).setTitle("Create another draft?")
            .setMessage("A new revision starts from source. Your previous edits remain in version history.")
            .setNegativeButton("Keep editing",null).setPositiveButton("Create draft",(d,w)->generateBrief(provider)).show();
    }
    private static String briefFailure(String status){
        switch(status){
            case "MODEL_UNAVAILABLE":return "AI model or runner unavailable.";
            case "INVALID_MODEL_OUTPUT":return "AI output did not meet the draft format.";
            case "INSUFFICIENT_MEMORY":case "MEMORY_UNKNOWN":return "AI cannot run with current memory resources.";
            case "THERMAL_THROTTLED":return "AI stopped because the device is too warm.";
            case "TIMEOUT":return "AI exceeded the three-minute runtime limit.";
            case "MODEL_RUN_FAILED":return "AI runner could not complete.";
            default:return "Draft unavailable. Check completed evidence in Transcript or reload saved revisions.";
        }
    }
    private void generateBrief(final MeetingBriefEngine provider){
        if(briefBusy||!briefLoaded||RecorderService.isRecording()||!"COMPLETED".equals(transcriptionState))return;
        briefBusy=true;synchronized(briefCancel){briefCancel.set(false);}final String parent=briefParent();
        final boolean ai=SmolBriefEngine.ID.equals(provider.id());
        briefNotice=ai?"Preparing experimental AI draft locally… May take up to 3 minutes. Partial snippets only; previous revisions retained.":"Preparing source draft locally… Previous revisions retained.";renderBrief();
        submitWork(()->{long begin=android.os.SystemClock.elapsedRealtime();String status="FAILED";try{
            JSONObject draft=MeetingBriefEngine.generate(provider,this,meetingDirectory,briefCancel);
            status=draft.optString("status");
            if(!"DRAFT".equals(status))throw new IOException("UNAVAILABLE");
            MeetingBriefStore.append(this,meetingDirectory,draft,parent,briefCancel);
            BriefDiagnostics.record(this,"GENERATE",android.os.SystemClock.elapsedRealtime()-begin,"OK",provider.id());
            handler.post(()->{if(isDestroyed()||deleting)return;refreshBrief();});
        }catch(Exception error){
            final boolean cancelled=briefCancel.get()||"BRIEF_CANCELLED".equals(error.getMessage())||"CANCELLED".equals(status);
            // Match fixed codes only; never display exception contents.
            if("BRIEF_UNAVAILABLE".equals(error.getMessage()))status="RECORDING";
            final String failure=cancelled?"Cancelled. Previous revisions retained.":"RECORDING".equals(status)?"Recording blocks draft generation. Previous revisions retained.":briefFailure(status)+" Previous revisions retained."+(ai?" Use Create source draft as a fallback.":"");
            BriefDiagnostics.record(this,"GENERATE",android.os.SystemClock.elapsedRealtime()-begin,cancelled?"CANCELLED":"UNAVAILABLE",provider.id());
            handler.post(()->{if(isDestroyed()||deleting)return;briefBusy=false;briefNotice=failure;renderBrief();});}});
    }
    private void briefEdit(String section,int index){
        try{
            JSONObject draft=currentBrief==null?new JSONObject().put("schemaVersion",1).put("engine","manual-v1").put("provenance","USER_ENTERED").put("status","DRAFT").put("claimsVerified",false).put("items",new JSONArray()):new JSONObject(currentBrief.toString());
            JSONObject original=index<0?null:draft.getJSONArray("items").getJSONObject(index);
            LinearLayout form=column();form.setPadding(dp(16),0,dp(16),dp(16));form.addView(text("Your interpretation remains an unverified draft. Owner and date are entered only by you.",16));
            EditText value=new EditText(this);value.setHint("Write your item");value.setText(original==null?"":original.optString("text"));value.setMinLines(3);value.setMaxLines(8);value.setFilters(new android.text.InputFilter[]{new android.text.InputFilter.LengthFilter(2000)});form.addView(value);
            EditText owner=new EditText(this),date=new EditText(this);owner.setHint("Owner · optional, entered by you");date.setHint("Date · optional, entered by you");
            for(EditText field:new EditText[]{owner,date}){field.setMinHeight(dp(48));field.setFilters(new android.text.InputFilter[]{new android.text.InputFilter.LengthFilter(120)});form.addView(field);}
            if(original!=null){owner.setText(original.isNull("owner")?"":original.optString("owner"));date.setText(original.isNull("date")?"":original.optString("date"));}
            CheckBox done=new CheckBox(this);done.setText("Done · my action state");done.setMinHeight(dp(48));done.setChecked(original!=null&&original.optBoolean("done"));if("actions".equals(section))form.addView(done);
            ArrayList<JSONObject> choices=new ArrayList<>();JSONArray existing=original==null?new JSONArray():original.getJSONArray("citations");
            JSONArray excerptChoices=excerptResult==null?null:excerptResult.optJSONArray("excerpts");if(excerptChoices!=null)for(int i=0;i<excerptChoices.length();i++)choices.add(excerptChoices.getJSONObject(i));
            ArrayList<String> labels=new ArrayList<>();labels.add("No source · manual / not enough evidence");if(existing.length()>0)labels.add("Keep existing source quotation(s)");for(JSONObject q:choices){String quote=q.getString("quote");labels.add(quote.length()>90?quote.substring(0,90)+"…":quote);}
            form.addView(text("Select an exact saved source snippet",16));Spinner source=new Spinner(this);source.setMinimumHeight(dp(48));source.setAdapter(new ArrayAdapter<String>(this,android.R.layout.simple_spinner_dropdown_item,labels));source.setSelection(existing.length()>0?1:0);form.addView(source);
            TextView error=text("",16);form.addView(error);
            android.app.AlertDialog dialog=new android.app.AlertDialog.Builder(this).setTitle(index<0?"Add draft item":"Edit draft item").setView(wrapManualForm(form)).setNegativeButton("Cancel",null).setPositiveButton("Save revision",null).create();
            dialog.setOnShowListener(d->dialog.getButton(android.app.AlertDialog.BUTTON_POSITIVE).setOnClickListener(v->{
                if(value.getText().toString().trim().isEmpty()){error.setText("Enter item text.");return;}
                try{
                    JSONArray citations=new JSONArray();int selection=source.getSelectedItemPosition();if(existing.length()>0&&selection==1)citations=new JSONArray(existing.toString());else if(selection>0){int choice=selection-(existing.length()>0?2:1);citations.put(new JSONObject(choices.get(choice).toString()));}
                    JSONObject item=new JSONObject().put("section",section).put("text",value.getText().toString()).put("provenance","USER_ENTERED").put("citations",citations).put("evidenceStatus",citations.length()==0?"MANUAL_UNSUPPORTED":"SOURCE_LINKED_UNVERIFIED").put("owner",owner.getText().toString().trim().isEmpty()?JSONObject.NULL:owner.getText().toString()).put("date",date.getText().toString().trim().isEmpty()?JSONObject.NULL:date.getText().toString()).put("ownerDateProvenance","USER_ENTERED_ONLY").put("done",done.isChecked());
                    if(index<0)draft.getJSONArray("items").put(item);else draft.getJSONArray("items").put(index,item);draft.put("provenance","USER_ENTERED");saveBriefEdit(draft);dialog.dismiss();
                }catch(Exception invalid){error.setText("Cannot save this selection. Reload evidence and try again.");}
            }));dialog.show();
        }catch(Exception error){briefNotice="Editing unavailable. Reload the saved brief.";renderBrief();}
    }
    private void saveBriefEdit(JSONObject draft){
        if(briefBusy)return;final long soundGeneration=PopUi.soundGeneration();String parent=briefParent();briefBusy=true;synchronized(briefCancel){briefCancel.set(false);}briefNotice="Saving a new revision…";renderBrief();
        submitWork(()->{long begin=android.os.SystemClock.elapsedRealtime();try{MeetingBriefStore.append(this,meetingDirectory,draft,parent,briefCancel);BriefDiagnostics.record(this,"EDIT",android.os.SystemClock.elapsedRealtime()-begin,"OK");handler.post(()->{if(isDestroyed()||deleting)return;if(visible&&PopUi.soundCurrent(soundGeneration))PopUi.feedback(briefTab,"save");Toast.makeText(this,"Changes saved as a new revision",Toast.LENGTH_SHORT).show();refreshBrief();});}catch(Exception error){BriefDiagnostics.record(this,"EDIT",android.os.SystemClock.elapsedRealtime()-begin,briefCancel.get()?"CANCELLED":"INVALID");handler.post(()->{if(isDestroyed()||deleting)return;briefBusy=false;briefNotice="Save could not be confirmed. Previous revisions retained. Reload and check evidence before retrying.";renderBrief();});}});
    }
    private void showBriefSource(JSONObject citation){
        submitWork(()->{try{MeetingBriefStore.load(this,meetingDirectory);JSONObject sourceJob=WindowedTranscription.inspectJob(this,meetingDirectory,citation.getJSONObject("anchor").getString("jobId"));JSONArray parts=citation.getJSONObject("anchor").getJSONObject("window").getJSONArray("sourceParts");handler.post(()->{if(isDestroyed()||deleting)return;LinearLayout box=column();box.setPadding(dp(24),0,dp(24),dp(24));box.addView(text(sourceJob.optBoolean("historical")?"Earlier saved transcript · Speaker unknown":"Saved transcript · Speaker unknown",18));TextView quote=text(citation.optString("quote"),21);quote.setTextIsSelectable(true);quote.setBackground(controlShape(0xFFE9EDFF,0xFF244ACB,1));box.addView(quote);box.addView(text("Audio links cover saved source windows. Word timings and claims remain unverified.",18));android.app.AlertDialog dialog=new android.app.AlertDialog.Builder(this).setTitle("Source excerpt").setView(wrapManualForm(box)).setPositiveButton("Close",null).create();for(int i=0;i<parts.length();i++){JSONObject part=parts.optJSONObject(i);Clip clip=findClip(part.optString("chunkFilename"));long base=clip==null?0:clip.sessionStart;Button play=button("Play from "+clock(base+part.optLong("startSample")/16)+" · to "+clock(base+part.optLong("endSample")/16));play.setOnClickListener(v->{playExcerptSource(part,false);briefSourceEndMs=part.optLong("endSample")/16;dialog.dismiss();});box.addView(play);}dialog.show();});}catch(Exception error){handler.post(()->{if(!isDestroyed()&&!deleting){briefNotice="Source no longer validates. Reload evidence before playback.";renderBrief();}});}});
    }

    volatile String excerptState="LOADING";
    volatile JSONObject excerptResult;
    private LinearLayout excerptBox;
    private final java.util.concurrent.atomic.AtomicBoolean excerptCancel=new java.util.concurrent.atomic.AtomicBoolean();
    final ArrayList<Button> excerptSourceButtons=new ArrayList<>();
    void refreshExcerpts(){if("RUNNING".equals(excerptState))return;excerptCancel.set(false);excerptState="RUNNING";excerptBox.removeAllViews();excerptBox.addView(text("Source excerpts · extractive",24));excerptBox.addView(text("Checking immutable transcript evidence locally…",18));Button cancel=button("Cancel excerpt refresh");cancel.setOnClickListener(v->{excerptCancel.set(true);cancel.setEnabled(false);excerptBox.addView(text("Cancellation requested; finishing the current bounded evidence check.",18));});excerptBox.addView(cancel);
        submitWork(()->{try{JSONObject result=EvidenceExcerptBaseline.build(this,meetingDirectory,8,5000,excerptCancel);handler.post(()->{if(isDestroyed()||deleting)return;excerptResult=result;excerptState=result.optString("status");renderExcerpts(result);});}catch(Exception error){handler.post(()->{if(isDestroyed()||deleting)return;excerptState="ERROR";excerptBox.removeAllViews();excerptBox.addView(text("Source excerpts unavailable: "+error.getMessage(),18));Button retry=button("Retry source excerpts");retry.setOnClickListener(v->refreshExcerpts());excerptBox.addView(retry);});}});
    }
    private void renderExcerpts(JSONObject result){excerptBox.removeAllViews();excerptSourceButtons.clear();excerptBox.addView(text("Source excerpts · extractive",24));excerptBox.addView(text("Exact transcript quotations ranked locally. These are not an abstractive summary, verified decisions or action items. Owners, dates and contradictions have not been evaluated.",18));excerptBox.addView(text("Saved evidence quotations. Audio links cover source windows; individual word timings are unavailable.",16));
        excerptBox.addView(text(result.optString("status")+" · "+result.optString("detail"),18));
        if("READY_EXTRACTIVE".equals(result.optString("status"))){excerptBox.addView(text("Coverage: "+result.optString("coverage")+" · "+result.optInt("omittedSentenceCount")+" sentences omitted from the excerpts. The full raw transcript remains available above.",16));JSONArray excerpts=result.optJSONArray("excerpts");if(excerpts!=null)for(int i=0;i<excerpts.length();i++){JSONObject item=excerpts.optJSONObject(i),anchor=item.optJSONObject("anchor");TextView quote=text(item.optString("quote"),18);quote.setTextIsSelectable(true);excerptBox.addView(quote);excerptBox.addView(text("Saved transcript quotation · unverified interpretation",16));JSONArray parts=anchor.optJSONObject("window").optJSONArray("sourceParts");if(parts!=null)for(int j=0;j<parts.length();j++){JSONObject part=parts.optJSONObject(j);long from=part.optLong("startSample")/16,to=part.optLong("endSample")/16;excerptBox.addView(text("Source audio · "+clock(from)+"–"+clock(to),16));Button link=button("Play from "+clock(from));link.setOnClickListener(v->{playExcerptSource(part,false);briefSourceEndMs=part.optLong("endSample")/16;});excerptSourceButtons.add(link);excerptBox.addView(link);}}}
        Button refresh=button("Refresh source excerpts locally");refresh.setOnClickListener(v->refreshExcerpts());excerptBox.addView(refresh);
    }
    void playExcerptSource(JSONObject part,boolean muted){Clip clip=findClip(part.optString("chunkFilename"));if(clip==null||clip.sha==null||!clip.sha.equals(part.optString("parentSourceSha256"))){playerStatus.setText("Excerpt source could not be verified. Refresh this meeting before playback.");return;}playClip(clip,muted,part.optLong("startSample")/16);}

    private void refreshNotes(){submitWork(()->{try{JSONArray notes=EvidenceNoteStore.load(meetingDirectory);handler.post(()->{if(isDestroyed()||deleting)return;notesBox.removeAllViews();notesBox.addView(text("Your notes",24));notesBox.addView(text("User-authored notes. Audio references do not verify your claims or align individual words.",16));
                if(notes.length()==0)notesBox.addView(text("No user notes yet.",18));
                for(int i=0;i<notes.length();i++){JSONObject note=notes.optJSONObject(i),source=note.optJSONObject("source");TextView body=text("User-authored: "+note.optString("text"),18);body.setTextIsSelectable(true);notesBox.addView(body);notesBox.addView(text("Source audio · "+clock(source.optLong("startMs"))+"–"+clock(source.optLong("endMs")),16));Clip clip=findClip(source.optString("chunkFilename"));if(clip!=null){Button play=button("Play note source from "+clock(source.optLong("startMs")));play.setOnClickListener(v->playClip(clip,false,source.optLong("startMs")));notesBox.addView(play);}}
                for(Clip clip:clips){Button add=button("Add your source note · part "+(clips.indexOf(clip)+1));add.setEnabled(clip.sha!=null);add.setOnClickListener(v->noteDialog(clip));notesBox.addView(add);}notesLoaded++;refreshExcerpts();restorePanePosition();
            });}catch(Exception error){handler.post(()->{if(isDestroyed()||deleting)return;notesBox.removeAllViews();notesBox.addView(text("User notes unavailable: "+error.getMessage(),18));Button retry=button("Retry user notes");retry.setOnClickListener(v->refreshNotes());notesBox.addView(retry);notesLoaded++;});}});}
    private void noteDialog(Clip clip){LinearLayout form=new LinearLayout(this);form.setOrientation(LinearLayout.VERTICAL);form.setPadding(dp(20),dp(8),dp(20),dp(8));form.addView(text("Your own note linked to an exact audio range in "+clip.name+". Automatic outputs are not generated.",18));
        EditText body=new EditText(this);body.setTextSize(18);body.setMinHeight(dp(96));body.setHint("Your note");body.setContentDescription("User-authored note");form.addView(body);
        EditText from=rangeInput(form,"Start in milliseconds",0),to=rangeInput(form,"End in milliseconds",clip.duration);TextView error=text("",18);form.addView(error);
        android.app.AlertDialog dialog=new android.app.AlertDialog.Builder(this).setTitle("Add your note").setView(wrapManualForm(form)).setNegativeButton("Cancel",null).setPositiveButton("Save user note",null).create();dialog.setOnShowListener(ignored->{dialog.getButton(-1).setMinHeight(dp(48));dialog.getButton(-1).setOnClickListener(v->{try{long a=Long.parseLong(from.getText().toString()),b=Long.parseLong(to.getText().toString());String value=body.getText().toString();if(value.trim().isEmpty()||value.codePointCount(0,value.length())>2000)throw new IOException("Supply a note of 1–2000 characters.");if(a<0||a>=b||b>clip.duration)throw new IOException("Range must stay within 0–"+clip.duration+" ms.");saveNote(clip,a,b,value);dialog.dismiss();}catch(Exception failure){error.setText(failure.getMessage());}});});dialog.show();
    }
    private EditText rangeInput(LinearLayout form,String title,long value){form.addView(text(title,16));EditText input=new EditText(this);input.setTextSize(18);input.setMinHeight(dp(48));input.setInputType(android.text.InputType.TYPE_CLASS_NUMBER);input.setContentDescription(title);input.setText(String.valueOf(value));form.addView(input);return input;}
    void saveNote(Clip clip,long start,long end,String value){noteSaveError=null;submitWork(()->{try{EvidenceNoteStore.append(meetingDirectory,UUID.randomUUID().toString(),clip.name,clip.sha,start,end,value,null,null);handler.post(()->{if(!isDestroyed()&&!deleting)refreshNotes();});}catch(Exception error){noteSaveError=error.getMessage();handler.post(()->{if(!isDestroyed()&&!deleting)notesBox.addView(text("Your note was not saved: "+error.getMessage(),18));});}});}


    private void addExportSection(){exportStatus=text("",18);optionsPane.addView(exportStatus);exportButton=PopUi.iconButton(this,MarginIconButton.Icon.EXPORT,"Export meeting",false);exportButton.setLayoutParams(new LinearLayout.LayoutParams(dp(48),dp(48)));exportButton.setOnClickListener(v->showExportSheet());optionsPane.addView(exportButton);saveBundleButton=button("Export meeting");saveBundleButton.setVisibility(android.view.View.GONE);optionsPane.addView(saveBundleButton);}
    private void showExportSheet(){if(meetingDirectory==null){Toast.makeText(this,"Meeting is still loading",Toast.LENGTH_SHORT).show();return;}final String[] kinds={"notes","transcript","audio","everything"};final String[] labels={"Notes\nSummary and action items · Text file","Transcript\nThe conversation, with timestamps · Text file","Audio\nOriginal recording · Audio file","Everything\nNotes, transcript and audio in one ZIP file"};final int[] choice={0};android.app.AlertDialog dialog=new android.app.AlertDialog.Builder(this).setTitle("Export meeting").setSingleChoiceItems(labels,0,(d,w)->{choice[0]=w;((android.app.AlertDialog)d).getButton(-1).setText("Export "+kinds[w]);}).setNegativeButton("Cancel",null).setPositiveButton("Export notes",(d,w)->prepareExport(kinds[choice[0]])).create();dialog.show();}
    private void prepareExport(String kind){if("RUNNING".equals(exportState))return;exportState="RUNNING";exportStatus.setText("Preparing "+kind+" for local export…");Toast.makeText(this,"Preparing local export…",Toast.LENGTH_SHORT).show();submitWork(()->{try{JSONObject result=MeetingExport.prepare(this,meetingDirectory,kind,new java.util.concurrent.atomic.AtomicBoolean());handler.post(()->{if(isDestroyed()||deleting)return;exportResult=result;exportState="READY";try{android.content.Intent intent=new android.content.Intent(android.content.Intent.ACTION_CREATE_DOCUMENT);intent.addCategory(android.content.Intent.CATEGORY_OPENABLE);intent.setType(result.getString("mimeType"));intent.putExtra(android.content.Intent.EXTRA_TITLE,result.getString("displayName"));startActivityForResult(intent,SAVE_BUNDLE);}catch(Exception e){exportStatus.setText("Local save could not open.");Toast.makeText(this,"Local save could not open",Toast.LENGTH_LONG).show();}});}catch(Exception e){handler.post(()->{if(isDestroyed()||deleting)return;exportState="ERROR";exportStatus.setText("Export unavailable. Saved source could not be prepared.");Toast.makeText(this,"Export unavailable. Saved source could not be prepared.",Toast.LENGTH_LONG).show();});}});}
    void createSourceBundle(){if("RUNNING".equals(exportState))return;exportState="RUNNING";exportError=null;exportButton.setEnabled(false);saveBundleButton.setEnabled(false);exportStatus.setText("Checking evidence and preparing a local bundle…");submitWork(()->{try{JSONObject result=SourceBundle.create(this,meetingDirectory,null);handler.post(()->{if(isDestroyed()||deleting)return;exportResult=result;exportState="READY";exportButton.setEnabled(true);saveBundleButton.setEnabled(true);exportStatus.setText("Source bundle prepared · "+result.optInt("entryCount")+" evidence files · "+result.optLong("bytes")+" bytes. Original evidence retained.");});}catch(Exception error){handler.post(()->{if(isDestroyed()||deleting)return;exportState="ERROR";exportError=error.getMessage();exportButton.setEnabled(true);exportStatus.setText("Source bundle unavailable: "+exportError+". Resolve the error and retry; original evidence retained.");});}});}
    private boolean cleanupPreparedExport(JSONObject prepared){
        if(prepared==null)return true;
        try{
            if(!Arrays.asList("notes","transcript","audio","everything").contains(prepared.optString("kind")))return false;
            File exports=new File(getFilesDir().getCanonicalFile(),"exports").getAbsoluteFile(),file=new File(prepared.getString("file")).getAbsoluteFile();
            String name=file.getName(),owner=SourceBundle.meetingId(meetingDirectory);
            if(!exports.equals(exports.getCanonicalFile())||!file.equals(file.getCanonicalFile())||!exports.equals(file.getParentFile())||!name.matches("[a-f0-9-]{36}--[a-f0-9-]{36}\\.(txt|wav|zip)")||!name.startsWith(owner+"--")||!UUID.fromString(name.substring(38,74)).toString().equals(name.substring(38,74))||!file.isFile()||file.length()!=prepared.getLong("bytes")||!prepared.getString("sha256").equals(SourceBundle.sha(file)))return false;
            if(!file.delete())return false;
            return true;
        }catch(Exception error){return false;}
    }
    @Override protected void onActivityResult(int request,int result,android.content.Intent data){super.onActivityResult(request,result,data);if(isFinishing()||AutoTranscription.cleanSlateRequested())return;if(request!=SAVE_BUNDLE)return;if(result==RESULT_CANCELED){boolean cleaned=cleanupPreparedExport(exportResult);if(cleaned){exportResult=null;exportState="NOT_STARTED";}exportStatus.setText(cleaned?"Local export cancelled.":"Local export cancelled. Prepared file remains available.");return;}if(result!=RESULT_OK||data==null){exportStatus.setText("Local save unavailable. Prepared file remains available.");return;}android.net.Uri uri=data.getData();if(!SourceBundle.localDocument(uri)){exportStatus.setText("Choose on-device Files or Downloads. Cloud and unknown providers are not supported; no audio was sent.");return;}if(exportResult==null){exportStatus.setText("Choose Export meeting to prepare a file.");return;}JSONObject prepared=exportResult;saveBundleButton.setEnabled(false);submitWork(()->{try(OutputStream output=getContentResolver().openOutputStream(uri,"wt")){if(output==null)throw new IOException("No local output stream");SourceBundle.copyVerified(new File(prepared.getString("file")),prepared.getString("sha256"),output);}catch(Exception error){handler.post(()->{if(!isDestroyed()&&!deleting){exportStatus.setText("Local save failed: "+error.getMessage()+". Destination may be partial; prepared bundle and original evidence remain available.");saveBundleButton.setEnabled(true);}});return;}final boolean cleaned=cleanupPreparedExport(prepared);handler.post(()->{if(cleaned&&exportResult==prepared){exportResult=null;exportState="NOT_STARTED";}if(!isDestroyed()&&!deleting){exportStatus.setText(cleaned?"Export saved to your chosen on-device document.":"Export saved locally. Prepared file remains available.");Toast.makeText(this,"Export saved locally",Toast.LENGTH_LONG).show();saveBundleButton.setEnabled(true);}});});}

    void playClip(Clip clip,boolean muted,long target) {
        closePlayer();selectedClip=clip;pausedPosition=target;
        if(!visible){PopUi.playbackClosed(playbackOwner);playerStatus.setText("Playback is available while this meeting is open.");return;}
        if(target<0 || target>=clip.duration){PopUi.playbackClosed(playbackOwner);playerStatus.setText("Source range unavailable.");return;}
        try {
            if(!clip.audio.isFile())throw new IOException("Audio file is missing");
            if(clip.sha==null || !clip.sha.equals(sha(clip.audio)))throw new IOException("Audio changed since this meeting was opened");
            MediaPlayer candidate=new MediaPlayer();player=candidate;PopUi.playbackStarted(playbackOwner);
            candidate.setAudioAttributes(new AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build());
            candidate.setVolume(muted?0f:1f,muted?0f:1f);
            candidate.setOnErrorListener((mp,what,extra)->{if(player==mp){closePlayer();playerStatus.setText("Audio unavailable ("+what+"/"+extra+").");}return true;});
            candidate.setOnPreparedListener(mp->{if(player!=mp || !visible)return;
                if(Math.abs(mp.getDuration()-clip.duration)>50){closePlayer();playerStatus.setText("Audio duration does not match its saved range.");return;}
                preparedDuration=mp.getDuration();seek.setMax(preparedDuration);mp.seekTo(target,MediaPlayer.SEEK_CLOSEST);
            });
            candidate.setOnSeekCompleteListener(mp->{if(player!=mp || !visible)return;PopUi.playbackStarted(playbackOwner);observedSeek=mp.getCurrentPosition();seek.setProgress(observedSeek);mp.start();playerStatus.setText("Playing · "+clock(observedSeek)+" / "+clock(preparedDuration));handler.removeCallbacks(progress);handler.post(progress);});
            candidate.setOnCompletionListener(mp->{if(player==mp){closePlayer();playerStatus.setText("Audio finished.");}});
            try(FileInputStream input=new FileInputStream(clip.audio)){candidate.setDataSource(input.getFD());}
            playerStatus.setText("Preparing local audio…");candidate.prepareAsync();
        } catch(Exception error){closePlayer();playerStatus.setText("Audio unavailable: "+error.getMessage());}
    }
    private final Runnable progress= new Runnable(){public void run(){if(player!=null && visible && preparedDuration>0){long position=player.getCurrentPosition();seek.setProgress((int)position);playerStatus.setText((player.isPlaying()?"Playing · ":"Paused · ")+clock(position)+" / "+clock(preparedDuration));if(briefSourceEndMs>=0&&position>=briefSourceEndMs){player.pause();pausedPosition=position;briefSourceEndMs=-1;playerStatus.setText("Source window finished.");return;}handler.postDelayed(this,250);}}};
    void closePlayer(){briefSourceEndMs=-1;handler.removeCallbacks(progress);if(player!=null){if(preparedDuration>0)pausedPosition=player.getCurrentPosition();player.release();player=null;}preparedDuration=0;PopUi.playbackClosed(playbackOwner);}
    boolean hasPlayer(){return player!=null;}
    String statusText(){return playerStatus.getText().toString();}
    @Override protected void onResume(){super.onResume();if(isFinishing()||AutoTranscription.cleanSlateRequested()){finish();return;}visible=true;PopUi.resumeSound(this);if(finishMarker.consume(UiSoundEngine.INSTANCE.muteEpoch()))UiSoundEngine.INSTANCE.play(this,"finish");AutoTranscription.kick(this);handler.removeCallbacks(queuePoll);handler.post(queuePoll);}
    @Override protected void onPause(){PopUi.pauseSound();visible=false;handler.removeCallbacks(queuePoll);closePlayer();saveViewPreferences();super.onPause();}
    @Override protected void onDestroy(){synchronized(briefCancel){briefCancel.set(true);}excerptCancel.set(true);closePlayer();if(deleting)loader.shutdown();else loader.shutdownNow();handler.removeCallbacksAndMessages(null);super.onDestroy();}
    private LinearLayout column(){LinearLayout v=new LinearLayout(this);v.setOrientation(LinearLayout.VERTICAL);return v;}
    private ScrollView pane(android.widget.FrameLayout parent,LinearLayout child){ScrollView v=new ScrollView(this);v.setFillViewport(true);child.setPadding(0,dp(8),0,dp(24));v.addView(child,new ScrollView.LayoutParams(-1,-2));parent.addView(v,new android.widget.FrameLayout.LayoutParams(-1,-2));return v;}
    private Button tab(String label){Button v=button(label);v.setBackgroundColor(PAPER);v.setMinHeight(dp(52));v.setMinimumHeight(dp(52));v.setPadding(dp(8),dp(8),dp(8),dp(8));return v;}
    void selectTab(String label){selectedTab="Summary".equals(label)||"Brief".equals(label)?"Brief":"Tasks".equals(label)?"Tasks":"Notes".equals(label)?"Notes":"Transcript";optionsOpen=false;updatePanels();restorePanePosition();}
    void showOptions(){new android.app.AlertDialog.Builder(this).setTitle("Meeting options").setItems(new String[]{"Export meeting","Your source notes","Speaker labels and audio details","Previous draft history"},(d,w)->{if(w==0)showExportSheet();else if(w==1)selectTab("Notes");else if(w==2){optionsOpen=true;updatePanels();}else showBriefHistory();}).setNeutralButton("Delete meeting",(d,w)->confirmDeleteMeeting()).setNegativeButton("Cancel",null).show();}
    private void confirmDeleteMeeting(){
        if(deleting||meetingDirectory==null){Toast.makeText(this,"Wait for the meeting to load.",Toast.LENGTH_SHORT).show();return;}
        new android.app.AlertDialog.Builder(this).setTitle("Delete this meeting?")
            .setMessage("Permanently delete this meeting's recording, transcript, summaries, notes, tasks and prepared exports. This cannot be undone. Other meetings, models and settings stay. Copies already saved outside the app stay.")
            .setNegativeButton("Cancel",null).setPositiveButton("Delete permanently",(d,w)->deleteMeeting()).show();
    }
    private void deleteMeeting(){
        if(deleting||meetingDirectory==null)return;
        if(briefBusy||"RUNNING".equals(exportState)||otherMeetingWork()||!AutoTranscription.beginCleanSlate()){
            Toast.makeText(this,"Finish recording, transcription or meeting work, then try again.",Toast.LENGTH_LONG).show();return;
        }
        deleting=true;visible=false;closePlayer();handler.removeCallbacksAndMessages(null);
        TextView progress=text("Deleting meeting…",21);progress.setPadding(dp(24),dp(48),dp(24),dp(24));setContentView(progress);
        final String id=meetingDirectory.getName();final android.content.Context app=getApplicationContext();
        // Drain this detail worker, then all Main task saves, before deleting. Neither executor is abandoned on rotation.
        loader.execute(()->MainActivity.runStorageTask(()->{
            String message;
            try{CleanSlate.deleteMeeting(app.getFilesDir(),app.getCacheDir(),id,()->AutoTranscription.removeDeletedMeeting(app,id));message="Meeting deleted. Other meetings and models kept.";}
            catch(CleanSlate.LegacyFilesPresent legacy){message="Meeting not deleted: older prepared exports cannot be assigned to one meeting. Delete all meetings can clear them, but also deletes every other meeting.";}
            catch(IOException failure){message="Deletion could not finish. Reopen this meeting and retry; some of its data may remain.";}
            finally{AutoTranscription.endCleanSlate();}
            final String result=message;
            new Handler(Looper.getMainLooper()).post(()->{
                Toast.makeText(app,result,Toast.LENGTH_LONG).show();
                if(!isDestroyed()){
                    startActivity(new android.content.Intent(this,MainActivity.class).addFlags(android.content.Intent.FLAG_ACTIVITY_CLEAR_TOP).putExtra("popPage","Meetings"));finish();
                }
            });
        }));
    }
    private void updatePanels(){if(bottomMeetings!=null){NotebookNav.select(bottomMeetings,!"Tasks".equals(selectedTab));NotebookNav.select(bottomTasks,"Tasks".equals(selectedTab));}if(transcriptScroll==null)return;boolean t=!optionsOpen&&"Transcript".equals(selectedTab),n=!optionsOpen&&"Notes".equals(selectedTab),b=!optionsOpen&&"Brief".equals(selectedTab),task=!optionsOpen&&"Tasks".equals(selectedTab);briefScroll.setVisibility(b?0:8);transcriptScroll.setVisibility(t?0:8);notesScroll.setVisibility(n?0:8);optionsScroll.setVisibility(optionsOpen?0:8);tasksScroll.setVisibility(task?0:8);Button[] buttons={briefTab,notesTab,transcriptTab};boolean[] chosen={b,task,t};for(int i=0;i<buttons.length;i++){buttons[i].setSelected(chosen[i]);buttons[i].setTextColor(chosen[i]?0xFF203B9F:SECONDARY);buttons[i].setBackground(controlShape(chosen[i]?0xFFFFFFFF:0xFFF1E6CF,0xFFC9BFA8,0));buttons[i].setContentDescription(buttons[i].getText()+(chosen[i]?", selected":""));}}
    private void restorePanePosition(){if(transcriptionState.equals("LOADING")||notesLoaded<1)return;if(optionsOpen&&!restoredOptions){restoredOptions=true;optionsScroll.post(()->optionsScroll.scrollTo(0,restoreOptionsY));}else if(!optionsOpen&&"Notes".equals(selectedTab)&&!restoredNotes){restoredNotes=true;notesScroll.post(()->notesScroll.scrollTo(0,restoreNotesY));}else if(!optionsOpen&&"Transcript".equals(selectedTab)&&!restoredTranscript){restoredTranscript=true;transcriptScroll.post(()->transcriptScroll.scrollTo(0,restoreTranscriptY));}}
    private void disclosure(LinearLayout parent,String label,android.view.View details){Button toggle=button(label);details.setVisibility(android.view.View.GONE);toggle.setContentDescription("Show "+label.toLowerCase(java.util.Locale.ROOT));toggle.setOnClickListener(v->{PopUi.feedback(v,"button");boolean open=details.getVisibility()!=android.view.View.VISIBLE;details.setVisibility(open?0:8);toggle.setSelected(open);toggle.setContentDescription((open?"Hide ":"Show ")+label.toLowerCase(java.util.Locale.ROOT));});parent.addView(toggle);parent.addView(details);}
    private static String clock(long millis){return String.format(java.util.Locale.getDefault(),"%d:%02d",Math.max(0,millis)/60000,Math.max(0,millis)/1000%60);}
    private void saveViewPreferences(){if(transcriptScroll==null)return;String k=stateKey();android.content.SharedPreferences.Editor e=getPreferences(MODE_PRIVATE).edit().putString(k+"tab",selectedTab).putInt(k+"transcriptY",restoredTranscript?transcriptScroll.getScrollY():restoreTranscriptY).putInt(k+"notesY",restoredNotes?notesScroll.getScrollY():restoreNotesY);if(selectedClip!=null)e.putString(k+"clip",selectedClip.name).putLong(k+"position",pausedPosition);e.apply();}
    @Override protected void onSaveInstanceState(Bundle out){if(optionsScroll==null){super.onSaveInstanceState(out);return;}out.putString("selectedTab",selectedTab);out.putBoolean("optionsOpen",optionsOpen);out.putBoolean("excerptsOpen",excerptsOpen);out.putInt("transcriptPage",transcriptPage);out.putInt("transcriptY",restoredTranscript?transcriptScroll.getScrollY():restoreTranscriptY);out.putInt("notesY",restoredNotes?notesScroll.getScrollY():restoreNotesY);out.putInt("optionsY",optionsScroll.getScrollY());out.putString("clip",selectedClip==null?null:selectedClip.name);out.putLong("position",player!=null&&preparedDuration>0?player.getCurrentPosition():pausedPosition);if(exportResult!=null&&"READY".equals(exportState))try{out.putString("preparedExport",exportResult.toString());}catch(Exception ignored){}super.onSaveInstanceState(out);}
    @Override public void onBackPressed(){if(optionsOpen){optionsOpen=false;updatePanels();restorePanePosition();}else super.onBackPressed();}
    private int dp(int value){return Math.round(value*getResources().getDisplayMetrics().density);}
    private TextView text(String value,int size){TextView view=new TextView(this);view.setText(value);view.setTextSize(Math.max(18,size));view.setTextColor(size==16?SECONDARY:INK);view.setLineSpacing(0,1.5f);view.setPadding(0,dp(size>=20?24:12),0,dp(size>=20?12:8));if(size>=24){view.setTypeface(android.graphics.Typeface.SANS_SERIF,android.graphics.Typeface.BOLD);if(android.os.Build.VERSION.SDK_INT>=28)view.setAccessibilityHeading(true);}return view;}
    private Button button(String value){return styledButton(value,false);}
    private Button primaryButton(String value){return styledButton(value,true);}
    private android.graphics.drawable.GradientDrawable controlShape(int fill,int stroke,int width){android.graphics.drawable.GradientDrawable shape=new android.graphics.drawable.GradientDrawable();shape.setColor(fill);shape.setCornerRadius(dp(12));if(width>0)shape.setStroke(dp(width),stroke);return shape;}
    private Button styledButton(String value,boolean primary){Button b=PopUi.button(this,value,primary);LinearLayout.LayoutParams params=new LinearLayout.LayoutParams(-1,-2);params.topMargin=dp(8);params.bottomMargin=dp(4);b.setLayoutParams(params);return b;}

    private static JSONObject readJson(File file)throws Exception{if(!file.isFile()||file.length()>262144)throw new IOException("Missing or oversized metadata");byte[] bytes;try(FileInputStream in=new FileInputStream(file)){ByteArrayOutputStream output=new ByteArrayOutputStream();byte[] buffer=new byte[8192];int count,total=0;while((count=in.read(buffer))!=-1){total+=count;if(total>262144)throw new IOException("Oversized metadata");output.write(buffer,0,count);}bytes=output.toByteArray();}if(bytes.length>262144)throw new IOException("Oversized metadata");return new JSONObject(new String(bytes,StandardCharsets.UTF_8));}
    private static String sha(File file)throws Exception{MessageDigest digest=MessageDigest.getInstance("SHA-256");try(FileInputStream in=new FileInputStream(file)){byte[] bytes=new byte[8192];int n;while((n=in.read(bytes))!=-1)digest.update(bytes,0,n);}StringBuilder result=new StringBuilder();for(byte b:digest.digest())result.append(String.format(Locale.ROOT,"%02x",b&255));return result.toString();}
}
