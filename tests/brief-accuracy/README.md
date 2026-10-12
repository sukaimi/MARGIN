# Brief accuracy regression checks

Run `sh tests/brief-accuracy/run.sh` with JDK 17. It uses only synthetic text and a temporary JVM output directory. No Android runtime, model weights, recording, network or device is used.

## What changed

- The Smol prompt explicitly preserves plans vs completion, decisions vs proposals, negation, conditional enquiries, uncertain dates, and source-stated owners/deadlines.
- Prompt/schema/parser now share an eight-item cap. The native output allowance is 384 tokens instead of 256, to give the model room for actions plus unresolved issues. The 2048-token context, model, resource gates and 180-second deadline are unchanged. At a previously observed approximately 3 tokens/second, the extra 128-token allowance could cost about 43 seconds; this is a rough estimate, not a new measurement. Native latency/memory/timeout impact is unmeasured.
- Native context shifting is explicitly disabled, so earlier evidence is not evicted to make room for generation. The flag exists in both the restored runner strings and the pinned upstream [argument parser](https://github.com/ggml-org/llama.cpp/blob/11fe02151f79c41d0d4af7da708755d73b9c0da6/common/arg.cpp). Character limits are not token counts: exact prompt plus output fit still needs the real tokenizer/runtime. A failed process or incomplete JSON is rejected, not accepted as a partial draft.
- The parser rejects the entire draft when a narrow lexical tripwire identifies an obvious single-source status inversion. It never fabricates a replacement claim or edits source evidence.
- Draft metadata records `accuracy-v2`; `claimsVerified` remains false and structured owner/date/done fields remain unset/false. Source-stated owners/deadlines belong in draft text pending human review.

## What the checks establish

The JVM checks exercise the actual pure-Java tripwire with synthetic planned/completed, negative, conditional, proposal and unconfirmed-date pairs. Positive fixtures preserve explicit owners/deadlines and qualifications. Source-wiring assertions verify that the production prompt and parser use this policy, output limits align, and the draft provenance remains unverified.

These checks are not full JSON-parser, Android, native Smol or model-quality tests. Prompt assertions show that instructions exist, not that the model obeys them. Passing the lexical guard does not prove entailment, completeness or correct owner/deadline attribution. It deliberately does not attempt to resolve multi-source claims. Many unsupported paraphrases can pass. Bounded snippet selection still skips oversized/later input, and cited evidence alone does not prove that all its qualifications survived a paraphrase.

## Runtime acceptance still required

On an explicitly authorized synthetic runtime only, run representative synthetic scenarios against the intended model and evidence. Inspect the complete JSON and citations, not just owner/deadline totals. Check agreed release remains agreed, planned work remains planned, unconfirmed dates and conditional enquiries appear, no negation is lost, proposals remain tentative, and explicit owners/deadlines remain correct. Also record status, truncation, latency and resource gates. Preserve failed outputs as unverified test artifacts. Do not use real recordings or assert improved model quality from these JVM checks.
