package ai.codeandcraft.secondbrain;
import java.io.*;
import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

public final class SessionIndexPathsTest {
    private static int checks;
    interface Action { void run() throws Exception; }
    static void check(boolean ok) { checks++;if(!ok)throw new AssertionError(); }
    static void rejected(Action action) throws Exception {
        try { action.run();throw new AssertionError("Expected rejection"); }
        catch(IOException expected) { checks++; }
    }
    static final class ChangingMtimeFile extends File {
        int reads; final long initial;
        ChangingMtimeFile(String name,long initial) { super(name);this.initial=initial; }
        @Override public long lastModified() { return initial+1000L*reads++; }
    }
    public static void main(String[] args) throws Exception {
        ChangingMtimeFile older=new ChangingMtimeFile("older",1),newer=new ChangingMtimeFile("newer",2);
        ChangingMtimeFile tieB=new ChangingMtimeFile("b",3),tieA=new ChangingMtimeFile("a",3);
        File[] original={older,tieB,newer,tieA};
        File[] sorted=SessionIndexPaths.newestFirst(original);
        check(Arrays.equals(sorted,new File[]{tieA,tieB,newer,older}));
        for(ChangingMtimeFile file:new ChangingMtimeFile[]{older,newer,tieB,tieA})check(file.reads==1);
        check(original[0]==older && original[1]==tieB);
        check(SessionIndexPaths.uuidLooking("00000000-0000-4000-8000-00000000000A"));
        check(!SessionIndexPaths.validId("00000000-0000-4000-8000-00000000000A"));
        check(!SessionIndexPaths.uuidLooking("unrelated-directory"));
        Path temp=Files.createTempDirectory("synthetic-session-index-");
        Path root=Files.createDirectory(temp.resolve("sessions"));
        String id="00000000-0000-4000-8000-000000000001";
        for(int i=1;i<=10;i++) {
            String name=String.format(Locale.ROOT,"00000000-0000-4000-8000-%012d",i);
            Path session=Files.createDirectory(root.resolve(name));
            byte[] bytes=("{\"id\":\""+name+"\",\"status\":\"COMPLETED\"}").getBytes(StandardCharsets.UTF_8);
            Files.write(session.resolve("session.json"),bytes);
            File chosen=SessionIndexPaths.candidate(root.toFile(),session.toFile());
            check(chosen.equals(session.toFile().getCanonicalFile()));
            check(Arrays.equals(bytes,SessionIndexPaths.metadata(chosen)));
            check(Arrays.equals(bytes,Files.readAllBytes(session.resolve("session.json"))));
        }
        Path alias=Files.createSymbolicLink(temp.resolve("alias"),root);
        check(SessionIndexPaths.candidate(alias.toFile(),alias.resolve(id).toFile()).equals(root.resolve(id).toFile().getCanonicalFile()));
        rejected(()->SessionIndexPaths.candidate(root.toFile(),root.resolve("../sessions/"+id).toFile()));
        Path outside=Files.createDirectory(temp.resolve("outside"));
        String escaped="00000000-0000-4000-8000-000000000011";
        Path outsideSession=Files.createDirectory(outside.resolve(escaped));
        Files.createSymbolicLink(root.resolve(escaped),outsideSession);
        rejected(()->SessionIndexPaths.candidate(root.toFile(),root.resolve(escaped).toFile()));
        rejected(()->SessionIndexPaths.candidate(root.toFile(),outsideSession.toFile()));
        String renamed="00000000-0000-4000-8000-000000000012";
        Files.createSymbolicLink(root.resolve(renamed),root.resolve(id));
        rejected(()->SessionIndexPaths.candidate(root.toFile(),root.resolve(renamed).toFile()));
        for(String bad:new String[]{"not-a-uuid","1-1-1-1-1","00000000-0000-4000-8000-00000000000A","../"+id})check(!SessionIndexPaths.validId(bad));
        Path malformed=Files.createDirectory(root.resolve("00000000-0000-4000-8000-00000000000A"));
        rejected(()->SessionIndexPaths.candidate(root.toFile(),malformed.toFile()));
        Path missing=Files.createDirectory(root.resolve("00000000-0000-4000-8000-000000000013"));
        rejected(()->SessionIndexPaths.metadata(missing.toFile()));
        Path metaLink=Files.createDirectory(root.resolve("00000000-0000-4000-8000-000000000014"));
        Files.createSymbolicLink(metaLink.resolve("session.json"),root.resolve(id).resolve("session.json"));
        rejected(()->SessionIndexPaths.metadata(metaLink.toFile()));
        Path oversized=Files.createDirectory(root.resolve("00000000-0000-4000-8000-000000000015"));
        Files.write(oversized.resolve("session.json"),new byte[SessionIndexPaths.MAX_METADATA_BYTES+1]);
        rejected(()->SessionIndexPaths.metadata(oversized.toFile()));
        Path directoryMeta=Files.createDirectory(root.resolve("00000000-0000-4000-8000-000000000016"));
        Files.createDirectory(directoryMeta.resolve("session.json"));
        rejected(()->SessionIndexPaths.metadata(directoryMeta.toFile()));
        // Synthetic backup remains untouched; no recovery, rename or deletion during indexing.
        byte[] backup="synthetic backup".getBytes(StandardCharsets.UTF_8);
        Files.write(missing.resolve("session.json.bak"),backup);
        rejected(()->SessionIndexPaths.metadata(missing.toFile()));
        check(Arrays.equals(backup,Files.readAllBytes(missing.resolve("session.json.bak"))));
        System.out.println("SessionIndexPathsTest: "+checks+" checks passed");
    }
}
