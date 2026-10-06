package ai.codeandcraft.secondbrain;
import android.content.Context;import android.os.SystemClock;import android.util.AtomicFile;import java.io.*;import java.nio.*;import java.nio.charset.StandardCharsets;import java.security.MessageDigest;import java.util.*;import org.json.*;

/** Serialized offline saved-audio jobs. Source windows, never word timestamps. */
public final class WindowedTranscription {
    private WindowedTranscription(){}
    private static final int RATE=AsrConfiguration.SOURCE_SAMPLE_RATE,MAX=AsrConfiguration.MAX_WINDOW_SAMPLES,TARGET=AsrConfiguration.TARGET_WINDOW_SAMPLES,MAX_WINDOWS=AsrConfiguration.MAX_WINDOWS,MAX_RAW_WINDOW_CHARS=16384,MAX_TOTAL_CHARS=262144;
    private static final class Limit extends IOException{Limit(String message){super(message);}}
    private static void require(boolean ok,String message)throws IOException{if(!ok)throw new IOException(message);}
    private static String hex(byte[] bytes){StringBuilder out=new StringBuilder();for(byte b:bytes)out.append(String.format(Locale.ROOT,"%02x",b&255));return out.toString();}
    private static String digest(InputStream input)throws Exception{MessageDigest hash=MessageDigest.getInstance("SHA-256");byte[] bytes=new byte[8192];int n;while((n=input.read(bytes))!=-1)hash.update(bytes,0,n);return hex(hash.digest());}
    private static JSONObject read(File file,int max)throws Exception{require(file.isFile()&&file.length()<=max,"Missing/oversized job record");byte[] bytes=new AtomicFile(file).readFully();require(bytes.length<=max,"Oversized job record");return new JSONObject(new String(bytes,StandardCharsets.UTF_8));}
    private static void atomic(File file,JSONObject json)throws Exception{byte[] bytes=json.toString().getBytes(StandardCharsets.UTF_8);require(bytes.length<=1048576,"Job record exceeds1MiB");AtomicFile atomic=new AtomicFile(file);FileOutputStream output=atomic.startWrite();try{output.write(bytes);atomic.finishWrite(output);}catch(Exception error){atomic.failWrite(output);throw error;}}
    private static JSONObject source(Context context,File meeting)throws Exception{
        require(UUID.fromString(meeting.getName()).toString().equals(meeting.getName()),"Invalid meeting id");JSONObject meta=read(new File(meeting,"session.json"),262144);JSONArray input=meta.getJSONArray("chunks"),chunks=new JSONArray();require(input.length()>0&&input.length()<=AsrConfiguration.MAX_SOURCE_CHUNKS,"Expected1–512 completed chunks");long offset=0;Set<String> names=new HashSet<>();
        for(int i=0;i<input.length();i++){String name=input.getJSONObject(i).getString("filename");require(names.add(name),"Duplicate source chunk");LocalAudioReference.Source audio=ManualSpeakerCorrections.source(meeting,name);File file=new File(meeting,name);long dataOffset=dataOffset(file),samples=dataBytes(file)/2;require(samples>0,"Empty source chunk");chunks.put(new JSONObject().put("filename",name).put("sha256",audio.sha256).put("sampleCount",samples).put("dataOffset",dataOffset).put("meetingStartSample",offset));offset+=samples;}
        JSONArray models=new JSONArray();for(String path:AsrConfiguration.assetPaths())try(InputStream inputModel=context.getAssets().open(path)){models.put(new JSONObject().put("path",path).put("sha256",digest(inputModel)));}
        JSONObject descriptor=AsrConfiguration.descriptor(chunks,models,offset);
        return new JSONObject().put("schemaVersion",AsrConfiguration.DESCRIPTOR_SCHEMA_VERSION).put("config",AsrConfiguration.CONFIG_LABEL).put("descriptor",descriptor).put("chunks",new JSONArray(descriptor.getJSONObject("source").getJSONArray("chunks").toString())).put("models",new JSONArray(descriptor.getJSONArray("assets").toString())).put("totalSamples",offset).put("fingerprint",AsrConfiguration.fingerprint(descriptor));
    }
    private static long dataOffset(File file)throws Exception{try(RandomAccessFile input=new RandomAccessFile(file,"r")){long at=12;while(at+8<=input.length()){input.seek(at);byte[] bytes=new byte[8];input.readFully(bytes);long size=Integer.toUnsignedLong(ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).getInt(4));if("data".equals(new String(bytes,0,4,StandardCharsets.US_ASCII)))return at+8;at+=8+size+(size%2);}throw new IOException("Missing PCM data");}}
    private static long dataBytes(File file)throws Exception{try(RandomAccessFile input=new RandomAccessFile(file,"r")){input.seek(dataOffset(file)-4);return Integer.toUnsignedLong(Integer.reverseBytes(input.readInt()));}}
    private static byte[] header(long samples){ByteBuffer b=ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN);int size=Math.toIntExact(samples*2);b.put("RIFF".getBytes(StandardCharsets.US_ASCII)).putInt(36+size).put("WAVEfmt ".getBytes(StandardCharsets.US_ASCII)).putInt(16).putShort((short)1).putShort((short)1).putInt(RATE).putInt(RATE*2).putShort((short)2).putShort((short)16).put("data".getBytes(StandardCharsets.US_ASCII)).putInt(size);return b.array();}
    private static JSONArray parts(JSONArray chunks,long start,long end)throws Exception {
        JSONArray result=new JSONArray();long total=0;
        for(int i=0;i<chunks.length();i++){JSONObject c=chunks.getJSONObject(i);long base=c.getLong("meetingStartSample"),limit=base+c.getLong("sampleCount"),a=Math.max(base,start),b=Math.min(limit,end);if(b>a){result.put(new JSONObject().put("chunkFilename",c.getString("filename")).put("parentSourceSha256",c.getString("sha256")).put("startSample",a-base).put("endSample",b-base).put("meetingStartSample",a).put("meetingEndSample",b).put("dataOffset",c.getLong("dataOffset")));total+=b-a;}}
        require(total==end-start,"Virtual source has a gap");return result;
    }
    private static String virtualSlice(File meeting,JSONArray chunks,long start,long end,File output)throws Exception {return virtualSlice(meeting,chunks,start,end,output,MAX);}
    private static String virtualSlice(File meeting,JSONArray chunks,long start,long end,File output,long maxSamples)throws Exception {
        require(start>=0&&end>start&&end-start<=maxSamples,"Invalid virtual bounded window");MessageDigest hash=MessageDigest.getInstance("SHA-256");byte[] header=header(end-start);hash.update(header);JSONArray segments=parts(chunks,start,end);FileOutputStream out=output==null?null:new FileOutputStream(output);
        try{if(out!=null)out.write(header);for(int i=0;i<segments.length();i++){JSONObject segment=segments.getJSONObject(i);try(RandomAccessFile input=new RandomAccessFile(new File(meeting,segment.getString("chunkFilename")),"r")){input.seek(segment.getLong("dataOffset")+segment.getLong("startSample")*2);long remaining=(segment.getLong("endSample")-segment.getLong("startSample"))*2;byte[] bytes=new byte[8192];while(remaining>0){int n=(int)Math.min(remaining,bytes.length);input.readFully(bytes,0,n);hash.update(bytes,0,n);if(out!=null)out.write(bytes,0,n);remaining-=n;}}}if(out!=null){out.flush();out.getFD().sync();}}finally{if(out!=null)out.close();}return hex(hash.digest());
    }
    private static void validatePlan(JSONObject plan,JSONObject snapshot)throws Exception {validatePlan(plan,snapshot,currentLimits());}
    private static long[] currentLimits(){return new long[]{MAX,MAX_WINDOWS,AsrConfiguration.MAX_OVERLAP_SAMPLES};}
    /** Planning limits a stored job was made under: schema 1 used fixed 400000/512/80000; later schemas store them in their descriptor. */
    private static long[] storedLimits(JSONObject stored)throws Exception {int version=stored.getInt("schemaVersion");if(version==1)return new long[]{400000,512,80000};require(version==AsrConfiguration.DESCRIPTOR_SCHEMA_VERSION,"Unsupported stored job schema");return AsrConfiguration.storedPlanning(stored.getJSONObject("descriptor"));}
    private static void validatePlan(JSONObject plan,JSONObject snapshot,long[] limits)throws Exception {
        final long maxSamples=limits[0],maxWindows=limits[1],maxOverlap=limits[2];
        require(AsrConfiguration.canonical(plan.getJSONObject("source")).equals(AsrConfiguration.canonical(snapshot)),"Plan source/model provenance changed");JSONArray windows=plan.getJSONArray("windows");require(windows.length()>0&&windows.length()<=maxWindows,"Invalid plan window count");long covered=0;
        for(int i=0;i<windows.length();i++){JSONObject w=windows.getJSONObject(i);long a=w.getLong("startSample"),b=w.getLong("endSample");require(w.getInt("index")==i&&a>=0&&a<=covered&&covered-a<=maxOverlap&&b>a&&b-a<=maxSamples&&b>covered&&b<=snapshot.getLong("totalSamples")&&w.getLong("meetingStartSample")==a&&w.getLong("meetingEndSample")==b,"Invalid original plan range");require(w.getJSONArray("sourceParts").toString().equals(parts(snapshot.getJSONArray("chunks"),a,b).toString()),"Original source-part mapping changed");covered=b;}
        require(covered==snapshot.getLong("totalSamples"),"Plan does not cover source");
    }
    private static JSONObject plan(Context context,File meeting,File job,JSONObject snapshot)throws Exception{
        File planFile=new File(job,"plan.json");if(planFile.isFile()){JSONObject p=read(planFile,1048576);require(p.getString("fingerprint").equals(snapshot.getString("fingerprint")),"Job source/model changed");validatePlan(p,snapshot);return p;}
        JSONArray windows=new JSONArray(),chunks=snapshot.getJSONArray("chunks");
        long samples=snapshot.getLong("totalSamples"),at=0,previousEnd=0;
        while(at<samples){require(windows.length()<MAX_WINDOWS,"Job window limit");long proposed=Math.min(samples,at+MAX),end=proposed,next=proposed;String boundary="source_end";
            if(proposed<samples){File scan=new File(job,"scan-"+UUID.randomUUID()+".wav");virtualSlice(meeting,chunks,at,proposed,scan);JSONObject vad=SileroSpeechGate.detect(context,PcmWavReader.read(scan).samples);JSONArray ranges=vad.getJSONArray("ranges");long last=0,best=-1,distance=Long.MAX_VALUE;
                for(int k=0;k<=ranges.length();k++){long begin=k==ranges.length()?MAX:ranges.getJSONObject(k).getLong("startSample");long safeStart=Math.max(last+AsrConfiguration.CUT_MARGIN_SAMPLES,AsrConfiguration.MIN_CUT_SAMPLES),safeEnd=Math.min(begin-AsrConfiguration.CUT_MARGIN_SAMPLES,AsrConfiguration.MAX_CUT_SAMPLES);if(begin-last>=AsrConfiguration.MIN_SILENCE_GAP_SAMPLES&&safeStart<=safeEnd){long candidate=Math.max(safeStart,Math.min(TARGET,safeEnd));long d=Math.abs(candidate-TARGET);if(d<distance){best=candidate;distance=d;}}if(k<ranges.length())last=Math.max(last,ranges.getJSONObject(k).getLong("endSample"));}
                if(best>0){end=at+best;next=end;boundary="vad_silence_estimate";}else{end=proposed;next=at+TARGET;boundary="overlap_needs_review";}
                require(scan.delete(),"Unable to remove derived planning scratch");
            }
            windows.put(new JSONObject().put("index",windows.length()).put("startSample",at).put("endSample",end).put("meetingStartSample",at).put("meetingEndSample",end).put("sourceParts",parts(chunks,at,end)).put("overlapSamples",Math.max(0,previousEnd-at)).put("boundaryKind",boundary));previousEnd=end;at=next;
        }

        JSONObject p=new JSONObject().put("schemaVersion",1).put("fingerprint",snapshot.getString("fingerprint")).put("source",snapshot).put("windows",windows);validatePlan(p,snapshot);atomic(planFile,p);return p;
    }
    private static JSONObject accepted(File meeting,File job,JSONObject window)throws Exception{return accepted(meeting,job,window,MAX);}
    private static JSONObject accepted(File meeting,File job,JSONObject window,long maxSamples)throws Exception{
        int index=window.getInt("index");File pointer=new File(job,String.format(Locale.ROOT,"accepted-%04d.json",index));if(!pointer.exists())return null;
        JSONObject output=read(pointer,262144);require(output.getJSONObject("window").toString().equals(window.toString()),"Stored original window range mismatch");String directory=output.getString("truthDirectory");require(directory.matches("window-[0-9]+-[a-f0-9-]{36}"),"Invalid truth directory pointer");
        File truthDirectory=new File(job,directory);require(job.getCanonicalFile().equals(truthDirectory.getCanonicalFile().getParentFile()),"Escaping truth pointer");JSONObject truth=TranscriptTruthStore.load(truthDirectory);String expected=virtualSlice(meeting,read(new File(job,"plan.json"),1048576).getJSONObject("source").getJSONArray("chunks"),window.getLong("startSample"),window.getLong("endSample"),null,maxSamples);
        require(expected.equals(truth.getJSONObject("source").getString("sha256"))&&expected.equals(output.getString("sliceSha256")),"Accepted window differs from original PCM");JSONObject raw=truth.getJSONObject("asr");require(raw.getString("text").length()<=MAX_RAW_WINDOW_CHARS,"Transcript window exceeds16384 characters; raw evidence retained");output.put("text",raw.getString("text")).put("timestampsCount",raw.getJSONArray("timestamps").length()).put("asrSkipped",raw.getBoolean("asrSkipped")).put("totalPssKb",raw.getLong("totalPssKb"));return output;
    }
    public static synchronized JSONObject run(Context context,File meeting,int maxNewWindows)throws Exception{return run(context,meeting,maxNewWindows,null);}
    public static synchronized JSONObject run(Context context,File meeting,int maxNewWindows,java.util.concurrent.atomic.AtomicBoolean pause)throws Exception{
        require(!RecorderService.isRecording(),"Cannot transcribe while recording");require(maxNewWindows>0,"Invalid work budget");long begin=SystemClock.elapsedRealtime();JSONObject snapshot=source(context,meeting);String id=AsrConfiguration.jobId(snapshot.getString("fingerprint"));File jobs=new File(meeting,"transcription-jobs"),job=new File(jobs,id);require(meeting.getCanonicalFile().equals(jobs.getCanonicalFile().getParentFile())&&jobs.getCanonicalFile().equals(job.getCanonicalFile().getParentFile()),"Escaping job path");if(!job.isDirectory())require(job.mkdirs(),"Unable to create job");JSONObject plan=plan(context,meeting,job,snapshot);JSONArray windows=plan.getJSONArray("windows"),outputs=new JSONArray();int newCount=0,skipped=0;
        atomic(new File(job,"progress.json"),new JSONObject().put("status","RUNNING").put("sourceFingerprint",snapshot.getString("fingerprint")).put("windowCount",windows.length()).put("updatedUtc",System.currentTimeMillis()));
        for(int i=0;i<windows.length();i++){
            JSONObject window=windows.getJSONObject(i),output=accepted(meeting,job,window);
            if(output!=null){outputs.put(output);skipped++;continue;}
            if(newCount>=maxNewWindows||Thread.currentThread().isInterrupted()||(pause!=null&&pause.get()))break;
            String attempt=UUID.randomUUID().toString();File scratch=new File(job,"input-"+i+"-"+attempt+".wav");String sha=virtualSlice(meeting,snapshot.getJSONArray("chunks"),window.getLong("startSample"),window.getLong("endSample"),scratch);
            atomic(new File(job,"progress.json"),new JSONObject().put("status","RUNNING").put("stage","DECODING").put("currentWindow",i).put("completedWindows",outputs.length()).put("windowCount",windows.length()).put("sourceFingerprint",snapshot.getString("fingerprint")));
            JSONObject raw=OfflineAsr.transcribe(context,scratch);require(raw.getString("text").length()<=MAX_RAW_WINDOW_CHARS,"Transcript window exceeds16384 characters");require(sha.equals(raw.getString("audioSha256")),"ASR window hash mismatch");File truthDir=new File(job,"window-"+i+"-"+attempt);TranscriptTruthStore.persist(truthDir,scratch,raw);
            output=new JSONObject().put("window",window).put("truthDirectory",truthDir.getName()).put("sliceSha256",sha);File target=new File(job,String.format(Locale.ROOT,"accepted-%04d.json",i));require(!target.exists(),"Window already accepted");File part=new File(job,target.getName()+"-"+attempt+".part");byte[] bytes=output.toString().getBytes(StandardCharsets.UTF_8);try(FileOutputStream out=new FileOutputStream(part)){out.write(bytes);out.flush();out.getFD().sync();}require(!target.exists()&&part.renameTo(target),"Unable to accept window");require(scratch.delete(),"Unable to remove derived input scratch");output.put("text",raw.getString("text")).put("timestampsCount",raw.getJSONArray("timestamps").length()).put("asrSkipped",raw.getBoolean("asrSkipped")).put("totalPssKb",raw.getLong("totalPssKb"));outputs.put(output);newCount++;
            atomic(new File(job,"progress.json"),new JSONObject().put("status","RUNNING").put("sourceFingerprint",snapshot.getString("fingerprint")).put("completedWindows",outputs.length()).put("windowCount",windows.length()).put("updatedUtc",System.currentTimeMillis()));
        }
        require(snapshot.getString("fingerprint").equals(source(context,meeting).getString("fingerprint")),"Source/model changed during transcription");
        StringBuilder merged=new StringBuilder();int rawTotal=0;boolean review=false;String previous="";for(int i=0;i<outputs.length();i++){JSONObject o=outputs.getJSONObject(i);String text=o.getString("text").trim();rawTotal+=o.getString("text").length();require(rawTotal<=MAX_TOTAL_CHARS,"Transcript text resource limit; accepted windows retained");if(o.getJSONObject("window").getLong("overlapSamples")>0&&!previous.isEmpty()&&!text.isEmpty()){review=true;}if(!text.isEmpty()){if(merged.length()>0)merged.append(' ');merged.append(text);previous=text;}}
        boolean complete=outputs.length()==windows.length();String status=complete?(review?"COMPLETED_NEEDS_BOUNDARY_REVIEW":"COMPLETED"):"PAUSED";
        JSONObject result=new JSONObject().put("schemaVersion",1).put("jobId",id).put("jobDirectory",job.getAbsolutePath()).put("sourceFingerprint",snapshot.getString("fingerprint")).put("status",status).put("windows",outputs).put("windowCount",windows.length()).put("completedWindows",outputs.length()).put("newWindows",newCount).put("reusedWindows",skipped).put("elapsedMs",SystemClock.elapsedRealtime()-begin).put("granularity","Original audio windows; VAD boundaries are estimates; word timestamps unavailable").put("mergedText",review?JSONObject.NULL:merged.toString()).put("boundaryReviewRequired",review).put("historical",false).put("readOnly",false);
        atomic(new File(job,"progress.json"),new JSONObject(result.toString()).put("windows",JSONObject.NULL));return result;
    }

    /** Read and verify accepted evidence without inference or progress mutation. */
    public static synchronized JSONObject inspect(Context context,File meeting)throws Exception {return inspect(context,meeting,MAX_WINDOWS);}
    public static synchronized JSONObject inspect(Context context,File meeting,int maxWindows)throws Exception {return inspect(context,meeting,maxWindows,MAX_TOTAL_CHARS);}
    public static synchronized JSONObject inspect(Context context,File meeting,int maxWindows,int maxTextChars)throws Exception {return inspect(context,meeting,null,maxWindows,maxTextChars);}
    /** Read-only access to one job by its original id: the current job, or a verified historical job. Throws if unknown or invalid. */
    public static synchronized JSONObject inspectJob(Context context,File meeting,String jobId)throws Exception {require(jobId!=null&&UUID.fromString(jobId).toString().equals(jobId),"Invalid job id");return inspect(context,meeting,jobId,MAX_WINDOWS,MAX_TOTAL_CHARS);}
    private static boolean isUuid(String name){try{return UUID.fromString(name).toString().equals(name);}catch(IllegalArgumentException error){return false;}}
    private static JSONObject provenance(JSONObject stored,String kind)throws Exception{
        JSONObject p=new JSONObject().put("kind",kind).put("schemaVersion",stored.getInt("schemaVersion")).put("config",stored.getString("config")).put("sourceFingerprint",stored.getString("fingerprint")).put("fingerprintVerified",true).put("totalSamples",stored.getLong("totalSamples")).put("chunks",new JSONArray(stored.getJSONArray("chunks").toString())).put("models",new JSONArray(stored.getJSONArray("models").toString()));
        if(stored.has("descriptor"))p.put("descriptor",new JSONObject(stored.getJSONObject("descriptor").toString()));return p;
    }
    /** Verified accepted windows of one job. Never writes. */
    private static JSONObject evidence(File meeting,File job,JSONObject p,String id,int maxWindows,int maxTextChars,long maxSamples)throws Exception {
        JSONArray windows=p.getJSONArray("windows"),outputs=new JSONArray();if(windows.length()>maxWindows)throw new Limit("Inspection window limit");boolean review=false;int rawTotal=0;String previous="";StringBuilder merged=new StringBuilder();
        for(int i=0;i<windows.length();i++){JSONObject output=accepted(meeting,job,windows.getJSONObject(i),maxSamples);if(output==null)break;outputs.put(output);String text=output.getString("text").trim();rawTotal+=output.getString("text").length();if(rawTotal>maxTextChars)throw new Limit("Transcript text resource limit; accepted windows retained");if(output.getJSONObject("window").getLong("overlapSamples")>0&&!previous.isEmpty()&&!text.isEmpty())review=true;if(!text.isEmpty()){if(merged.length()>0)merged.append(' ');merged.append(text);previous=text;}}
        boolean complete=outputs.length()==windows.length();return new JSONObject().put("jobId",id).put("status",complete?(review?"COMPLETED_NEEDS_BOUNDARY_REVIEW":"COMPLETED"):"PAUSED").put("windows",outputs).put("windowCount",windows.length()).put("completedWindows",outputs.length()).put("boundaryReviewRequired",review).put("mergedText",review?JSONObject.NULL:merged.toString());
    }
    private static JSONObject inspectCurrent(File meeting,File job,JSONObject snapshot,String currentId,int maxWindows,int maxTextChars)throws Exception {
        JSONObject p=read(new File(job,"plan.json"),1048576);require(p.getString("fingerprint").equals(snapshot.getString("fingerprint")),"Job source/model changed");validatePlan(p,snapshot);
        return evidence(meeting,job,p,currentId,maxWindows,maxTextChars,MAX).put("historical",false).put("readOnly",false).put("currentJobId",currentId).put("provenance",provenance(p.getJSONObject("source"),"CURRENT_DESCRIPTOR"));
    }
    /** Stored fingerprint, id and source bytes of a prior job. Stored model hashes are reported as stored, never as current. */
    private static void verifyStored(JSONObject stored,String id,JSONObject current)throws Exception {
        int version=stored.getInt("schemaVersion");String expected;
        if(version==1)expected=AsrConfiguration.legacyFingerprintV1(stored);
        else if(version==AsrConfiguration.DESCRIPTOR_SCHEMA_VERSION){JSONObject d=stored.getJSONObject("descriptor");AsrConfiguration.validate(d);JSONObject source=d.getJSONObject("source");expected=AsrConfiguration.fingerprint(d);
            require(AsrConfiguration.canonical(source.getJSONArray("chunks")).equals(AsrConfiguration.canonical(stored.getJSONArray("chunks")))&&AsrConfiguration.canonical(d.getJSONArray("assets")).equals(AsrConfiguration.canonical(stored.getJSONArray("models")))&&source.getLong("totalSamples")==stored.getLong("totalSamples"),"Stored snapshot differs from its descriptor");}
        else throw new IOException("Unsupported stored job schema");
        require(expected.equals(stored.getString("fingerprint"))&&AsrConfiguration.jobId(expected).equals(id),"Stored fingerprint or job id mismatch");
        require(AsrConfiguration.canonical(stored.getJSONArray("chunks")).equals(AsrConfiguration.canonical(current.getJSONArray("chunks")))&&stored.getLong("totalSamples")==current.getLong("totalSamples"),"Stored source differs from current audio");
    }
    private static JSONObject inspectHistorical(File meeting,JSONObject current,String currentId,String id,int maxWindows,int maxTextChars)throws Exception {
        File jobs=new File(meeting,"transcription-jobs"),job=new File(jobs,id);require(meeting.getCanonicalFile().equals(jobs.getCanonicalFile().getParentFile())&&jobs.getCanonicalFile().equals(job.getCanonicalFile().getParentFile()),"Escaping job path");
        File file=new File(job,"plan.json");require(file.isFile(),"Unknown transcription job");JSONObject p=read(file,1048576),stored=p.getJSONObject("source");
        verifyStored(stored,id,current);require(p.getString("fingerprint").equals(stored.getString("fingerprint")),"Stored plan fingerprint mismatch");long[] limits=storedLimits(stored);validatePlan(p,stored,limits);
        return evidence(meeting,job,p,id,maxWindows,maxTextChars,limits[0]).put("historical",true).put("readOnly",true).put("currentJobId",currentId).put("provenance",provenance(stored,stored.getInt("schemaVersion")==1?"HISTORICAL_LEGACY_V1":"HISTORICAL_DESCRIPTOR_V"+stored.getInt("schemaVersion")));
    }
    private static JSONObject inspect(Context context,File meeting,String requested,int maxWindows,int maxTextChars)throws Exception {
        require(maxTextChars>0&&maxTextChars<=MAX_TOTAL_CHARS,"Invalid transcript text limit");
        require(maxWindows>0&&maxWindows<=MAX_WINDOWS,"Invalid inspection window limit");
        JSONObject snapshot=source(context,meeting);String currentId=AsrConfiguration.jobId(snapshot.getString("fingerprint"));File jobs=new File(meeting,"transcription-jobs"),currentJob=new File(jobs,currentId);boolean currentExists=new File(currentJob,"plan.json").exists();
        if(requested!=null){if(requested.equals(currentId))return currentExists?inspectCurrent(meeting,currentJob,snapshot,currentId,maxWindows,maxTextChars):null;return inspectHistorical(meeting,snapshot,currentId,requested,maxWindows,maxTextChars);}
        if(currentExists)return inspectCurrent(meeting,currentJob,snapshot,currentId,maxWindows,maxTextChars);
        File[] directories=jobs.listFiles();if(directories==null)return null;Arrays.sort(directories,(a,b)->a.getName().compareTo(b.getName()));
        ArrayList<JSONObject> found=new ArrayList<>();int rejected=0;
        for(File directory:directories){String name=directory.getName();if(!directory.isDirectory()||!isUuid(name)||name.equals(currentId)||!new File(directory,"plan.json").isFile())continue;
            try{found.add(inspectHistorical(meeting,snapshot,currentId,name,maxWindows,maxTextChars));}catch(Limit limit){throw limit;}catch(Exception invalid){rejected++;}}
        if(found.isEmpty())return null;
        // Deterministic choice: completed jobs first, then most accepted windows, then lowest original job id.
        Collections.sort(found,(a,b)->{boolean ac=a.optString("status").startsWith("COMPLETED"),bc=b.optString("status").startsWith("COMPLETED");if(ac!=bc)return ac?-1:1;int by=Integer.compare(b.optInt("completedWindows"),a.optInt("completedWindows"));return by!=0?by:a.optString("jobId").compareTo(b.optString("jobId"));});
        return found.get(0).put("historicalCandidates",found.size()).put("rejectedHistoricalJobs",rejected);
    }
}
