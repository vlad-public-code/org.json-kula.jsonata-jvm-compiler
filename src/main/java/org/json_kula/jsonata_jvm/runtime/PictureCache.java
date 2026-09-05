package org.json_kula.jsonata_jvm.runtime;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Function;

/**
 * A small bounded cache for analysed picture strings.
 *
 * <p>Every {@code $formatNumber}, {@code $formatInteger}, {@code $fromMillis} and
 * {@code $toMillis} call used to re-parse its picture, though the picture is a
 * compile-time literal at essentially every call site.
 *
 * <p>Bounded on <em>both</em> axes, which matters because a picture can come from input
 * data and its length is therefore caller-controlled: an entry count caps how many are
 * kept, and a key-length limit means a pathologically long picture is analysed afresh
 * each time rather than retained. The same shape as {@code RegexRegistry}'s compiled-regex
 * cache.
 *
 * <p>Cached values must be immutable. A formatter that mutated an analysis it was handed
 * would corrupt every later call for the same picture — which is why the analysis records
 * are records with with-ers rather than mutable structs.
 *
 * @param <V> the analysed form
 */
public final class PictureCache<V> {

    /** A picture longer than this is not worth keeping, and is the hostile-input case. */
    private static final int MAX_KEY_LENGTH = 256;

    private final Map<String, V> entries;
    private final Function<String, V> analyse;

    /**
     * @param maxEntries how many analyses to retain, least-recently-used evicted first
     * @param analyse    the analysis to memoize; must not throw a checked exception
     */
    public PictureCache(int maxEntries, Function<String, V> analyse) {
        this.analyse = analyse;
        this.entries = Collections.synchronizedMap(new LinkedHashMap<>(16, 0.75f, true) {
            @Override
            protected boolean removeEldestEntry(Map.Entry<String, V> eldest) {
                return size() > maxEntries;
            }
        });
    }

    /** Returns the analysis of {@code picture}, computing it if it is not already held. */
    public V get(String picture) {
        if (picture.length() > MAX_KEY_LENGTH) return analyse.apply(picture);
        return entries.computeIfAbsent(picture, analyse);
    }

    /**
     * Wraps a checked-exception failure so an analysis can still be memoized.
     *
     * <p>An invalid picture is a per-picture property, so the failure is cached and
     * rethrown alongside the successes rather than being re-derived on every call.
     */
    public static final class AnalysisFailure extends RuntimeException {
        private final RuntimeEvaluationException cause;

        public AnalysisFailure(RuntimeEvaluationException cause) {
            super(cause.getMessage(), cause, false, false);
            this.cause = cause;
        }

        /** Rethrows the original checked exception. */
        public RuntimeEvaluationException unwrap() {
            return new RuntimeEvaluationException(cause.getErrorCode(), cause.getMessage());
        }
    }
}
