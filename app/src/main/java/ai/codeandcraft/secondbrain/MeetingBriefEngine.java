package ai.codeandcraft.secondbrain;

import android.content.Context;
import java.io.File;
import java.util.concurrent.atomic.AtomicBoolean;
import org.json.*;

/** Replaceable post-recording provider. All outputs remain editable, unverified drafts. */
public interface MeetingBriefEngine {
    String id();
    JSONObject draft(Context context, File meeting, AtomicBoolean cancel) throws Exception;

    /** Provider boundary: providers cannot declare user edits, owners, dates or completion state. */
    static JSONObject generate(MeetingBriefEngine provider, Context context, File meeting, AtomicBoolean cancel) throws Exception {
        if(RecorderService.isRecording() || cancel.get())throw new java.io.IOException("BRIEF_UNAVAILABLE");
        JSONObject output=provider.draft(context,meeting,cancel);
        if(output.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8).length>MeetingBriefStore.MAX_BYTES)
            throw new java.io.IOException("BRIEF_LIMIT");
        if(!"DRAFT".equals(output.optString("status")))return output;
        if(!provider.id().equals(output.getString("engine")) || output.getBoolean("claimsVerified") ||
            !java.util.Arrays.asList("EXACT_EXCERPT","AI_DRAFT").contains(output.getString("provenance")))throw new java.io.IOException("BRIEF_PROVIDER_INVALID");
        JSONArray items=output.getJSONArray("items");if(items.length()>64)throw new java.io.IOException("BRIEF_LIMIT");
        for(int i=0;i<items.length();i++){
            JSONObject item=items.getJSONObject(i);
            if(!java.util.Arrays.asList("EXACT_EXCERPT","AI_DRAFT").contains(item.getString("provenance")) ||
                !item.has("owner") || !item.has("date") || !item.isNull("owner") || !item.isNull("date") || item.optBoolean("done"))
                throw new java.io.IOException("BRIEF_PROVIDER_INVALID");
        }
        if(cancel.get() || Thread.currentThread().isInterrupted())throw new java.io.IOException("BRIEF_CANCELLED");
        return output;
    }

    final class SourceDraft implements MeetingBriefEngine {
        public String id() { return "source-excerpt-v1"; }
        public JSONObject draft(Context context, File meeting, AtomicBoolean cancel) throws Exception {
            JSONObject evidence = EvidenceExcerptBaseline.build(context, meeting, 4, 5000, cancel);
            JSONObject result = new JSONObject().put("schemaVersion",1).put("engine",id())
                .put("provenance","EXACT_EXCERPT").put("status",evidence.optString("status"))
                .put("claimsVerified",false).put("items",new JSONArray());
            if (!"READY_EXTRACTIVE".equals(evidence.optString("status"))) return result;
            JSONArray excerpts=evidence.getJSONArray("excerpts"), items=result.getJSONArray("items");
            for(int i=0;i<excerpts.length();i++) {
                JSONObject quote=excerpts.getJSONObject(i);
                items.put(new JSONObject().put("section","summary").put("text",quote.getString("quote"))
                    .put("provenance","EXACT_EXCERPT").put("citations",new JSONArray().put(quote))
                    .put("owner",JSONObject.NULL).put("date",JSONObject.NULL).put("done",false));
            }
            return result.put("status","DRAFT");
        }
    }
}
