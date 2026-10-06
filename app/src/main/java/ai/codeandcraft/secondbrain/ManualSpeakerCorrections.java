package ai.codeandcraft.secondbrain;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import org.json.*;

/** User assertions kept separate from immutable acoustic/transcript truth. */
public final class ManualSpeakerCorrections {
    private ManualSpeakerCorrections() { }
    private static void require(boolean ok,String message)throws IOException{if(!ok)throw new IOException(message);}
    private static void uuid(String id)throws IOException{try{require(id!=null && UUID.fromString(id).toString().equals(id),"Invalid event/meeting id");}catch(IllegalArgumentException error){throw new IOException("Invalid event/meeting id",error);}}
    private static File root(File meeting)throws Exception{uuid(meeting.getName());require(meeting.isDirectory(),"Missing meeting");File root=new File(meeting,"corrections");require(meeting.getCanonicalFile().equals(root.getCanonicalFile().getParentFile()),"Escaping corrections path");return root;}
    public static LocalAudioReference.Source source(File meeting,String filename)throws Exception{
        uuid(meeting.getName());require(filename!=null && filename.matches("chunk-[0-9]+\\.wav"),"Invalid chunk path");
        File file=new File(meeting,filename);require(meeting.getCanonicalFile().equals(file.getCanonicalFile().getParentFile()),"Escaping source path");
        require(file.isFile(),"Missing source");return LocalAudioReference.inspect(file);
    }
    private static long integer(JSONObject object,String key)throws Exception{Object v=object.get(key);require(v instanceof Integer || v instanceof Long,"Non-integer range");return ((Number)v).longValue();}
    private static String scope(JSONObject e)throws Exception{return e.getString("chunkFilename")+":"+e.getString("sourceSha256")+":"+integer(e,"startMs")+":"+integer(e,"endMs");}
    private static void validate(File meeting,JSONObject e,Map<String,LocalAudioReference.Source> cache)throws Exception{
        uuid(e.getString("eventId"));require(e.getInt("schemaVersion")==1 && "user".equals(e.getString("actorType")) && "manual".equals(e.getString("provenance")),"Invalid correction provenance");
        require(!e.has("confidence") && !e.has("acousticIdentity") && !e.has("voiceprint"),"Forbidden recognition claim");
        String file=e.getString("chunkFilename");LocalAudioReference.Source audio=cache.get(file);if(audio==null){audio=source(meeting,file);cache.put(file,audio);}
        require(audio.sha256.equals(e.getString("sourceSha256")) && integer(e,"sourceDurationMs")==audio.durationMs,"Stale source reference");
        long start=integer(e,"startMs"),end=integer(e,"endMs");require(start>=0 && start<end && end<=audio.durationMs,"Range out of bounds");
        require(integer(e,"createdUtc")>0,"Invalid event time");
        if(e.has("priorEventId"))uuid(e.getString("priorEventId"));
        if(e.has("originalSpeakerId"))require(e.getString("originalSpeakerId").matches("[A-Za-z0-9_-]{1,80}"),"Invalid original speaker reference");
        String operation=e.getString("operation");require("assignSpeaker".equals(operation)||"clearSpeaker".equals(operation),"Invalid operation");
        if("assignSpeaker".equals(operation)){String label=e.getString("displayLabel");int length=label.codePointCount(0,label.length());require(length>0&&length<=80&&!label.trim().isEmpty(),"Invalid display label");}
        else require(!e.has("displayLabel")&&e.has("priorEventId"),"Clear must supersede an assignment");
    }
    private static JSONObject read(File file)throws Exception{
        require(file.length()<=16384,"Event too large");ByteArrayOutputStream output=new ByteArrayOutputStream();
        try(FileInputStream in=new FileInputStream(file)){byte[] b=new byte[4096];int n,total=0;while((n=in.read(b))!=-1){total+=n;require(total<=16384,"Event too large");output.write(b,0,n);}}
        return new JSONObject(new String(output.toByteArray(),StandardCharsets.UTF_8));
    }
    public static synchronized JSONObject load(File meeting)throws Exception{
        File directory=root(meeting);File[] files=directory.listFiles();if(directory.exists())require(directory.isDirectory()&&files!=null,"Unable to read correction history");
        require(files==null||files.length<=256,"Correction directory limit reached");
        Map<String,JSONObject> events=new TreeMap<>();Map<String,LocalAudioReference.Source> cache=new HashMap<>();
        if(files!=null)for(File file:files){if(file.getName().endsWith(".part"))continue;
            require(file.getName().endsWith(".json")&&file.isFile()&&directory.getCanonicalFile().equals(file.getCanonicalFile().getParentFile()),"Invalid event file");
            JSONObject event=read(file);validate(meeting,event,cache);String id=event.getString("eventId");require(file.getName().equals(id+".json"),"Event filename mismatch");require(events.put(id,event)==null,"Duplicate event id");
        }
        require(events.size()<=128,"Correction event limit reached");
        Map<String,String> successor=new HashMap<>(),roots=new HashMap<>();
        for(JSONObject e:events.values()){
            String id=e.getString("eventId"),prior=e.optString("priorEventId",null),key=scope(e);
            if(prior!=null){JSONObject before=events.get(prior);require(before!=null&&scope(before).equals(key),"Invalid supersession scope");
                require(before.optString("originalSpeakerId","").equals(e.optString("originalSpeakerId","")),"Original speaker reference changed");
                require(successor.put(prior,id)==null,"Ambiguous correction history");
            }else require(roots.put(key,id)==null,"Duplicate root scope");
        }
        for(JSONObject e:events.values()){Set<String> seen=new HashSet<>();JSONObject at=e;while(at.has("priorEventId")){require(seen.add(at.getString("eventId")),"Correction cycle");at=events.get(at.getString("priorEventId"));require(at!=null,"Missing predecessor");}}
        JSONArray history=new JSONArray(),effective=new JSONArray();
        for(JSONObject e:events.values()){history.put(e);if(!successor.containsKey(e.getString("eventId")))effective.put(e);}
        // Reject conflicting overlapping current assignments. Cleared scopes remain audit-visible.
        for(int i=0;i<effective.length();i++)for(int k=i+1;k<effective.length();k++){
            JSONObject a=effective.getJSONObject(i),b=effective.getJSONObject(k);
            if(a.getString("chunkFilename").equals(b.getString("chunkFilename")) && "assignSpeaker".equals(a.getString("operation")) && "assignSpeaker".equals(b.getString("operation")))
                require(integer(a,"endMs")<=integer(b,"startMs")||integer(b,"endMs")<=integer(a,"startMs"),"Overlapping manual scopes");
        }
        return new JSONObject().put("history",history).put("effective",effective).put("provenance","manual; not speaker recognition");
    }
    public static synchronized JSONObject append(File meeting,String eventId,String filename,String expectedSha,long start,long end,String label,String prior,String originalSpeakerId)throws Exception{
        uuid(eventId);JSONObject existing=load(meeting);JSONArray history=existing.getJSONArray("history"),heads=existing.getJSONArray("effective");require(history.length()<128,"Correction event limit reached");
        LocalAudioReference.Source audio=source(meeting,filename);require(audio.sha256.equals(expectedSha),"Stale source reference");
        JSONObject event=new JSONObject().put("schemaVersion",1).put("eventId",eventId).put("createdUtc",System.currentTimeMillis()).put("actorType","user").put("provenance","manual").put("chunkFilename",filename).put("sourceSha256",audio.sha256).put("sourceDurationMs",audio.durationMs).put("startMs",start).put("endMs",end).put("operation",label==null?"clearSpeaker":"assignSpeaker");
        if(label!=null)event.put("displayLabel",label);if(prior!=null){uuid(prior);event.put("priorEventId",prior);}if(originalSpeakerId!=null)event.put("originalSpeakerId",originalSpeakerId);
        validate(meeting,event,new HashMap<>());
        boolean predecessorFound=prior==null;
        for(int i=0;i<heads.length();i++){JSONObject head=heads.getJSONObject(i);
            if(prior!=null&&prior.equals(head.getString("eventId"))){require(scope(head).equals(scope(event)),"Invalid supersession scope");require(head.optString("originalSpeakerId","").equals(event.optString("originalSpeakerId","")),"Original speaker reference changed");predecessorFound=true;}
            else if(scope(head).equals(scope(event)))throw new IOException("Supersede latest event for this scope");
            if(label!=null&&"assignSpeaker".equals(head.getString("operation"))&&filename.equals(head.getString("chunkFilename"))&&!Objects.equals(prior,head.getString("eventId")))require(end<=integer(head,"startMs")||integer(head,"endMs")<=start,"Overlapping manual scopes");
        }
        require(predecessorFound,"Supersede latest event for this scope");
        File directory=root(meeting);if(!directory.exists())require(directory.mkdir(),"Unable to create corrections directory");
        File part=new File(directory,eventId+".json.part"),finalFile=new File(directory,eventId+".json");require(!finalFile.exists()&&part.createNewFile(),"Event already reserved");
        byte[] bytes=event.toString().getBytes(StandardCharsets.UTF_8);require(bytes.length<=16384,"Event too large");
        try(FileOutputStream out=new FileOutputStream(part)){out.write(bytes);out.flush();out.getFD().sync();}
        require(!finalFile.exists()&&part.renameTo(finalFile),"Unable to commit correction");return new JSONObject(event.toString());
    }
}
