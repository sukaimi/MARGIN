package ai.codeandcraft.secondbrain;

import java.io.*;
import java.util.UUID;
import java.util.Arrays;

/** Read-only index selection; canonical parents may legitimately be aliased. */
final class SessionIndexPaths {
    static final int MAX_METADATA_BYTES = 262144;
    private SessionIndexPaths() {}
    static boolean validId(String id) {
        try { return id.length()==36 && UUID.fromString(id).toString().equals(id); }
        catch (IllegalArgumentException e) { return false; }
    }
    static boolean uuidLooking(String id) {
        return id.matches("(?i)[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}");
    }
    static File[] newestFirst(File[] files) {
        // Snapshot once: the comparator must never observe changing filesystem times.
        final class Entry {
            final File file; final long modified;
            Entry(File file) { this.file=file; modified=file.lastModified(); }
        }
        Entry[] entries=new Entry[files.length];
        for(int i=0;i<files.length;i++)entries[i]=new Entry(files[i]);
        Arrays.sort(entries,(a,b)->{
            int order=Long.compare(b.modified,a.modified);
            return order!=0?order:a.file.getName().compareTo(b.file.getName());
        });
        File[] sorted=new File[files.length];
        for(int i=0;i<entries.length;i++)sorted[i]=entries[i].file;
        return sorted;
    }
    static File candidate(File root, File entry) throws IOException {
        if (!validId(entry.getName())) throw new IOException();
        for(File part=entry;part!=null;part=part.getParentFile())
            if("..".equals(part.getName()) || ".".equals(part.getName())) throw new IOException();
        File canonicalRoot=root.getCanonicalFile(), canonical=entry.getCanonicalFile();
        if (!canonicalRoot.equals(entry.getAbsoluteFile().getParentFile().getCanonicalFile())
                || !canonicalRoot.equals(canonical.getParentFile())
                || !entry.getName().equals(canonical.getName()) || !canonical.isDirectory())
            throw new IOException();
        return canonical;
    }
    static byte[] metadata(File session) throws IOException {
        File canonical=session.getCanonicalFile();
        File meta=new File(canonical,"session.json");
        // Do not use AtomicFile.readFully: recovery can rename/delete preserved files.
        if (!meta.equals(meta.getCanonicalFile()) || !meta.isFile()
                || meta.length()>MAX_METADATA_BYTES) throw new IOException();
        try (InputStream in=new FileInputStream(meta); ByteArrayOutputStream out=new ByteArrayOutputStream()) {
            byte[] buffer=new byte[4096]; int n,total=0;
            while ((n=in.read(buffer))!=-1) {
                total+=n; if(total>MAX_METADATA_BYTES) throw new IOException();
                out.write(buffer,0,n);
            }
            return out.toByteArray();
        }
    }
}
