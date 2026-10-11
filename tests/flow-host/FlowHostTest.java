package ai.codeandcraft.secondbrain;
import java.io.*;import java.nio.file.*;import java.util.*;import java.util.concurrent.atomic.AtomicBoolean;import org.json.*;import android.content.Context;
public class FlowHostTest {
 static int checks; static void check(boolean b,String label){checks++;if(!b)throw new AssertionError(label);}
 interface Checked {void run()throws Exception;} static void rejected(Checked r,String label)throws Exception{try{r.run();}catch(Exception expected){checks++;return;}throw new AssertionError(label);}
 static JSONObject read(File f)throws Exception{return new JSONObject(Files.readString(f.toPath()));}
 static void write(File f,JSONObject j)throws Exception{Files.writeString(f.toPath(),j.toString());}
 public static void main(String[] args)throws Exception{
  File files=new File(args[1]);files.mkdirs();Context c=new Context(files);AtomicBoolean cancel=new AtomicBoolean();File pointer=new File(files,"meeting.txt");
  if(args[0].equals("write")){
   SessionStore session=SessionStore.create(files,16000);File m=session.directory();Files.writeString(pointer.toPath(),m.getName());
   check(read(new File(m,"session.json")).getString("status").equals("ACTIVE"),"active");
   byte[] pcm=new byte[32000];for(int i=0;i<pcm.length;i+=2){short v=(short)(Math.sin(i*.09)*9000);pcm[i]=(byte)v;pcm[i+1]=(byte)(v>>8);}
   WavChunkWriter writer=new WavChunkWriter(m,session.nextChunkIndex(),16000);writer.write(pcm,pcm.length);writer.checkpoint();check(new File(m,"chunk-000000.wav.part").exists(),"partial exists");
   File wav=writer.finish();session.complete();check(!new File(m,"chunk-000000.wav.part").exists(),"final rename");check(read(new File(m,"session.json")).getLong("totalDurationMillis")==1000,"duration");
   PcmWavReader.Audio audio=PcmWavReader.read(wav);check(audio.samples.length==16000,"PCM read");
   String jobId=UUID.randomUUID().toString();File job=new File(m,"transcription-jobs/"+jobId);check(job.mkdirs(),"job dir");
   String text="The release remains on hold. Testing must finish before a release decision.";
   JSONObject asr=new JSONObject().put("audioSha256",audio.sha256).put("durationMs",1000).put("startMs",0).put("endMs",1000).put("text",text);
   File truth=new File(job,"window-000000");TranscriptTruthStore.persist(truth,wav,asr);check(TranscriptTruthStore.load(truth).getJSONObject("asr").getString("text").equals(text),"truth persisted");
   JSONObject window=new JSONObject().put("startSample",0).put("endSample",16000);
   JSONObject output=new JSONObject().put("truthDirectory",truth.getName()).put("window",window).put("sliceSha256",audio.sha256).put("text",text);
   write(new File(m,"host-native-job.json"),new JSONObject().put("jobId",jobId).put("status","COMPLETED").put("boundaryReviewRequired",false).put("windows",new JSONArray().put(output)));
   JSONObject draft=MeetingBriefEngine.generate(new MeetingBriefEngine.SourceDraft(),c,m,cancel);check(draft.getString("status").equals("DRAFT"),"extractive draft");check(draft.getJSONArray("items").length()==2,"both sentences");check(!draft.getBoolean("claimsVerified"),"not certified");
   JSONObject saved=MeetingBriefStore.append(c,m,draft,null,cancel);check(saved.getLong("sequence")==1,"first version");
   rejected(()->MeetingBriefStore.append(c,m,draft,null,cancel),"stale parent rejected");cancel.set(true);rejected(()->MeetingBriefStore.append(c,m,draft,saved.getString("versionId"),cancel),"cancel rejected");cancel.set(false);
   RecorderService.recording=true;rejected(()->MeetingBriefStore.append(c,m,draft,saved.getString("versionId"),cancel),"recording rejected");RecorderService.recording=false;
   JSONObject bad=new JSONObject(draft.toString());bad.getJSONArray("items").getJSONObject(0).put("text","Unsupported different claim");rejected(()->MeetingBriefStore.append(c,m,bad,saved.getString("versionId"),cancel),"mismatched quote rejected");
   JSONObject next=MeetingBriefStore.append(c,m,draft,saved.getString("versionId"),cancel);check(next.getLong("sequence")==2,"second version");
   JSONObject state=read(new File(m,"session.json"));state.put("status","ACTIVE");write(new File(m,"session.json"),state);rejected(()->MeetingBriefStore.append(c,m,draft,next.getString("versionId"),cancel),"active session rejected");state.put("status","COMPLETED");write(new File(m,"session.json"),state);
   JSONObject jobState=read(new File(m,"host-native-job.json"));jobState.put("boundaryReviewRequired",true);write(new File(m,"host-native-job.json"),jobState);check(MeetingBriefEngine.generate(new MeetingBriefEngine.SourceDraft(),c,m,cancel).getString("status").equals("BOUNDARY_REVIEW_REQUIRED"),"overlap gated");jobState.put("boundaryReviewRequired",false);write(new File(m,"host-native-job.json"),jobState);
  }else{
   File m=new File(files,"sessions/"+Files.readString(pointer.toPath()));JSONArray saved=MeetingBriefStore.load(c,m);check(saved.length()==2,"fresh-process reopen versions");check(saved.getJSONObject(1).getLong("sequence")==2,"revision chain");check(saved.getJSONObject(1).getString("parentVersionId").equals(saved.getJSONObject(0).getString("versionId")),"parent link");
   JSONObject job=read(new File(m,"host-native-job.json"));File truth=new File(m,"transcription-jobs/"+job.getString("jobId")+"/window-000000");check(TranscriptTruthStore.load(truth).getJSONObject("asr").getString("text").contains("remains on hold"),"transcript reopen");check(PcmWavReader.read(new File(m,"chunk-000000.wav")).durationMs==1000,"audio reopen");
   if(args[0].equals("tamper")){File rec=new File(truth,"record.json");JSONObject altered=read(rec);altered.getJSONObject("asr").put("text","Changed transcript.");write(rec,altered);rejected(()->MeetingBriefStore.load(c,m),"record hash tamper rejected");File audio=new File(truth,"source.wav");try(RandomAccessFile f=new RandomAccessFile(audio,"rw")){f.seek(45);f.write(99);}rejected(()->TranscriptTruthStore.load(truth),"audio hash tamper rejected");}
  }
  System.out.println("PASS: "+args[0]+" "+checks+" host flow checks (native ASR mocked; no microphone/Android/native inference execution)");
 }
}
