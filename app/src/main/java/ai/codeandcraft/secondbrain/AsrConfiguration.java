package ai.codeandcraft.secondbrain;
import java.io.IOException;import java.nio.charset.StandardCharsets;import java.security.MessageDigest;import java.util.*;import org.json.*;

/**
 * Single authority for every ASR/VAD/window-planning value. OfflineAsr, SileroSpeechGate and
 * WindowedTranscription pass these constants to the native setters and the job descriptor is built
 * from the same fields, so provenance cannot drift from what is actually consumed.
 * Float settings are encoded in the descriptor as Float.toString text so the canonical form is exact.
 */
public final class AsrConfiguration {
    private AsrConfiguration(){}
    public static final int DESCRIPTOR_SCHEMA_VERSION=2;
    public static final String CONFIG_LABEL="asr-descriptor-v2";
    /** Historical label of schema-1 jobs. Kept only to recognise old jobs; never written for new jobs. */
    public static final String LEGACY_V1_CONFIG="v2-virtual-pcm:whisper-tiny.en-int8:sherpa1.13.5:cpu2:silero0.5cpu1:w25s:target20s:gap800ms";

    public static final String SHERPA_VERSION="1.13.5",WHISPER_MODEL_LABEL="whisper-tiny.en-int8";

    public static final int SOURCE_SAMPLE_RATE=16000,SOURCE_CHANNELS=1,SOURCE_BYTES_PER_FRAME=2,MAX_SOURCE_CHUNKS=512;
    public static final String SOURCE_CONTAINER="virtual-pcm-wav-chunks",SOURCE_ENCODING="pcm_s16le";

    public static final String WHISPER_ENCODER="models/whisper-tiny-en/tiny.en-encoder.int8.onnx",WHISPER_DECODER="models/whisper-tiny-en/tiny.en-decoder.int8.onnx",WHISPER_TOKENS="models/whisper-tiny-en/tiny.en-tokens.txt",WHISPER_LANGUAGE="en",WHISPER_TASK="transcribe",WHISPER_DECODING_METHOD="greedy_search",WHISPER_MODEL_TYPE="whisper",WHISPER_PROVIDER="cpu";
    public static final boolean WHISPER_TOKEN_TIMESTAMPS=true;
    public static final int WHISPER_FEATURE_SAMPLE_RATE=16000,WHISPER_FEATURE_DIM=80,WHISPER_THREADS=2;

    public static final String SILERO_MODEL="models/vad/silero_vad.onnx",SILERO_PROVIDER="cpu";
    public static final float SILERO_THRESHOLD=0.5f,SILERO_MIN_SILENCE_SECONDS=0.3f,SILERO_MIN_SPEECH_SECONDS=0.1f,SILERO_MAX_SPEECH_SECONDS=20f;
    public static final int SILERO_WINDOW_SIZE=512,SILERO_SAMPLE_RATE=16000,SILERO_THREADS=1;

    public static final int MAX_WINDOW_SAMPLES=400000,TARGET_WINDOW_SAMPLES=320000,MAX_WINDOWS=512,MIN_CUT_SAMPLES=240000,MAX_CUT_SAMPLES=384000,CUT_MARGIN_SAMPLES=4800,MIN_SILENCE_GAP_SAMPLES=12800,MAX_OVERLAP_SAMPLES=80000;

    /** Asset order is part of the identity; whisper encoder, decoder, tokens, then VAD model. */
    public static String[] assetPaths(){return new String[]{WHISPER_ENCODER,WHISPER_DECODER,WHISPER_TOKENS,SILERO_MODEL};}

    private static void require(boolean ok,String message)throws IOException{if(!ok)throw new IOException(message);}
    private static String hex(byte[] bytes){StringBuilder out=new StringBuilder();for(byte b:bytes)out.append(String.format(Locale.ROOT,"%02x",b&255));return out.toString();}
    private static String sha256(String text)throws Exception{return hex(MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8)));}
    private static long integer(JSONObject object,String key)throws Exception{Object v=object.get(key);require(v instanceof Integer||v instanceof Long,"Non-integer "+key);return ((Number)v).longValue();}

    private static JSONObject sourceFormat()throws Exception{return new JSONObject().put("container",SOURCE_CONTAINER).put("encoding",SOURCE_ENCODING).put("channels",SOURCE_CHANNELS).put("sampleRate",SOURCE_SAMPLE_RATE).put("bytesPerFrame",SOURCE_BYTES_PER_FRAME).put("maxChunks",MAX_SOURCE_CHUNKS);}

    /** Validates and normalises source chunks and asset hashes. Rejects malformed or unexpected structure. */
    private static JSONArray[] checkSource(JSONArray chunks,JSONArray assets,long totalSamples,String[] expected)throws Exception{
        require(chunks!=null&&chunks.length()>0&&chunks.length()<=MAX_SOURCE_CHUNKS,"Expected1–512 source chunks");JSONArray normalChunks=new JSONArray(),normalAssets=new JSONArray();Set<String> names=new HashSet<>();long offset=0;
        for(int i=0;i<chunks.length();i++){JSONObject c=chunks.getJSONObject(i);require(c.length()==5,"Unexpected source chunk fields");String name=c.getString("filename"),sha=c.getString("sha256");require(name.matches("chunk-[0-9]+\\.wav")&&names.add(name),"Invalid/duplicate source chunk name");require(sha.matches("[a-f0-9]{64}"),"Invalid source chunk hash");long samples=integer(c,"sampleCount"),data=integer(c,"dataOffset"),start=integer(c,"meetingStartSample");require(samples>0&&data>=20&&start==offset,"Invalid source chunk range");normalChunks.put(new JSONObject().put("filename",name).put("sha256",sha).put("sampleCount",samples).put("dataOffset",data).put("meetingStartSample",start));offset+=samples;}
        require(totalSamples>0&&totalSamples==offset,"Source total samples mismatch");
        require(expected!=null&&expected.length==4,"Unexpected asset path set");require(assets!=null&&assets.length()==expected.length,"Unexpected asset count");
        for(int i=0;i<expected.length;i++){JSONObject a=assets.getJSONObject(i);require(a.length()==2&&expected[i].equals(a.getString("path"))&&a.getString("sha256").matches("[a-f0-9]{64}"),"Invalid asset entry "+expected[i]);normalAssets.put(new JSONObject().put("path",expected[i]).put("sha256",a.getString("sha256")));}
        return new JSONArray[]{normalChunks,normalAssets};
    }

    /** Complete descriptor of everything that determines an ASR job's output, from the live constants. */
    public static JSONObject descriptor(JSONArray chunks,JSONArray assets,long totalSamples)throws Exception{
        JSONArray[] checked=checkSource(chunks,assets,totalSamples,assetPaths());
        JSONObject whisper=new JSONObject().put("language",WHISPER_LANGUAGE).put("task",WHISPER_TASK).put("decodingMethod",WHISPER_DECODING_METHOD).put("enableTokenTimestamps",WHISPER_TOKEN_TIMESTAMPS).put("featureSampleRate",WHISPER_FEATURE_SAMPLE_RATE).put("featureDim",WHISPER_FEATURE_DIM).put("provider",WHISPER_PROVIDER).put("numThreads",WHISPER_THREADS).put("modelType",WHISPER_MODEL_TYPE).put("modelLabel",WHISPER_MODEL_LABEL).put("encoder",WHISPER_ENCODER).put("decoder",WHISPER_DECODER).put("tokens",WHISPER_TOKENS);
        JSONObject silero=new JSONObject().put("threshold",Float.toString(SILERO_THRESHOLD)).put("minSilenceDuration",Float.toString(SILERO_MIN_SILENCE_SECONDS)).put("minSpeechDuration",Float.toString(SILERO_MIN_SPEECH_SECONDS)).put("maxSpeechDuration",Float.toString(SILERO_MAX_SPEECH_SECONDS)).put("windowSize",SILERO_WINDOW_SIZE).put("sampleRate",SILERO_SAMPLE_RATE).put("provider",SILERO_PROVIDER).put("numThreads",SILERO_THREADS).put("model",SILERO_MODEL);
        JSONObject planning=new JSONObject().put("maxWindowSamples",MAX_WINDOW_SAMPLES).put("targetWindowSamples",TARGET_WINDOW_SAMPLES).put("maxWindows",MAX_WINDOWS).put("minCutSamples",MIN_CUT_SAMPLES).put("maxCutSamples",MAX_CUT_SAMPLES).put("cutMarginSamples",CUT_MARGIN_SAMPLES).put("minSilenceGapSamples",MIN_SILENCE_GAP_SAMPLES).put("maxOverlapSamples",MAX_OVERLAP_SAMPLES);
        return new JSONObject().put("descriptorSchemaVersion",DESCRIPTOR_SCHEMA_VERSION).put("whisper",whisper).put("silero",silero).put("sherpa",new JSONObject().put("runtimeVersion",SHERPA_VERSION)).put("planning",planning).put("source",new JSONObject().put("format",sourceFormat()).put("chunks",checked[0]).put("totalSamples",totalSamples)).put("assets",checked[1]);
    }

    /** Structural validation of a stored or constructed descriptor. Setting values may differ from current ones. */
    public static void validate(JSONObject d)throws Exception{
        require(d!=null&&d.length()==7&&d.getInt("descriptorSchemaVersion")==DESCRIPTOR_SCHEMA_VERSION,"Unsupported descriptor schema");
        for(String key:new String[]{"whisper","silero","sherpa","planning"})require(d.get(key) instanceof JSONObject,"Descriptor section missing: "+key);
        JSONObject source=d.getJSONObject("source");require(source.length()==3&&canonical(source.getJSONObject("format")).equals(canonical(sourceFormat())),"Unsupported source format");
        JSONObject whisper=d.getJSONObject("whisper"),silero=d.getJSONObject("silero");
        String[] own={whisper.getString("encoder"),whisper.getString("decoder"),whisper.getString("tokens"),silero.getString("model")};
        checkSource(source.getJSONArray("chunks"),d.getJSONArray("assets"),integer(source,"totalSamples"),own);
        storedPlanning(d);
    }

    /** Planning limits {maxWindowSamples,maxWindows,maxOverlapSamples} stored in a descriptor, sanity bounded. */
    public static long[] storedPlanning(JSONObject d)throws Exception{
        JSONObject p=d.getJSONObject("planning");long max=integer(p,"maxWindowSamples"),windows=integer(p,"maxWindows"),overlap=integer(p,"maxOverlapSamples");
        require(max>0&&max<=16000L*3600&&windows>0&&windows<=4096&&overlap>=0&&overlap<max,"Invalid stored planning values");return new long[]{max,windows,overlap};
    }

    /** Stable, recursive, key-sorted encoding. Rejects floating point values; encode those as text. */
    public static String canonical(Object value)throws Exception{StringBuilder out=new StringBuilder();encode(value,out);return out.toString();}
    private static void encode(Object v,StringBuilder out)throws Exception{
        if(v instanceof JSONObject){JSONObject o=(JSONObject)v;TreeSet<String> keys=new TreeSet<>();Iterator<String> it=o.keys();while(it.hasNext())keys.add(it.next());out.append('{');boolean first=true;for(String k:keys){if(!first)out.append(',');first=false;out.append(JSONObject.quote(k)).append(':');encode(o.get(k),out);}out.append('}');}
        else if(v instanceof JSONArray){JSONArray a=(JSONArray)v;out.append('[');for(int i=0;i<a.length();i++){if(i>0)out.append(',');encode(a.get(i),out);}out.append(']');}
        else if(v instanceof String)out.append(JSONObject.quote((String)v));
        else if(v instanceof Integer||v instanceof Long||v instanceof Short||v instanceof Byte)out.append(((Number)v).longValue());
        else if(v instanceof Boolean)out.append(((Boolean)v).booleanValue());
        else if(v==null||v==JSONObject.NULL)out.append("null");
        else throw new IOException("Non-canonical JSON value "+v.getClass().getSimpleName());
    }

    public static String fingerprint(JSONObject descriptor)throws Exception{return sha256(canonical(descriptor));}
    public static String jobId(String fingerprint){return UUID.nameUUIDFromBytes(fingerprint.getBytes(StandardCharsets.UTF_8)).toString();}

    /** Recomputes the schema-1 fingerprint exactly as the original code did, from the stored fields only. */
    public static String legacyFingerprintV1(JSONObject stored)throws Exception{
        require(stored.length()==6&&stored.getInt("schemaVersion")==1,"Unexpected legacy snapshot fields");
        JSONObject unsigned=new JSONObject().put("schemaVersion",1).put("config",stored.getString("config")).put("chunks",stored.getJSONArray("chunks")).put("models",stored.getJSONArray("models")).put("totalSamples",stored.getLong("totalSamples"));
        return sha256(unsigned.toString());
    }
}
