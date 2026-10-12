package ai.codeandcraft.secondbrain;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.util.ArrayList;
import java.util.List;

/** Deletes only app-owned meeting data. Never traverses models, settings or shared storage. */
final class CleanSlate {
    private static final int MAX_ENTRIES = 100000, MAX_DEPTH = 64;
    private CleanSlate() {}

    /** Caller must exclude recording, transcription and meeting-screen work for this whole call. */
    static int deleteMeetingData(File filesDir, File cacheDir) throws IOException {
        File files = root(filesDir), cache = root(cacheDir);
        if (files.equals(cache) || inside(files, cache) || inside(cache, files))
            throw new IOException("Storage roots overlap");
        List<File> plan = plan(files, cache);
        int removed = 0;
        for (File entry : plan) {
            safe(entry, inside(entry, files) ? files : cache);
            Files.delete(entry.toPath());
            removed++;
        }
        if (!plan(files, cache).isEmpty()) throw new IOException("Meeting data remains");
        return removed;
    }

    interface QueueRemoval { void remove() throws IOException; }
    static final class LegacyFilesPresent extends IOException {}
    private static final String UUID_PATTERN = "[a-f0-9]{8}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{12}";

    /** One canonical meeting only; caller holds the same worker exclusion as full cleanup. */
    static int deleteMeeting(File filesDir, File cacheDir, String id, QueueRemoval queue) throws IOException {
        if (id == null || !id.matches(UUID_PATTERN)) throw new IOException("Invalid meeting id");
        File files = root(filesDir), cache = root(cacheDir);
        if (files.equals(cache) || inside(files, cache) || inside(cache, files))
            throw new IOException("Storage roots overlap");
        List<File> plan = meetingPlan(files, cache, id);
        // Validate every path before changing the queue or deleting evidence.
        queue.remove();
        int removed = 0;
        for (File entry : plan) {
            safe(entry, inside(entry, files) ? files : cache);
            Files.delete(entry.toPath()); removed++;
        }
        if (!meetingPlan(files, cache, id).isEmpty()) throw new IOException("Meeting data remains");
        return removed;
    }

    private static List<File> meetingPlan(File files, File cache, String id) throws IOException {
        List<File> plan = new ArrayList<>();
        for (String suffix : new String[]{"", ".bak", ".new"}) {
            File queue = new File(files, "auto-transcription-index.json" + suffix);
            if (exists(queue)) {
                safe(queue, files);
                if (!queue.isFile() || queue.length() > 262144) throw new IOException("Unsafe queue data");
            }
        }
        File sessions = new File(files, "sessions");
        if (exists(sessions)) safe(sessions, files);
        collect(new File(sessions, id), files, plan, 0);
        File exports = new File(files, "exports");
        if (exists(exports)) {
            safe(exports, files);
            for (File entry : children(exports)) {
                String name = entry.getName();
                if (!name.matches(UUID_PATTERN + "--" + UUID_PATTERN + "\\.(zip|txt|wav)(\\.part)?"))
                    throw new LegacyFilesPresent();
                if (name.startsWith(id + "--")) collect(entry, files, plan, 0);
            }
        }
        for (File entry : children(cache)) {
            String name = entry.getName();
            if (name.matches("export-" + UUID_PATTERN)) throw new LegacyFilesPresent();
            if (name.matches("export-" + id + "--" + UUID_PATTERN)) collect(entry, cache, plan, 0);
        }
        File prompts = new File(cache, "smolbrief");
        if (exists(prompts)) {
            safe(prompts, cache);
            for (File entry : children(prompts)) {
                String name = entry.getName();
                if (name.matches(UUID_PATTERN + "\\.(prompt|schema\\.json)")) throw new LegacyFilesPresent();
                if (name.matches(id + "--" + UUID_PATTERN + "\\.(prompt|schema\\.json)")) collect(entry, cache, plan, 0);
            }
        }
        // Keep the list entry until every owned derivative is removed, so an I/O failure can be retried from the UI.
        File session = new File(sessions, id), metadata = new File(session, "session.json");
        if (plan.remove(metadata)) plan.add(metadata);
        if (plan.remove(session)) plan.add(session);
        return plan;
    }

    private static List<File> plan(File files, File cache) throws IOException {
        List<File> plan = new ArrayList<>();
        collect(new File(files, "sessions"), files, plan, 0);
        collect(new File(files, "exports"), files, plan, 0);
        for (String suffix : new String[]{"", ".bak", ".new"})
            collect(new File(files, "auto-transcription-index.json" + suffix), files, plan, 0);
        for (File entry : children(cache)) {
            if (entry.getName().matches("export-" + UUID_PATTERN + "(--" + UUID_PATTERN + ")?"))
                collect(entry, cache, plan, 0);
        }
        File prompts = new File(cache, "smolbrief");
        if (exists(prompts)) {
            safe(prompts, cache);
            for (File entry : children(prompts)) {
                if (entry.getName().matches(UUID_PATTERN + "(--" + UUID_PATTERN + ")?\\.(prompt|schema\\.json)"))
                    collect(entry, cache, plan, 0);
            }
        }
        return plan;
    }

    private static File root(File input) throws IOException {
        File root = input.getCanonicalFile();
        if (!root.isDirectory()) throw new IOException("Storage root unavailable");
        return root;
    }
    private static boolean inside(File entry, File root) {
        return entry.toPath().startsWith(root.toPath()) && !entry.equals(root);
    }
    private static boolean exists(File file) {
        return Files.exists(file.toPath(), LinkOption.NOFOLLOW_LINKS);
    }
    private static void safe(File entry, File root) throws IOException {
        if (!inside(entry, root) || Files.isSymbolicLink(entry.toPath())
                || !entry.equals(entry.getCanonicalFile()))
            throw new IOException("Unsafe meeting data path");
    }
    private static File[] children(File directory) throws IOException {
        File[] entries = directory.listFiles();
        if (entries == null) throw new IOException("Meeting data could not be listed");
        return entries;
    }
    private static void collect(File entry, File root, List<File> plan, int depth) throws IOException {
        if (!exists(entry)) return;
        safe(entry, root);
        if (depth > MAX_DEPTH || plan.size() >= MAX_ENTRIES)
            throw new IOException("Meeting data exceeds cleanup safety limit");
        if (entry.isDirectory()) {
            for (File child : children(entry)) collect(child, root, plan, depth + 1);
        } else if (!entry.isFile()) throw new IOException("Unexpected meeting data type");
        if (plan.size() >= MAX_ENTRIES) throw new IOException("Meeting data exceeds cleanup safety limit");
        plan.add(entry);
    }
}
