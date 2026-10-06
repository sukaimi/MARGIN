package ai.codeandcraft.secondbrain;

import java.security.SecureRandom;
import java.util.function.LongSupplier;

/** Process-local capability; no Android, persistence, caller identity or exported mint API. */
final class RecordEntryGuard {
    static final String EXTRA_TOKEN = "recordEntryToken";
    static final long TTL_NANOS = 10_000_000_000L;
    static final RecordEntryGuard INSTANCE = new RecordEntryGuard(System::nanoTime);
    private final LongSupplier clock;
    private final SecureRandom random = new SecureRandom();
    private String pending;
    private long issuedAt;

    RecordEntryGuard(LongSupplier monotonicClock) { clock = monotonicClock; }

    // Production call site must be solely inside the Detail Record click listener.
    synchronized String issueFromDetailClick() {
        byte[] bytes = new byte[32];
        random.nextBytes(bytes);
        StringBuilder token = new StringBuilder(64);
        for (byte value : bytes) {
            token.append(Character.forDigit((value >>> 4) & 15, 16));
            token.append(Character.forDigit(value & 15, 16));
        }
        pending = token.toString(); // Overwrite: at most one outstanding capability.
        issuedAt = clock.getAsLong();
        return pending;
    }

    synchronized boolean allowRecord(String page, String token, boolean restored) {
        if (pending == null) return false;
        long age = clock.getAsLong() - issuedAt;
        if (age < 0 || age >= TTL_NANOS) { pending = null; return false; }
        if (token == null || !pending.equals(token)) return false;
        pending = null; // Consume even if recreation or a non-Record page rejects it.
        return !restored && "Record".equals(page);
    }
}
