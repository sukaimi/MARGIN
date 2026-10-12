package ai.codeandcraft.secondbrain;

import android.app.*;
import android.content.*;
import android.content.pm.PackageManager;
import android.os.*;
import android.view.*;
import android.widget.*;
import org.json.*;
import java.io.*;
import java.util.*;
import java.util.concurrent.*;

public class MainActivity extends Activity {
    private final Handler handler=new Handler(Looper.getMainLooper());
    // Process-wide ordering keeps saves from older Activity instances ahead of deletion.
    private static final ExecutorService loader=Executors.newSingleThreadExecutor();
    static void runStorageTask(Runnable task){loader.execute(task);}
    private final ArrayList<JSONObject> meetings=new ArrayList<>();
    private LinearLayout root,content;private TextView state,timer;private RecordingOrbView orb;
    private boolean manualFinishCue,cleanSlateVisible;
    private long manualFinishSoundEpoch,cleanSlateRevision;
    private String page="Meetings",error;private boolean visible,indexBusy,indexReady,startPending,finishing,sheetOpen;
    private int open,drafts,unavailable,indexFailures,indexDiagnostics;private boolean indexIncomplete;private long timerBase=-1,startRequestUtc;private String recordingId;
    private final HashSet<String> beforeStart=new HashSet<>();
    private final Runnable poll=new Runnable(){public void run(){if(!visible)return;updateCapture();handler.postDelayed(this,500);}};
    @Override public void onCreate(Bundle b){super.onCreate(b);if(b!=null){page=b.getString("page","Meetings");finishing=b.getBoolean("finishing");startRequestUtc=b.getLong("startRequestUtc");recordingId=b.getString("recordingId");timerBase=b.getLong("timerBase",-1);captureRequestOwner=b.getLong("captureRequestOwner");for(String id:b.getStringArrayList("beforeStart")==null?new ArrayList<String>():b.getStringArrayList("beforeStart"))beforeStart.add(id);}boolean liveCapture=RecordingStartGate.INSTANCE.canRestore(RecorderService.isRecording(),RecorderService.getStatus());startPending=RecordingStartGate.INSTANCE.pending()&&!RecorderService.isRecording()&&!"STARTING".equals(RecorderService.getStatus())&&!"RECORDING".equals(RecorderService.getStatus());boolean completedFinish=finishing&&"STOPPED".equals(RecorderService.getStatus())&&!RecorderService.isRecording()&&RecordingStartGate.INSTANCE.canResolveCompleted(captureRequestOwner,startRequestUtc,recordingId);if(liveCapture){captureRequestOwner=RecordingStartGate.INSTANCE.owner();if(RecordingStartGate.INSTANCE.requestUtc()>0)startRequestUtc=RecordingStartGate.INSTANCE.requestUtc();}if(!liveCapture&&!completedFinish){if("Record".equals(page))page="Meetings";finishing=false;}getWindow().setStatusBarColor(PopUi.PAPER);getWindow().setNavigationBarColor(PopUi.PAPER);getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR|View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR);handleIncomingIntent(getIntent(),b!=null);}
    @Override protected void onNewIntent(Intent intent){super.onNewIntent(intent);setIntent(intent);handleIncomingIntent(intent,false);}
    private void handleIncomingIntent(Intent intent,boolean restored){
        String target=intent==null?null:intent.getStringExtra("popPage");
        String token=intent==null?null:intent.getStringExtra(RecordEntryGuard.EXTRA_TOKEN);
        // Never retain a capability in the Activity intent across recreation.
        if(intent!=null)intent.removeExtra(RecordEntryGuard.EXTRA_TOKEN);
        boolean permitted=RecordEntryGuard.INSTANCE.allowRecord(target,token,restored);
        if("Record".equals(target)&&intent!=null)intent.removeExtra("popPage");
        if(restored){render();return;}
        if("Record".equals(target)){
            if(permitted){record();return;}
            page="Meetings";
        }else if(!restored&&("Meetings".equals(target)||"Tasks".equals(target)))page=target;
        render();
    }
    private boolean capture(){String s=RecorderService.getStatus();return RecordingStartGate.INSTANCE.pending()||RecorderService.isRecording()||"STARTING".equals(s)||"RECORDING".equals(s);}
    private void render(){if(orb!=null)orb.setActive(false);root=PopUi.column(this);root.setBackground(NotebookSurface.page(this));root.setForceDarkAllowed(false);setContentView(root);ScrollView scroll=new ScrollView(this);scroll.setFillViewport(true);content=PopUi.column(this);int pad=getResources().getConfiguration().screenWidthDp<=360?18:22;content.setPadding(PopUi.dp(this,pad),PopUi.dp(this,18),PopUi.dp(this,pad),PopUi.dp(this,24));scroll.addView(content,new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,ViewGroup.LayoutParams.WRAP_CONTENT));root.addView(scroll,new LinearLayout.LayoutParams(-1,0,1));state=null;timer=null;orb=null;
        if(AutoTranscription.cleanSlateRequested()){cleanSlateVisible=true;PopUi.add(content,PopUi.text(this,"Deleting meeting data…",21,true),16);return;}
        if("Record".equals(page)){renderRecorder();return;}
        LinearLayout header=new LinearLayout(this);header.setGravity(Gravity.CENTER_VERTICAL);View mark=PopUi.brandMark(this);mark.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);header.addView(mark,new LinearLayout.LayoutParams(PopUi.dp(this,28),PopUi.dp(this,28)));TextView brand=PopUi.text(this,"JotterBook",21,true);brand.setTextColor(0xFF203AAF);brand.setLetterSpacing(.12f);header.addView(brand,new LinearLayout.LayoutParams(0,-2,1));header.addView(PopUi.sound(this),new LinearLayout.LayoutParams(PopUi.dp(this,48),PopUi.dp(this,48)));Button about=PopUi.iconButton(this,MarginIconButton.Icon.INFO,"About JotterBook",false);about.setOnClickListener(v->{PopUi.feedback(v,"button");showAbout();});LinearLayout.LayoutParams infoParams=new LinearLayout.LayoutParams(PopUi.dp(this,48),PopUi.dp(this,48));infoParams.leftMargin=PopUi.dp(this,8);header.addView(about,infoParams);content.addView(header,new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,ViewGroup.LayoutParams.WRAP_CONTENT));
        PopUi.add(content,PopUi.homeTitle(this,"Tasks".equals(page)?"Your tasks":"Your meetings"),16);
        if(error!=null)PopUi.add(content,PopUi.text(this,error,18,false),12);
        if(!indexReady){PopUi.add(content,PopUi.text(this,"Loading meetings…",18,false),16);}else if("Tasks".equals(page)){renderTasks();}else renderMeetings();
        LinearLayout nav=new LinearLayout(this);nav.setPadding(PopUi.dp(this,8),PopUi.dp(this,8),PopUi.dp(this,8),PopUi.dp(this,8));nav.setBackground(NotebookSurface.cloth(this));for(String name:new String[]{"Meetings","Record","Tasks"}){Button b=NotebookNav.button(this,name,name+("Tasks".equals(name)&&indexReady?" ("+open+")":""),page.equals(name));b.setOnClickListener(v->{if("Record".equals(name))record();else{if(!page.equals(name))PopUi.feedback(v,"tab");page=name;render();}});nav.addView(b,new LinearLayout.LayoutParams(0,-2,1));}LinearLayout.LayoutParams np=new LinearLayout.LayoutParams(-1,-2);np.setMargins(PopUi.dp(this,16),PopUi.dp(this,10),PopUi.dp(this,16),PopUi.dp(this,10));root.addView(nav,np);
    }
    private void renderMeetings(){
        quick("Review notes",drafts+" drafts to check",0xFFFFE18B,()->{for(JSONObject m:meetings)if(m.optJSONObject("_brief")!=null){detail(m,"Summary",false);break;}});quick("View tasks",open+" to follow up",PopUi.CORAL,()->{page="Tasks";render();});
        if(meetings.isEmpty())PopUi.add(content,PopUi.text(this,indexFailures>0||indexIncomplete?"Saved meetings could not be loaded. Try reopening the app.":"No recordings yet.",21.4f,false),20);
        boolean first=true;for(JSONObject m:meetings){if(first){PopUi.add(content,PopUi.text(this,"Latest meeting",18,true),21);first=false;}LinearLayout card=PopUi.card(this,0xFFFFFFFF);PopUi.add(content,card,16);PopUi.add(card,PopUi.text(this,title(m),21.4f,true),0);PopUi.add(card,PopUi.text(this,date(m)+" · "+duration(m.optLong("totalDurationMillis"))+" · "+m.optInt("_open")+" open tasks",18,false),6);PopUi.add(card,PopUi.text(this,m.optJSONObject("_brief")!=null?"Unverified notes · Review needed":m.optString("status","Needs attention"),18,false),8);Button notes=PopUi.button(this,"View notes",false);notes.setOnClickListener(v->{PopUi.feedback(v,"button");detail(m,"Summary",false);});PopUi.add(card,notes,8);Button export=PopUi.iconButton(this,MarginIconButton.Icon.EXPORT,"Export meeting",false);export.setOnClickListener(v->detail(m,"Summary",true));LinearLayout.LayoutParams exportParams=new LinearLayout.LayoutParams(PopUi.dp(this,48),PopUi.dp(this,48));exportParams.topMargin=PopUi.dp(this,8);card.addView(export,exportParams);}
        indexLimitNote();}
    private void quick(String title,String subtitle,int color,Runnable run){LinearLayout card=PopUi.card(this,color);PopUi.add(card,PopUi.text(this,title,20,true),0);PopUi.add(card,PopUi.text(this,subtitle,18,false),4);card.setMinimumHeight(PopUi.dp(this,88));card.setFocusable(true);card.setSoundEffectsEnabled(false);card.setContentDescription(title+", "+subtitle);card.setOnClickListener(v->run.run());PopUi.add(content,card,12);}
    private void renderTasks(){PopUi.add(content,PopUi.text(this,open+" open tasks",21.4f,false),12);for(JSONObject m:meetings){JSONObject brief=m.optJSONObject("_brief");if(brief==null)continue;JSONArray items=brief.optJSONArray("items");for(int i=0;i<items.length();i++){JSONObject item=items.optJSONObject(i);if(!"actions".equals(item.optString("section")))continue;final int index=i;LinearLayout card=PopUi.card(this,0xFFFFFFFF);PopUi.add(content,card,12);CheckBox done=new CheckBox(this);done.setSoundEffectsEnabled(false);done.setText(item.optString("text"));done.setTextSize(20.3f);done.setTextColor(PopUi.INK);done.setMinHeight(PopUi.dp(this,48));done.setChecked(item.optBoolean("done"));done.setEnabled(!capture());if(done.isChecked())done.setPaintFlags(done.getPaintFlags()|android.graphics.Paint.STRIKE_THRU_TEXT_FLAG);card.addView(done,new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,ViewGroup.LayoutParams.WRAP_CONTENT));done.setOnClickListener(v->{done.setChecked(item.optBoolean("done"));saveTask(m,index,null,null,null,!item.optBoolean("done"),done);});PopUi.add(card,PopUi.text(this,item.optBoolean("done")?"Completed":"To do",18,true),4);if(!item.isNull("owner"))PopUi.add(card,PopUi.text(this,"Assigned to: "+item.optString("owner"),18,false),4);if(!item.isNull("date"))PopUi.add(card,PopUi.text(this,"Due: "+item.optString("date"),18,false),4);PopUi.add(card,PopUi.text(this,"Unverified suggestion · Check before acting.",18,false),8);Button from=PopUi.button(this,"From meeting: "+title(m),false);from.setOnClickListener(v->detail(m,"Tasks",false));PopUi.add(card,from,8);Button edit=PopUi.button(this,"Edit",false);edit.setEnabled(!capture());edit.setOnClickListener(v->{PopUi.feedback(v,"button");editTask(card,m,index,item);});PopUi.add(card,edit,8);}}if(open==0)PopUi.add(content,PopUi.text(this,"No open tasks in saved briefs.",18,false),16);indexLimitNote();}
    private void editTask(LinearLayout card,JSONObject m,int i,JSONObject item){card.removeAllViews();PopUi.add(card,PopUi.text(this,"Edit task",24,true),0);EditText text=PopUi.input(this,"Task",item.optString("text"),2000),owner=PopUi.input(this,"Assigned to",item.isNull("owner")?"":item.optString("owner"),120),date=PopUi.input(this,"Due",item.isNull("date")?"":item.optString("date"),120);text.setMinLines(3);PopUi.add(card,text,12);PopUi.add(card,owner,12);PopUi.add(card,date,12);Button cancel=PopUi.button(this,"Cancel",false),save=PopUi.button(this,"Save changes",true);cancel.setOnClickListener(v->render());save.setOnClickListener(v->saveTask(m,i,text.getText().toString(),owner.getText().toString(),date.getText().toString(),item.optBoolean("done"),save));PopUi.add(card,cancel,12);PopUi.add(card,save,8);}
    private void saveTask(JSONObject m,int i,String text,String owner,String date,boolean done,View cue){if(AutoTranscription.cleanSlateRequested())return;if(text!=null&&text.trim().isEmpty()){Toast.makeText(this,"Enter task text.",Toast.LENGTH_SHORT).show();return;}cue.setEnabled(false);final long soundGeneration=PopUi.soundGeneration();loader.execute(()->{try{JSONObject draft=new JSONObject(m.getJSONObject("_brief").toString()),item=draft.getJSONArray("items").getJSONObject(i);if(text!=null)item.put("text",text).put("owner",owner.trim().isEmpty()?JSONObject.NULL:owner).put("date",date.trim().isEmpty()?JSONObject.NULL:date);item.put("done",done).put("provenance","USER_ENTERED").put("ownerDateProvenance","USER_ENTERED_ONLY");draft.put("provenance","USER_ENTERED");MeetingBriefStore.append(this,SessionIndexPaths.candidate(new File(getFilesDir(),"sessions"),new File(new File(getFilesDir(),"sessions"),m.getString("_id"))),draft,draft.getString("versionId"),null);handler.post(()->{if(isDestroyed())return;if(visible&&PopUi.soundCurrent(soundGeneration))PopUi.feedback(cue,"save");Toast.makeText(this,"Task saved as a new revision",Toast.LENGTH_SHORT).show();loadIndex();});}catch(Exception e){handler.post(()->{if(!isDestroyed()){cue.setEnabled(true);Toast.makeText(this,"Task save could not be confirmed. Reload saved notes before retrying.",Toast.LENGTH_LONG).show();}});}});}
    private void indexLimitNote(){
        if(indexFailures>0)PopUi.add(content,PopUi.text(this,indexFailures+" saved meeting entries could not be loaded.",18,false),12);
        if(indexIncomplete)PopUi.add(content,PopUi.text(this,"Meeting list is incomplete; task counts may be partial.",18,false),12);
        if(unavailable>0)PopUi.add(content,PopUi.text(this,unavailable+" meeting briefs unavailable; task counts exclude them.",18,false),12);
    }
    private void loadIndex(){
        if(AutoTranscription.cleanSlateRequested())return;
        if(indexBusy)return;indexBusy=true;indexReady=false;
        final long requestedRevision=AutoTranscription.cleanSlateRevision();
        loader.execute(()->{
            ArrayList<JSONObject> rows=new ArrayList<>();Map<String,File> selected=new HashMap<>();
            Map<String,Integer> failures=new TreeMap<>();
            int count=0,draftCount=0,entryFailed=0,briefFailed=0,scanned=0,ignored=0,directories=0,equal=0,different=0;
            boolean incomplete=false,completed=false;String stage="ROOT_LIST";
            try{
                File root=new File(getFilesDir(),"sessions");
                File[] files=root.listFiles();
                if(files==null && root.exists())throw new IOException();
                if(files!=null){
                    stage="ENTRY_SORT";files=SessionIndexPaths.newestFirst(files);
                    for(File f:files){
                        stage="PATH";
                        if(!f.isDirectory()){ignored++;continue;}
                        directories++;
                        if(!SessionIndexPaths.uuidLooking(f.getName())){ignored++;continue;}
                        if(scanned>=512){incomplete=true;break;}scanned++;
                        try{
                            File candidate=SessionIndexPaths.candidate(root,f);
                            if(f.getAbsoluteFile().equals(candidate))equal++;else different++;
                            stage="METADATA_READ";byte[] bytes=SessionIndexPaths.metadata(candidate);
                            stage="METADATA_PARSE";JSONObject m=new JSONObject(new String(bytes,java.nio.charset.StandardCharsets.UTF_8));
                            m.put("_id",f.getName());rows.add(m);selected.put(f.getName(),candidate);
                        }catch(Exception e){incomplete=true;entryFailed++;indexFailure(failures,stage,e);}
                    }
                }
                stage="ROW_SORT";
                rows.sort((a,b)->Long.compare(b.optLong("startUtc"),a.optLong("startUtc")));
                if(rows.size()>128)incomplete=true;
                while(rows.size()>128)rows.remove(rows.size()-1);
                for(JSONObject m:rows){
                    stage="BRIEF";
                    try{
                        JSONArray versions=MeetingBriefStore.load(this,selected.get(m.getString("_id")));
                        if(versions.length()>0){
                            JSONObject brief=versions.getJSONObject(versions.length()-1);int n=0;
                            JSONArray items=brief.getJSONArray("items");
                            for(int i=0;i<items.length();i++)if("actions".equals(items.getJSONObject(i).optString("section"))&&!items.getJSONObject(i).optBoolean("done"))n++;
                            m.put("_brief",brief);m.put("_open",n);draftCount++;count+=n;
                        }
                    }catch(Exception e){briefFailed++;indexFailure(failures,"BRIEF",e);}
                }
                completed=true;
            }catch(Exception e){incomplete=true;indexFailure(failures,stage,e);}
            finally{
                // Errors propagate, but the UI still receives an incomplete result and resets.
                if(!completed){incomplete=true;failures.put(stage+":Incomplete",1);}
                final int n=count,d=draftCount,f=briefFailed,ef=entryFailed;final boolean partial=incomplete;
                try{
                    // At most three aggregate lines per Activity; no identifiers, content or messages.
                    if(indexDiagnostics++<3)android.util.Log.i("MeetingIndex","scanned="+scanned+" directories="+directories+" ignored="+ignored+" rows="+rows.size()+" entryFailed="+entryFailed+" briefFailed="+briefFailed+" absoluteCanonicalEqual="+equal+" absoluteCanonicalDifferent="+different+" incomplete="+incomplete+" failures="+failures);
                }finally{
                    handler.post(()->{indexBusy=false;if(isDestroyed())return;if(requestedRevision!=AutoTranscription.cleanSlateRevision()){loadIndex();return;}indexReady=true;meetings.clear();meetings.addAll(rows);open=n;drafts=d;unavailable=f;indexFailures=ef;indexIncomplete=partial;if(!"Record".equals(page))render();});
                }
            }
        });
    }
    private static void indexFailure(Map<String,Integer> failures,String stage,Exception e){
        String key=stage+":"+e.getClass().getSimpleName();failures.put(key,failures.containsKey(key)?failures.get(key)+1:1);
    }
    private static JSONObject read(File f)throws Exception{File meta=new File(f,"session.json");if(!meta.isFile()||meta.length()>262144)throw new IOException();return new JSONObject(new String(new android.util.AtomicFile(meta).readFully(),java.nio.charset.StandardCharsets.UTF_8));}
    private void detail(JSONObject m,String tab,boolean export){detail(m,tab,export,false);}
    private void detail(JSONObject m,String tab,boolean export,boolean manualFinish){if(AutoTranscription.cleanSlateRequested())return;startActivity(new Intent(this,MeetingDetailActivity.class).putExtra("sessionId",m.optString("_id")).putExtra("popTab",tab).putExtra("popExport",export).putExtra(MeetingDetailActivity.MANUAL_FINISH,manualFinish));}
    private String title(JSONObject m){String s=m.optString("title");return s.isEmpty()?"Meeting":s;}
    private String date(JSONObject m){long t=m.optLong("startUtc");return t>0?java.text.DateFormat.getDateTimeInstance().format(new Date(t)):"Date unavailable";}
    private String duration(long n){return n>0?String.format(Locale.getDefault(),"%d:%02d",n/60000,n/1000%60):"Duration unavailable";}
    private void record(){if(AutoTranscription.cleanSlateRequested())return;page="Record";render();if(!capture()&&!finishing){if(checkSelfPermission(android.Manifest.permission.RECORD_AUDIO)!=PackageManager.PERMISSION_GRANTED)requestPermissions(new String[]{android.Manifest.permission.RECORD_AUDIO},1);else startCapture();}}
    private void startCapture(){if(AutoTranscription.cleanSlateRequested())return;if(capture()||finishing)return;startRequestUtc=System.currentTimeMillis();captureRequestOwner=RecordingStartGate.INSTANCE.begin(startRequestUtc);if(captureRequestOwner==0)return;try{beforeStart.clear();File[] files=new File(getFilesDir(),"sessions").listFiles();if(files!=null)for(File f:files)beforeStart.add(f.getName());error=null;recordingId=null;timerBase=-1;startPending=true;page="Record";render();PopUi.capturePending=true;PopUi.stopSound();PopUi.cue(content);AutoTranscription.requestCapture();waitForTranscriptionBeforeCapture();}catch(Exception e){RecordingStartGate.INSTANCE.fail(captureRequestOwner);startPending=false;PopUi.capturePending=false;AutoTranscription.releaseCapture();AutoTranscription.kick(this);error="Recording could not start.";}updateCapture();}
    private long captureRequestOwner;
    private void waitForTranscriptionBeforeCapture(){if(isDestroyed()||!visible||RecorderService.isRecording()||!startPending||captureRequestOwner!=RecordingStartGate.INSTANCE.owner())return;if(!AutoTranscription.quiescent()){if(state!=null)state.setText("Waiting for the current transcript window to save…");handler.postDelayed(this::waitForTranscriptionBeforeCapture,250);return;}if(!RecordingStartGate.INSTANCE.claim(captureRequestOwner))return;try{startForegroundService(new Intent(this,RecorderService.class).setAction(RecorderService.ACTION_START));}catch(Exception e){RecordingStartGate.INSTANCE.fail(captureRequestOwner);startPending=false;PopUi.capturePending=false;AutoTranscription.releaseCapture();AutoTranscription.kick(this);error="Recording could not start.";}}
    @Override public void onRequestPermissionsResult(int request,String[] permissions,int[] results){super.onRequestPermissionsResult(request,permissions,results);if(request==1){if(results.length>0&&results[0]==PackageManager.PERMISSION_GRANTED)startCapture();else{error="Microphone permission is required to record.";page="Meetings";render();}}}
    private void renderRecorder(){Button cancel=PopUi.button(this,"Cancel",false);cancel.setOnClickListener(v->{RecorderDiagnostics.log(this,"CANCEL_DIALOG_OPEN");sheetOpen=true;if(orb!=null)orb.setActive(false);new AlertDialog.Builder(this).setTitle("Finish recording?").setMessage("Finish and keep the recording data on this phone.").setNegativeButton("Keep recording",(d,w)->{RecorderDiagnostics.log(this,"CANCEL_DIALOG_KEEP");sheetOpen=false;updateCapture();}).setPositiveButton("Finish and keep",(d,w)->{RecorderDiagnostics.log(this,"CANCEL_DIALOG_FINISH");sheetOpen=false;finishCapture("CANCEL_FINISH");}).setOnCancelListener(d->{RecorderDiagnostics.log(this,"CANCEL_DIALOG_DISMISS");sheetOpen=false;updateCapture();}).show();});PopUi.add(content,cancel,0);PopUi.add(content,PopUi.text(this,"New meeting",38.7f,true),24);state=PopUi.text(this,"Starting recording…",18,true);PopUi.add(content,state,12);timer=PopUi.text(this,"Elapsed unavailable",55.8f,false);timer.setTextColor(0xFF203BA0);PopUi.add(content,timer,20);orb=new RecordingOrbView(this);content.addView(orb,new LinearLayout.LayoutParams(-1,PopUi.dp(this,230)));Button finish=PopUi.button(this,"Finish recording",true);finish.setOnClickListener(v->{RecorderDiagnostics.log(this,"UI_FINISH_REQUEST");finishCapture("UI_FINISH");});PopUi.add(content,finish,16);updateCapture();}
    private void finishCapture(String stopReason){if(finishing)return;if(!RecorderService.isRecording()&&RecordingStartGate.INSTANCE.cancelWaiting(captureRequestOwner)){startPending=false;PopUi.capturePending=false;AutoTranscription.releaseCapture();AutoTranscription.kick(this);page="Meetings";render();return;}if(!capture()){page="Meetings";render();return;}try{finishing=true;manualFinishCue=true;manualFinishSoundEpoch=UiSoundEngine.INSTANCE.muteEpoch();startService(new Intent(this,RecorderService.class).setAction(RecorderService.ACTION_STOP).putExtra(RecorderService.EXTRA_STOP_REASON,RecorderDiagnostics.stopReason(stopReason)));updateCapture();}catch(Exception e){finishing=false;manualFinishCue=false;error="Recording could not be stopped. Check its status.";if(state!=null)state.setText(error);}}
    private boolean resolvingSession;
    private void resolveSession(boolean completed){if(resolvingSession)return;resolvingSession=true;if(completed)PopUi.prepareSound(this);loader.execute(()->{JSONObject found=null;try{File[] files=new File(getFilesDir(),"sessions").listFiles();if(files!=null)for(File f:files){if(!f.isDirectory())continue;try{JSONObject m=read(f);boolean candidate=recordingId!=null?recordingId.equals(f.getName()):startRequestUtc>0?!beforeStart.contains(f.getName())&&m.optLong("startUtc")>=startRequestUtc:"ACTIVE".equals(m.optString("status"));if(candidate&&(!completed||"COMPLETED".equals(m.optString("status")))){m.put("_id",f.getName());found=m;break;}}catch(Exception ignored){}}}catch(Exception ignored){}final JSONObject result=found;handler.post(()->{resolvingSession=false;if(isDestroyed())return;if(result!=null){recordingId=result.optString("_id");if(timerBase<0&&result.optLong("startUtc")>0)timerBase=SystemClock.elapsedRealtime()-Math.max(0,System.currentTimeMillis()-result.optLong("startUtc"));if(completed){finishing=false;page="Meetings";PopUi.capturePending=false;detail(result,"Transcript",false,manualFinishCue&&manualFinishSoundEpoch==UiSoundEngine.INSTANCE.muteEpoch());manualFinishCue=false;}}else if(completed){finishing=false;manualFinishCue=false;error="Capture stopped; no completed new meeting was found. Check saved meetings.";page="Meetings";loadIndex();render();}});});}
    private void updateCapture(){if(!AutoTranscription.cleanSlateRequested()&&(cleanSlateVisible||cleanSlateRevision!=AutoTranscription.cleanSlateRevision())){cleanSlateVisible=false;cleanSlateRevision=AutoTranscription.cleanSlateRevision();loadIndex();render();}String s=RecorderService.getStatus();startPending=RecordingStartGate.INSTANCE.pending()&&!RecorderService.isRecording()&&!"STARTING".equals(s)&&!"RECORDING".equals(s);if(("STOPPED".equals(s)&&!startPending)||("ERROR".equals(s)&&!startPending))PopUi.capturePending=false;if("STARTING".equals(s)||"RECORDING".equals(s))startPending=false;if("ERROR".equals(s)&&!startPending){startPending=false;finishing=false;error="Recording needs attention. Saved data is retained.";}if(finishing&&"STOPPED".equals(s)&&!RecorderService.isRecording()&&RecordingStartGate.INSTANCE.canResolveCompleted(captureRequestOwner,startRequestUtc,recordingId)){startPending=false;resolveSession(true);}else if("Record".equals(page)&&capture()&&(recordingId==null||timerBase<0))resolveSession(false);if(state!=null)state.setText(error!=null?error:finishing?"Finishing and saving audio…":"RECORDING".equals(s)?"● Recording":"STARTING".equals(s)||startPending?"Starting recording…":"Capture stopped");if(timer!=null)timer.setText(timerBase>=0?duration(Math.max(1,SystemClock.elapsedRealtime()-timerBase)):"Elapsed unavailable");if(orb!=null)orb.setActive(visible&&!sheetOpen&&"Record".equals(page)&&!finishing&&"RECORDING".equals(s));}
    static String edge0Availability(){return "Optional Edge0 is not installed or validated.";}
    private void showAbout(){String version="Build identity unavailable";try{android.content.pm.PackageInfo p=getPackageManager().getPackageInfo(getPackageName(),0);version=p.versionName+" · build "+p.getLongVersionCode();}catch(Exception ignored){}new AlertDialog.Builder(this).setTitle("JotterBook").setMessage(version+"\nPrivate local meeting notebook. AI drafts are unverified and use partial sources; source fallback is available. Automatic speakers and summaries remain unaccepted. Word timings unavailable. Controlled endurance and battery checks are deferred to field validation. "+edge0Availability()).setNegativeButton("Delete all meetings",(d,w)->confirmCleanSlate()).setPositiveButton("Done",null).show();}
    private void confirmCleanSlate(){
        if(capture()||finishing||MeetingDetailActivity.hasOpenWork()){
            Toast.makeText(this,"Finish recording and close meeting work before deleting.",Toast.LENGTH_LONG).show();return;
        }
        new AlertDialog.Builder(this).setTitle("Delete all meetings?")
            .setMessage("Permanently delete every meeting, recording, transcript, summary, note and task in this app, including prepared exports. This cannot be undone. Downloaded models and app settings stay. Copies already saved outside the app are not deleted.")
            .setNegativeButton("Cancel",null).setPositiveButton("Delete permanently",(d,w)->deleteAllMeetings()).show();
    }
    private void deleteAllMeetings(){
        if(capture()||finishing||MeetingDetailActivity.hasOpenWork()||!AutoTranscription.beginCleanSlate()){
            Toast.makeText(this,"Finish recording or transcription, then try deletion again.",Toast.LENGTH_LONG).show();return;
        }
        page="Meetings";error=null;render();
        // Same executor as Main's task saves/index reads; earlier work finishes first.
        loader.execute(()->{
            boolean deleted=false;
            try{CleanSlate.deleteMeetingData(getFilesDir(),getCacheDir());deleted=true;}
            catch(IOException failure){/* Do not expose meeting paths or content. */}
            finally{AutoTranscription.endCleanSlate();}
            final boolean complete=deleted;
            handler.post(()->{
                if(isDestroyed())return;
                cleanSlateVisible=false;meetings.clear();open=drafts=unavailable=indexFailures=0;
                beforeStart.clear();recordingId=null;timerBase=-1;
                error=complete?"All meeting data deleted. Models and settings kept.":"Deletion could not finish. Some meeting data may remain; retry Delete all meetings. Models and settings were not targeted.";
                loadIndex();render();
            });
        });
    }
    @Override protected void onResume(){super.onResume();visible=true;PopUi.resumeSound(this);if(startPending&&AutoTranscription.captureRequested())waitForTranscriptionBeforeCapture();if(!capture()&&!AutoTranscription.captureRequested()) { AutoTranscription.kick(this); }if(AutoTranscription.cleanSlateRequested())render();else loadIndex();handler.removeCallbacks(poll);handler.post(poll);}
    @Override protected void onPause(){PopUi.pauseSound();visible=false;handler.removeCallbacks(poll);if(orb!=null)orb.setActive(false);super.onPause();}
    @Override protected void onDestroy(){handler.removeCallbacksAndMessages(null);super.onDestroy();}
    @Override protected void onSaveInstanceState(Bundle b){b.putString("page",page);b.putBoolean("finishing",finishing);b.putBoolean("startPending",startPending);b.putLong("startRequestUtc",startRequestUtc);b.putLong("captureRequestOwner",captureRequestOwner);b.putString("recordingId",recordingId);b.putLong("timerBase",timerBase);b.putStringArrayList("beforeStart",new ArrayList<>(beforeStart));super.onSaveInstanceState(b);}
    @Override public void onBackPressed(){if(AutoTranscription.cleanSlateRequested())return;if("Record".equals(page)&&capture()){sheetOpen=true;if(orb!=null)orb.setActive(false);new AlertDialog.Builder(this).setTitle("Keep recording?").setMessage("Recording continues when you leave this screen.").setNegativeButton("Stay",(d,w)->{sheetOpen=false;updateCapture();}).setPositiveButton("Meetings",(d,w)->{sheetOpen=false;page="Meetings";render();}).setOnCancelListener(d->{sheetOpen=false;updateCapture();}).show();}else if(!"Meetings".equals(page)){page="Meetings";render();}else super.onBackPressed();}
}
