package org.json_kula.jsonata_jvm.runtime.datetime;

final class RomanNumerals {

    private RomanNumerals() {}

    private static final int[]    VALS  = {1000,900,500,400,100,90,50,40,10,9,5,4,1};
    private static final String[] SYMS  = {"M","CM","D","CD","C","XC","L","XL","X","IX","V","IV","I"};

    // Proper structural regex — rejects arbitrary IVXLCDM strings like "DIM" or "MIX".
    private static final java.util.regex.Pattern VALID_ROMAN =
            java.util.regex.Pattern.compile(
                    "^M{0,3}(CM|CD|D?C{0,3})(XC|XL|L?X{0,3})(IX|IV|V?I{0,3})$");

    /** Returns true only for structurally valid Roman numeral strings (I..MMMCMXCIX). */
    static boolean isValid(String s) {
        if (s == null || s.isEmpty()) return false;
        String upper = s.toUpperCase();
        return !upper.isEmpty() && VALID_ROMAN.matcher(upper).matches();
    }

    /** Converts a Roman numeral string (upper or lower case) to its Arabic integer value. */
    static int toArabic(String roman) {
        String upper = roman.toUpperCase();
        int result = 0, prev = 0;
        for (int i = upper.length() - 1; i >= 0; i--) {
            int val = charValue(upper.charAt(i));
            if (val < prev) result -= val;
            else { result += val; prev = val; }
        }
        return result;
    }

    /** Converts an integer 1..3999 to uppercase Roman numerals; returns the decimal string outside that range. */
    static String toRoman(int n) {
        if (n <= 0 || n > 3999) return String.valueOf(n);
        StringBuilder sb = new StringBuilder();
        for (int k = 0; k < VALS.length; k++)
            while (n >= VALS[k]) { sb.append(SYMS[k]); n -= VALS[k]; }
        return sb.toString();
    }

    private static int charValue(char c) {
        return switch (Character.toUpperCase(c)) {
            case 'I' -> 1;   case 'V' -> 5;   case 'X' -> 10;  case 'L' -> 50;
            case 'C' -> 100; case 'D' -> 500; case 'M' -> 1000; default -> 0;
        };
    }
}
