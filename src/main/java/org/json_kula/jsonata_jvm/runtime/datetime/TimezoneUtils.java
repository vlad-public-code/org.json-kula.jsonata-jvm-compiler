package org.json_kula.jsonata_jvm.runtime.datetime;

import java.time.ZoneOffset;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.json_kula.jsonata_jvm.runtime.RuntimeEvaluationException;

final class TimezoneUtils {

    private TimezoneUtils() {}

    private static final Pattern GMT_OFFSET =
            Pattern.compile("GMT([+-])(\\d{1,2})(?::(\\d{2}))?");
    // Normalises ±HHMM → ±HH:MM in timestamps (only when no colon is present)
    private static final Pattern BARE_OFFSET_IN_TIMESTAMP =
            Pattern.compile(" ([+-])(\\d{2})(\\d{2})$");

    /**
     * Parses a timezone string into a {@link ZoneOffset}.
     *
     * <p>Accepted formats (previously only {@code ±HHMM} was accepted):
     * <ul>
     *   <li>{@code Z} or {@code UTC} or empty — UTC</li>
     *   <li>{@code ±HHMM} — e.g. {@code -0500}, {@code +0530}</li>
     *   <li>{@code ±HH:MM} — e.g. {@code -05:00}, {@code +05:30} (Fix: was rejected before)</li>
     *   <li>{@code GMT±HH:MM} / {@code GMT±HH} — e.g. {@code GMT-05:00}</li>
     * </ul>
     */
    static ZoneOffset parseZoneOffset(String tz) throws RuntimeEvaluationException {
        if (tz == null || tz.isEmpty() || "Z".equals(tz) || "UTC".equals(tz)) return ZoneOffset.UTC;
        if ("0000".equals(tz) || "+0000".equals(tz) || "-0000".equals(tz)) return ZoneOffset.UTC;

        if (tz.startsWith("GMT")) {
            if (tz.length() == 3) return ZoneOffset.UTC;
            Matcher m = GMT_OFFSET.matcher(tz);
            if (!m.matches()) throw invalid(tz);
            int h = Integer.parseInt(m.group(2));
            int min = m.group(3) != null ? Integer.parseInt(m.group(3)) : 0;
            int secs = (h * 60 + min) * 60;
            return ZoneOffset.ofTotalSeconds("-".equals(m.group(1)) ? -secs : secs);
        }

        char sign = tz.charAt(0);
        if (sign != '+' && sign != '-') throw invalid(tz);
        try {
            int h, min;
            if (tz.contains(":")) {
                // ±HH:MM  (Fix: this format was previously rejected)
                String[] parts = tz.substring(1).split(":");
                h   = Integer.parseInt(parts[0]);
                min = Integer.parseInt(parts[1]);
            } else {
                // ±HHMM
                if (tz.length() != 5) throw invalid(tz);
                h   = Integer.parseInt(tz.substring(1, 3));
                min = Integer.parseInt(tz.substring(3, 5));
            }
            int secs = (h * 60 + min) * 60;
            return ZoneOffset.ofTotalSeconds(sign == '-' ? -secs : secs);
        } catch (RuntimeEvaluationException e) {
            throw e;
        } catch (Exception e) {
            throw invalid(tz);
        }
    }

    /**
     * Normalises a bare {@code ±HHMM} suffix at the very end of a timestamp string to
     * {@code ±HH:MM} so that {@link java.time.Instant#parse} can accept it.
     * Returns the original string if no normalisation is needed.
     *
     * <p>Fix: removed the incorrect length-6 branches that produced malformed offsets.
     */
    static String normalizeOffsetInTimestamp(String timestamp) {
        int len = timestamp.length();
        // Already ends with Z / z — no work needed
        if (len >= 1) {
            char last = timestamp.charAt(len - 1);
            if (last == 'Z' || last == 'z') return timestamp;
        }
        // Scan backwards for the last +/- that is preceded only by digits and colons
        int signPos = -1;
        for (int i = len - 1; i >= 0; i--) {
            char c = timestamp.charAt(i);
            if (c == '+' || c == '-') { signPos = i; break; }
            if (!Character.isDigit(c) && c != ':') break;
        }
        if (signPos < 0) return timestamp;

        String tz = timestamp.substring(signPos);
        if (tz.contains(":")) return timestamp; // already has colon

        // Only handle ±HHMM (length 5 including sign); anything else is left untouched
        if (tz.length() == 5) {
            String normalised = tz.substring(0, 3) + ":" + tz.substring(3);
            return timestamp.substring(0, signPos) + normalised;
        }
        return timestamp;
    }

    private static RuntimeEvaluationException invalid(String tz) {
        return new RuntimeEvaluationException("D3110", "Invalid timezone: " + tz);
    }
}
