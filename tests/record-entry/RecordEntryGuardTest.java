package ai.codeandcraft.secondbrain;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicLong;

public final class RecordEntryGuardTest {
    private static int checks;
    private static void check(boolean condition, String message) {
        checks++;
        if (!condition) throw new AssertionError(message);
    }
    private static String block(String source, String marker) {
        int start = source.indexOf(marker);
        check(start >= 0, "Missing integration marker: " + marker);
        int open = source.indexOf('{', start), depth = 1, end = open + 1;
        while (depth > 0 && end < source.length()) {
            char c = source.charAt(end++);
            if (c == '{') depth++;
            if (c == '}') depth--;
        }
        check(depth == 0, "Unbalanced integration block");
        return source.substring(open + 1, end - 1);
    }
    private static int count(String source, String text) {
        return (source.length() - source.replace(text, "").length()) / text.length();
    }
    public static void main(String[] args) throws Exception {
        AtomicLong now = new AtomicLong(42);
        RecordEntryGuard guard = new RecordEntryGuard(now::get);
        check(!guard.allowRecord(null, null, false), "Null external entry");
        check(!guard.allowRecord("Record", null, false), "Plain external Record");
        check(!guard.allowRecord("Record", "caller=internal", false), "Identity extras are not trust");
        String valid = guard.issueFromDetailClick();
        check(valid.matches("[0-9a-f]{64}"), "256-bit token format");
        check(!guard.allowRecord("Record", "0".repeat(64), false), "Forged token");
        check(!guard.allowRecord("Record", null, false), "Missing token with pending capability");
        check(guard.allowRecord("Record", valid, false), "Valid internal token");
        check(!guard.allowRecord("Record", valid, false), "Replay after consumption");
        String expired = guard.issueFromDetailClick();
        now.addAndGet(RecordEntryGuard.TTL_NANOS);
        check(!guard.allowRecord("Record", expired, false), "Expiry at boundary");
        check(!guard.allowRecord("Record", expired, false), "Expired token stays rejected");
        String older = guard.issueFromDetailClick();
        String newer = guard.issueFromDetailClick();
        check(!older.equals(newer), "Independent random tokens");
        check(!guard.allowRecord("Record", older, false), "Overwritten token");
        now.addAndGet(RecordEntryGuard.TTL_NANOS - 1);
        check(guard.allowRecord("Record", newer, false), "Fresh replacement before expiry");
        check(!guard.allowRecord("Record", newer, false), "Replacement consumes once");
        String restored = guard.issueFromDetailClick();
        check(!guard.allowRecord("Record", restored, true), "Restore must not start capture");
        check(!guard.allowRecord("Record", restored, false), "Restore cannot retain capability");
        String wrongPage = guard.issueFromDetailClick();
        check(!guard.allowRecord("Tasks", wrongPage, false), "Non-Record target");
        check(!guard.allowRecord("Record", wrongPage, false), "Non-Record entry consumes capability");
        String priorProcess = guard.issueFromDetailClick();
        check(!new RecordEntryGuard(now::get).allowRecord("Record", priorProcess, false), "No process persistence");
        String backwards = guard.issueFromDetailClick();
        now.decrementAndGet();
        check(!guard.allowRecord("Record", backwards, false), "Negative elapsed time fails closed");

        Path base = Path.of(args[0], "app/src/main/java/ai/codeandcraft/secondbrain");
        String main = Files.readString(base.resolve("MainActivity.java"));
        String create = block(main, "public void onCreate(Bundle b)");
        String incoming = block(main, "protected void onNewIntent(Intent intent)");
        check(create.contains("handleIncomingIntent(getIntent(),b!=null)"), "Cold/restore entry uses shared handler");
        check(incoming.contains("handleIncomingIntent(intent,false)"), "Warm entry uses shared handler");
        check(!create.contains("record();") && !incoming.contains("record();"), "No lifecycle bypass");
        check(create.contains("if(b!=null&&\"Record\".equals(page))page=\"Meetings\""), "Restored page is safe before rendering");
        String handler = block(main, "private void handleIncomingIntent(Intent intent,boolean restored)");
        check(handler.contains("RecordEntryGuard.INSTANCE.allowRecord(target,token,restored)"), "Guard is consulted");
        check(handler.contains("if(permitted){record();return;}"), "Only permitted incoming Record can capture");
        check(count(handler, "record();") == 1, "No second incoming capture path");
        check(handler.contains("page=\"Meetings\";") && handler.indexOf("page=\"Meetings\";") < handler.indexOf("render();"), "Rejected Record renders safe Meetings");
        check(handler.contains("intent.removeExtra(RecordEntryGuard.EXTRA_TOKEN)"), "Token removed from stored intent");
        check(!block(main, "protected void onSaveInstanceState(Bundle b)").contains("TOKEN"), "Token not saved");
        check(main.contains("r.setOnClickListener(v->record())"), "Main direct Record click retained");
        String detail = Files.readString(base.resolve("MeetingDetailActivity.java"));
        String click = block(detail, "n.setOnClickListener(v->");
        check(click.contains("new android.content.Intent(this,MainActivity.class)"), "Explicit internal destination");
        check(click.contains("if(\"Record\".equals(name))intent.putExtra(RecordEntryGuard.EXTRA_TOKEN,RecordEntryGuard.INSTANCE.issueFromDetailClick())"), "Mint only on actual Detail Record click");
        check(count(detail, "issueFromDetailClick()") == 1, "Single Detail mint site");
        try (var files = Files.list(base)) {
            for (Path file : files.filter(p -> p.toString().endsWith(".java")).toList()) {
                if (!file.getFileName().toString().equals("RecordEntryGuard.java") && !file.getFileName().toString().equals("MeetingDetailActivity.java"))
                    check(!Files.readString(file).contains("issueFromDetailClick("), "No other production mint site: " + file.getFileName());
            }
        }
        System.out.println("PASS: " + checks + " guard and source integration checks (JVM/source only; no Android or device execution)");
    }
}
