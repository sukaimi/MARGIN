package ai.codeandcraft.secondbrain;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Collections;

public final class BriefAccuracyPolicyTest {
    private static int checks;
    private static void check(boolean ok, String name) {
        checks++;
        if (!ok) throw new AssertionError(name);
    }
    private static void accepts(String section, String source, String claim) {
        check(!BriefAccuracyPolicy.obviousStatusConflict(section, claim, Collections.singletonList(source)), claim);
    }
    private static void rejects(String section, String source, String claim) {
        check(BriefAccuracyPolicy.obviousStatusConflict(section, claim, Collections.singletonList(source)), claim);
    }
    public static void main(String[] args) throws Exception {
        rejects("summary", "Maya will release the update on Friday.", "Maya released the update on Friday.");
        rejects("summary", "Leo will complete the test by Tuesday.", "Leo completed the test by Tuesday.");
        rejects("summary", "We did not send the enquiry.", "We sent the enquiry.");
        rejects("summary", "We won’t ship the patch.", "We shipped the patch.");
        rejects("summary", "If approved, Maya will send the enquiry on Friday.", "Maya sent the enquiry on Friday.");
        rejects("decisions", "We agreed to release on Friday.", "The Friday release is uncertain.");
        rejects("decisions", "We agreed to release on Friday.", "The Friday release is tentative.");
        rejects("decisions", "I propose a Friday release.", "The release is approved for Friday.");
        rejects("decisions", "We suggest a Friday release.", "Release on Friday.");
        rejects("summary", "The release date is not yet confirmed.", "The release date is confirmed.");
        accepts("decisions", "We agreed to release on Friday.", "Agreed: release on Friday.");
        accepts("actions", "Maya will release the update on Friday.", "Maya will release the update on Friday.");
        accepts("summary", "Maya will release the update on Friday.", "The update will be released on Friday.");
        accepts("summary", "We did not send the enquiry.", "The enquiry was not sent.");
        accepts("summary", "We did not send the enquiry.", "The enquiry wasn't sent.");
        accepts("summary", "Maya released the update on Friday.", "The update was released on Friday.");
        accepts("questions", "The date is not confirmed.", "The date is unconfirmed.");
        accepts("questions", "If approved, Maya will send the enquiry on Friday.", "If approved, Maya will send the enquiry on Friday.");
        accepts("summary", "I propose a Friday release.", "A Friday release was proposed.");
        accepts("decisions", "We agreed to release if tests pass.", "Release remains uncertain until tests pass.");
        accepts("decisions", "We agreed to release on Friday, with ownership unclear.", "Release agreed; ownership uncertain.");
        accepts("actions", "Leo will test by Tuesday; Maya will send results by Friday.", "Leo will test by Tuesday; Maya will send results by Friday.");
        check(!BriefAccuracyPolicy.obviousStatusConflict("summary", "Maya released the update.",
            Arrays.asList("Maya will release the update.", "Maya released the update.")), "Mixed sources left for review");
        // These are policy/wiring assertions, not evidence that a model follows the prompt.
        String prompt = BriefAccuracyPolicy.INSTRUCTIONS;
        for (String required : new String[]{"planned work is not completed work", "Only explicit agreement", "negation, conditions and uncertainty",
                "explicit action owners and deadlines", "never guess", "Retain unconfirmed dates and conditional enquiries", "each under 300", "at most 8"})
            check(prompt.contains(required), "Prompt contract: " + required);
        String engine = Files.readString(Path.of(args[0], "app/src/main/java/ai/codeandcraft/secondbrain/SmolBriefEngine.java"));
        check(engine.contains("INSTRUCTIONS = BriefAccuracyPolicy.INSTRUCTIONS"), "Real engine uses prompt");
        check(engine.contains("if (BriefAccuracyPolicy.obviousStatusConflict(section, text, citedText)) return null;"), "Real parser fails closed");
        check(engine.contains("citedText.add(s.text)"), "Guard reads code-owned cited evidence");
        check(engine.contains("\"-c\", \"2048\", \"-n\", \"384\""), "Output headroom; unchanged context");
        check(engine.contains("\"--no-context-shift\""), "Do not evict earlier evidence to extend generation");
        check(engine.contains("MAX_ITEMS = 8"), "Prompt/parser/schema item limit aligned");
        check(engine.contains("put(\"claimsVerified\", false)"), "No quality assertion");
        check(engine.contains("put(\"owner\", JSONObject.NULL).put(\"date\", JSONObject.NULL).put(\"done\", false)"), "No inferred structured owner/deadline/completion");
        check(engine.contains("put(\"accuracyPolicy\", BriefAccuracyPolicy.REVISION)"), "Draft version identifies policy");
        System.out.println("PASS: " + checks + " brief accuracy tripwire/contract checks; no model inference executed");
    }
}
