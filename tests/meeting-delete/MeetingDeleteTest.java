package ai.codeandcraft.secondbrain;
import java.io.*;
import java.nio.file.*;
import java.util.*;

public final class MeetingDeleteTest {
    private static final String A="11111111-1111-1111-1111-111111111111",B="22222222-2222-2222-2222-222222222222",R="33333333-3333-3333-3333-333333333333";
    private static int checks;
    private static void ok(boolean b,String label){checks++;if(!b)throw new AssertionError(label);}
    private static Path put(Path root,String name)throws Exception{Path f=root.resolve(name);Files.createDirectories(f.getParent());Files.writeString(f,"fixture:"+name);return f;}
    private static Map<Path,byte[]> snapshot(Path root)throws Exception{Map<Path,byte[]> result=new HashMap<>();try(var stream=Files.walk(root)){for(Path p:stream.filter(Files::isRegularFile).toArray(Path[]::new))result.put(p,Files.readAllBytes(p));}return result;}
    private static void unchanged(Map<Path,byte[]> saved)throws Exception{for(var e:saved.entrySet())ok(Files.exists(e.getKey())&&Arrays.equals(e.getValue(),Files.readAllBytes(e.getKey())),"Unchanged "+e.getKey());}
    private static void reject(Path f,Path c,String id)throws Exception{boolean failed=false;int[] queue={0};try{CleanSlate.deleteMeeting(f.toFile(),c.toFile(),id,()->queue[0]++);}catch(IOException e){failed=true;}ok(failed,"Rejected unsafe deletion");ok(queue[0]==0,"Rejected before queue mutation");}
    public static void main(String[] args)throws Exception{
        Path root=Path.of(args[0]),f=Files.createDirectories(root.resolve("files")),c=Files.createDirectories(root.resolve("cache"));
        String[] sessionFiles={"session.json","chunk-0.wav","chunk-1.wav.part","transcripts/t/record.json","transcription-jobs/j/truth/source.wav","briefs/revision.json","notes/event.json","corrections/event.json"};
        for(String id:new String[]{A,B}){
            for(String n:sessionFiles)put(f,"sessions/"+id+"/"+n);
            for(String suffix:new String[]{"zip","txt","wav","zip.part","txt.part","wav.part"})put(f,"exports/"+id+"--"+R+"."+suffix);
            put(c,"export-"+id+"--"+R+"/snapshot.zip");put(c,"smolbrief/"+id+"--"+R+".prompt");put(c,"smolbrief/"+id+"--"+R+".schema.json");
        }
        Map<Path,byte[]> kept=new HashMap<>();
        for(String n:new String[]{"models/smollm3/model.gguf","models/whisper/model.onnx","models/vad/silero.onnx","settings.json"}){Path p=put(f,n);kept.put(p,Files.readAllBytes(p));}
        for(String n:new String[]{"smolbrief/model.verified","unrelated-cache.bin","export-other/keep"}){Path p=put(c,n);kept.put(p,Files.readAllBytes(p));}
        for(var e:snapshot(root).entrySet())if(e.getKey().toString().contains(B))kept.put(e.getKey(),e.getValue());
        Map<Path,byte[]> before=snapshot(root); // Cancel means no deletion API invocation; real dialog wiring is source-checked.
        unchanged(before);
        var planner=CleanSlate.class.getDeclaredMethod("meetingPlan",File.class,File.class,String.class);planner.setAccessible(true);
        @SuppressWarnings("unchecked") List<File> planned=(List<File>)planner.invoke(null,f.toFile(),c.toFile(),A);
        ok(planned.get(planned.size()-2).equals(f.resolve("sessions/"+A+"/session.json").toFile()),"Metadata removed after derivatives for retry");
        ok(planned.get(planned.size()-1).equals(f.resolve("sessions/"+A).toFile()),"Session directory removed last");
        int[] queue={0};int deleted=CleanSlate.deleteMeeting(f.toFile(),c.toFile(),A,()->queue[0]++);
        ok(deleted>10,"Target data removed");ok(queue[0]==1,"Queue filtered once");
        ok(!Files.exists(f.resolve("sessions/"+A)),"Selected session absent");
        for(String ext:new String[]{"zip","txt","wav","zip.part","txt.part","wav.part"})ok(!Files.exists(f.resolve("exports/"+A+"--"+R+"."+ext)),"Selected export removed");
        ok(!Files.exists(c.resolve("export-"+A+"--"+R)),"Selected temporary export removed");
        ok(!Files.exists(c.resolve("smolbrief/"+A+"--"+R+".prompt")),"Selected prompt removed");
        ok(!Files.exists(c.resolve("smolbrief/"+A+"--"+R+".schema.json")),"Selected schema removed");
        unchanged(kept);
        ok(CleanSlate.deleteMeeting(f.toFile(),c.toFile(),A,()->queue[0]++)==0,"Repeated deletion idempotent");
        // Fresh scan of on-disk data represents the next process's index input.
        File[] indexed=f.resolve("sessions").toFile().listFiles();ok(indexed!=null&&indexed.length==1,"Reopened index has one meeting");
        ok(SessionIndexPaths.candidate(f.resolve("sessions").toFile(),indexed[0]).getName().equals(B),"Reopened index retains only B");
        for(String id:new String[]{"", "../models", "../"+B, A+"/../"+B, A.toUpperCase(Locale.ROOT).replace('1','A'), "not-a-uuid"})reject(f,c,id);
        reject(f,c,null);
        Path target=put(f,"sessions/"+A+"/session.json");
        boolean bad=false;try{CleanSlate.deleteMeeting(f.toFile(),c.toFile(),A,()->{throw new IOException("queue failure");});}catch(IOException e){bad=true;}ok(bad&&Files.exists(target),"Queue failure retains target");
        Path legacy=put(f,"exports/"+R+".wav");reject(f,c,A);ok(Files.exists(target)&&Files.exists(legacy),"Legacy export stops before deletion");Files.delete(legacy);
        Path prompt=put(c,"smolbrief/"+R+".prompt");reject(f,c,A);Files.delete(prompt);
        Path legacyWork=Files.createDirectory(c.resolve("export-"+R));reject(f,c,A);Files.delete(legacyWork);
        Path linked=f.resolve("sessions/"+A+"/linked");Files.createSymbolicLink(linked,f.resolve("models"));reject(f,c,A);Files.delete(linked);unchanged(kept);
        Path outside=put(root,"outside/keep");Path q=f.resolve("auto-transcription-index.json.new");Files.createSymbolicLink(q,outside);reject(f,c,A);ok(Files.exists(outside),"Linked queue outside target preserved");Files.delete(q);
        Path work=c.resolve("export-"+A+"--"+R);Files.createSymbolicLink(work,root.resolve("outside"));reject(f,c,A);Files.delete(work);
        CleanSlate.deleteMeeting(f.toFile(),c.toFile(),A,()->{});unchanged(kept);
        CleanSlate.deleteMeetingData(f.toFile(),c.toFile());
        ok(!Files.exists(c.resolve("export-"+B+"--"+R)),"Full cleanup includes new owned work");
        ok(!Files.exists(c.resolve("smolbrief/"+B+"--"+R+".prompt")),"Full cleanup includes new owned prompts");
        ok(Files.exists(c.resolve("smolbrief/model.verified")),"Full cleanup keeps model verification");
        System.out.println("PASS: "+checks+" per-meeting filesystem/isolation checks");
    }
}
