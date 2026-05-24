package org.json_kula.jsonata_jvm.runtime.datetime;

import java.time.*;
import java.time.format.DateTimeParseException;
import org.json_kula.jsonata_jvm.runtime.RuntimeEvaluationException;

public final class IsoConverter {

    private IsoConverter() {}

    // -------------------------------------------------------------------------
    // millis → ISO string
    // -------------------------------------------------------------------------

    public static String millisToIso(long millis) {
        return millisToIso(millis, "UTC");
    }

    public static String millisToIso(long millis, String timezone) throws RuntimeEvaluationException {
        Instant instant = Instant.ofEpochMilli(millis);
        ZoneOffset offset = (timezone == null || timezone.isEmpty() || "UTC".equals(timezone))
                ? ZoneOffset.UTC
                : TimezoneUtils.parseZoneOffset(timezone);
        ZonedDateTime dt = instant.atZone(offset);
        String tzSuffix = (offset.getTotalSeconds() == 0) ? "Z"
                : dt.getOffset().getId();  // e.g. "+05:30"
        return String.format("%04d-%02d-%02dT%02d:%02d:%02d.%03d%s",
                dt.getYear(), dt.getMonthValue(), dt.getDayOfMonth(),
                dt.getHour(), dt.getMinute(), dt.getSecond(),
                instant.getNano() / 1_000_000,
                tzSuffix);
    }

    // -------------------------------------------------------------------------
    // ISO string → millis
    // -------------------------------------------------------------------------

    public static long isoToMillis(String timestamp) throws RuntimeEvaluationException {
        // Fast path: standard ISO 8601 instant
        try {
            return Instant.parse(timestamp).toEpochMilli();
        } catch (DateTimeParseException ignored) {}

        // Normalise bare ±HHMM offset to ±HH:MM and retry
        String normalised = TimezoneUtils.normalizeOffsetInTimestamp(timestamp);
        if (!normalised.equals(timestamp)) {
            try {
                return Instant.parse(normalised).toEpochMilli();
            } catch (DateTimeParseException ignored) {}
        }

        // Year-only or year-month partial dates
        try {
            if (timestamp.matches("\\d{4}")) {
                return LocalDate.of(Integer.parseInt(timestamp), 1, 1)
                        .atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli();
            }
            if (timestamp.matches("\\d{4}-\\d{2}")) {
                String[] parts = timestamp.split("-");
                return LocalDate.of(Integer.parseInt(parts[0]), Integer.parseInt(parts[1]), 1)
                        .atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli();
            }
            return LocalDate.parse(timestamp)
                    .atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli();
        } catch (DateTimeParseException e2) {
            throw new RuntimeEvaluationException(
                    "D3110", "$toMillis: invalid ISO 8601 timestamp: " + timestamp);
        }
    }
}
