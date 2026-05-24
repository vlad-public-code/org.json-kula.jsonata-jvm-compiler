package org.json_kula.jsonata_jvm.runtime;

import org.json_kula.jsonata_jvm.runtime.datetime.IsoConverter;
import org.json_kula.jsonata_jvm.runtime.datetime.PictureFormatter;
import org.json_kula.jsonata_jvm.runtime.datetime.PictureParser;

/**
 * Facade for the JSONata date/time built-in functions.
 *
 * <p>All implementation lives in {@code org.json_kula.jsonata_jvm.runtime.datetime}.
 * This class exists solely so that {@link JsonataRuntime} can call a stable API.
 */
final class DateTimeUtils {

    private DateTimeUtils() {}

    /** Formats {@code millis} as an ISO 8601 UTC string, e.g. {@code "2017-11-07T15:12:37.121Z"}. */
    static String millisToIso(long millis) {
        return IsoConverter.millisToIso(millis);
    }

    /** Formats {@code millis} as ISO 8601 in the given timezone (e.g. {@code "-0500"}). */
    static String millisToIso(long millis, String timezone) throws RuntimeEvaluationException {
        return IsoConverter.millisToIso(millis, timezone);
    }

    /** Formats {@code millis} using an XPath/XQuery picture string. */
    static String millisToPicture(long millis, String picture, String timezone)
            throws RuntimeEvaluationException {
        return PictureFormatter.format(millis, picture, timezone);
    }

    /** Parses an ISO 8601 timestamp string and returns milliseconds since the Unix epoch. */
    static long isoToMillis(String timestamp) throws RuntimeEvaluationException {
        return IsoConverter.isoToMillis(timestamp);
    }

    /**
     * Parses {@code timestamp} using an XPath/XQuery picture string.
     *
     * @return epoch millis, or {@link Long#MIN_VALUE} if the input does not match.
     */
    static long pictureToMillis(String timestamp, String picture)
            throws RuntimeEvaluationException {
        return PictureParser.parse(timestamp, picture);
    }
}
