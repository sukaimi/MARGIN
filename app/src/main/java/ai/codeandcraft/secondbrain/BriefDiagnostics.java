package ai.codeandcraft.secondbrain;

import android.content.Context;
import android.os.Debug;
import java.io.*;
import java.nio.charset.StandardCharsets;
import org.json.JSONObject;

/** Fixed vocabulary only: no exception details, evidence, identity or meeting keys. */
public final class BriefDiagnostics {
    private BriefDiagnostics() {}
    public static synchronized void record(Context context,String stage,long elapsedMs,String code) {
        record(context,stage,elapsedMs,code,"GENERATE".equals(stage)?"source-excerpt-v1":"EDIT".equals(stage)?"manual-v1":"brief-store-v1");
    }
    public static synchronized void record(Context context,String stage,long elapsedMs,String code,String engine) {
        if(!java.util.Arrays.asList("source-excerpt-v1","smollm3-q4km-v1","manual-v1","brief-store-v1").contains(engine))return;
        if(!java.util.Arrays.asList("LOAD","GENERATE","EDIT").contains(stage) || !java.util.Arrays.asList("OK","CANCELLED","UNAVAILABLE","INVALID").contains(code))return;
        try {
            File filesRoot=context.getFilesDir().getCanonicalFile();
            File root=new File(filesRoot,"diagnostics");if(!root.equals(root.getCanonicalFile()))return;
            if(!root.exists()&&!root.mkdir())return;
            File file=new File(root,"brief.jsonl"),old=new File(root,"brief.previous.jsonl");
            if(!file.equals(file.getCanonicalFile())||!old.equals(old.getCanonicalFile()))return;
            if(file.length()>32768){if(old.exists()&&!old.delete())return;if(!file.renameTo(old))return;}
            Debug.MemoryInfo memory=new Debug.MemoryInfo();Debug.getMemoryInfo(memory);
            JSONObject event=new JSONObject().put("stage",stage).put("engine",engine).put("buildVersion",context.getPackageManager().getPackageInfo(context.getPackageName(),0).versionCode).put("elapsedMs",Math.max(0,elapsedMs)).put("errorCode",code).put("memoryPssKb",memory.getTotalPss());
            try(FileOutputStream out=new FileOutputStream(file,true)){out.write((event.toString()+"\n").getBytes(StandardCharsets.UTF_8));}
        } catch(Exception ignored) { /* Diagnostics never affect evidence or report exception contents. */ }
    }
}
