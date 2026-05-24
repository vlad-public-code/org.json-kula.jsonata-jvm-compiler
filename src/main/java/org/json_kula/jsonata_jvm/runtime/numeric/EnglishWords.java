package org.json_kula.jsonata_jvm.runtime.numeric;

import org.json_kula.jsonata_jvm.runtime.RuntimeEvaluationException;

import java.util.*;
import java.util.regex.Pattern;

/**
 * Converts between long integers and English number words for {@code $formatInteger}
 * and {@code $parseInteger} with pictures {@code "w"}, {@code "W"}, {@code "Ww"}.
 */
final class EnglishWords {

    private EnglishWords() {}

    private static final String[] ONES = {
        "", "one", "two", "three", "four", "five", "six", "seven", "eight", "nine",
        "ten", "eleven", "twelve", "thirteen", "fourteen", "fifteen", "sixteen",
        "seventeen", "eighteen", "nineteen"
    };
    private static final String[] TENS = {
        "", "", "twenty", "thirty", "forty", "fifty", "sixty", "seventy", "eighty", "ninety"
    };
    private static final long[]   MAGNITUDES = { 1_000_000_000_000L, 1_000_000_000L, 1_000_000L, 1_000L };
    private static final String[] MAG_WORDS  = { "trillion", "billion", "million", "thousand" };

    private static final Map<String, Long> WORD_VALUES;
    static {
        Map<String, Long> m = new HashMap<>();
        String[] ones = {
            "zero","one","two","three","four","five","six","seven","eight","nine",
            "ten","eleven","twelve","thirteen","fourteen","fifteen","sixteen",
            "seventeen","eighteen","nineteen"
        };
        for (int j = 0; j < ones.length; j++) m.put(ones[j], (long) j);
        String[] tens = {"twenty","thirty","forty","fifty","sixty","seventy","eighty","ninety"};
        for (int j = 0; j < tens.length; j++) m.put(tens[j], (long) (j + 2) * 10);
        m.put("hundred",     100L);
        m.put("thousand",    1_000L);
        m.put("million",     1_000_000L);
        m.put("billion",     1_000_000_000L);
        m.put("trillion",    1_000_000_000_000L);
        m.put("quadrillion", 1_000_000_000_000_000L);
        m.put("quintillion", 1_000_000_000_000_000_000L);
        WORD_VALUES = Collections.unmodifiableMap(m);
    }

    // Pre-compiled split pattern — avoids regex compilation on every call.
    private static final Pattern PARSE_SPLIT = Pattern.compile("[\\s,\\-]+");

    // =========================================================================
    // Cardinal / ordinal word generation
    // =========================================================================

    /**
     * Returns English words for a {@code double} that may exceed {@link Long#MAX_VALUE}.
     * Trillions are factored out repeatedly until the remainder fits in a long:
     * e.g., 1e46 → "ten billion trillion trillion trillion".
     */
    static String toWordsDouble(double n, boolean ordinal) throws RuntimeEvaluationException {
        if (n < 0) return "minus " + toWordsDouble(-n, false);
        if (n == 0) return ordinal ? "zeroth" : "zero";
        if (n <= Long.MAX_VALUE) return toWords(Math.round(n), ordinal);

        final double TRILLION = 1_000_000_000_000.0;
        int trillionCount = 0;
        double work = n;
        while (work >= TRILLION) {
            work = work / TRILLION;
            trillionCount++;
        }
        String base = toWords(Math.round(work), false);
        StringBuilder sb = new StringBuilder(base);
        for (int i = 0; i < trillionCount; i++) sb.append(" trillion");
        return sb.toString();
    }

    /** Returns the English cardinal or ordinal words for {@code n}. */
    static String toWords(long n, boolean ordinal) throws RuntimeEvaluationException {
        if (n == 0) return ordinal ? "zeroth" : "zero";
        if (n < 0)  return "minus " + toWords(-n, ordinal);
        String cardinal = wordsBelow(n);
        return ordinal ? toOrdinalWord(n, cardinal) : cardinal;
    }

    private static String wordsBelow(long n) {
        if (n == 0) return "";
        if (n < 20) return ONES[(int) n];
        if (n < 100) {
            String t = TENS[(int) (n / 10)];
            return n % 10 == 0 ? t : t + "-" + ONES[(int) (n % 10)];
        }
        if (n < 1000) {
            String h = ONES[(int) (n / 100)] + " hundred";
            long rest = n % 100;
            return rest == 0 ? h : h + " and " + wordsBelow(rest);
        }
        for (int m = 0; m < MAGNITUDES.length; m++) {
            if (n >= MAGNITUDES[m]) {
                long hi   = n / MAGNITUDES[m];
                long rest = n % MAGNITUDES[m];
                String s  = wordsBelow(hi) + " " + MAG_WORDS[m];
                if (rest > 0) {
                    return rest < 100 ? s + " and " + wordsBelow(rest)
                                      : s + ", " + wordsBelow(rest);
                }
                return s;
            }
        }
        return String.valueOf(n); // fallback for values above trillion (shouldn't occur within long range)
    }

    private static String toOrdinalWord(long n, String cardinal) {
        if (n < 20) {
            return switch ((int) n) {
                case 1  -> "first";     case 2  -> "second";      case 3  -> "third";
                case 4  -> "fourth";    case 5  -> "fifth";       case 6  -> "sixth";
                case 7  -> "seventh";   case 8  -> "eighth";      case 9  -> "ninth";
                case 10 -> "tenth";     case 11 -> "eleventh";    case 12 -> "twelfth";
                case 13 -> "thirteenth"; case 14 -> "fourteenth"; case 15 -> "fifteenth";
                case 16 -> "sixteenth"; case 17 -> "seventeenth"; case 18 -> "eighteenth";
                case 19 -> "nineteenth";
                default -> cardinal;
            };
        }
        if (n < 100) {
            if (n % 10 == 0) {
                return switch ((int) (n / 10)) {
                    case 2 -> "twentieth";  case 3 -> "thirtieth"; case 4 -> "fortieth";
                    case 5 -> "fiftieth";   case 6 -> "sixtieth";  case 7 -> "seventieth";
                    case 8 -> "eightieth";  case 9 -> "ninetieth";
                    default -> cardinal;
                };
            }
            int onesDigit = (int) (n % 10);
            int tensDigit = (int) (n / 10);
            String onesOrd = switch (onesDigit) {
                case 1 -> "first";   case 2 -> "second";  case 3 -> "third";
                case 4 -> "fourth";  case 5 -> "fifth";   case 6 -> "sixth";
                case 7 -> "seventh"; case 8 -> "eighth";  case 9 -> "ninth";
                default -> "";
            };
            String tensWord = switch (tensDigit) {
                case 2 -> "twenty"; case 3 -> "thirty"; case 4 -> "forty";
                case 5 -> "fifty";  case 6 -> "sixty";  case 7 -> "seventy";
                case 8 -> "eighty"; case 9 -> "ninety";
                default -> "";
            };
            return tensWord + "-" + onesOrd;
        }
        // n >= 100: append ordinal suffix to the last magnitude name.
        if (n % 100 == 0) {
            for (String mag : new String[]{"trillion", "billion", "million", "thousand"}) {
                if (cardinal.endsWith(mag)) {
                    return cardinal.substring(0, cardinal.length() - mag.length()) + mag + "th";
                }
            }
            // Falls through to "hundredth"
            return cardinal.replace("hundred", "hundredth");
        }
        // Non-round: convert the last 1–99 to ordinal, prefix with the main part.
        long lastPart = n % 100;
        if (lastPart > 0) {
            String lastOrdinal = toOrdinalWord(lastPart, "");
            String mainPart    = wordsBelow(n - lastPart);
            return mainPart + " and " + lastOrdinal;
        }
        return cardinal + "th";
    }

    // =========================================================================
    // Title-case
    // =========================================================================

    /** Applies title-case to an English number-word string, keeping "and" lowercase. */
    static String titleCase(String s) {
        if (s == null || s.isEmpty()) return s;
        StringBuilder sb = new StringBuilder(s.length());
        boolean capNext = true;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == ' ' || c == ',' || c == '-') {
                sb.append(c);
                capNext = true;
            } else if (capNext) {
                // Keep "and" lowercase (JSONata title-case convention)
                if (s.regionMatches(true, i, "and ", 0, 4)) {
                    sb.append('a');
                } else {
                    sb.append(Character.toUpperCase(c));
                }
                capNext = false;
            } else {
                sb.append(c);
            }
        }
        return sb.toString();
    }

    // =========================================================================
    // Parsing English words → long
    // =========================================================================

    /**
     * Converts English number words (cardinal or ordinal) to a {@code long}.
     *
     * <p>Uses the standard stacking algorithm: units accumulate in a sub-total,
     * which is multiplied into the running total whenever a magnitude word
     * (thousand, million, …) is encountered. This correctly handles phrases like
     * "one million one thousand" → 1,001,000 (each magnitude group is independent).
     */
    static long parseWords(String s) throws RuntimeEvaluationException {
        String[] tokens = PARSE_SPLIT.split(s.toLowerCase().trim());
        boolean negative  = false;
        long total         = 0;
        long subtotal      = 0;
        long lastMagnitude = 0;   // tracks last committed magnitude to detect ascending order

        for (String tok : tokens) {
            if (tok.isEmpty() || tok.equals("and")) continue;
            if (tok.equals("minus")) { negative = true; continue; }

            Long val = WORD_VALUES.get(tok);
            if (val == null)
                throw new RuntimeEvaluationException(null,
                        "$parseInteger: unrecognised word token \"" + tok + "\"");

            if (val == 100L) {
                // "hundred" multiplies the current sub-total (or implies 1).
                subtotal = (subtotal == 0 ? 1 : subtotal) * 100;
            } else if (val >= 1_000L) {
                try {
                    if (lastMagnitude > 0 && val > lastMagnitude) {
                        // Ascending magnitude: "one thousand trillion" → 1000 × 10^12 = 10^15.
                        long base = Math.addExact(total, subtotal == 0 ? 0 : subtotal);
                        total = Math.multiplyExact(base == 0 ? 1 : base, val);
                    } else {
                        // Descending or first: "one million one thousand" → 10^6 + 1×10^3.
                        long contribution = Math.multiplyExact(subtotal == 0 ? 1 : subtotal, val);
                        total = Math.addExact(total, contribution);
                    }
                } catch (ArithmeticException e) {
                    return Long.MAX_VALUE;
                }
                subtotal      = 0;
                lastMagnitude = val;
            } else {
                subtotal += val;
            }
        }
        try {
            total = Math.addExact(total, subtotal);
        } catch (ArithmeticException e) {
            return Long.MAX_VALUE;
        }
        return negative ? -total : total;
    }

    // =========================================================================
    // Ordinal suffix stripping (for $parseInteger with ";o" modifier)
    // =========================================================================

    /** Strips the ordinal suffix from a word-form ordinal string. */
    static String stripOrdinalSuffix(String s) {
        String lower = s.toLowerCase().trim();
        int lastSpace = lower.lastIndexOf(' ');
        String lastWord = lastSpace >= 0 ? lower.substring(lastSpace + 1) : lower;

        // Irregular ordinals
        if (lastWord.equals("twelfth")) return replaceLastWord(lower, lastSpace, "twelve");
        if (lastWord.equals("fifth"))   return replaceLastWord(lower, lastSpace, "five");
        if (lastWord.equals("ninth"))   return replaceLastWord(lower, lastSpace, "nine");
        if (lastWord.equals("first"))   return replaceLastWord(lower, lastSpace, "one");
        if (lastWord.equals("second"))  return replaceLastWord(lower, lastSpace, "two");
        if (lastWord.equals("third"))   return replaceLastWord(lower, lastSpace, "three");

        // "twentieth" → "twenty", "thirtieth" → "thirty", etc.
        if (lower.endsWith("ieth")) return lower.substring(0, lower.length() - 4) + "y";

        // Hyphenated: "thirty-fourth" → last part after "-" has ordinal suffix
        if (lower.contains("-")) {
            String[] parts = lower.split("-");
            String lastPart = parts[parts.length - 1];
            if (lastPart.endsWith("st") || lastPart.endsWith("nd")
                    || lastPart.endsWith("rd") || lastPart.endsWith("th")) {
                String root = lastPart.substring(0, lastPart.length() - 2);
                String cardinal = ordinalRootToCardinal(root);
                StringBuilder result = new StringBuilder();
                for (int i = 0; i < parts.length - 1; i++) {
                    if (i > 0) result.append(" ");
                    result.append(parts[i]);
                }
                if (result.length() > 0) result.append(" ");
                result.append(cardinal);
                return result.toString();
            }
        }

        // Standard suffix stripping
        if (lower.endsWith("st") || lower.endsWith("nd")
                || lower.endsWith("rd") || lower.endsWith("th")) {
            return ordinalRootToCardinal(lower.substring(0, lower.length() - 2));
        }
        return s;
    }

    private static String replaceLastWord(String full, int lastSpace, String replacement) {
        return lastSpace >= 0 ? full.substring(0, lastSpace + 1) + replacement : replacement;
    }

    private static String ordinalRootToCardinal(String root) {
        return switch (root) {
            case "nin"          -> "nine";
            case "fif"          -> "five";
            case "twelf"        -> "twelve";
            case "thi"          -> "three";
            case "fir"          -> "one";
            case "seco", "secon" -> "two";
            default             -> root;
        };
    }
}
