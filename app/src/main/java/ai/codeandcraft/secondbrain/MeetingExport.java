package ai.codeandcraft.secondbrain;

import android.content.Context;
import android.system.*;
import java.io.*;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.text.SimpleDateFormat;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.zip.*;
import org.json.*;

/**
 * Bounded local derivative exports. Every format is built from one validated SourceBundle snapshot, never from
 * live files, and never modifies originals. Failures carry only an EXPORT_* code, never a private file name.
 */
public final class MeetingExport {
    private MeetingExport() {}
    private static final int RATE = 16000, MAX_TEXT_BYTES = 2 * 1024 * 1024, MAX_JSON = 1048576;
    private static final long MAX_BYTES = SourceBundle.MAX_BYTES;
    private static final String[] KINDS = {"notes", "transcript", "audio", "everything"};
    private static final String[][] SECTIONS = {{"summary", "Summary"}, {"decisions", "Decisions"}, {"actions", "Action items"}, {"questions", "Open questions"}, {"risks", "Risks"}};

    private static void require(boolean ok, String code) throws IOException { if (!ok) throw new IOException(code); }
    private static void stop(AtomicBoolean cancel) throws IOException { require(!cancel.get() && !Thread.currentThread().isInterrupted(), "EXPORT_CANCELLED"); }
    private static long u32(byte[] b, int at) { return Integer.toUnsignedLong(ByteBuffer.wrap(b).order(ByteOrder.LITTLE_ENDIAN).getInt(at)); }
    private static String hex(byte[] bytes) { StringBuilder s = new StringBuilder(); for (byte b : bytes) s.append(String.format(Locale.ROOT, "%02x", b & 255)); return s.toString(); }
    private static String clock(long ms) { long s = Math.max(0, ms) / 1000, h = s / 3600, m = s / 60 % 60; return h > 0 ? String.format(Locale.ROOT, "%d:%02d:%02d", h, m, s % 60) : String.format(Locale.ROOT, "%02d:%02d", m, s % 60); }
    private static String span(long startMs, long endMs) { return clock(startMs) + "–" + clock(endMs); }
    private static String utf8(byte[] bytes) { return new String(bytes, StandardCharsets.UTF_8); }

    /** Maps any failure to a stable code. Known SourceBundle reasons keep a meaning; everything else is generic. */
    private static IOException generic(Exception error) {
        String m = error.getMessage() == null ? "" : error.getMessage();
        if (error instanceof IOException && m.matches("EXPORT_[A-Z_]+")) return (IOException) error;
        if (m.contains("cancelled")) return new IOException("EXPORT_CANCELLED");
        if (m.startsWith("Finish recording before exporting")) return new IOException("EXPORT_RECORDING_ACTIVE");
        if (m.startsWith("Local export limit reached")) return new IOException("EXPORT_LIMIT");
        if (m.startsWith("Not enough free storage") || m.contains("byte limit") || m.contains("entry limit")) return new IOException("EXPORT_STORAGE");
        if (m.startsWith("Finish or recover")) return new IOException("EXPORT_NOT_COMPLETE");
        return new IOException("EXPORT_FAILED");
    }

    public static JSONObject prepare(Context context, File meeting, String kind, AtomicBoolean cancel) throws Exception {
        AtomicBoolean token = cancel == null ? new AtomicBoolean() : cancel;
        if (!Arrays.asList(KINDS).contains(kind)) throw new IOException("EXPORT_INVALID_KIND");
        File zip = null, produced = null, work = null, moved = null;
        try {
            stop(token);
            JSONObject bundle = SourceBundle.create(context, meeting, token);
            zip = ownedZip(context, bundle.getString("file"));
            stop(token);
            long startUtc = 0;
            JSONObject result;
            if ("everything".equals(kind)) {
                try (Snapshot s = new Snapshot(zip, bundle.getString("sha256"))) { startUtc = s.json("session.json", 262144).optLong("startUtc", 0); }
                result = new JSONObject(bundle.toString()).put("mimeType", "application/zip").put("displayName", name(kind, "zip", startUtc)).put("kind", kind);
                stop(token);
                zip = null;
                return result;
            }
            File cache = context.getCacheDir().getAbsoluteFile();
            require(cache.equals(cache.getCanonicalFile()) && cache.isDirectory(), "EXPORT_FAILED");
            File candidate = new File(cache, "export-" + UUID.randomUUID());
            require(candidate.equals(candidate.getCanonicalFile()) && candidate.mkdir(), "EXPORT_FAILED");
            work = candidate;
            File destination = new File(work, zip.getName());
            require(!destination.exists(), "EXPORT_FAILED");
            java.nio.file.Files.move(zip.toPath(), destination.toPath());
            moved = destination;
            zip = moved;
            stop(token);
            try (Snapshot s = new Snapshot(zip, bundle.getString("sha256"))) {
                JSONObject session = s.json("session.json", 262144);
                startUtc = session.optLong("startUtc", 0);
                if ("audio".equals(kind)) result = audio(context, s, session, startUtc, token);
                else {
                    String text = "notes".equals(kind) ? notes(s, session, startUtc) : transcript(context, meeting, s, session, startUtc, token);
                    byte[] bytes = text.getBytes(StandardCharsets.UTF_8);
                    require(bytes.length <= MAX_TEXT_BYTES, "EXPORT_LIMIT");
                    result = commit(context, kind, "txt", "text/plain", startUtc, bytes.length, token, out -> out.write(bytes));
                }
            }
            produced = new File(result.getString("file"));
            stop(token);
            produced = null;
            return result;
        } catch (Exception error) {
            throw generic(error);
        } finally {
            // Only files created by this call are ever removed: the validation ZIP and an uncommitted/cancelled output.
            if (zip != null) zip.delete();
            if (produced != null) produced.delete();
            if (moved != null && !moved.equals(zip)) moved.delete();
            if (work != null) work.delete();
        }
    }

    private static File ownedZip(Context context, String path) throws Exception {
        File file = new File(path).getAbsoluteFile(), exports = new File(context.getFilesDir().getCanonicalFile(), "exports");
        require(file.equals(file.getCanonicalFile()) && exports.equals(exports.getCanonicalFile()) && exports.equals(file.getParentFile()) && file.getName().matches("[a-f0-9-]{36}\\.zip") && UUID.fromString(file.getName().substring(0, 36)).toString().equals(file.getName().substring(0, 36)) && file.isFile(), "EXPORT_FAILED");
        return file;
    }

    private static String name(String kind, String ext, long startUtc) {
        return "margin-" + kind + "-" + new SimpleDateFormat("yyyyMMdd-HHmm", Locale.ROOT).format(new Date(startUtc > 0 ? startUtc : System.currentTimeMillis())) + "." + ext;
    }

    /** Hash-checked read-only view of the bundle ZIP. */
    private static final class Snapshot implements Closeable {
        final ZipFile zip; final Map<String, String> hashes = new TreeMap<>();
        Snapshot(File file, String sha) throws Exception {
            require(file.length() <= MAX_BYTES + 4L * 1024 * 1024 && sha.equals(SourceBundle.sha(file)), "EXPORT_FAILED");
            zip = new ZipFile(file);
            try {
                JSONArray files = new JSONObject(utf8(read("manifest.json", MAX_JSON, null))).getJSONArray("files");
                for (int i = 0; i < files.length(); i++) require(hashes.put(files.getJSONObject(i).getString("path"), files.getJSONObject(i).getString("sha256")) == null, "EXPORT_FAILED");
            } catch (Exception error) {
                try { zip.close(); } catch (IOException closeError) { error.addSuppressed(closeError); }
                throw error;
            }
        }
        boolean has(String path) { return hashes.containsKey(path) && zip.getEntry(path) != null; }
        byte[] bytes(String path, int max) throws Exception { require(has(path), "EXPORT_FAILED"); return read(path, max, hashes.get(path)); }
        JSONObject json(String path, int max) throws Exception { return new JSONObject(utf8(bytes(path, max))); }
        List<String> paths(String prefix, String suffix) {
            List<String> out = new ArrayList<>();
            for (String p : hashes.keySet()) if (p.startsWith(prefix) && p.endsWith(suffix) && p.indexOf('/', prefix.length()) < 0) out.add(p);
            return out;
        }
        private byte[] read(String path, int max, String expected) throws Exception {
            ZipEntry entry = zip.getEntry(path); require(entry != null && entry.getSize() >= 0 && entry.getSize() <= max, "EXPORT_FAILED");
            MessageDigest d = MessageDigest.getInstance("SHA-256"); ByteArrayOutputStream out = new ByteArrayOutputStream();
            try (InputStream in = zip.getInputStream(entry)) { byte[] b = new byte[8192]; int n; while ((n = in.read(b)) != -1) { require(out.size() + n <= max, "EXPORT_FAILED"); out.write(b, 0, n); d.update(b, 0, n); } }
            require(expected == null || expected.equals(hex(d.digest())), "EXPORT_FAILED");
            return out.toByteArray();
        }
        @Override public void close() throws IOException { zip.close(); }
    }

    private static String header(String title, JSONObject session, long startUtc) {
        StringBuilder s = new StringBuilder(title).append('\n');
        String t = session.optString("title", "");
        if (!t.trim().isEmpty()) s.append("Title: ").append(t.trim()).append('\n');
        if (startUtc > 0) s.append("Recorded: ").append(new SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.ROOT).format(new Date(startUtc))).append('\n');
        return s.toString();
    }

    private static String notes(Snapshot s, JSONObject session, long startUtc) throws Exception {
        Map<String, Long> offsets = new HashMap<>(); long at = 0; JSONArray chunks = session.getJSONArray("chunks");
        for (int i = 0; i < chunks.length(); i++) { offsets.put(chunks.getJSONObject(i).getString("filename"), at); at += chunks.getJSONObject(i).getLong("durationMillis"); }
        JSONObject latest = null;
        for (String path : s.paths("briefs/", ".json")) { JSONObject v = s.json(path, MeetingBriefStore.MAX_BYTES); if (latest == null || v.getLong("sequence") > latest.getLong("sequence")) latest = v; }
        List<JSONObject> own = new ArrayList<>();
        for (String path : s.paths("notes/", ".json")) own.add(s.json(path, 16384));
        own.sort(Comparator.comparingLong((JSONObject n) -> start(n, offsets)).thenComparing(n -> n.optString("noteId")));
        StringBuilder out = new StringBuilder(header("MARGIN meeting notes", session, startUtc));
        out.append("\nEXPERIMENTAL AND UNVERIFIED. Draft items were made from saved audio and have not been checked for accuracy. Check them before you share or act on them.\n");
        int written = 0;
        if (latest != null) for (String[] section : SECTIONS) {
            JSONArray items = latest.getJSONArray("items"); boolean head = false;
            for (int i = 0; i < items.length(); i++) {
                JSONObject item = items.getJSONObject(i); if (!section[0].equals(item.getString("section"))) continue;
                if (!head) { out.append("\n").append(section[1]).append('\n'); head = true; }
                String p = item.getString("provenance"), label = "USER_ENTERED".equals(p) ? "entered by you" : "AI_DRAFT".equals(p) ? "AI draft, unverified" : "exact excerpt of the machine transcript, unverified";
                out.append("actions".equals(section[0]) ? (item.getBoolean("done") ? "- [x] " : "- [ ] ") : "- ").append(item.getString("text").replace("\r\n", "\n").replace("\n", "\n  ")).append("\n  Source: ").append(label).append('\n');
                if (!item.isNull("owner")) out.append("  Owner (recorded manually): ").append(item.getString("owner")).append('\n');
                if (!item.isNull("date")) out.append("  Date (recorded manually): ").append(item.getString("date")).append('\n');
                JSONArray citations = item.getJSONArray("citations");
                for (int j = 0; j < citations.length(); j++) {
                    JSONObject c = citations.getJSONObject(j), w = c.getJSONObject("anchor").optJSONObject("window"); String quote = c.getString("quote");
                    out.append("  Saved audio window ").append(w == null ? "unknown" : span(w.optLong("startSample") / (RATE / 1000), w.optLong("endSample") / (RATE / 1000)));
                    if (!quote.equals(item.getString("text"))) out.append(": \"").append(quote.replace("\n", " ")).append('"');
                    out.append('\n');
                }
                written++;
            }
        }
        if (!own.isEmpty()) {
            out.append("\nYour notes (entered by you)\n");
            for (JSONObject n : own) {
                JSONObject src = n.getJSONObject("source"); Long base = offsets.get(src.getString("chunkFilename"));
                out.append("- ").append(base == null ? "" : "[" + span(base + src.getLong("startMs"), base + src.getLong("endMs")) + "] ").append(n.getString("text").replace("\r\n", "\n").replace("\n", "\n  ")).append('\n');
                written++;
            }
        }
        require(written > 0, "EXPORT_NO_NOTES");
        return out.toString();
    }
    private static long start(JSONObject note, Map<String, Long> offsets) {
        try { JSONObject src = note.getJSONObject("source"); Long base = offsets.get(src.getString("chunkFilename")); return base == null ? Long.MAX_VALUE : base + src.getLong("startMs"); } catch (JSONException error) { return Long.MAX_VALUE; }
    }

    private static String transcript(Context context, File meeting, Snapshot s, JSONObject session, long startUtc, AtomicBoolean cancel) throws Exception {
        JSONObject job = WindowedTranscription.inspect(context, meeting);
        require(job != null && job.getInt("completedWindows") > 0, "EXPORT_NO_TRANSCRIPT");
        String prefix = "transcription-jobs/" + job.getString("jobId") + "/"; JSONArray live = job.getJSONArray("windows");
        int done = job.getInt("completedWindows"), total = job.getInt("windowCount");
        StringBuilder out = new StringBuilder(header("MARGIN transcript (machine transcription, unverified)", session, startUtc));
        out.append("\nCoverage: ").append(done).append(" of ").append(total).append(" saved audio windows transcribed.");
        if (done < total) out.append(" PARTIAL: the remaining audio has no transcript yet.");
        out.append("\nTimes mark the start and end of each saved audio window, not individual words. Speakers are not identified. Window boundaries are estimates, so words at a boundary may be cut or repeated.\n");
        if (job.optBoolean("boundaryReviewRequired")) out.append("BOUNDARY REVIEW NEEDED: some windows overlap and their joined text has not been checked.\n");
        for (int i = 0; i < live.length(); i++) {
            stop(cancel);
            JSONObject w = live.getJSONObject(i), pointer = s.json(prefix + String.format(Locale.ROOT, "accepted-%04d.json", w.getJSONObject("window").getInt("index")), 262144), window = pointer.getJSONObject("window");
            String raw = s.json(prefix + pointer.getString("truthDirectory") + "/record.json", MAX_JSON).getJSONObject("asr").getString("text");
            require(pointer.getString("truthDirectory").equals(w.getString("truthDirectory")) && raw.equals(w.getString("text")), "EXPORT_FAILED");
            long overlap = window.optLong("overlapSamples"); String boundary = window.optString("boundaryKind");
            out.append("\n[").append(span(window.getLong("startSample") / (RATE / 1000), window.getLong("endSample") / (RATE / 1000))).append(']');
            if (overlap > 0) out.append(" (overlaps the previous window by ").append(overlap / RATE).append(" s)");
            if ("overlap_needs_review".equals(boundary)) out.append(" (no clear pause found at the end of this window; text may repeat)");
            else if ("vad_silence_estimate".equals(boundary)) out.append(" (window end estimated from a pause)");
            out.append('\n').append(raw.trim().isEmpty() ? "(no speech recognised in this window)" : raw.trim()).append('\n');
        }
        return out.toString();
    }

    /** Streams the PCM of every chunk, in session metadata order, into one mono 16 kHz PCM16 WAV without decoding or resampling. */
    private static JSONObject audio(Context ctx, Snapshot s, JSONObject session, long startUtc, AtomicBoolean cancel) throws Exception {
        JSONArray chunks = session.getJSONArray("chunks"); require(chunks.length() > 0 && chunks.length() <= 512, "EXPORT_LIMIT");
        String[] names = new String[chunks.length()]; long[] sizes = new long[names.length]; long total = 0;
        for (int i = 0; i < names.length; i++) {
            names[i] = chunks.getJSONObject(i).getString("filename"); require(names[i].matches("chunk-[0-9]+\\.wav") && s.has(names[i]), "EXPORT_FAILED");
            try (InputStream in = new BufferedInputStream(s.zip.getInputStream(s.zip.getEntry(names[i])), 4096)) { sizes[i] = payload(new DataInputStream(in), s.zip.getEntry(names[i]).getSize()); }
            require(sizes[i] / 32 == chunks.getJSONObject(i).getLong("durationMillis"), "EXPORT_FAILED");
            total += sizes[i]; require(total <= MAX_BYTES - 44 && total <= 0xFFFFFFFFL - 36, "EXPORT_LIMIT");
        }
        final long pcm = total;
        return commit(ctx, "audio", "wav", "audio/wav", startUtc, pcm + 44, cancel, out -> {
            ByteBuffer h = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN);
            h.put("RIFF".getBytes(StandardCharsets.US_ASCII)).putInt((int) (36 + pcm)).put("WAVEfmt ".getBytes(StandardCharsets.US_ASCII)).putInt(16).putShort((short) 1).putShort((short) 1).putInt(RATE).putInt(RATE * 2).putShort((short) 2).putShort((short) 16).put("data".getBytes(StandardCharsets.US_ASCII)).putInt((int) pcm);
            out.write(h.array());
            for (int i = 0; i < names.length; i++) copy(s, names[i], sizes[i], out, cancel);
        });
    }

    /** Validates a chunk header (any chunk order, no fixed 44-byte assumption), leaves the stream at the first PCM byte and returns the payload size. */
    private static long payload(DataInputStream in, long entrySize) throws IOException {
        byte[] h = new byte[12]; in.readFully(h);
        require("RIFF".equals(new String(h, 0, 4, StandardCharsets.US_ASCII)) && "WAVE".equals(new String(h, 8, 4, StandardCharsets.US_ASCII)) && 8 + u32(h, 4) == entrySize, "EXPORT_AUDIO_UNSUPPORTED");
        long at = 12; boolean format = false;
        while (at + 8 <= entrySize) {
            byte[] c = new byte[8]; in.readFully(c); String id = new String(c, 0, 4, StandardCharsets.US_ASCII); long size = u32(c, 4);
            require(size <= entrySize - at - 8, "EXPORT_AUDIO_UNSUPPORTED");
            if ("fmt ".equals(id)) {
                require(!format && size >= 16, "EXPORT_AUDIO_UNSUPPORTED"); byte[] f = new byte[16]; in.readFully(f); ByteBuffer b = ByteBuffer.wrap(f).order(ByteOrder.LITTLE_ENDIAN);
                require(b.getShort(0) == 1 && b.getShort(2) == 1 && b.getInt(4) == RATE && b.getInt(8) == RATE * 2 && b.getShort(12) == 2 && b.getShort(14) == 16, "EXPORT_AUDIO_UNSUPPORTED");
                format = true; skip(in, size - 16 + (size & 1));
            } else if ("data".equals(id)) {
                require(format && size > 0 && size % 2 == 0, "EXPORT_AUDIO_UNSUPPORTED"); return size;
            } else skip(in, size + (size & 1));
            at += 8 + size + (size & 1);
        }
        throw new IOException("EXPORT_AUDIO_UNSUPPORTED");
    }
    private static void skip(InputStream in, long count) throws IOException {
        byte[] b = new byte[4096];
        while (count > 0) { int n = in.read(b, 0, (int) Math.min(count, b.length)); require(n > 0, "EXPORT_AUDIO_UNSUPPORTED"); count -= n; }
    }
    private static void copy(Snapshot s, String name, long size, OutputStream out, AtomicBoolean cancel) throws Exception {
        ZipEntry entry = s.zip.getEntry(name); MessageDigest d = MessageDigest.getInstance("SHA-256");
        try (InputStream raw = s.zip.getInputStream(entry)) {
            DigestInputStream in = new DigestInputStream(new BufferedInputStream(raw, 65536), d);
            require(payload(new DataInputStream(in), entry.getSize()) == size, "EXPORT_FAILED");
            byte[] b = new byte[65536]; long left = size;
            while (left > 0) { stop(cancel); int n = in.read(b, 0, (int) Math.min(left, b.length)); require(n > 0, "EXPORT_FAILED"); out.write(b, 0, n); left -= n; }
            while (in.read(b) != -1) stop(cancel);
        }
        require(s.hashes.get(name).equals(hex(d.digest())), "EXPORT_FAILED");
    }

    private interface Producer { void write(OutputStream out) throws Exception; }

    /** Counting, hashing, cancellable sink with the shared 512 MiB bound. */
    private static final class Sink extends OutputStream {
        final OutputStream file; final AtomicBoolean cancel; final MessageDigest digest; long count;
        Sink(OutputStream file, AtomicBoolean cancel) throws Exception { this.file = file; this.cancel = cancel; digest = MessageDigest.getInstance("SHA-256"); }
        @Override public void write(int b) throws IOException { write(new byte[] {(byte) b}, 0, 1); }
        @Override public void write(byte[] b, int off, int len) throws IOException { stop(cancel); count += len; require(count <= MAX_BYTES, "EXPORT_LIMIT"); digest.update(b, off, len); file.write(b, off, len); }
    }

    /** Writes UUID.part, syncs, renames atomically, syncs the directory and re-reads the committed file's hash. */
    private static JSONObject commit(Context context, String kind, String ext, String mime, long startUtc, long estimate, AtomicBoolean cancel, Producer producer) throws Exception {
        File dir = new File(context.getFilesDir().getCanonicalFile(), "exports").getAbsoluteFile();
        require(dir.equals(dir.getCanonicalFile()) && dir.isDirectory(), "EXPORT_FAILED");
        File[] previous = dir.listFiles();
        require(previous != null && previous.length < 16, "EXPORT_LIMIT");
        require(dir.getUsableSpace() > estimate + 16L * 1024 * 1024, "EXPORT_STORAGE");
        String id = UUID.randomUUID().toString(); File part = new File(dir, id + "." + ext + ".part"), result = new File(dir, id + "." + ext);
        require(part.createNewFile(), "EXPORT_FAILED"); boolean committed = false;
        try {
            Sink sink; String sha;
            try (FileOutputStream stream = new FileOutputStream(part)) {
                sink = new Sink(stream, cancel); producer.write(sink); stream.flush(); stream.getFD().sync();
            }
            require(sink.count > 0 && part.length() == sink.count, "EXPORT_FAILED");
            sha = hex(sink.digest.digest()); stop(cancel);
            require(!result.exists() && part.renameTo(result), "EXPORT_FAILED"); committed = true;
            FileDescriptor fd = Os.open(dir.getPath(), OsConstants.O_RDONLY, 0); try { Os.fsync(fd); } finally { Os.close(fd); }
            require(result.length() == sink.count && sha.equals(SourceBundle.sha(result)), "EXPORT_FAILED");
            JSONObject out = new JSONObject().put("file", result.getAbsolutePath()).put("sha256", sha).put("bytes", sink.count).put("mimeType", mime).put("displayName", name(kind, ext, startUtc)).put("kind", kind);
            committed = false;
            return out;
        } finally {
            if (part.exists()) part.delete();
            if (committed) result.delete();
        }
    }
}
