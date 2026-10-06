package ai.codeandcraft.secondbrain;
import java.io.*;import java.nio.charset.StandardCharsets;import java.security.MessageDigest;import java.util.*;import org.json.*;
/** Local user-entered notes; references identify evidence, not proof of the note's claims. */
public final class EvidenceNoteStore {
    private EvidenceNoteStore(){}
    private static void require(boolean ok,String message)throws IOException{if(!ok)throw new IOException(message);}
    private static void uuid(String id)throws Exception{require(id!=null&&UUID.fromString(id).toString().equals(id),"Invalid note/meeting id");}
    private static long integer(JSONObject object,String key)throws Exception{Object v=object.get(key);require(v instanceof Integer||v instanceof Long,"Non-integer note range");return ((Number)v).longValue();}
    private static File notes(File meeting)throws Exception{uuid(meeting.getName());require(meeting.isDirectory(),"Missing meeting");File root=new File(meeting,"notes");require(meeting.getCanonicalFile().equals(root.getCanonicalFile().getParentFile()),"Escaping notes path");return root;}
    private static String sha(File file)throws Exception{MessageDigest digest=MessageDigest.getInstance("SHA-256");try(FileInputStream input=new FileInputStream(file)){byte[] b=new byte[8192];int n;while((n=input.read(b))!=-1)digest.update(b,0,n);}StringBuilder result=new StringBuilder();for(byte b:digest.digest())result.append(String.format(Locale.ROOT,"%02x",b&255));return result.toString();}
    private static File truthDirectory(File meeting,String recordId)throws Exception{
        require(recordId!=null&&recordId.matches("[A-Za-z0-9_-]{1,80}"),"Invalid transcript record id");File transcripts=new File(meeting,"transcripts").getCanonicalFile();
        require(meeting.getCanonicalFile().equals(transcripts.getParentFile()),"Escaping transcripts path");File record=new File(transcripts,recordId).getCanonicalFile();require(transcripts.equals(record.getParentFile()),"Escaping transcript record");return record;
    }
    private static void validate(File meeting,JSONObject note)throws Exception{
        uuid(note.getString("noteId"));require(note.getInt("schemaVersion")==1&&"user_entered".equals(note.getString("provenance"))&&!note.getBoolean("claimsVerified"),"Invalid note provenance");
        String text=note.getString("text");require(!text.trim().isEmpty()&&text.codePointCount(0,text.length())<=2000,"Invalid note text");require(integer(note,"createdUtc")>0,"Invalid note time");
        JSONObject source=note.getJSONObject("source");LocalAudioReference.Source actual=ManualSpeakerCorrections.source(meeting,source.getString("chunkFilename"));
        require(actual.sha256.equals(source.getString("sha256"))&&actual.durationMs==integer(source,"durationMs"),"Stale note audio reference");long start=integer(source,"startMs"),end=integer(source,"endMs");require(start>=0&&start<end&&end<=actual.durationMs,"Note range out of bounds");
        if(note.has("transcript")){
            JSONObject pointer=note.getJSONObject("transcript");File directory=truthDirectory(meeting,pointer.getString("recordId"));JSONObject truth=TranscriptTruthStore.load(directory),truthSource=truth.getJSONObject("source");
            require(sha(new File(directory,"record.json")).equals(pointer.getString("recordSha256")),"Stale note transcript record");
            require(actual.sha256.equals(truthSource.getString("sha256"))&&truthSource.getLong("durationMs")==actual.durationMs&&integer(pointer,"sourceStartMs")==0&&integer(pointer,"sourceEndMs")==actual.durationMs,"Transcript source reference mismatch");
        }
        if(note.has("manualCorrectionEventId")){
            String id=note.getString("manualCorrectionEventId");uuid(id);JSONArray history=ManualSpeakerCorrections.load(meeting).getJSONArray("history");boolean found=false;
            for(int i=0;i<history.length();i++){JSONObject event=history.getJSONObject(i);if(id.equals(event.getString("eventId"))){require(event.getString("chunkFilename").equals(source.getString("chunkFilename"))&&event.getString("sourceSha256").equals(actual.sha256)&&event.getLong("startMs")==start&&event.getLong("endMs")==end,"Manual context scope mismatch");found=true;}}
            require(found,"Missing manual correction reference");
        }
    }
    private static JSONObject read(File file)throws Exception{require(file.length()<=16384,"Note too large");ByteArrayOutputStream output=new ByteArrayOutputStream();try(FileInputStream in=new FileInputStream(file)){byte[] bytes=new byte[4096];int n,total=0;while((n=in.read(bytes))!=-1){total+=n;require(total<=16384,"Note too large");output.write(bytes,0,n);}}return new JSONObject(new String(output.toByteArray(),StandardCharsets.UTF_8));}
    public static synchronized JSONArray load(File meeting)throws Exception{
        File root=notes(meeting);File[] files=root.listFiles();if(root.exists())require(root.isDirectory()&&files!=null,"Unable to load notes");require(files==null||files.length<=256,"Notes directory limit");
        ArrayList<JSONObject> accepted=new ArrayList<>();if(files!=null)for(File file:files){if(file.getName().endsWith(".part"))continue;require(file.isFile()&&file.getName().endsWith(".json")&&root.getCanonicalFile().equals(file.getCanonicalFile().getParentFile()),"Invalid note file");JSONObject note=read(file);require(file.getName().equals(note.getString("noteId")+".json"),"Note filename mismatch");validate(meeting,note);accepted.add(note);}
        require(accepted.size()<=128,"Note count limit");accepted.sort(Comparator.comparing(n->n.optString("noteId")));JSONArray result=new JSONArray();for(JSONObject note:accepted)result.put(note);return result;
    }
    public static synchronized JSONObject append(File meeting,String noteId,String filename,String expectedSha,long start,long end,String text,String recordId,String correctionId)throws Exception{
        uuid(noteId);require(load(meeting).length()<128,"Note count limit");LocalAudioReference.Source audio=ManualSpeakerCorrections.source(meeting,filename);require(audio.sha256.equals(expectedSha),"Stale note audio reference");
        JSONObject source=new JSONObject().put("chunkFilename",filename).put("sha256",audio.sha256).put("durationMs",audio.durationMs).put("startMs",start).put("endMs",end);
        JSONObject note=new JSONObject().put("schemaVersion",1).put("noteId",noteId).put("createdUtc",System.currentTimeMillis()).put("provenance","user_entered").put("claimsVerified",false).put("text",text).put("source",source).put("referenceMeaning","User-selected audio range; no word alignment or verified entailment.");
        if(recordId!=null){File record=truthDirectory(meeting,recordId);note.put("transcript",new JSONObject().put("recordId",recordId).put("recordSha256",sha(new File(record,"record.json"))).put("sourceStartMs",0).put("sourceEndMs",audio.durationMs));}
        if(correctionId!=null)note.put("manualCorrectionEventId",correctionId);validate(meeting,note);
        File directory=notes(meeting);if(!directory.exists())require(directory.mkdir(),"Unable to create notes directory");File part=new File(directory,noteId+".json.part"),finalFile=new File(directory,noteId+".json");require(!finalFile.exists()&&part.createNewFile(),"Note already reserved");byte[] bytes=note.toString().getBytes(StandardCharsets.UTF_8);require(bytes.length<=16384,"Note too large");
        try(FileOutputStream out=new FileOutputStream(part)){out.write(bytes);out.flush();out.getFD().sync();}require(!finalFile.exists()&&part.renameTo(finalFile),"Unable to commit note");return new JSONObject(note.toString());
    }
}
