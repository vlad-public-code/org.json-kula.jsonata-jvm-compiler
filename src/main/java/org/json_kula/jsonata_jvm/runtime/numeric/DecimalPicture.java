package org.json_kula.jsonata_jvm.runtime.numeric;

import org.json_kula.jsonata_jvm.runtime.RuntimeEvaluationException;

import java.math.RoundingMode;
import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
import java.util.*;

/**
 * Core picture-string formatter for {@code $formatNumber}.
 *
 * <p>Implements the XPath/JSONata decimal-format picture string specification
 * (W3C XSLT 2.0 §16): prefix/suffix, mandatory/optional digit placeholders,
 * grouping separators, decimal separator, exponent separator, percent/per-mille,
 * and Unicode digit families.
 */
final class DecimalPicture {

    private DecimalPicture() {}

    /**
     * Formats {@code v} (non-negative) according to the given single sub-picture string.
     *
     * <p>The caller is responsible for splitting positive/negative sub-pictures and
     * handling sign; {@code v} should already be the absolute value with any percent/
     * per-mille scaling applied.
     */
    static String format(double v, String pic,
                         char decimalSep, char groupSep, char exponentSep,
                         String percent, String perMille,
                         char zeroDigit, char digitChar) throws RuntimeEvaluationException {

        // Pre-allocate the specials array once (avoids per-character allocation inside the loop)
        String[] specials = { percent, perMille };

        // Walk the picture string, categorising each character
        StringBuilder prefix   = new StringBuilder();
        StringBuilder intSpec  = new StringBuilder();
        StringBuilder fracSpec = new StringBuilder();
        StringBuilder expSpec  = new StringBuilder();
        StringBuilder suffix   = new StringBuilder();

        List<Integer> intGroupOffsets  = new ArrayList<>();
        List<Integer> fracGroupOffsets = new ArrayList<>();

        boolean inCore       = false;
        boolean pastDecimal  = false;
        boolean pastExponent = false;
        boolean inSuffix     = false;

        int intDigitCount  = 0;
        int fracDigitCount = 0;
        int intDigitsSinceLastComma  = 0;
        int fracDigitsSinceLastComma = 0;
        boolean intSawComma  = false;
        boolean fracSawComma = false;

        int decimalCount    = 0;
        int percentCount    = 0;
        int perMilleCount   = 0;
        boolean lastWasGrp  = false;
        boolean intHadOptAfterMand  = false;
        boolean fracHadOptAfterMand = false;
        boolean intLastWasMand  = false;
        boolean fracLastWasOpt  = false;

        int i = 0;
        outer:
        while (i < pic.length()) {
            // Multi-char special strings (percent/per-mille) take priority
            for (String special : specials) {
                if (pic.startsWith(special, i)) {
                    boolean isPercent  = special.equals(percent);
                    boolean isPerMille = special.equals(perMille);
                    if (isPercent)  percentCount++;
                    if (isPerMille) perMilleCount++;
                    if (!inCore || inSuffix) {
                        (inCore ? suffix : prefix).append(special);
                    } else if (pastExponent) {
                        throw new RuntimeEvaluationException("D3092",
                                "$formatNumber: a percent or per-mille character must not appear in the exponent part of the picture string");
                    } else {
                        inSuffix = true;
                        suffix.append(special);
                    }
                    i += special.length();
                    continue outer;
                }
            }

            char c = pic.charAt(i);
            boolean isMand = isMandatoryDigit(c, zeroDigit);
            boolean isOpt  = (c == digitChar);
            boolean isDec  = (c == decimalSep);
            boolean isGrp  = (c == groupSep);
            boolean isExp  = (c == exponentSep);
            boolean isCore = isMand || isOpt || isDec || isGrp || isExp;

            if (!inCore && isCore) inCore = true;

            if (!inCore) {
                prefix.append(c);
            } else if (inSuffix) {
                suffix.append(c);
            } else if (pastExponent) {
                if (isMand || isOpt) {
                    expSpec.append(isMand ? '0' : '#');
                } else if (isGrp) {
                    throw new RuntimeEvaluationException("D3093",
                            "$formatNumber: a grouping separator must not appear in the exponent part of the picture string");
                } else {
                    inSuffix = true; suffix.append(c);
                }
            } else if (pastDecimal) {
                if (isMand || isOpt) {
                    fracSpec.append(isMand ? '0' : '#');
                    fracDigitCount++;
                    fracDigitsSinceLastComma++;
                    if (isMand && fracLastWasOpt) fracHadOptAfterMand = true;
                    if (isOpt) fracLastWasOpt = true;
                    lastWasGrp = false;
                } else if (isDec) {
                    decimalCount++;
                    lastWasGrp = false;
                    inSuffix = true; suffix.append(c);
                } else if (isGrp) {
                    if (lastWasGrp)
                        throw new RuntimeEvaluationException("D3089",
                                "$formatNumber: a grouping separator must not be adjacent to another grouping separator");
                    if (fracDigitsSinceLastComma > 0) {
                        fracGroupOffsets.add(fracDigitsSinceLastComma);
                        fracDigitsSinceLastComma = 0;
                    }
                    fracSawComma = true;
                    lastWasGrp = true;
                } else if (isExp) {
                    if (lastWasGrp)
                        throw new RuntimeEvaluationException("D3087",
                                "$formatNumber: a grouping separator must not be adjacent to a decimal separator");
                    pastExponent = true;
                    lastWasGrp = false;
                } else {
                    inSuffix = true; suffix.append(c);
                    lastWasGrp = false;
                }
            } else {
                // Integer part
                if (isMand || isOpt) {
                    intSpec.append(isMand ? '0' : '#');
                    intDigitCount++;
                    intDigitsSinceLastComma++;
                    if (isOpt && intLastWasMand) intHadOptAfterMand = true;
                    if (isMand) intLastWasMand = true;
                    lastWasGrp = false;
                } else if (isGrp) {
                    if (lastWasGrp)
                        throw new RuntimeEvaluationException("D3089",
                                "$formatNumber: a grouping separator must not be adjacent to another grouping separator");
                    if (intDigitsSinceLastComma > 0) {
                        intGroupOffsets.add(intDigitsSinceLastComma);
                        intDigitsSinceLastComma = 0;
                    }
                    intSawComma = true;
                    lastWasGrp = true;
                } else if (isDec) {
                    if (lastWasGrp)
                        throw new RuntimeEvaluationException("D3087",
                                "$formatNumber: a grouping separator must not be adjacent to a decimal separator");
                    decimalCount++;
                    pastDecimal = true;
                    lastWasGrp = false;
                } else if (isExp) {
                    pastExponent = true;
                    lastWasGrp = false;
                } else if (intDigitCount > 0 || intSawComma) {
                    throw new RuntimeEvaluationException("D3086",
                            "$formatNumber: an invalid character appeared in the sub-picture");
                } else {
                    inSuffix = true; suffix.append(c);
                    lastWasGrp = false;
                }
            }
            i++;
        }

        // Post-parse validation
        if (decimalCount > 1)
            throw new RuntimeEvaluationException("D3081",
                    "$formatNumber: there must only be one decimal separator in the picture string");
        if (percentCount > 1)
            throw new RuntimeEvaluationException("D3082",
                    "$formatNumber: there must only be one percent character in the picture string");
        if (perMilleCount > 1)
            throw new RuntimeEvaluationException("D3083",
                    "$formatNumber: there must only be one per-mille character in the picture string");
        if (percentCount > 0 && perMilleCount > 0)
            throw new RuntimeEvaluationException("D3084",
                    "$formatNumber: a picture string must not contain both a percent and a per-mille character");
        if (pastExponent && intDigitCount == 0 && fracDigitCount == 0)
            throw new RuntimeEvaluationException("D3085",
                    "$formatNumber: the picture string must contain at least one digit or zero-digit placeholder");
        if (intHadOptAfterMand)
            throw new RuntimeEvaluationException("D3090",
                    "$formatNumber: an optional digit character must not appear after a mandatory digit character in the integer part of the picture string");
        if (fracHadOptAfterMand)
            throw new RuntimeEvaluationException("D3091",
                    "$formatNumber: a mandatory digit character must not appear after an optional digit character in the fractional part of the picture string");
        if (lastWasGrp && !pastDecimal && !pastExponent)
            throw new RuntimeEvaluationException("D3088",
                    "$formatNumber: a grouping separator must not appear at the end of the integer part of the picture string");

        if (!inCore) intSpec.append('0');

        // Build the Java DecimalFormat pattern (numeric part only)
        StringBuilder javaPat = new StringBuilder();
        javaPat.append(!intSpec.isEmpty() ? intSpec : "0");
        if (pastDecimal) {
            javaPat.append('.');
            javaPat.append(fracSpec);
        }
        if (pastExponent) {
            javaPat.append('E');
            javaPat.append(!expSpec.isEmpty() ? expSpec : "0");
        }

        // Format using DecimalFormat (grouping disabled — we apply it manually)
        DecimalFormatSymbols dfs = new DecimalFormatSymbols(Locale.ROOT);
        dfs.setDecimalSeparator('.');
        dfs.setZeroDigit('0');

        DecimalFormat df = new DecimalFormat(javaPat.toString(), dfs);
        df.setGroupingUsed(false);
        df.setRoundingMode(RoundingMode.HALF_EVEN);

        java.math.BigDecimal bdVal = new java.math.BigDecimal(Double.toString(v));
        String raw = df.format(bdVal);

        // Split raw output into mantissa and exponent parts
        int eIdx = raw.indexOf('E');
        String mantissa = eIdx >= 0 ? raw.substring(0, eIdx) : raw;
        String expStr   = eIdx >= 0 ? raw.substring(eIdx + 1) : null;

        int dotPos  = mantissa.indexOf('.');
        String intPart  = dotPos >= 0 ? mantissa.substring(0, dotPos) : mantissa;
        String fracPart = dotPos >= 0 ? mantissa.substring(dotPos + 1) : "";

        // Scientific notation: re-normalise to match picture intent
        if (pastExponent) {
            int fracMandatory = 0;
            for (int j = 0; j < fracSpec.length(); j++)
                if (fracSpec.charAt(j) == '0') fracMandatory++;

            boolean hasMandatoryLeading = intSpec.toString().contains("0");
            boolean startsWithOptional  = intSpec.isEmpty()
                    || (intSpec.length() == 1 && intSpec.charAt(0) == '#');

            java.math.BigDecimal bd = new java.math.BigDecimal(Double.toString(v));

            if (hasMandatoryLeading) {
                int intDigits = 0;
                for (int j = 0; j < intSpec.length(); j++)
                    if (intSpec.charAt(j) == '0') intDigits++;
                if (intDigits == 0) intDigits = 1;

                java.math.BigDecimal lower = java.math.BigDecimal.valueOf(Math.pow(10, intDigits - 1));
                java.math.BigDecimal upper = java.math.BigDecimal.valueOf(Math.pow(10, intDigits));
                java.math.BigDecimal test = bd;
                int exp = 0;

                while (test.compareTo(upper) >= 0) {
                    test = test.divide(java.math.BigDecimal.TEN, java.math.RoundingMode.HALF_UP);
                    exp++;
                }
                while (test.compareTo(lower) < 0 && test.compareTo(java.math.BigDecimal.ZERO) > 0) {
                    test = test.multiply(java.math.BigDecimal.TEN);
                    exp--;
                }

                java.math.BigDecimal rounded = test.setScale(fracMandatory, java.math.RoundingMode.HALF_UP);
                String str = rounded.toPlainString();
                int p = str.indexOf('.');
                intPart  = p >= 0 ? str.substring(0, p) : str;
                fracPart = p >= 0 ? str.substring(p + 1) : "";
                expStr   = String.valueOf(exp);

            } else if (startsWithOptional) {
                if (fracMandatory == 0) {
                    double rounded = Math.round(v * 10) / 10.0;
                    intPart  = String.valueOf((int) rounded);
                    fracPart = String.valueOf((int) ((rounded - (int) rounded) * 10));
                    expStr   = "0";
                } else {
                    java.math.BigDecimal rounded = bd.setScale(fracMandatory, java.math.RoundingMode.HALF_UP);
                    String str = rounded.toPlainString();
                    int p = str.indexOf('.');
                    intPart  = p >= 0 ? str.substring(0, p) : str;
                    fracPart = p >= 0 ? str.substring(p + 1) : "";
                    if (pic.startsWith(".") && intPart.equals("0")) intPart = "";
                    expStr   = "0";
                }
            }
        }

        // Insert grouping separators
        int digitBase    = digitBase(zeroDigit);
        boolean customDig = (digitBase != '0');
        boolean customDec = (decimalSep != '.');
        boolean customGrp = (groupSep != ',');
        char grpChar = customGrp ? groupSep : ',';

        if (intSawComma && !intGroupOffsets.isEmpty())
            intPart = insertGrouping(intPart, intGroupOffsets, intDigitsSinceLastComma, grpChar);
        if (fracSawComma && !fracGroupOffsets.isEmpty())
            fracPart = insertGroupingFrac(fracPart, fracGroupOffsets, grpChar);

        // Assemble result
        StringBuilder sb = new StringBuilder();
        sb.append(prefix);
        appendNumericPart(sb, intPart, customDig, digitBase);
        if (dotPos >= 0 || pastDecimal) {
            sb.append(customDec ? decimalSep : '.');
            appendNumericPart(sb, fracPart, customDig, digitBase);
        }
        if (expStr != null) {
            sb.append(exponentSep);
            int expLen = expSpec.length();
            if (expStr.startsWith("-")) { sb.append('-'); expStr = expStr.substring(1); }
            char padChar = customDig ? (char) digitBase : '0';
            int zeros = Math.max(0, expLen - expStr.length());
            sb.append(String.valueOf(padChar).repeat(zeros));
            appendNumericPart(sb, expStr, customDig, digitBase);
        }
        sb.append(suffix);
        return sb.toString();
    }

    // =========================================================================
    // Grouping separator insertion
    // =========================================================================

    /**
     * Inserts grouping separators into the integer-part digit string.
     *
     * <p>If there is only one grouping separator in the picture, or all
     * inter-separator gaps equal {@code primaryGroupSize}, the grouping repeats
     * uniformly from the right.  Otherwise the exact picture positions are used.
     */
    private static String insertGrouping(String digits, List<Integer> offsets,
                                         int primaryGroupSize, char sep) {
        int n = digits.length();
        if (primaryGroupSize <= 0 || n <= primaryGroupSize) return digits;

        List<Integer> insertAt = new ArrayList<>();
        if (offsets.size() <= 1) {
            for (int posFromRight = primaryGroupSize; posFromRight < n; posFromRight += primaryGroupSize)
                insertAt.add(n - posFromRight);
        } else {
            boolean regular = true;
            for (int k = 1; k < offsets.size(); k++) {
                if (!offsets.get(k).equals(primaryGroupSize)) { regular = false; break; }
            }
            if (regular) {
                for (int posFromRight = primaryGroupSize; posFromRight < n; posFromRight += primaryGroupSize)
                    insertAt.add(n - posFromRight);
            } else {
                int posFromRight = primaryGroupSize;
                int pfl = n - posFromRight;
                if (pfl > 0) insertAt.add(pfl);
                for (int k = offsets.size() - 1; k >= 1; k--) {
                    posFromRight += offsets.get(k);
                    pfl = n - posFromRight;
                    if (pfl > 0) insertAt.add(pfl);
                }
            }
        }
        if (insertAt.isEmpty()) return digits;
        Collections.sort(insertAt);

        StringBuilder sb = new StringBuilder(n + insertAt.size());
        int posIdx = 0;
        for (int j = 0; j < n; j++) {
            if (posIdx < insertAt.size() && j == insertAt.get(posIdx)) {
                sb.append(sep); posIdx++;
            }
            sb.append(digits.charAt(j));
        }
        return sb.toString();
    }

    /** Inserts grouping separators into the fractional-part digit string (left-to-right). */
    private static String insertGroupingFrac(String digits, List<Integer> offsets, char sep) {
        int n = digits.length();
        List<Integer> positions = new ArrayList<>();
        int cumulative = 0;
        for (int offset : offsets) {
            cumulative += offset;
            if (cumulative < n) positions.add(cumulative);
        }
        if (positions.isEmpty()) return digits;

        StringBuilder sb = new StringBuilder(n + positions.size());
        int posIdx = 0;
        for (int j = 0; j < n; j++) {
            sb.append(digits.charAt(j));
            if (posIdx < positions.size() && j + 1 == positions.get(posIdx)) {
                sb.append(sep); posIdx++;
            }
        }
        return sb.toString();
    }

    // =========================================================================
    // Digit-substitution helpers
    // =========================================================================

    /** Appends a string of (possibly ASCII) digit characters to {@code sb},
     *  substituting them for the custom digit family when needed. */
    private static void appendNumericPart(StringBuilder sb, String s,
                                          boolean customDigits, int digitBase) {
        for (int k = 0; k < s.length(); k++) {
            char rc = s.charAt(k);
            if (rc >= '0' && rc <= '9') {
                sb.append(customDigits ? (char) (rc - '0' + digitBase) : rc);
            } else {
                sb.append(rc);
            }
        }
    }

    /**
     * Returns {@code true} if {@code c} is a mandatory-digit placeholder
     * in the Unicode decimal digit family containing {@code zeroDigit}.
     */
    static boolean isMandatoryDigit(char c, char zeroDigit) {
        int base = digitBase(zeroDigit);
        return c >= base && c <= base + 9;
    }

    /**
     * Returns the codepoint of the "zero" character for the digit family
     * that {@code zeroDigit} belongs to.
     */
    static int digitBase(char zeroDigit) {
        int v = Character.getNumericValue(zeroDigit);
        if (v < 0 || v > 9) return zeroDigit;
        return zeroDigit - v;
    }
}
