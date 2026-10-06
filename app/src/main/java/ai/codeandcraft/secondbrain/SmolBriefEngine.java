package ai.codeandcraft.secondbrain;

import android.app.ActivityManager;
import android.content.Context;
import android.os.Build;
import android.os.PowerManager;
import android.os.SystemClock;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.text.BreakIterator;
import java.text.SimpleDateFormat;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.json.*;

/**
 * Experimental local SmolLM3 AI_DRAFT provider. Builds a draft only; never persists.
 * Citations are code-owned exact excerpt records copied from the verified evidence corpus; the model
 * only chooses section, text and snippet ids. Coverage is always bounded/partial-labelled.
 */
public final class SmolBriefEngine implements MeetingBriefEngine {
    static final String ID = "smollm3-q4km-v1";
    private static final String MODEL_SHA256 = "8334b850b7bd46238c16b0c550df2138f0889bf433809008cc17a8b05761863e";
    private static final long MODEL_BYTES = 1915305312L;
    private static final String EXECUTABLE = "libmargin-llama.so";
    private static final int MAX_SNIPPETS = 16, MAX_RAW_CHARS = 1800, MAX_SNIPPET_CHARS = 400;
    private static final int MAX_ITEMS = 12, MAX_ITEM_CHARS = 300, MAX_IDS_PER_ITEM = 4;
    private static final int MAX_STDOUT = 64 * 1024;
    private static final long DEADLINE_MS = 180_000L, MIN_AVAIL_BYTES = 2800L << 20, RESERVE_BYTES = 384L << 20;
    private static final List<String> SECTIONS = Arrays.asList("summary", "decisions", "actions", "questions", "risks");
    private static final String INSTRUCTIONS =
        "You draft an editable meeting brief using ONLY the numbered snippets in the user message. "
        + "Do not add facts, names, owners, dates or quotes that are not in the snippets. Never infer an owner, assignment or deadline. Metadata is not meeting evidence. Items may be wrong; keep them short. "
        + "Reply with one JSON object {\"items\":[{\"section\":\"summary|decisions|actions|questions|risks\",\"text\":\"...\",\"sourceIds\":[1]}]} and nothing else. "
        + "Use at most 6 items, each under 300 characters, each citing snippet ids. If nothing is supported, return {\"items\":[]}.";

    private static final class Snippet { String text; int start, end; JSONObject anchor; }
    private static final class Stop extends Exception { final String status; Stop(String s) { super(s); status = s; } }

    @Override public String id() { return ID; }

    @Override public JSONObject draft(Context context, File meeting, AtomicBoolean cancel) throws Exception {
        long begin = SystemClock.elapsedRealtime();
        File work = null, prompt = null, schema = null;
        try {
            checkLive(cancel);
            JSONObject evidence = EvidenceExcerptBaseline.build(context, meeting, 8, 5000, cancel);
            checkLive(cancel);
            String status = evidence.optString("status");
            if (!"READY_EXTRACTIVE".equals(status)) return result(status.isEmpty() ? "INSUFFICIENT_EVIDENCE" : status, "SOURCE_NOT_READY", begin);
            boolean[] omitted = new boolean[1];
            ArrayList<Snippet> snippets = snippets(evidence.getJSONArray("evidenceCorpus"), omitted);
            if (snippets.isEmpty()) return result("INSUFFICIENT_EVIDENCE", "NO_BOUNDED_SNIPPETS", begin);
            String gate = gate(context);
            if (gate != null) return result(gate, gate, begin);
            File model = verifiedModel(context, cancel);
            File exe = new File(context.getApplicationInfo().nativeLibraryDir, EXECUTABLE);
            if (model == null || !exe.isFile() || !exe.canExecute()) return result("MODEL_UNAVAILABLE", "MODEL_UNAVAILABLE", begin);
            work = privateDir(context);
            String name = UUID.randomUUID().toString();
            prompt = inside(work, name + ".prompt"); schema = inside(work, name + ".schema.json");
            write(prompt, buildPrompt(snippets));
            write(schema, schema(snippets.size()));
            String out = run(context, exe, model, prompt, schema, cancel);
            JSONArray items = parse(out, snippets);
            if (items == null) return result("INVALID_MODEL_OUTPUT", "INVALID_MODEL_OUTPUT", begin);
            checkLive(cancel);
            JSONObject r = result("DRAFT", "EXPERIMENTAL_PARTIAL_COVERAGE", begin);
            r.put("items", items);
            return r.put("label", "Experimental AI draft — may be wrong, editable, not accepted facts")
                .put("coverage", omitted[0] ? "PARTIAL_SNIPPETS_ONLY" : "ALL_BOUNDED_SNIPPETS_ONLY")
                .put("snippetCount", snippets.size()).put("sourceSentencesOmitted", omitted[0])
                .put("warning", "Draft used at most " + MAX_SNIPPETS + " short transcript sentences (" + MAX_RAW_CHARS
                    + " characters); it does not cover the whole meeting. Check against the source.");
        } catch (Stop stop) {
            return result(stop.status, stop.status, begin);
        } catch (IOException e) {
            if (e.getMessage() != null && e.getMessage().startsWith("BRIEF_")) throw e;
            throw new IOException("BRIEF_FAILED");
        } catch (Exception e) {
            throw new IOException("BRIEF_FAILED");
        } finally {
            if (prompt != null) prompt.delete();
            if (schema != null) schema.delete();
        }
    }

    private static JSONObject result(String status, String detail, long begin) throws Exception {
        return new JSONObject().put("schemaVersion", 1).put("engine", ID).put("status", status).put("detail", detail)
            .put("provenance", "AI_DRAFT").put("claimsVerified", false).put("items", new JSONArray())
            .put("experimental", true).put("elapsedMs", SystemClock.elapsedRealtime() - begin);
    }

    private static void checkLive(AtomicBoolean cancel) throws IOException {
        if (cancel.get() || Thread.currentThread().isInterrupted()) throw new IOException("BRIEF_CANCELLED");
        if (RecorderService.isRecording()) throw new IOException("BRIEF_UNAVAILABLE");
    }

    /** Real sentence boundaries and exact offsets; oversized complete sentences are skipped, never clipped. */
    private static ArrayList<Snippet> snippets(JSONArray corpus, boolean[] omitted) throws Exception {
        ArrayList<Snippet> list = new ArrayList<>();
        int chars = 0;
        for (int i = 0; i < corpus.length(); i++) {
            JSONObject entry = corpus.getJSONObject(i);
            String text = entry.getString("rawText");
            BreakIterator it = BreakIterator.getSentenceInstance(Locale.ENGLISH);
            it.setText(text);
            int start = it.first();
            for (int end = it.next(); end != BreakIterator.DONE; start = end, end = it.next()) {
                int a = start, b = end;
                while (a < b && Character.isWhitespace(text.charAt(a))) a++;
                while (b > a && Character.isWhitespace(text.charAt(b - 1))) b--;
                if (a == b) continue;
                int len = b - a;
                if (list.size() >= MAX_SNIPPETS || len > MAX_SNIPPET_CHARS || chars + len > MAX_RAW_CHARS) { omitted[0] = true; continue; }
                Snippet s = new Snippet();
                s.text = text.substring(a, b); s.start = a; s.end = b; s.anchor = new JSONObject(entry.getJSONObject("anchor").toString());
                chars += len; list.add(s);
            }
        }
        return list;
    }

    /** Returns a status string when the device must not run the model now, else null. */
    private static String gate(Context context) {
        ActivityManager am = (ActivityManager) context.getSystemService(Context.ACTIVITY_SERVICE);
        if (am == null) return "MEMORY_UNKNOWN";
        ActivityManager.MemoryInfo mi = new ActivityManager.MemoryInfo();
        am.getMemoryInfo(mi);
        if (mi.lowMemory || mi.availMem < MIN_AVAIL_BYTES) return "INSUFFICIENT_MEMORY";
        if (thermal(context) >= 3) return "THERMAL_THROTTLED";
        return null;
    }

    private static int thermal(Context context) {
        if (Build.VERSION.SDK_INT < 29) return 0;
        PowerManager pm = (PowerManager) context.getSystemService(Context.POWER_SERVICE);
        return pm == null ? 0 : pm.getCurrentThermalStatus();
    }

    private static File privateDir(Context context) throws IOException {
        File dir = new File(context.getCacheDir(), "smolbrief");
        if (!dir.isDirectory() && !dir.mkdirs()) throw new IOException("dir");
        return dir.getCanonicalFile();
    }

    private static File inside(File dir, String name) throws IOException {
        File f = new File(dir, name).getCanonicalFile();
        if (!dir.equals(f.getParentFile())) throw new IOException("path");
        return f;
    }

    private static void write(File f, String s) throws IOException {
        try (OutputStream out = new FileOutputStream(f)) { out.write(s.getBytes(StandardCharsets.UTF_8)); }
    }

    /** Fixed model path, exact size, SHA-256 once; result cached by size+mtime in private cache. */
    private static File verifiedModel(Context context, AtomicBoolean cancel) throws Exception {
        File root = new File(context.getFilesDir(), "models/smollm3").getCanonicalFile();
        File model = new File(root, "SmolLM3-Q4_K_M.gguf").getCanonicalFile();
        if (!root.equals(model.getParentFile()) || !model.isFile() || model.length() != MODEL_BYTES) return null;
        String stamp = MODEL_SHA256 + "|" + model.length() + "|" + model.lastModified();
        File mark = inside(privateDir(context), "model.verified");
        if (mark.isFile() && mark.length() < 256) {
            try (BufferedReader r = new BufferedReader(new InputStreamReader(new FileInputStream(mark), StandardCharsets.UTF_8))) {
                if (stamp.equals(r.readLine())) return model;
            }
        }
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        try (InputStream in = new FileInputStream(model)) {
            byte[] buf = new byte[1 << 16];
            int n;
            while ((n = in.read(buf)) != -1) { checkLive(cancel); digest.update(buf, 0, n); }
        }
        StringBuilder hex = new StringBuilder();
        for (byte b : digest.digest()) hex.append(String.format(Locale.ROOT, "%02x", b & 255));
        if (!MODEL_SHA256.equals(hex.toString())) { mark.delete(); return null; }
        write(mark, stamp + "\n");
        return model;
    }

    /** Exact SmolLM3 ChatML render, no tools: the system header is intentionally left unclosed. */
    private static String buildPrompt(List<Snippet> snippets) throws Exception {
        JSONArray arr = new JSONArray();
        for (int i = 0; i < snippets.size(); i++) arr.put(new JSONObject().put("id", i + 1).put("text", snippets.get(i).text));
        String user = new JSONObject().put("snippets", arr).toString()
            + "\n\nRequest: draft the brief items as specified.";
        String today = new SimpleDateFormat("dd MMMM yyyy", Locale.ENGLISH).format(new Date());
        return "<|im_start|>system\n## Metadata\n\nKnowledge Cutoff Date: June 2025\nToday Date: " + today
            + "\nReasoning Mode: /no_think\n\n## Custom Instructions\n\n" + INSTRUCTIONS
            + "\n\n<|im_start|>user\n" + user + "<|im_end|>\n<|im_start|>assistant\n<think>\n\n</think>\n";
    }

    private static String schema(int snippetCount) throws Exception {
        JSONObject item = new JSONObject().put("type", "object").put("additionalProperties", false)
            .put("required", new JSONArray().put("section").put("text").put("sourceIds"))
            .put("properties", new JSONObject()
                .put("section", new JSONObject().put("enum", new JSONArray(SECTIONS)))
                .put("text", new JSONObject().put("type", "string").put("minLength", 1).put("maxLength", MAX_ITEM_CHARS))
                .put("sourceIds", new JSONObject().put("type", "array").put("minItems", 1).put("maxItems", MAX_IDS_PER_ITEM)
                    .put("items", new JSONObject().put("type", "integer").put("minimum", 1).put("maximum", snippetCount))));
        return new JSONObject().put("type", "object").put("additionalProperties", false)
            .put("required", new JSONArray().put("items"))
            .put("properties", new JSONObject().put("items", new JSONObject().put("type", "array").put("maxItems", MAX_ITEMS).put("items", item)))
            .toString();
    }

    private static final class Reader extends Thread {
        final InputStream in; final ByteArrayOutputStream buf = new ByteArrayOutputStream(); volatile boolean overflow, failed;
        Reader(InputStream in) { this.in = in; }
        @Override public void run() {
            byte[] b = new byte[4096];
            try {
                int n;
                while ((n = in.read(b)) != -1) {
                    if (buf.size() + n > MAX_STDOUT) { overflow = true; return; }
                    buf.write(b, 0, n);
                }
            } catch (IOException ignored) { failed = true; }
        }
    }

    /** Owns the child: argv only, stderr to /dev/null, bounded stdout, killed on cancel/recording/limits. */
    private static String run(Context context, File exe, File model, File prompt, File schema, AtomicBoolean cancel) throws Exception {
        List<String> argv = Arrays.asList(exe.getAbsolutePath(), "-m", model.getAbsolutePath(),
            "--no-conversation", "--simple-io", "--no-display-prompt", "--no-warmup", "--fit", "off",
            "-c", "2048", "-n", "256", "-t", "2", "-tb", "2", "-b", "128", "-ub", "128", "-ngl", "0", "-fa", "off",
            "--temp", "0.6", "--top-p", "0.95", "--seed", "42",
            "-f", prompt.getAbsolutePath(), "-jf", schema.getAbsolutePath());
        ProcessBuilder pb = new ProcessBuilder(argv);
        pb.redirectError(ProcessBuilder.Redirect.to(new File("/dev/null")));
        pb.environment().put("LD_LIBRARY_PATH", context.getApplicationInfo().nativeLibraryDir);
        ActivityManager am = (ActivityManager) context.getSystemService(Context.ACTIVITY_SERVICE);
        ActivityManager.MemoryInfo mi = new ActivityManager.MemoryInfo();
        checkLive(cancel);
        String initialGate = gate(context);
        if (initialGate != null) throw new Stop(initialGate);
        Process proc = pb.start();
        Reader reader = new Reader(proc.getInputStream());
        String stop = null;
        try {
            proc.getOutputStream().close();
            reader.start();
            long deadline = SystemClock.elapsedRealtime() + DEADLINE_MS;
            while (!proc.waitFor(100, TimeUnit.MILLISECONDS)) {
                if (cancel.get() || Thread.currentThread().isInterrupted()) { stop = "BRIEF_CANCELLED"; break; }
                if (RecorderService.isRecording()) { stop = "BRIEF_UNAVAILABLE"; break; }
                if (SystemClock.elapsedRealtime() >= deadline) { stop = "TIMEOUT"; break; }
                if (reader.overflow) { stop = "INVALID_MODEL_OUTPUT"; break; }
                if (reader.failed) { stop = "MODEL_RUN_FAILED"; break; }
                if (thermal(context) >= 3) { stop = "THERMAL_THROTTLED"; break; }
                if (am != null) { am.getMemoryInfo(mi); if (mi.lowMemory || mi.availMem < RESERVE_BYTES) { stop = "INSUFFICIENT_MEMORY"; break; } }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            stop = "BRIEF_CANCELLED";
        } finally {
            // Clear interrupt during owned-child cleanup; restore it afterwards.
            boolean interrupted = Thread.interrupted();
            try {
                if (proc.isAlive()) {
                    proc.destroy();
                    if (!proc.waitFor(3, TimeUnit.SECONDS)) {
                        proc.destroyForcibly();
                        if (!proc.waitFor(3, TimeUnit.SECONDS)) stop = "MODEL_RUN_FAILED";
                    }
                }
                // On normal exit drain to EOF before closing stdout. Never parse a partial buffer.
                reader.join(2000);
                if (reader.isAlive() && stop == null) stop = "MODEL_RUN_FAILED";
            } catch (InterruptedException e) {
                interrupted = true;
                proc.destroyForcibly();
                stop = "BRIEF_CANCELLED";
            } finally {
                if (proc.isAlive()) {
                    proc.destroyForcibly();
                    long cleanupDeadline = SystemClock.elapsedRealtime() + 3000;
                    while (proc.isAlive() && SystemClock.elapsedRealtime() < cleanupDeadline) {
                        try { proc.waitFor(100, TimeUnit.MILLISECONDS); }
                        catch (InterruptedException e) { interrupted = true; }
                    }
                    if (proc.isAlive() && stop == null) stop = "MODEL_RUN_FAILED";
                }
                try { proc.getInputStream().close(); } catch (IOException ignored) { }
                try { reader.join(2000); } catch (InterruptedException e) { interrupted = true; }
                if (interrupted) Thread.currentThread().interrupt();
            }
        }
        if (stop != null) {
            if (stop.startsWith("BRIEF_")) throw new IOException(stop);
            throw new Stop(stop);
        }
        if (reader.overflow || reader.failed || reader.isAlive() || proc.isAlive() || proc.exitValue() != 0) throw new Stop(reader.overflow ? "INVALID_MODEL_OUTPUT" : "MODEL_RUN_FAILED");
        return new String(reader.buf.toByteArray(), StandardCharsets.UTF_8);
    }

    /** All-or-nothing: any invalid item rejects the whole output. Citations copied from snippets by id. */
    private static JSONArray parse(String raw, List<Snippet> snippets) {
        try {
            String json = raw.trim();
            String terminal = "[end of text]";
            if (json.endsWith(terminal)) json = json.substring(0, json.length() - terminal.length()).trim();
            if (!json.startsWith("{") || !json.endsWith("}")) return null;
            // Android JSONObject is lenient; enforce JSON syntax before converting values.
            try (android.util.JsonReader strict = new android.util.JsonReader(new StringReader(json))) {
                strict.setLenient(false);
                strict.beginObject();
                while (strict.hasNext()) { strict.nextName(); strict.skipValue(); }
                strict.endObject();
                if (strict.peek() != android.util.JsonToken.END_DOCUMENT) return null;
            }
            JSONTokener tokener = new JSONTokener(json);
            Object value = tokener.nextValue();
            if (!(value instanceof JSONObject) || tokener.nextClean() != 0) return null;
            JSONObject root = (JSONObject) value;
            if (root.length() != 1 || !root.has("items")) return null;
            JSONArray in = root.getJSONArray("items"), out = new JSONArray();
            if (in.length() > MAX_ITEMS) return null;
            for (int i = 0; i < in.length(); i++) {
                JSONObject o = in.getJSONObject(i);
                if (o.length() != 3 || !(o.get("section") instanceof String) || !(o.get("text") instanceof String)) return null;
                String section = o.getString("section"), text = o.getString("text").trim();
                if (!SECTIONS.contains(section) || text.isEmpty() || text.length() > MAX_ITEM_CHARS) return null;
                JSONArray ids = o.getJSONArray("sourceIds");
                if (ids.length() < 1 || ids.length() > MAX_IDS_PER_ITEM) return null;
                TreeSet<Integer> unique = new TreeSet<>();
                for (int k = 0; k < ids.length(); k++) {
                    Object v = ids.get(k);
                    if (!(v instanceof Integer)) return null;
                    int id = (Integer) v;
                    if (id < 1 || id > snippets.size()) return null;
                    unique.add(id);
                }
                JSONArray citations = new JSONArray();
                for (int id : unique) {
                    Snippet s = snippets.get(id - 1);
                    citations.put(new JSONObject().put("quote", s.text).put("rawTextCharStart", s.start)
                        .put("rawTextCharEnd", s.end).put("anchor", new JSONObject(s.anchor.toString())));
                }
                out.put(new JSONObject().put("section", section).put("text", text).put("provenance", "AI_DRAFT")
                    .put("citations", citations).put("owner", JSONObject.NULL).put("date", JSONObject.NULL).put("done", false));
            }
            return out;
        } catch (Exception e) {
            return null;
        }
    }
}
