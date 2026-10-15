package org.gtnhmigration.compat;
import java.util.Map;
import java.util.UUID;
/** Independently authored model of reviewed BQ3.8.89 single-item non-consuming retrieval semantics.
 * Partial progress is a current-inventory observation, not a high-water mark.
 * Completed requirements remain latched; multiple calls must not accumulate.
 */
public final class LegacyRetrievalCounter {
    private LegacyRetrievalCounter() {}
    public static long next(long previous, long required, Map<UUID, ? extends Iterable<Integer>> matches) {
        if (required < 1 || previous < 0) throw new IllegalArgumentException("Invalid retrieval progress");
        if (previous >= required) return required;
        long total = 0;
        for (Iterable<Integer> stacks : matches.values()) {
            for (int count : stacks) {
                if (count < 0) throw new IllegalArgumentException("Negative item count");
                total += Math.min((long) count, required - total);
                if (total >= required) return required;
            }
        }
        return total;
    }
}
