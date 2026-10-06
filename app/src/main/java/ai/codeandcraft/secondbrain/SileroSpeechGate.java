package ai.codeandcraft.secondbrain;

import android.content.Context;
import android.os.SystemClock;
import org.json.JSONArray;
import org.json.JSONObject;

import com.k2fsa.sherpa.onnx.*;

public final class SileroSpeechGate {

    private SileroSpeechGate() {}

    public static JSONObject detect(Context context, float[] samples) throws Exception {
        if (context == null) {
            throw new Exception("Context must not be null");
        }
        if (samples == null) {
            throw new Exception("Samples must not be null");
        }
        if (samples.length <= 0) {
            throw new Exception("Samples must contain at least one element");
        }

        long totalStart = SystemClock.elapsedRealtime();

        JSONArray ranges = new JSONArray();
        Vad v = null;
        int frameSizeSamples = AsrConfiguration.SILERO_WINDOW_SIZE;
        int sourceSampleCount = samples.length;
        int trailingPaddingSamples = (frameSizeSamples - sourceSampleCount % frameSizeSamples) % frameSizeSamples;
        try {
            SileroVadModelConfig s = new SileroVadModelConfig();
            s.setModel(AsrConfiguration.SILERO_MODEL);
            s.setThreshold(AsrConfiguration.SILERO_THRESHOLD);
            s.setMinSilenceDuration(AsrConfiguration.SILERO_MIN_SILENCE_SECONDS);
            s.setMinSpeechDuration(AsrConfiguration.SILERO_MIN_SPEECH_SECONDS);
            s.setWindowSize(AsrConfiguration.SILERO_WINDOW_SIZE);
            s.setMaxSpeechDuration(AsrConfiguration.SILERO_MAX_SPEECH_SECONDS);

            VadModelConfig c = new VadModelConfig();
            c.setSileroVadModelConfig(s);
            c.setSampleRate(AsrConfiguration.SILERO_SAMPLE_RATE);
            c.setNumThreads(AsrConfiguration.SILERO_THREADS);
            c.setProvider(AsrConfiguration.SILERO_PROVIDER);

            v = new Vad(context.getAssets(), c);

            for (int offset = 0; offset < sourceSampleCount; offset += frameSizeSamples) {
                float[] window = new float[frameSizeSamples];
                int windowLen = Math.min(frameSizeSamples, sourceSampleCount - offset);
                System.arraycopy(samples, offset, window, 0, windowLen);
                v.acceptWaveform(window);
            }
            v.flush();

            while (!v.empty()) {
                SpeechSegment seg = v.front();
                int rawStart = seg.getStart();
                float[] segSamples = seg.getSamples();
                int rawEnd = rawStart + (segSamples == null ? 0 : segSamples.length);

                int start = Math.max(0, rawStart);
                int end = Math.min(samples.length, rawEnd);

                if (end > start) {
                    JSONObject range = new JSONObject();
                    range.put("startSample", start);
                    range.put("endSample", end);
                    range.put("startMs", (int) (start * 1000.0 / AsrConfiguration.SILERO_SAMPLE_RATE));
                    range.put("endMs", (int) (end * 1000.0 / AsrConfiguration.SILERO_SAMPLE_RATE));
                    range.put("rawStartSample", rawStart);
                    range.put("rawEndSample", rawEnd);
                    ranges.put(range);
                }
                v.pop();
            }
        } finally {
            if (v != null) {
                v.release();
            }
        }

        long elapsedMs = SystemClock.elapsedRealtime() - totalStart;

        JSONObject out = new JSONObject();
        out.put("detected", ranges.length() > 0);
        out.put("ranges", ranges);
        out.put("elapsedMs", elapsedMs);
        out.put("engine", "silero-vad");
        out.put("rangeKind", "vad_estimate");
        out.put("sampleRate", AsrConfiguration.SILERO_SAMPLE_RATE);
        out.put("frameSizeSamples", frameSizeSamples);
        out.put("sourceSampleCount", sourceSampleCount);
        out.put("trailingPaddingSamples", trailingPaddingSamples);

        return out;
    }
}