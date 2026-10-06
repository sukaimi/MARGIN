package ai.codeandcraft.secondbrain;

import android.content.Context;
import android.os.Debug;
import android.os.SystemClock;
import org.json.JSONArray;
import org.json.JSONObject;

import com.k2fsa.sherpa.onnx.*;

import java.io.File;

public final class OfflineAsr {

    private OfflineAsr() {}

    public static JSONObject transcribe(Context context, File wav) throws Exception {
        if (context == null) throw new Exception("Context must not be null");
        if (RecorderService.isRecording()) throw new Exception("Cannot transcribe while recording is active");

        long totalStart = SystemClock.elapsedRealtime();

        PcmWavReader.Audio audio = PcmWavReader.read(wav);

        // VAD runs on CPU1 before Whisper loads on CPU2.
        JSONObject vad = SileroSpeechGate.detect(context, audio.samples);
        boolean detected = vad.optBoolean("detected", false);

        if (!detected) {
            return noSpeechResult(audio, vad, totalStart);
        }

        FeatureConfig f = new FeatureConfig();
        f.setSampleRate(AsrConfiguration.WHISPER_FEATURE_SAMPLE_RATE);
        f.setFeatureDim(AsrConfiguration.WHISPER_FEATURE_DIM);

        OfflineWhisperModelConfig w = new OfflineWhisperModelConfig();
        w.setEncoder(AsrConfiguration.WHISPER_ENCODER);
        w.setDecoder(AsrConfiguration.WHISPER_DECODER);
        w.setLanguage(AsrConfiguration.WHISPER_LANGUAGE);
        w.setTask(AsrConfiguration.WHISPER_TASK);
        w.setEnableTokenTimestamps(AsrConfiguration.WHISPER_TOKEN_TIMESTAMPS);

        OfflineModelConfig m = new OfflineModelConfig();
        m.setWhisper(w);
        m.setTokens(AsrConfiguration.WHISPER_TOKENS);
        m.setNumThreads(AsrConfiguration.WHISPER_THREADS);
        m.setProvider(AsrConfiguration.WHISPER_PROVIDER);
        m.setModelType(AsrConfiguration.WHISPER_MODEL_TYPE);

        OfflineRecognizerConfig c = new OfflineRecognizerConfig();
        c.setFeatConfig(f);
        c.setModelConfig(m);
        c.setDecodingMethod(AsrConfiguration.WHISPER_DECODING_METHOD);

        long loadStart = SystemClock.elapsedRealtime();
        OfflineRecognizer r = null;
        OfflineStream s = null;
        long loadMs = 0;
        long decodeMs = 0;
        OfflineRecognizerResult result = null;
        Debug.MemoryInfo info = new Debug.MemoryInfo();
        long totalPssKb = 0;
        JSONArray tokensArr = new JSONArray();
        JSONArray timestampsArr = new JSONArray();
        try {
            r = new OfflineRecognizer(context.getAssets(), c);
            loadMs = SystemClock.elapsedRealtime() - loadStart;

            s = r.createStream();
            long decodeStart = SystemClock.elapsedRealtime();
            s.acceptWaveform(audio.samples, AsrConfiguration.WHISPER_FEATURE_SAMPLE_RATE);
            r.decode(s);
            decodeMs = SystemClock.elapsedRealtime() - decodeStart;
            result = r.getResult(s);

            Debug.getMemoryInfo(info);
            totalPssKb = info.getTotalPss();

            for (String t : result.getTokens()) tokensArr.put(t);
            float[] ts = result.getTimestamps();
            for (float t : ts) timestampsArr.put(t);
        } finally {
            try {
                if (s != null) s.release();
            } finally {
                if (r != null) r.release();
            }
        }

        JSONObject out = new JSONObject();
        out.put("text", result.getText());
        out.put("tokens", tokensArr);
        out.put("timestamps", timestampsArr);
        // VAD ranges are model estimates, not word-level timestamps.
        out.put("timestampKind", "model_estimate");
        out.put("engine", "sherpa-onnx");
        out.put("version", AsrConfiguration.SHERPA_VERSION);
        out.put("model", AsrConfiguration.WHISPER_MODEL_LABEL);
        out.put("audioSha256", audio.sha256);
        out.put("durationMs", audio.durationMs);
        out.put("startMs", 0);
        out.put("endMs", audio.durationMs);
        out.put("loadMs", loadMs);
        out.put("decodeMs", decodeMs);
        long elapsedMs = SystemClock.elapsedRealtime() - totalStart;
        out.put("elapsedMs", elapsedMs);
        out.put("totalPssKb", totalPssKb);
        out.put("memoryKind", "post_decode_pss_snapshot");
        out.put("status", "transcribed");
        out.put("speechDetected", true);
        out.put("asrSkipped", false);
        out.put("vad", vad);

        return out;
    }

    private static JSONObject noSpeechResult(PcmWavReader.Audio audio, JSONObject vad, long totalStart) throws Exception {
        Debug.MemoryInfo info = new Debug.MemoryInfo();
        Debug.getMemoryInfo(info);
        long totalPssKb = info.getTotalPss();

        JSONObject out = new JSONObject();
        out.put("text", "");
        out.put("tokens", new JSONArray());
        out.put("timestamps", new JSONArray());
        out.put("status", "no_speech");
        out.put("speechDetected", false);
        out.put("asrSkipped", true);
        out.put("vad", vad);
        out.put("audioSha256", audio.sha256);
        out.put("durationMs", audio.durationMs);
        out.put("startMs", 0);
        out.put("endMs", audio.durationMs);
        out.put("timestampKind", "unavailable");
        out.put("engine", "silero-vad");
        out.put("model", "silero-vad");
        out.put("version", AsrConfiguration.SHERPA_VERSION);
        out.put("loadMs", 0);
        out.put("decodeMs", 0);
        long elapsedMs = SystemClock.elapsedRealtime() - totalStart;
        out.put("elapsedMs", elapsedMs);
        out.put("totalPssKb", totalPssKb);
        out.put("memoryKind", "post_vad_pss_snapshot");

        return out;
    }
}