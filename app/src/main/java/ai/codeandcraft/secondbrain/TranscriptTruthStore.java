package ai.codeandcraft.secondbrain;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import org.json.JSONException;
import org.json.JSONObject;

public final class TranscriptTruthStore {

    private static final int BUFFER_SIZE = 8192;
    private static final int MAX_RECORD_BYTES = 262144;
    private static final int SCHEMA_VERSION = 1;
    private static final String SOURCE_NAME = "source.wav";
    private static final String RECORD_NAME = "record.json";
    private static final String SOURCE_PART = "source.wav.part";
    private static final String RECORD_PART = "record.json.part";

    private TranscriptTruthStore() {
    }

    public static File persist(File newDirectory, File sourceWav, JSONObject asr) throws Exception {
        if (newDirectory == null) throw new IllegalArgumentException("newDirectory must not be null");
        if (newDirectory.getParentFile() == null || !newDirectory.getParentFile().isDirectory())
            throw new IOException("Parent directory must already exist");
        if (sourceWav == null) throw new IllegalArgumentException("sourceWav must not be null");
        if (asr == null) throw new IllegalArgumentException("asr must not be null");

        if (newDirectory.exists()) throw new IOException("Directory already exists");

        if (!newDirectory.mkdir()) throw new IOException("Unable to reserve directory");

        try {
            PcmWavReader.Audio actual = PcmWavReader.read(sourceWav);

            JSONObject asrCopy = new JSONObject(asr.toString());
            String asrSha = asrCopy.getString("audioSha256");
            if (!asrSha.equals(actual.sha256))
                throw new IOException("ASR audioSha256 does not match source");
            long asrDuration = asrCopy.getLong("durationMs");
            if (asrDuration != actual.durationMs)
                throw new IOException("ASR durationMs does not match source");
            if (asrCopy.getLong("startMs") != 0)
                throw new IOException("ASR startMs must be 0");
            if (asrCopy.getLong("endMs") != actual.durationMs)
                throw new IOException("ASR endMs does not match source");

            File sourceDest = new File(newDirectory, SOURCE_NAME);
            if (sourceDest.exists()) throw new IOException("source.wav already present");

            File sourcePart = new File(newDirectory, SOURCE_PART);
            if (!sourcePart.createNewFile()) throw new IOException("Unable to create source.wav.part");

            copyAndSync(sourceWav, sourcePart);

            PcmWavReader.Audio copied = PcmWavReader.read(sourcePart);
            if (!copied.sha256.equals(actual.sha256))
                throw new IOException("Copied source hash mismatch");
            if (copied.durationMs != actual.durationMs)
                throw new IOException("Copied source duration mismatch");
            if (sourceDest.exists() || !sourcePart.renameTo(sourceDest))
                throw new IOException("Unable to finalize source.wav");

            JSONObject record = new JSONObject();
            record.put("schemaVersion", SCHEMA_VERSION);
            record.put("recordId", newDirectory.getName());
            record.put("createdUtc", System.currentTimeMillis());
            JSONObject source = new JSONObject();
            source.put("path", SOURCE_NAME);
            source.put("sha256", actual.sha256);
            source.put("sampleRate", 16000);
            source.put("durationMs", actual.durationMs);
            source.put("startMs", 0);
            source.put("endMs", actual.durationMs);
            record.put("source", source);
            record.put("asr", asrCopy);

            byte[] recordBytes = record.toString().getBytes(StandardCharsets.UTF_8);
            if (recordBytes.length > MAX_RECORD_BYTES)
                throw new IOException("record.json exceeds " + MAX_RECORD_BYTES + " bytes");

            File recordPart = new File(newDirectory, RECORD_PART);
            if (!recordPart.createNewFile()) throw new IOException("Unable to create record.json.part");
            writeAndSync(recordBytes, recordPart);

            File recordDest = new File(newDirectory, RECORD_NAME);
            if (recordDest.exists() || !recordPart.renameTo(recordDest))
                throw new IOException("Unable to finalize record.json");

            return newDirectory;
        } catch (Exception e) {
            // Retain reserved directory and partial files for inspection.
            throw e;
        }
    }

    public static JSONObject load(File directory) throws Exception {
        if (directory == null) throw new IllegalArgumentException("directory must not be null");
        if (!directory.isDirectory()) throw new IOException("Not a directory");

        File recordFile = new File(directory, RECORD_NAME);
        if (!recordFile.isFile()) throw new IOException("record.json missing");
        if (recordFile.length() > MAX_RECORD_BYTES) throw new IOException("record.json too large");

        File sourceFile = new File(directory, SOURCE_NAME);
        if (!sourceFile.isFile()) throw new IOException("source.wav missing");

        byte[] bytes = readBounded(recordFile);
        JSONObject record;
        try {
            record = new JSONObject(new String(bytes, StandardCharsets.UTF_8));
        } catch (JSONException e) {
            throw new IOException("record.json malformed", e);
        }

        if (record.getInt("schemaVersion") != SCHEMA_VERSION)
            throw new IOException("Unsupported schemaVersion");
        if (!record.getString("recordId").equals(directory.getName()))
            throw new IOException("recordId does not match directory");

        JSONObject source = record.getJSONObject("source");
        if (!source.getString("path").equals(SOURCE_NAME))
            throw new IOException("source.path must be source.wav");

        PcmWavReader.Audio actual = PcmWavReader.read(sourceFile);
        if (!source.getString("sha256").equals(actual.sha256))
            throw new IOException("source sha256 mismatch");
        if (source.getInt("sampleRate") != 16000)
            throw new IOException("source sampleRate must be 16000");
        if (source.getLong("durationMs") != actual.durationMs)
            throw new IOException("source durationMs mismatch");
        if (source.getLong("startMs") != 0)
            throw new IOException("source startMs must be 0");
        if (source.getLong("endMs") != actual.durationMs)
            throw new IOException("source endMs mismatch");

        JSONObject asr = record.getJSONObject("asr");
        if (!asr.getString("audioSha256").equals(actual.sha256))
            throw new IOException("asr audioSha256 mismatch");
        if (asr.getLong("durationMs") != actual.durationMs)
            throw new IOException("asr durationMs mismatch");
        if (asr.getLong("startMs") != 0)
            throw new IOException("asr startMs must be 0");
        if (asr.getLong("endMs") != actual.durationMs)
            throw new IOException("asr endMs mismatch");

        return record;
    }

    private static void copyAndSync(File source, File dest) throws IOException {
        try (FileInputStream in = new FileInputStream(source);
             FileOutputStream out = new FileOutputStream(dest)) {
            byte[] buf = new byte[BUFFER_SIZE];
            int n;
            while ((n = in.read(buf)) != -1) out.write(buf, 0, n);
            out.flush();
            out.getFD().sync();
        }
    }

    private static void writeAndSync(byte[] data, File dest) throws IOException {
        try (FileOutputStream out = new FileOutputStream(dest)) {
            out.write(data);
            out.flush();
            out.getFD().sync();
        }
    }

    private static byte[] readBounded(File file) throws IOException {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        try (FileInputStream in = new FileInputStream(file)) {
            byte[] buf = new byte[BUFFER_SIZE];
            int n, total = 0;
            while ((n = in.read(buf)) != -1) {
                total += n;
                if (total > MAX_RECORD_BYTES) throw new IOException("record.json too large");
                baos.write(buf, 0, n);
            }
        }
        return baos.toByteArray();
    }


}