package org.json_kula.jsonata_jvm.runtime.datetime;

final class WordNumbers {

    private WordNumbers() {}

    private static final String[] UNITS = {
        "", "one","two","three","four","five","six","seven","eight","nine"
    };
    private static final String[] UNITS_ORD = {
        "", "first","second","third","fourth","fifth","sixth","seventh","eighth","ninth"
    };
    private static final String[] TEENS = {
        "ten","eleven","twelve","thirteen","fourteen","fifteen",
        "sixteen","seventeen","eighteen","nineteen"
    };
    // Fix: teen ordinals were missing; previous code returned cardinal forms for 10–19.
    private static final String[] TEENS_ORD = {
        "tenth","eleventh","twelfth","thirteenth","fourteenth","fifteenth",
        "sixteenth","seventeenth","eighteenth","nineteenth"
    };
    private static final String[] TENS = {
        "","","twenty","thirty","forty","fifty","sixty","seventy","eighty","ninety"
    };
    // Fix: exact-tens ordinals were missing; "twentieth" etc.
    private static final String[] TENS_ORD = {
        "","","twentieth","thirtieth","fortieth","fiftieth",
        "sixtieth","seventieth","eightieth","ninetieth"
    };

    /** Returns the English cardinal words for {@code n} (e.g. 2017 → "two thousand and seventeen"). */
    static String toCardinal(int n) {
        if (n <= 0) return String.valueOf(n);
        StringBuilder sb = new StringBuilder();
        if (n >= 1000) {
            sb.append(UNITS[n / 1000]).append(" thousand");
            n %= 1000;
            if (n > 0) sb.append(" and ");
        }
        if (n >= 100) {
            sb.append(UNITS[n / 100]).append(" hundred");
            n %= 100;
            if (n > 0) sb.append(" and ");
        }
        if (n >= 20) {
            sb.append(TENS[n / 10]);
            int ones = n % 10;
            if (ones > 0) sb.append("-").append(UNITS[ones]);
        } else if (n >= 10) {
            sb.append(TEENS[n - 10]);
        } else if (n > 0) {
            sb.append(UNITS[n]);
        }
        return sb.toString();
    }

    /**
     * Returns the English ordinal words for {@code n} (e.g. 12 → "twelfth", 31 → "thirty-first").
     *
     * <p>Fixes vs original: teens 10, 11, 13–19 now return "tenth", "eleventh", "thirteenth"…;
     * exact multiples of 100/1000 append "th" correctly ("hundredth", "thousandth").
     */
    static String toOrdinal(int n) {
        if (n <= 0) return String.valueOf(n);
        // Irregular ordinals
        return switch (n) {
            case 1 -> "first";
            case 2 -> "second";
            case 3 -> "third";
            case 4 -> "fourth";
            case 5 -> "fifth";
            case 8 -> "eighth";
            case 9 -> "ninth";
            case 12 -> "twelfth";
            default -> buildOrdinal(n);
        };
    }

    private static String buildOrdinal(int n) {
        StringBuilder sb = new StringBuilder();

        if (n >= 1000) {
            sb.append(UNITS[n / 1000]).append(" thousand");
            n %= 1000;
            if (n == 0) { sb.append("th"); return sb.toString(); } // "thousandth"
            sb.append(" and ");
        }
        if (n >= 100) {
            sb.append(UNITS[n / 100]).append(" hundred");
            n %= 100;
            if (n == 0) { sb.append("th"); return sb.toString(); } // "hundredth"
            sb.append(" and ");
        }
        if (n >= 20) {
            int tensDigit = n / 10;
            int ones = n % 10;
            if (ones == 0) {
                sb.append(TENS_ORD[tensDigit]);
            } else {
                sb.append(TENS[tensDigit]).append("-").append(UNITS_ORD[ones]);
            }
        } else if (n >= 10) {
            // Fix: was returning cardinal teen forms; now returns ordinal forms.
            sb.append(TEENS_ORD[n - 10]);
        } else if (n > 0) {
            sb.append(UNITS_ORD[n]);
        }
        return sb.toString();
    }

    /**
     * Converts English number words to a decimal digit string.
     * Returns the original string unchanged when no number words are found.
     * Handles ordinals ("first"→1), hyphenated composites ("twenty-one"→21), and "and" connectors.
     */
    static String wordsToDigits(String input) {
        // Normalise: strip commas, remove "and", expand hyphens, lower-case
        String lower = input.toLowerCase()
                .replace(",", "")
                .replace(" and ", " ")
                .replace("-", " ");

        // Map ordinal words to their cardinal counterparts
        lower = lower
            .replace("first", "one").replace("second", "two").replace("third", "three")
            .replace("fourth", "four").replace("fifth", "five").replace("sixth", "six")
            .replace("seventh", "seven").replace("eighth", "eight").replace("ninth", "nine")
            .replace("tenth", "ten").replace("eleventh", "eleven").replace("twelfth", "twelve")
            .replace("thirteenth", "thirteen").replace("fourteenth", "fourteen")
            .replace("fifteenth", "fifteen").replace("sixteenth", "sixteen")
            .replace("seventeenth", "seventeen").replace("eighteenth", "eighteen")
            .replace("nineteenth", "nineteen")
            .replace("twentieth", "twenty").replace("thirtieth", "thirty")
            .replace("fortieth", "forty").replace("fiftieth", "fifty")
            .replace("sixtieth", "sixty").replace("seventieth", "seventy")
            .replace("eightieth", "eighty").replace("ninetieth", "ninety");

        String[] words = lower.split("\\s+");
        int result = 0;
        int current = 0;
        boolean matched = false;

        for (String word : words) {
            switch (word) {
                case "one"     -> { current += 1;    matched = true; }
                case "two"     -> { current += 2;    matched = true; }
                case "three"   -> { current += 3;    matched = true; }
                case "four"    -> { current += 4;    matched = true; }
                case "five"    -> { current += 5;    matched = true; }
                case "six"     -> { current += 6;    matched = true; }
                case "seven"   -> { current += 7;    matched = true; }
                case "eight"   -> { current += 8;    matched = true; }
                case "nine"    -> { current += 9;    matched = true; }
                case "ten"     -> { current += 10;   matched = true; }
                case "eleven"  -> { current += 11;   matched = true; }
                case "twelve"  -> { current += 12;   matched = true; }
                case "thirteen"  -> { current += 13; matched = true; }
                case "fourteen"  -> { current += 14; matched = true; }
                case "fifteen"   -> { current += 15; matched = true; }
                case "sixteen"   -> { current += 16; matched = true; }
                case "seventeen" -> { current += 17; matched = true; }
                case "eighteen"  -> { current += 18; matched = true; }
                case "nineteen"  -> { current += 19; matched = true; }
                case "twenty"  -> { current += 20;   matched = true; }
                case "thirty"  -> { current += 30;   matched = true; }
                case "forty"   -> { current += 40;   matched = true; }
                case "fifty"   -> { current += 50;   matched = true; }
                case "sixty"   -> { current += 60;   matched = true; }
                case "seventy" -> { current += 70;   matched = true; }
                case "eighty"  -> { current += 80;   matched = true; }
                case "ninety"  -> { current += 90;   matched = true; }
                case "hundred" -> { current *= 100;  matched = true; }
                case "thousand" -> {
                    result += (current == 0 ? 1000 : current * 1000);
                    current = 0;
                    matched = true;
                }
                default -> {} // non-number word — leave unchanged
            }
        }
        result += current;
        return matched ? String.valueOf(result) : input;
    }
}
