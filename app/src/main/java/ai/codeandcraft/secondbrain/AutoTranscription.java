package ai.codeandcraft.secondbrain;

import android.content.Context;
import android.content.Intent;
import android.util.AtomicFile;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import org.json.*;

/** Explicit saved-session queue. Neither eligibility nor restart is inferred from old sessions. */
final class AutoTranscription {
    static final int LIMIT=32;
    private static final String MARKER="auto-transcription.json";
    private static boolean captureRequested,working,cleanSlate;
    private static long cleanSlateRevision;
    private static final String PROCESS=UUID.randomUUID().toString();
    private static AtomicBoolean activePause;
    private static String activeId;
    private static Thread activeThread;
    interface Runner { JSONObject run(Context c,File meeting,AtomicBoolean pause)throws Exception; }
    static final Runner ENGINE=(c,m,p)->WindowedTranscription.run(c,m,1,p);
    private AutoTranscription(){}
    static File meeting(Context c,String id)throws Exception {
        if(!UUID.fromString(id).toString().equals(id))throw new IOException("Invalid meeting id");
        File root=new File(c.getFilesDir(),"sessions").getCanonicalFile(),m=new File(root,id).getAbsoluteFile();
        if(!m.equals(m.getCanonicalFile())||!root.equals(m.getParentFile()))throw new IOException("Invalid meeting path");return m;
    }
    static JSONObject read(File f)throws Exception {
        if(!f.isFile()||f.length()>262144)throw new IOException("Queue record unavailable");
        return new JSONObject(new String(new AtomicFile(f).readFully(),StandardCharsets.UTF_8));
    }
    static void write(File f,JSONObject j)throws Exception {
        AtomicFile a=new AtomicFile(f);FileOutputStream o=a.startWrite();try{o.write(j.toString().getBytes(StandardCharsets.UTF_8));a.finishWrite(o);}catch(Exception e){a.failWrite(o);throw e;}
        java.io.FileDescriptor fd=android.system.Os.open(f.getParent(),android.system.OsConstants.O_RDONLY,0);try{android.system.Os.fsync(fd);}finally{android.system.Os.close(fd);}
    }
    private static File index(Context c){return new File(c.getFilesDir(),"auto-transcription-index.json");}
    private static JSONArray ids(Context c)throws Exception {File f=index(c);JSONArray list=f.exists()?read(f).getJSONArray("ids"):new JSONArray();if(list.length()>LIMIT)throw new IOException("Queue index exceeds bounded limit");return list;}
    private static void register(Context c,String id)throws Exception {
        JSONArray old=ids(c),next=new JSONArray();boolean found=false;
        for(int i=0;i<old.length();i++){String s=old.getString(i);if(id.equals(s))found=true;JSONObject state=status(c,s);if(state!=null&&!state.optString("status").startsWith("COMPLETED"))next.put(s);}
        if(found)return;
        if(next.length()>=LIMIT)throw new IOException("Queue full; finish existing work before retrying");next.put(id);write(index(c),new JSONObject().put("ids",next));
    }
    private static String pin(File m)throws Exception {return pin(m,false);}
    private static String pin(File m,boolean manual)throws Exception {
        JSONObject meta=read(new File(m,"session.json"));
        if(!"COMPLETED".equals(meta.optString("status"))&&!(manual&&"RECOVERED_INTERRUPTED".equals(meta.optString("status"))))throw new IOException("Only completed audio can be queued");
        JSONArray chunks=meta.getJSONArray("chunks");if(chunks.length()==0||chunks.length()>512)throw new IOException("No completed audio");
        MessageDigest d=MessageDigest.getInstance("SHA-256");d.update(meta.toString().getBytes(StandardCharsets.UTF_8));
        for(int i=0;i<chunks.length();i++){String name=chunks.getJSONObject(i).getString("filename");if(!name.matches("chunk-[0-9]+\\.wav"))throw new IOException("Invalid source chunk");File f=new File(m,name);if(!f.getAbsoluteFile().equals(f.getCanonicalFile()))throw new IOException("Linked source chunk");try(InputStream in=new FileInputStream(f)){byte[] b=new byte[8192];int n;while((n=in.read(b))!=-1)d.update(b,0,n);}}
        StringBuilder s=new StringBuilder();for(byte b:d.digest())s.append(String.format(Locale.ROOT,"%02x",b&255));return s.toString();
    }
    static synchronized JSONObject status(Context c,String id)throws Exception {File m=meeting(c,id),f=new File(m,MARKER);return f.exists()?read(f):null;}
    static void enqueue(Context c,String id)throws Exception {
        synchronized(AutoTranscription.class){if(status(c,id)!=null)return;}
        String source=pin(meeting(c,id)); // Expensive audio hashing never owns the queue monitor.
        synchronized(AutoTranscription.class){if(status(c,id)!=null)return;reserve(c,id,source,false);}
    }
    static synchronized void enqueueRecorded(Context c,String id)throws Exception {
        File m=meeting(c,id);if(status(c,id)!=null)return;
        if(!"COMPLETED".equals(read(new File(m,"session.json")).optString("status")))throw new IOException("Only completed audio can be queued");
        // Durable eligibility before recorder cleanup; first worker pins audio after release.
        reserve(c,id,null,true);
    }
    private static void reserve(Context c,String id,String source,boolean awaitCleanup)throws Exception {
        JSONObject entry=new JSONObject().put("status","QUEUED").put("automatic",true).put("awaitCleanupOwner",awaitCleanup?PROCESS:"");
        if(source!=null)entry.put("sourcePin",source);
        try{register(c,id);}catch(Exception full){entry.put("status","ERROR").put("error","Audio saved; automatic queue unavailable. Retry after completing queued work.");write(new File(meeting(c,id),MARKER),entry);throw full;}
        write(new File(meeting(c,id),MARKER),entry);
    }
    static synchronized void captureReleased(Context c,String id)throws Exception {
        try{if(id!=null){JSONObject s=status(c,id);if(s!=null){s.remove("awaitCleanupOwner");write(new File(meeting(c,id),MARKER),s);}}}
        finally{releaseCapture();PopUi.capturePending=false;}
    }
    private static boolean ready(JSONObject s){return !PROCESS.equals(s.optString("awaitCleanupOwner"));}
    static void manual(Context c,String id)throws Exception {
        boolean absent; synchronized(AutoTranscription.class){absent=status(c,id)==null;}
        String source=absent?pin(meeting(c,id),true):null;
        synchronized(AutoTranscription.class){
        if(status(c,id)==null){register(c,id);write(new File(meeting(c,id),MARKER),new JSONObject().put("status","QUEUED").put("sourcePin",source).put("automatic",false).put("owner",PROCESS));}else {JSONObject s=status(c,id);String state=s.getString("status");if(state.startsWith("COMPLETED")||"RUNNING".equals(state)||"QUEUED".equals(state))return;if("ERROR".equals(state)&&s.optBoolean("configurationChanged")){s.remove("enginePin");s.remove("jobId");s.remove("configurationChanged");}s.put("status","QUEUED").put("owner",PROCESS).remove("error");register(c,id);write(new File(meeting(c,id),MARKER),s);}
        }
    }
    static synchronized void pause(Context c,String id)throws Exception {
        JSONObject s=status(c,id);if(s==null)return;s.put("status","PAUSED");write(new File(meeting(c,id),MARKER),s);if(activePause!=null&&id.equals(activeId))activePause.set(true);
    }
    static synchronized void requestCapture(){captureRequested=true;if(activePause!=null)activePause.set(true);}
    static synchronized boolean captureRequested(){return captureRequested;}
    static synchronized boolean quiescent(){return !working;}
    static synchronized void releaseCapture(){captureRequested=false;}
    static synchronized boolean cleanSlateRequested(){return cleanSlate;}
    static synchronized boolean beginCleanSlate(){
        if(cleanSlate||working||blocked()||RecordingStartGate.INSTANCE.pending()
                ||"STARTING".equals(RecorderService.getStatus())||"RECORDING".equals(RecorderService.getStatus()))return false;
        cleanSlate=true;return true;
    }
    static synchronized void removeDeletedMeeting(Context c,String id)throws IOException {
        if(!cleanSlate||working)throw new IOException("Deletion requires idle queue");
        if(id==null||!id.matches("[a-f0-9]{8}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{12}"))throw new IOException("Invalid meeting id");
        try {
            File f=index(c).getAbsoluteFile();
            for(String suffix:new String[]{"",".bak",".new"}){
                File candidate=new File(f+suffix);
                if(java.nio.file.Files.isSymbolicLink(candidate.toPath())||!candidate.equals(candidate.getCanonicalFile())
                        ||(candidate.exists()&&(!candidate.isFile()||candidate.length()>262144)))throw new IOException("Unsafe queue path");
            }
            if(!f.exists()&&!new File(f+".bak").exists()&&!new File(f+".new").exists())return;
            File backup=new File(f+".bak");
            // Parse only the chosen, preflighted file. A backup is not a second AtomicFile.
            ByteArrayOutputStream bytes=new ByteArrayOutputStream();
            try(InputStream input=new FileInputStream(backup.isFile()?backup:f)){
                byte[] buffer=new byte[4096];int n,total=0;
                while((n=input.read(buffer))!=-1){total+=n;if(total>262144)throw new IOException("Queue record exceeds limit");bytes.write(buffer,0,n);}
            }
            JSONObject record=new JSONObject(bytes.toString(StandardCharsets.UTF_8.name()));JSONArray old=record.getJSONArray("ids"),next=new JSONArray();
            if(old.length()>LIMIT)throw new IOException("Queue index exceeds bounded limit");
            for(int i=0;i<old.length();i++){String candidate=old.getString(i);if(!id.equals(candidate))next.put(candidate);}
            write(f,record.put("ids",next));
        }catch(Exception failure){throw new IOException("Meeting queue cleanup unavailable",failure);}
    }
    static synchronized long cleanSlateRevision(){return cleanSlateRevision;}
    static synchronized void endCleanSlate(){cleanSlate=false;cleanSlateRevision++;}
    static synchronized boolean blocked(){return cleanSlate||captureRequested||RecorderService.isRecording()||PopUi.capturePending;}
    /** Public static final configuration fields must be scalar constants, never identity-based objects. */
    static String configurationConstants()throws Exception {return configurationConstants(AsrConfiguration.class);}
    static String configurationConstants(Class<?> configuration)throws Exception {
        StringBuilder out=new StringBuilder();
        java.lang.reflect.Field[] fields=configuration.getFields();Arrays.sort(fields,Comparator.comparing(java.lang.reflect.Field::getName));
        for(java.lang.reflect.Field field:fields)if(java.lang.reflect.Modifier.isStatic(field.getModifiers())&&java.lang.reflect.Modifier.isFinal(field.getModifiers())){
            Class<?> type=field.getType();if(!(type.isPrimitive()||type==String.class))throw new IOException("Non-scalar ASR configuration: "+field.getName());
            out.append(field.getName()).append("=").append(String.valueOf(field.get(null))).append("\n");
        }
        return out.toString();
    }
    private static final class ConfigurationChanged extends IOException {ConfigurationChanged(String message){super(message);}}
    private static String enginePin(Context c)throws Exception {
        MessageDigest d=MessageDigest.getInstance("SHA-256");d.update(configurationConstants().getBytes(StandardCharsets.UTF_8));
        for(String path:AsrConfiguration.assetPaths()){d.update(path.getBytes(StandardCharsets.UTF_8));try(InputStream in=c.getAssets().open(path)){byte[] b=new byte[8192];int n;while((n=in.read(b))!=-1)d.update(b,0,n);}}
        StringBuilder out=new StringBuilder();for(byte b:d.digest())out.append(String.format(Locale.ROOT,"%02x",b&255));return out.toString();
    }
    /** One engine window per step; instance runner injection is used only by isolated fixtures. */
    static boolean step(Context c,Runner runner)throws Exception {
        String id=null;JSONObject s=null;File m=null;AtomicBoolean pause=new AtomicBoolean();
        synchronized(AutoTranscription.class){if(working||blocked())return false;JSONArray list=ids(c);for(int i=0;i<list.length();i++){String candidate=list.getString(i);JSONObject v=status(c,candidate);if(v!=null&&!v.optBoolean("automatic")&&!PROCESS.equals(v.optString("owner"))&&("RUNNING".equals(v.optString("status"))||"QUEUED".equals(v.optString("status")))){v.put("status","INTERRUPTED");write(new File(meeting(c,candidate),MARKER),v);continue;}if(v!=null&&ready(v)&&("QUEUED".equals(v.optString("status"))||"RUNNING".equals(v.optString("status")))){id=candidate;s=v;break;}}if(id==null)return false;m=meeting(c,id);s.remove("waitingForeground");s.remove("error");s.put("status","RUNNING").put("owner",PROCESS);write(new File(m,MARKER),s);working=true;activePause=pause;activeId=id;activeThread=Thread.currentThread();}
        try {
            String source=pin(m,!s.optBoolean("automatic"));
            synchronized(AutoTranscription.class){JSONObject latest=status(c,id);if(!latest.has("sourcePin")){latest.put("sourcePin",source);write(new File(m,MARKER),latest);}s.put("sourcePin",latest.getString("sourcePin"));}
            if(!s.getString("sourcePin").equals(source))throw new IOException("Saved source changed; original job retained");
            if(runner==ENGINE){String assets=enginePin(c);if(s.has("enginePin")&&!assets.equals(s.getString("enginePin")))throw new ConfigurationChanged("Offline engine assets changed; saved job retained");s.put("enginePin",assets);synchronized(AutoTranscription.class){JSONObject latest=status(c,id);latest.put("enginePin",assets);write(new File(m,MARKER),latest);}}
            if(runner==ENGINE&&s.has("jobId")){JSONObject prior=WindowedTranscription.inspectJob(c,m,s.getString("jobId"));if(prior==null||prior.optBoolean("historical"))throw new ConfigurationChanged("Saved engine assets/configuration changed; original job retained");}
            JSONObject result=runner.run(c,m,pause);
            synchronized(AutoTranscription.class){JSONObject latest=status(c,id);String state=result.getString("status");if(!state.startsWith("COMPLETED"))state="PAUSED".equals(latest.optString("status"))?"PAUSED":"INTERRUPTED".equals(latest.optString("status"))?"INTERRUPTED":blocked()&&!latest.optBoolean("automatic")?"INTERRUPTED":"QUEUED";latest.put("status",state).put("jobId",result.getString("jobId")).put("lastNewWindows",result.optInt("newWindows")).put("completedWindows",result.optInt("completedWindows")).put("windowCount",result.optInt("windowCount"));write(new File(m,MARKER),latest);}
        }catch(Exception e){synchronized(AutoTranscription.class){s=status(c,id);if(!"PAUSED".equals(s.optString("status"))&&!"INTERRUPTED".equals(s.optString("status")))s.put("status","ERROR");s.put("error","Offline transcription unavailable: "+e.getClass().getSimpleName()+" · "+String.valueOf(e.getMessage()));if(e instanceof ConfigurationChanged)s.put("configurationChanged",true);write(new File(m,MARKER),s);}}
        finally{synchronized(AutoTranscription.class){working=false;activePause=null;activeId=null;activeThread=null;}}
        return true;
    }
    static synchronized void cancelActive(Context c,String reason,boolean timeout,Thread owner)throws Exception {
        if(owner==null||activeThread!=owner||activePause==null)return;activePause.set(true);
        JSONObject s=status(c,activeId);if(s==null||"PAUSED".equals(s.optString("status"))||"INTERRUPTED".equals(s.optString("status")))return;
        s.put("status",timeout||!s.optBoolean("automatic")?"INTERRUPTED":"QUEUED").put("error",reason);
        write(new File(meeting(c,activeId),MARKER),s);
    }
    static synchronized void foregroundUnavailable(Context c,String reason)throws Exception {
        if(cleanSlate)return;
        JSONArray list=ids(c);for(int i=0;i<list.length();i++){String id=list.getString(i);JSONObject s=status(c,id);if(s!=null&&("QUEUED".equals(s.optString("status"))||"RUNNING".equals(s.optString("status")))){
            if(s.optBoolean("automatic")&&(!working||!id.equals(activeId)))s.put("status","QUEUED");s.put("error",reason).put("waitingForeground",true);write(new File(meeting(c,id),MARKER),s);
        }}
    }
    static synchronized boolean eligible(Context c)throws Exception{if(cleanSlate)return false;JSONArray list=ids(c);for(int i=0;i<list.length();i++){JSONObject s=status(c,list.getString(i));if(s!=null&&ready(s)&&("QUEUED".equals(s.optString("status"))||"RUNNING".equals(s.optString("status")))&&(s.optBoolean("automatic")||PROCESS.equals(s.optString("owner"))))return true;}return false;}
    static void kick(Context c){if(blocked())return;try{if(!eligible(c))return;}catch(Exception e){return;}try{c.startForegroundService(new Intent(c,AutoTranscriptionService.class));}catch(Exception e){try{foregroundUnavailable(c,"Background start unavailable; waiting for an app foreground kick.");}catch(Exception ignored){}}}
}
