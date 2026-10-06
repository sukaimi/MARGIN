package ai.codeandcraft.secondbrain;

import android.content.Context;
import android.system.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import org.json.*;

/** Append-only derivative revisions. Citation integrity never certifies edited-text entailment. */
public final class MeetingBriefStore {
    private MeetingBriefStore() {}
    public static final int MAX_BYTES=262144, MAX_VERSIONS=128;
    private static void require(boolean ok) throws IOException { if(!ok) throw new IOException("BRIEF_INVALID"); }
    private static void uuid(String id) throws Exception { require(UUID.fromString(id).toString().equals(id)); }
    private static File child(File root,String name) throws Exception {
        File file=new File(root,name).getAbsoluteFile();
        require(file.equals(file.getCanonicalFile()) && root.getCanonicalFile().equals(file.getParentFile())); return file;
    }
    private static File root(Context context,File meeting) throws Exception {
        uuid(meeting.getName()); require(meeting.isDirectory() && meeting.getAbsoluteFile().equals(meeting.getCanonicalFile()));
        require(meeting.getCanonicalPath().startsWith(context.getFilesDir().getCanonicalPath()+File.separator));
        return child(meeting,"briefs");
    }
    private static void completed(File meeting) throws Exception {
        JSONObject session=read(child(meeting,"session.json"));
        require(Arrays.asList("COMPLETED","RECOVERED_INTERRUPTED").contains(session.optString("status")));
        JSONArray pending=session.optJSONArray("pending"),chunks=session.optJSONArray("chunks");
        require((pending==null||pending.length()==0)&&chunks!=null&&chunks.length()>0&&chunks.length()<=32);
        File[] partial=meeting.listFiles((d,n)->n.endsWith(".wav.part"));require(partial!=null&&partial.length==0);
    }
    private static JSONObject read(File file) throws Exception {
        require(file.isFile() && file.length()<=MAX_BYTES);
        try(InputStream in=new FileInputStream(file);ByteArrayOutputStream out=new ByteArrayOutputStream()) {
            byte[] b=new byte[4096]; int n,total=0;
            while((n=in.read(b))!=-1){total+=n;require(total<=MAX_BYTES);out.write(b,0,n);}
            return new JSONObject(out.toString("UTF-8"));
        }
    }
    private static long integer(JSONObject value,String key) throws Exception {
        Object n=value.get(key); require(n instanceof Integer || n instanceof Long); return ((Number)n).longValue();
    }
    private static void citation(Context context,File meeting,JSONObject citation,Map<String,JSONObject> jobs) throws Exception {
        JSONObject anchor=citation.getJSONObject("anchor"); String id=anchor.getString("jobId");uuid(id);
        JSONObject job=jobs.get(id);
        if(job==null){job=WindowedTranscription.inspectJob(context,meeting,id);require(job!=null && "COMPLETED".equals(job.getString("status")) && !job.optBoolean("boundaryReviewRequired"));require(job.getJSONArray("windows").length()<=64);jobs.put(id,job);}
        JSONObject accepted=null;JSONArray windows=job.getJSONArray("windows");
        for(int i=0;i<windows.length();i++){JSONObject w=windows.getJSONObject(i);if(w.getString("truthDirectory").equals(anchor.getString("truthDirectory"))){accepted=w;break;}}
        require(accepted!=null);
        require(AsrConfiguration.canonical(accepted.getJSONObject("window")).equals(AsrConfiguration.canonical(anchor.getJSONObject("window"))));
        File jobsRoot=child(meeting,"transcription-jobs"), directory=child(jobsRoot,id), truth=child(directory,anchor.getString("truthDirectory"));
        require(SourceBundle.sha(child(truth,"record.json")).equals(anchor.getString("recordSha256")));
        require(accepted.getString("sliceSha256").equals(anchor.getString("sliceSha256")));
        String raw=accepted.getString("text"),quote=citation.getString("quote");
        long a=integer(citation,"rawTextCharStart"),b=integer(citation,"rawTextCharEnd");
        require(a>=0 && b>a && b<=raw.length() && b-a<=2000 && raw.substring((int)a,(int)b).equals(quote));
    }
    private static void validate(Context context,File meeting,JSONObject draft,Map<String,JSONObject> jobs) throws Exception {
        require(draft.getInt("schemaVersion")==1 && "DRAFT".equals(draft.getString("status")) && !draft.getBoolean("claimsVerified"));
        require(draft.getString("engine").matches("[a-z0-9-]{1,64}"));
        require(Arrays.asList("EXACT_EXCERPT","AI_DRAFT","USER_ENTERED").contains(draft.getString("provenance")));
        JSONArray items=draft.getJSONArray("items");require(items.length()<=64);int excerpts=0;
        for(int i=0;i<items.length();i++){
            JSONObject item=items.getJSONObject(i);String section=item.getString("section"),text=item.getString("text"),p=item.getString("provenance");
            require(Arrays.asList("summary","decisions","actions","questions","risks").contains(section));
            require(!text.trim().isEmpty() && text.length()<=2000 && Arrays.asList("EXACT_EXCERPT","USER_ENTERED","AI_DRAFT").contains(p));
            require(item.get("done") instanceof Boolean && item.has("owner") && item.has("date"));
            if(!"USER_ENTERED".equals(p))require(!item.getBoolean("done") && item.isNull("owner") && item.isNull("date"));
            for(String key:new String[]{"owner","date"}) if(!item.isNull(key)) {
                require("USER_ENTERED".equals(p) && "USER_ENTERED_ONLY".equals(item.getString("ownerDateProvenance")));
                require(item.getString(key).length()<=120);
            }
            JSONArray citations=item.getJSONArray("citations");require(citations.length()<=4);
            if(citations.length()==0)require("USER_ENTERED".equals(p) && "MANUAL_UNSUPPORTED".equals(item.getString("evidenceStatus")));
            for(int j=0;j<citations.length();j++)citation(context,meeting,citations.getJSONObject(j),jobs);
            if("EXACT_EXCERPT".equals(p)){require("summary".equals(section) && citations.length()==1 && text.equals(citations.getJSONObject(0).getString("quote")));excerpts++;}
        }
        require(excerpts<=4);
    }
    public static synchronized JSONArray load(Context context,File meeting) throws Exception {
        File root=root(context,meeting);File[] files=root.listFiles();require(!root.exists() || (root.isDirectory() && files!=null));require(files==null || files.length<=MAX_VERSIONS*2);
        ArrayList<JSONObject> values=new ArrayList<>();Map<String,JSONObject> jobs=new HashMap<>();
        if(files!=null)for(File file:files){if(file.getName().endsWith(".part"))continue;require(file.getName().endsWith(".json"));JSONObject v=read(child(root,file.getName()));uuid(v.getString("versionId"));require(file.getName().equals(v.getString("versionId")+".json"));validate(context,meeting,v,jobs);values.add(v);}
        require(values.size()<=MAX_VERSIONS);values.sort(Comparator.comparingLong(v->v.optLong("sequence")));
        JSONArray result=new JSONArray();String parent=null;long sequence=0;
        for(JSONObject v:values){require(integer(v,"sequence")==++sequence);require(parent==null?v.isNull("parentVersionId"):parent.equals(v.getString("parentVersionId")));parent=v.getString("versionId");result.put(v);}
        return result;
    }
    public static synchronized JSONObject append(Context context,File meeting,JSONObject draft,String expectedParent,AtomicBoolean cancel) throws Exception {
        AtomicBoolean token=cancel==null?new AtomicBoolean():cancel;
        require(!token.get() && !Thread.currentThread().isInterrupted() && !RecorderService.isRecording());
        require(draft.toString().getBytes(StandardCharsets.UTF_8).length<=MAX_BYTES);completed(meeting);JSONArray versions=load(context,meeting);require(versions.length()<MAX_VERSIONS);
        String parent=versions.length()==0?null:versions.getJSONObject(versions.length()-1).getString("versionId");require(Objects.equals(parent,expectedParent));
        JSONObject value=new JSONObject(draft.toString());validate(context,meeting,value,new HashMap<>());
        String id=UUID.randomUUID().toString();value.put("versionId",id).put("parentVersionId",parent==null?JSONObject.NULL:parent).put("sequence",versions.length()+1);
        byte[] bytes=value.toString().getBytes(StandardCharsets.UTF_8);require(bytes.length<=MAX_BYTES);File root=root(context,meeting);if(!root.exists())require(root.mkdir());
        File part=child(root,id+".json.part"),target=child(root,id+".json");require(part.createNewFile());
        try {
            try(FileOutputStream out=new FileOutputStream(part)){out.write(bytes);out.flush();out.getFD().sync();}
            synchronized(token){require(!token.get() && !Thread.currentThread().isInterrupted());require(!target.exists() && part.renameTo(target));}
            FileDescriptor fd=Os.open(root.getPath(),OsConstants.O_RDONLY,0);try{Os.fsync(fd);}finally{Os.close(fd);}
            return value;
        } finally { if(part.exists())part.delete(); }
    }
}
