package ai.codeandcraft.secondbrain;

import java.io.*;
import java.nio.file.*;
import java.util.*;

public final class CleanSlateTest {
    private static int checks;
    private static final String ID="12345678-1234-1234-1234-123456789abc";
    private static void check(boolean condition,String message){checks++;if(!condition)throw new AssertionError(message);}
    private static Path put(Path root,String path)throws Exception{Path f=root.resolve(path);Files.createDirectories(f.getParent());Files.writeString(f,"retained fixture "+path);return f;}
    private static void rejected(Path f,Path c)throws Exception{boolean bad=false;try{CleanSlate.deleteMeetingData(f.toFile(),c.toFile());}catch(IOException e){bad=true;}check(bad,"Unsafe path rejected");}
    public static void main(String[] args)throws Exception{
        Path tmp=Path.of(args[0]);Files.createDirectories(tmp);
        Path files=Files.createDirectory(tmp.resolve("files")),cache=Files.createDirectory(tmp.resolve("cache"));
        Map<Path,byte[]> preserved=new HashMap<>();
        for(String path:new String[]{"models/smollm3/SmolLM3-Q4_K_M.gguf","models/whisper-tiny-en/tiny.en-encoder.int8.onnx","models/vad/silero_vad.onnx","settings.json","diagnostics/brief.jsonl","sessions-unrelated/keep.txt"}){Path p=put(files,path);preserved.put(p,Files.readAllBytes(p));}
        for(String path:new String[]{"smolbrief/model.verified","unrelated.bin","export-other/keep.bin"}){Path p=put(cache,path);preserved.put(p,Files.readAllBytes(p));}
        for(String path:new String[]{"sessions/"+ID+"/session.json","sessions/"+ID+"/chunk-0.wav","sessions/"+ID+"/chunk-1.wav.part","sessions/"+ID+"/transcripts/t/raw.json","sessions/"+ID+"/briefs/revision.json","sessions/"+ID+"/notes/events.jsonl","sessions/orphan/no-metadata.wav","exports/a.zip","auto-transcription-index.json","auto-transcription-index.json.new","auto-transcription-index.json.bak"})put(files,path);
        put(cache,"export-"+ID+"/temporary.zip");put(cache,"smolbrief/"+ID+".prompt");put(cache,"smolbrief/"+ID+".schema.json");
        int count=CleanSlate.deleteMeetingData(files.toFile(),cache.toFile());check(count>10,"Data removed");
        check(!Files.exists(files.resolve("sessions")),"Sessions removed");check(!Files.exists(files.resolve("exports")),"Exports removed");
        for(String suffix:new String[]{"",".new",".bak"})check(!Files.exists(files.resolve("auto-transcription-index.json"+suffix)),"Queue index removed");
        check(!Files.exists(cache.resolve("export-"+ID)),"Temporary export removed");check(!Files.exists(cache.resolve("smolbrief/"+ID+".prompt")),"Prompt removed");check(!Files.exists(cache.resolve("smolbrief/"+ID+".schema.json")),"Schema removed");
        for(Map.Entry<Path,byte[]> e:preserved.entrySet())check(Arrays.equals(e.getValue(),Files.readAllBytes(e.getKey())),"Protected bytes kept: "+e.getKey());
        check(CleanSlate.deleteMeetingData(files.toFile(),cache.toFile())==0,"Repeated cleanup idempotent");
        rejected(files,files);rejected(files,files.resolve("models"));
        Path session=Files.createDirectory(files.resolve("sessions"));Path original=put(session,"kept-first.wav");
        Path outside=put(tmp,"outside/source.wav");Files.createSymbolicLink(session.resolve("linked.wav"),outside);
        rejected(files,cache);check(Files.exists(original),"Preflight prevents partial delete");check(Files.exists(outside),"Outside file protected");
        Files.delete(session.resolve("linked.wav"));Files.createSymbolicLink(session.resolve("linked-model"),files.resolve("models"));
        rejected(files,cache);check(Files.exists(files.resolve("models/smollm3/SmolLM3-Q4_K_M.gguf")),"Linked model tree protected");
        Files.delete(session.resolve("linked-model"));Files.createSymbolicLink(session.resolve("broken"),tmp.resolve("missing"));rejected(files,cache);Files.delete(session.resolve("broken"));
        Files.move(session,files.resolve("saved-sessions"));Files.createSymbolicLink(session,files.resolve("models"));rejected(files,cache);Files.delete(session);
        Files.move(files.resolve("saved-sessions"),session);
        Files.createSymbolicLink(cache.resolve("export-"+ID),tmp.resolve("outside"));rejected(files,cache);check(Files.exists(original),"Cache escape rejects before deleting sessions");Files.delete(cache.resolve("export-"+ID));
        Path deep=session;for(int i=0;i<66;i++)deep=Files.createDirectory(deep.resolve("x"));rejected(files,cache);check(Files.exists(original),"Depth limit checked before deletion");
        // Android may supply aliased parent paths: resolving trusted roots is supported.
        Path af=Files.createDirectory(tmp.resolve("alias-files")),ac=Files.createDirectory(tmp.resolve("alias-cache"));put(af,"sessions/"+ID+"/session.json");Path alias=tmp.resolve("parent-alias");Files.createSymbolicLink(alias,tmp);
        check(CleanSlate.deleteMeetingData(alias.resolve("alias-files").toFile(),alias.resolve("alias-cache").toFile())>0,"Aliased storage root supported");
        System.out.println("PASS: "+checks+" clean-slate filesystem checks");
    }
}
