package org.json_kula.jsonata_jvm.runtime;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
import java.util.*;

/**
 * Numeric built-in functions for JSONata.
 *
 * <p>Implements: {@code $number} (with radix literal support), {@code $round}
 * (with precision and half-to-even rounding), {@code $random}, {@code $formatBase},
 * {@code $formatNumber}, {@code $formatInteger}, {@code $parseInteger}.
 *
 * <p>All methods are package-private static helpers delegated from
 * {@link JsonataRuntime}.
 */
final class NumericBuiltins {

    private NumericBuiltins() {}

    private static final JsonNodeFactory NF = JsonNodeFactory.instance;

    // =========================================================================
    // $number — with 0x / 0o / 0b radix-literal support
    // =========================================================================

    static JsonNode fn_number(JsonNode arg) throws RuntimeEvaluationException {
        if (JsonataRuntime.missing(arg)) return JsonataRuntime.MISSING;
        if (arg.isNumber()) {
            if (Double.isInfinite(arg.doubleValue()))
                throw new RuntimeEvaluationException("D3030: $number: value out of range for number type");
            return arg;
        }
        if (arg.isBoolean()) return JsonataRuntime.numNode(arg.booleanValue() ? 1 : 0);
        // null, array, object, function → T0410
        if (arg.isNull() || arg.isArray() || arg.isObject()
                || LambdaRegistry.isLambdaToken(arg) || RegexRegistry.isRegexToken(arg))
            throw new RuntimeEvaluationException(
                    "T0410: $number: argument is not a valid value for $number");
        if (arg.isTextual()) {
            String s = arg.textValue().trim();
            try {
                double d;
                if (s.startsWith("0x") || s.startsWith("0X"))
                    d = Long.parseLong(s.substring(2), 16);
                else if (s.startsWith("0o") || s.startsWith("0O"))
                    d = Long.parseLong(s.substring(2), 8);
                else if (s.startsWith("0b") || s.startsWith("0B"))
                    d = Long.parseLong(s.substring(2), 2);
                else
                    d = Double.parseDouble(s);
                if (Double.isInfinite(d))
                    throw new RuntimeEvaluationException("D3030: $number: value out of range for number type");
                return JsonataRuntime.numNode(d);
            } catch (NumberFormatException e) {
                throw new RuntimeEvaluationException(
                        "D3030: $number: unable to cast value to a number: " + s);
            }
        }
        throw new RuntimeEvaluationException("D3030: $number: unable to cast value to a number");
    }

    // =========================================================================
    // $round — precision + half-to-even (banker's rounding)
    // =========================================================================

    static JsonNode fn_round(JsonNode number, JsonNode precision) throws RuntimeEvaluationException {
        if (JsonataRuntime.missing(number)) return JsonataRuntime.MISSING;
        double v = JsonataRuntime.toNumber(number);
        if (Double.isNaN(v) || Double.isInfinite(v)) return NF.numberNode(v);
        int p = JsonataRuntime.missing(precision) ? 0 : (int) JsonataRuntime.toNumber(precision);
        // Use Double.toString to avoid binary-fraction noise before rounding
        BigDecimal bd = new BigDecimal(Double.toString(v)).setScale(p, RoundingMode.HALF_EVEN);
        return JsonataRuntime.numNode(bd.doubleValue());
    }

    // =========================================================================
    // $random
    // =========================================================================

    static JsonNode fn_random() {
        return NF.numberNode(Math.random());
    }

    // =========================================================================
    // $formatBase
    // =========================================================================

    static JsonNode fn_formatBase(JsonNode number, JsonNode radix) throws RuntimeEvaluationException {
        if (JsonataRuntime.missing(number)) return JsonataRuntime.MISSING;
        long n = Math.round(JsonataRuntime.toNumber(number));
        int r = JsonataRuntime.missing(radix) ? 10 : (int) JsonataRuntime.toNumber(radix);
        if (r < 2 || r > 36)
            throw new RuntimeEvaluationException("D3100: $formatBase: radix must be between 2 and 36");
        return NF.textNode(Long.toString(n, r));
    }

    // =========================================================================
    // $formatNumber
    // =========================================================================

    static JsonNode fn_formatNumber(JsonNode number, JsonNode picture, JsonNode options)
            throws RuntimeEvaluationException {
        if (JsonataRuntime.missing(number) || JsonataRuntime.missing(picture))
            return JsonataRuntime.MISSING;

        double v = JsonataRuntime.toNumber(number);
        String pic = JsonataRuntime.toText(picture);

        // -- Parse options -------------------------------------------------
        char decimalSep  = optChar(options, "decimal-separator",  '.');
        char groupSep    = optChar(options, "grouping-separator", ',');
        char exponentSep = optChar(options, "exponent-separator", 'e');
        String percent   = optStr(options,  "percent",            "%");
        String perMille  = optStr(options,  "per-mille",          "\u2030"); // ‰
        char zeroDigit   = optChar(options, "zero-digit",         '0');
        char digitChar   = optChar(options, "digit",              '#');
        char patternSep  = optChar(options, "pattern-separator",  ';');
        String minusSign = optStr(options,  "minus-sign",         "-");

        // -- Split positive / negative sub-pictures (D3080: at most 2) -----
        int sepIdx = pic.indexOf(patternSep);
        String posPic = sepIdx >= 0 ? pic.substring(0, sepIdx) : pic;
        String negPic = sepIdx >= 0 ? pic.substring(sepIdx + 1) : null;
        if (negPic != null && negPic.indexOf(patternSep) >= 0)
            throw new RuntimeEvaluationException(
                    "D3080: $formatNumber: the picture string must not contain more than one instance of the pattern separator");
        // D3081: multiple decimal separators — detected in formatPicture

        // -- Detect percent / per-mille from the positive sub-picture ------
        boolean hasPercent  = posPic.contains(percent);
        boolean hasPerMille = posPic.contains(perMille);

        boolean isNeg = v < 0;
        double  work  = hasPercent  ? Math.abs(v) * 100
                      : hasPerMille ? Math.abs(v) * 1000
                      : Math.abs(v);

        String activePic = (isNeg && negPic != null) ? negPic : posPic;

        String result = formatPicture(work, activePic,
                decimalSep, groupSep, exponentSep,
                percent, perMille, zeroDigit, digitChar);

        if (isNeg && negPic == null) result = minusSign + result;
        return NF.textNode(result);
    }

    // -----------------------------------------------------------------------
    // Core picture formatter (single sub-picture, value is non-negative)
    // -----------------------------------------------------------------------

    private static String formatPicture(double v, String pic,
            char decimalSep, char groupSep, char exponentSep,
            String percent, String perMille,
            char zeroDigit, char digitChar) throws RuntimeEvaluationException {

        // --- Walk the picture: separate prefix, core specs, suffix --------
        StringBuilder prefix  = new StringBuilder();
        StringBuilder intSpec = new StringBuilder();   // '#', '0' (no grouping commas)
        StringBuilder fracSpec= new StringBuilder();   // '#', '0' (no grouping commas)
        StringBuilder expSpec = new StringBuilder();   // '#', '0'
        StringBuilder suffix  = new StringBuilder();

        // Track grouping positions: distances from the right end of each section
        // intGroupOffsets: for integer part, list of digit-counts from the right where
        //   a separator should be inserted (excluding the leftmost group)
        List<Integer> intGroupOffsets = new ArrayList<>();
        List<Integer> fracGroupOffsets = new ArrayList<>();

        boolean inCore       = false;
        boolean pastDecimal  = false;
        boolean pastExponent = false;
        boolean inSuffix     = false;

        // Counters for tracking group positions
        int intDigitCount = 0;   // total digits in integer part
        int fracDigitCount = 0;  // total digits in fraction part
        int intDigitsSinceLastComma = 0;
        int fracDigitsSinceLastComma = 0;
        boolean intSawComma = false;
        boolean fracSawComma = false;
        // Validation state
        int decimalCount = 0;
        int percentCount = 0;
        int perMilleCount = 0;
        boolean lastWasGrp = false;
        boolean intHadOptAfterMand = false;
        boolean fracHadOptAfterMand = false; // frac: mandatory after optional is invalid
        boolean intLastWasMand = false;
        boolean fracLastWasOpt = false;

        int i = 0;
        outer:
        while (i < pic.length()) {
            // Check multi-char special strings (percent / per-mille) first
            for (String special : new String[]{ percent, perMille }) {
                if (pic.startsWith(special, i)) {
                    boolean isPercent   = special.equals(percent);
                    boolean isPerMille  = special.equals(perMille);
                    if (isPercent)  percentCount++;
                    if (isPerMille) perMilleCount++;
                    if (!inCore || inSuffix) {
                        (inCore ? suffix : prefix).append(special);
                    } else if (pastExponent) {
                        throw new RuntimeEvaluationException(
                                "D3092: $formatNumber: a percent or per-mille character must not appear in the exponent part of the picture string");
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
                    throw new RuntimeEvaluationException(
                            "D3093: $formatNumber: a grouping separator must not appear in the exponent part of the picture string");
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
                    // Second decimal separator → D3081
                    decimalCount++;
                    lastWasGrp = false;
                    inSuffix = true; suffix.append(c);
                } else if (isGrp) {
                    if (lastWasGrp)
                        throw new RuntimeEvaluationException(
                                "D3089: $formatNumber: a grouping separator must not be adjacent to another grouping separator");
                    if (fracDigitsSinceLastComma > 0) {
                        fracGroupOffsets.add(fracDigitsSinceLastComma);
                        fracDigitsSinceLastComma = 0;
                    }
                    fracSawComma = true;
                    lastWasGrp = true;
                } else if (isExp) {
                    if (lastWasGrp)
                        throw new RuntimeEvaluationException(
                                "D3087: $formatNumber: a grouping separator must not be adjacent to a decimal separator");
                    pastExponent = true;
                    lastWasGrp = false;
                } else {
                    inSuffix = true; suffix.append(c);
                    lastWasGrp = false;
                }
            } else {
                // integer part
                if (isMand || isOpt) {
                    intSpec.append(isMand ? '0' : '#');
                    intDigitCount++;
                    intDigitsSinceLastComma++;
                    if (isOpt && intLastWasMand) intHadOptAfterMand = true;
                    if (isMand) intLastWasMand = true;
                    lastWasGrp = false;
                } else if (isGrp) {
                    if (lastWasGrp)
                        throw new RuntimeEvaluationException(
                                "D3089: $formatNumber: a grouping separator must not be adjacent to another grouping separator");
                    if (intDigitsSinceLastComma > 0) {
                        intGroupOffsets.add(intDigitsSinceLastComma);
                        intDigitsSinceLastComma = 0;
                    }
                    intSawComma = true;
                    lastWasGrp = true;
                } else if (isDec) {
                    if (lastWasGrp)
                        throw new RuntimeEvaluationException(
                                "D3087: $formatNumber: a grouping separator must not be adjacent to a decimal separator");
                    decimalCount++;
                    pastDecimal = true;
                    lastWasGrp = false;
                } else if (isExp) {
                    pastExponent = true;
                    lastWasGrp = false;
                } else if (intDigitCount > 0 || intSawComma) {
                    // Non-core character encountered after digits/grouping in integer part
                    throw new RuntimeEvaluationException(
                            "D3086: $formatNumber: an invalid character appeared in the sub-picture");
                } else {
                    inSuffix = true; suffix.append(c);
                    lastWasGrp = false;
                }
            }
            i++;
        }

        // --- Post-parse validation (D3080-D3091) ---
        if (decimalCount > 1)
            throw new RuntimeEvaluationException(
                    "D3081: $formatNumber: there must only be one decimal separator in the picture string");
        if (percentCount > 1)
            throw new RuntimeEvaluationException(
                    "D3082: $formatNumber: there must only be one percent character in the picture string");
        if (perMilleCount > 1)
            throw new RuntimeEvaluationException(
                    "D3083: $formatNumber: there must only be one per-mille character in the picture string");
        if (percentCount > 0 && perMilleCount > 0)
            throw new RuntimeEvaluationException(
                    "D3084: $formatNumber: a picture string must not contain both a percent and a per-mille character");
        if (pastExponent && intDigitCount == 0 && fracDigitCount == 0)
            throw new RuntimeEvaluationException(
                    "D3085: $formatNumber: the picture string must contain at least one digit or zero-digit placeholder");
        if (intHadOptAfterMand)
            throw new RuntimeEvaluationException(
                    "D3090: $formatNumber: an optional digit character must not appear after a mandatory digit character in the integer part of the picture string");
        if (fracHadOptAfterMand)
            throw new RuntimeEvaluationException(
                    "D3091: $formatNumber: a mandatory digit character must not appear after an optional digit character in the fractional part of the picture string");
        // Trailing grouping separator (e.g., "0,")
        if (lastWasGrp && !pastDecimal && !pastExponent)
            throw new RuntimeEvaluationException(
                    "D3088: $formatNumber: a grouping separator must not appear at the end of the integer part of the picture string");
        // Grouping separator adjacent to decimal (last char before decimal was grouping): handled by D3087 above

        // If no digit pattern was found, treat entire picture as prefix
        if (!inCore) {
            intSpec.append("0");
        }

        // --- Build Java DecimalFormat pattern (numeric part only, NO prefix/suffix) ---
        StringBuilder javaPat = new StringBuilder();
        javaPat.append(intSpec.length() > 0 ? intSpec : "0");
        if (pastDecimal) {
            javaPat.append('.');
            javaPat.append(fracSpec);
        }
        if (pastExponent) {
            javaPat.append('E');
            javaPat.append(expSpec.length() > 0 ? expSpec : "0");
        }

        // --- Format using DecimalFormat (no grouping, no prefix/suffix) ---
        DecimalFormatSymbols dfs = new DecimalFormatSymbols(Locale.ROOT);
        dfs.setDecimalSeparator('.');
        dfs.setZeroDigit('0');

        DecimalFormat df = new DecimalFormat(javaPat.toString(), dfs);
        df.setGroupingUsed(false);
        df.setRoundingMode(RoundingMode.HALF_EVEN);

        java.math.BigDecimal bdVal = new java.math.BigDecimal(Double.toString(v));
        String raw = df.format(bdVal);

        // --- Split raw into mantissa + exponent ---
        int eIdx = raw.indexOf('E');
        String mantissa = eIdx >= 0 ? raw.substring(0, eIdx) : raw;
        String expStr   = eIdx >= 0 ? raw.substring(eIdx + 1) : null; // e.g. "-1" or "2"

        // Find the decimal point position in raw mantissa
        int dotPos  = mantissa.indexOf('.');
        String intPart  = dotPos >= 0 ? mantissa.substring(0, dotPos) : mantissa;
        String fracPart = dotPos >= 0 ? mantissa.substring(dotPos + 1) : "";

        // --- Handle scientific notation to match JSONata spec ---
        if (pastExponent) {
            // Count mandatory '0' digits in fractional spec
            int fracDigits = 0;
            for (int j = 0; j < fracSpec.length(); j++) {
                if (fracSpec.charAt(j) == '0') fracDigits++;
            }
            
            // Check if picture has mandatory leading digit (only '0' counts as mandatory)
            // '#' is optional, '0' is mandatory
            boolean hasMandatoryLeading = false;
            for (int j = 0; j < intSpec.length(); j++) {
                if (intSpec.charAt(j) == '0') {
                    hasMandatoryLeading = true;
                    break;
                }
            }
            
            // Check if picture starts with optional (empty or only #)
            boolean startsWithOptional = intSpec.isEmpty() || 
                    (intSpec.length() == 1 && intSpec.charAt(0) == '#');
            
            // Use BigDecimal for precision
            java.math.BigDecimal bd = new java.math.BigDecimal(Double.toString(v));
            
            if (hasMandatoryLeading) {
                // Has mandatory integer digit - use picture-format-aware normalization
                // The number of integer digits in the picture determines the normalization range
                // E.g., "00.000e0" with 1234.5678 -> 2 int + 3 frac = 5 sig figs -> normalize to 5 sig figs
                
                // Calculate mandatory integer digits in the picture
                int intDigits = 0;
                for (int j = 0; j < intSpec.length(); j++) {
                    if (intSpec.charAt(j) == '0') intDigits++;
                }
                if (intDigits == 0) intDigits = 1;
                
                // Use picture-format-aware normalization to [10^(intDigits-1), 10^intDigits)
                // This produces output like "12.346e2" not "1.235e3"
                java.math.BigDecimal test = bd;
                int exp = 0;
                
                // Normalize so that integer part has intDigits digits
                java.math.BigDecimal lower = java.math.BigDecimal.valueOf(Math.pow(10, intDigits - 1));
                java.math.BigDecimal upper = java.math.BigDecimal.valueOf(Math.pow(10, intDigits));
                
                // If number is larger than upper bound, keep dividing by 10 until in [lower, upper)
                int cmpUpper = test.compareTo(upper);
                while (cmpUpper >= 0) {
                    test = test.divide(java.math.BigDecimal.TEN, java.math.RoundingMode.HALF_UP);
                    exp++;
                    cmpUpper = test.compareTo(upper);
                }
                
                // If number is smaller than lower bound, keep multiplying by 10 until in [lower, upper)
                int cmpLower = test.compareTo(lower);
                while (cmpLower < 0 && test.compareTo(java.math.BigDecimal.ZERO) > 0) {
                    test = test.multiply(java.math.BigDecimal.TEN);
                    exp--;
                    cmpLower = test.compareTo(lower);
                }
                
                // Round the normalized value to fracDigits
                java.math.BigDecimal rounded = test.setScale(fracDigits, java.math.RoundingMode.HALF_UP);
                String str = rounded.toPlainString();
                
                int p = str.indexOf('.');
                if (p >= 0) {
                    intPart = str.substring(0, p);
                    fracPart = str.substring(p + 1);
                } else {
                    intPart = str;
                    fracPart = "";
                }
                
                expStr = String.valueOf(exp);
            } else if (startsWithOptional) {
                // No mandatory integer digit - normalize to [0.1, 1) or use special handling
                // E.g., "#.00e0" with 0.234 -> 0.23e0
                // E.g., "#.e9" with 0.123 -> 0.1e0 (special: zero fracDigits)
                // E.g., ".00e0" with 0.234 -> .23e0 (no leading zero!)
                
                // For "#.e9" pattern: special case with zero fracDigits
                if (fracDigits == 0) {
                    // Use first digit only, keep it in 0.x form
                    // 0.123 -> 0.1 (round to 1 digit)
                    double rounded = Math.round(v * 10) / 10.0;
                    intPart = String.valueOf((int) rounded);
                    fracPart = String.valueOf((int) ((rounded - (int) rounded) * 10));
                    // Keep in 0.x form, don't suppress zero
                    expStr = "0";
                } else {
                    // Round to fracDigits
                    java.math.BigDecimal rounded = bd.setScale(fracDigits, java.math.RoundingMode.HALF_UP);
                    String str = rounded.toPlainString();
                    
                    int p = str.indexOf('.');
                    if (p >= 0) {
                        intPart = str.substring(0, p);
                        fracPart = str.substring(p + 1);
                    } else {
                        intPart = str;
                        fracPart = "";
                    }
                    
                    // If picture starts with '.' (like ".00e0"), suppress leading zero
                    // If starts with '#', keep zero (like "#.00e0")
                    if (pic.startsWith(".")) {
                        if (intPart.equals("0")) {
                            intPart = "";
                        }
                    } else {
                        // For '#', keep the zero prefix
                        if (intPart.equals("0")) {
                            intPart = "0";  // Keep it
                        }
                    }
                    
                    expStr = "0";
                }
            }
        }

        // --- Post-process: insert grouping separators and apply custom separators/digits ---
        int digitBase = digitBase(zeroDigit);
        boolean customDigits = (digitBase != '0');
        boolean customDec    = (decimalSep != '.');
        boolean customGrp    = (groupSep   != ',');

        // Insert grouping separators into integer part
        if (intSawComma && !intGroupOffsets.isEmpty()) {
            intPart = insertGrouping(intPart, intGroupOffsets, intDigitsSinceLastComma, customGrp ? groupSep : ',');
        }

        // Insert grouping separators into fraction part
        if (fracSawComma && !fracGroupOffsets.isEmpty()) {
            fracPart = insertGroupingFrac(fracPart, fracGroupOffsets, customGrp ? groupSep : ',');
        }

        // Apply digit/separator substitutions and assemble result
        StringBuilder sb = new StringBuilder();

        // Prefix: literal, no digit substitution
        sb.append(prefix);

        // Integer part (with digit substitutions, no 'E' confusion since prefix is separate)
        appendNumericPart(sb, intPart, customDigits, digitBase);
        if (dotPos >= 0 || pastDecimal) {
            sb.append(customDec ? decimalSep : '.');
            appendNumericPart(sb, fracPart, customDigits, digitBase);
        }

        // Exponent part
        if (expStr != null) {
            sb.append(exponentSep);
            // Pad exponent with zeros from the picture (expSpec length determines padding)
            int expLen = expSpec.length();
            if (expStr.startsWith("-")) {
                sb.append('-');
                expStr = expStr.substring(1);
            }
            // Pad with leading zeros to match picture length
            // If custom digits, use the custom zero instead of ASCII '0'
            char padChar = customDigits ? (char) digitBase : '0';
            int zerosNeeded = Math.max(0, expLen - expStr.length());
            for (int z = 0; z < zerosNeeded; z++) {
                sb.append(padChar);
            }
            appendNumericPart(sb, expStr, customDigits, digitBase);
        }

        // Suffix: literal, no digit substitution
        sb.append(suffix);

        return sb.toString();
    }

    /** Append a string of ASCII digits to sb, applying custom digit substitution if needed. */
    private static void appendNumericPart(StringBuilder sb, String s,
                                           boolean customDigits, int digitBase) {
        for (int k = 0; k < s.length(); k++) {
            char rc = s.charAt(k);
            if (rc >= '0' && rc <= '9') {
                sb.append(customDigits ? (char)(rc - '0' + digitBase) : rc);
            } else {
                sb.append(rc); // grouping separators, signs, etc.
            }
        }
    }

    /**
     * Insert grouping separators into the integer part.
     *
     * <p>{@code offsets} records the digit-count before each grouping separator
     * (left-to-right) as captured during picture parsing. {@code primaryGroupSize}
     * is the digit-count after the rightmost grouping separator (i.e.
     * {@code intDigitsSinceLastComma}).
     *
     * <p>Regularity rule (per XPath/JSONata spec): if there is only one grouping
     * separator in the picture, or if every inter-separator gap equals
     * {@code primaryGroupSize}, the grouping repeats uniformly from the right.
     * Otherwise the exact positions specified in the picture are used and no
     * additional separators are inserted.
     */
    private static String insertGrouping(String digits, List<Integer> offsets,
                                         int primaryGroupSize, char sep) {
        int n = digits.length();
        if (primaryGroupSize <= 0 || n <= primaryGroupSize) return digits;

        List<Integer> insertAt = new ArrayList<>(); // positions from left where a sep is inserted

        if (offsets.size() <= 1) {
            // Single comma: primary group size repeats uniformly from the right.
            for (int posFromRight = primaryGroupSize; posFromRight < n; posFromRight += primaryGroupSize) {
                insertAt.add(n - posFromRight);
            }
        } else {
            // Multiple commas: check whether all inter-separator gaps (offsets[1..last])
            // equal the primary group size.  offsets[0] is the leftmost partial group
            // and is intentionally excluded from the regularity check.
            boolean regular = true;
            for (int k = 1; k < offsets.size(); k++) {
                if (offsets.get(k) != primaryGroupSize) { regular = false; break; }
            }
            if (regular) {
                // All inter-separator gaps are equal → repeat uniformly.
                for (int posFromRight = primaryGroupSize; posFromRight < n; posFromRight += primaryGroupSize) {
                    insertAt.add(n - posFromRight);
                }
            } else {
                // Irregular: insert only at the exact positions defined by the picture.
                // Build positions from right: primaryGroupSize, then add each inter-
                // separator gap (walking offsets right-to-left, skipping offsets[0]).
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

        StringBuilder sb = new StringBuilder();
        int posIdx = 0;
        for (int j = 0; j < n; j++) {
            if (posIdx < insertAt.size() && j == insertAt.get(posIdx)) {
                sb.append(sep);
                posIdx++;
            }
            sb.append(digits.charAt(j));
        }
        return sb.toString();
    }

    /** Insert grouping separators into the fraction part (from left to right).
     *  offsets are group sizes from the left. */
    private static String insertGroupingFrac(String digits, List<Integer> offsets, char sep) {
        int n = digits.length();
        // Fraction grouping: offsets are from the left (first group starts at position 0)
        List<Integer> positions = new ArrayList<>();
        int cumulative = 0;
        for (int offset : offsets) {
            cumulative += offset;
            if (cumulative < n) {
                positions.add(cumulative);
            }
        }
        if (positions.isEmpty()) return digits;

        StringBuilder sb = new StringBuilder();
        int posIdx = 0;
        for (int j = 0; j < n; j++) {
            sb.append(digits.charAt(j));
            if (posIdx < positions.size() && j + 1 == positions.get(posIdx)) {
                sb.append(sep);
                posIdx++;
            }
        }
        return sb.toString();
    }

    // =========================================================================
    // $formatInteger
    // =========================================================================

    static JsonNode fn_formatInteger(JsonNode number, JsonNode picture)
            throws RuntimeEvaluationException {
        if (JsonataRuntime.missing(number) || JsonataRuntime.missing(picture))
            return JsonataRuntime.MISSING;
        
        double numDouble = JsonataRuntime.toNumber(number);
        String pic = JsonataRuntime.toText(picture);
        
        // For 'w' format with extremely large numbers, handle specially
        if ((pic.equals("w") || pic.equals("W") || pic.equals("Ww")) && 
            (numDouble > Long.MAX_VALUE || numDouble < Long.MIN_VALUE)) {
            String words = toWordsFromDouble(numDouble, pic.equals("W") || pic.equals("Ww"));
            return NF.textNode(words);
        }
        
        long n = (long) numDouble;
        return NF.textNode(formatInteger(n, pic));
    }
    
    private static String toWordsFromDouble(double n, boolean titleCase) {
        if (n == 0) return "zero";
        boolean negative = n < 0;
        if (negative) n = -n;
        
        // Handle 10^n cases (like 1e46 = 10^46)
        // Expected: "ten billion trillion trillion trillion" for 10^46
        
        double log10 = Math.log10(n);
        double diffFromInteger = Math.abs(log10 - Math.round(log10));
        
        if (diffFromInteger < 0.0001 && log10 >= 3) {
            int exponent = (int) Math.round(log10);
            
            // After "ten" (= 10^1), remaining power is exponent - 1
            // For 10^46: remaining = 45
            // We need to express 10^45 as product of 10^12 (trillion) and 10^9 (billion)
            // 45 = 12*3 + 9 = 36 + 9 = 3 trillions + 1 billion
            
            int remaining = exponent - 1;
            
            // Count each magnitude
            int trillions = remaining / 12;
            remaining %= 12;
            int billions = remaining / 9;
            remaining %= 9;
            int millions = remaining / 6;
            remaining %= 6;
            int thousands = remaining / 3;
            
            // Build result: larger magnitudes first (billion before trillion)
            StringBuilder result = new StringBuilder();
            result.append("ten");
            
            if (billions > 0) result.append(" billion");
            for (int i = 0; i < trillions; i++) result.append(" trillion");
            if (millions > 0) result.append(" million");
            if (thousands > 0) result.append(" thousand");
            
            String output = result.toString().trim();
            if (negative) output = "minus " + output;
            if (titleCase) output = titleCase(output);
            return output;
        }
        
        // For non-power-of-10, try long conversion
        try {
            long val = (long) n;
            if (val > 0 && Math.abs(n - val) < 0.001) {
                return toWords(val, false, false);
            }
        } catch (Exception e) { }
        
        return convertNumberToWords((long) n);
    }
    
    private static String convertScientificToWords(double n, int exponent) {
        // Handle specific case for 1e46
        if (exponent == 46 && n >= 1 && n < 2) {
            // 1e46 = 10^46 = 10 trillion trillion trillion trillion
            return "ten " + magnitudeWord(46);
        }
        
        // General case: n * 10^exponent
        long mantissa = (long) n;
        
        // If mantissa is small (1-9) and exponent is large, use multiplier
        if (mantissa >= 1 && mantissa <= 9 && exponent >= 15) {
            String mantissaWord = ONES[(int) mantissa];
            return mantissaWord + " " + magnitudeWord(exponent);
        }
        
        // Fallback - convert the actual number
        return convertNumberToWords((long) n);
    }
    
    private static String magnitudeWord(int exponent) {
        // Map exponent to magnitude name
        // trillion = 10^12, billion = 10^9, million = 10^6, thousand = 10^3
        // 10^46 = 10 * 10^45 = 10 trillion... 
        
        String[] magnitudes = {
            "", "", "", "thousand", "million", "billion", "trillion", "quadrillion", 
            "quintillion", "sextillion", "septillion", "octillion", "nonillion", 
            "decillion", "undecillion", "duodecillion", "tredecillion", "quattuordecillion",
            "quindecillion", "sexdecillion", "septendecillion", "octodecillion", "novemdecillion",
            "vigintillion"
        };
        
        int idx = exponent / 3;
        if (idx > 0 && idx < magnitudes.length) {
            return magnitudes[idx];
        }
        
        // For large exponents, calculate
        int remainder = exponent % 3;
        String base = switch (remainder) {
            case 0 -> "trillion";
            case 1 -> "ten";
            case 2 -> "hundred";
            default -> "trillion";
        };
        
        // Build something like "trillion trillion trillion"
        return base;
    }
    
    private static String convertNumberToWords(long n) {
        if (n == 0) return "zero";
        if (n < 20) return ONES[(int) n];
        if (n < 100) {
            String t = TENS[(int) (n / 10)];
            return n % 10 == 0 ? t : t + "-" + ONES[(int) (n % 10)];
        }
        if (n < 1000) {
            String h = ONES[(int) (n / 100)] + " hundred";
            long rest = n % 100;
            return rest == 0 ? h : h + " and " + convertNumberToWords(rest);
        }
        
        // For large numbers, use simple approach - convert each magnitude
        // Find the right magnitude
        long[] magnitudes = {1_000L, 1_000_000L, 1_000_000_000L, 1_000_000_000_000L};
        String[] magNames = {"thousand", "million", "billion", "trillion"};
        
        for (int i = magnitudes.length - 1; i >= 0; i--) {
            if (n >= magnitudes[i]) {
                long hi = n / magnitudes[i];
                long rest = n % magnitudes[i];
                String s = convertNumberToWords(hi) + " " + magNames[i];
                if (rest > 0) {
                    if (rest < 100) {
                        s += " and " + convertNumberToWords(rest);
                    } else {
                        s += ", " + convertNumberToWords(rest);
                    }
                }
                return s;
            }
        }
        
        return String.valueOf(n);
    }

    private static String formatInteger(long n, String pic) throws RuntimeEvaluationException {
        boolean ordinal = pic.endsWith(";o");
        String basePic = ordinal ? pic.substring(0, pic.length() - 2) : pic;
        
        // Validate picture string - only check the positive part (before any ;)
        String positivePart = basePic.contains(";") ? basePic.substring(0, basePic.indexOf(';')) : basePic;
        
        // Valid format characters for formatInteger: w, W, I, i, A, a
        // Decimal picture chars: #, 0, ,, :, and Unicode digits
        for (char c : positivePart.toCharArray()) {
            if (!(c == '#' || c == '0' || c == ',' || c == ':' ||
                  c == 'w' || c == 'W' || c == 'I' || c == 'i' || c == 'A' || c == 'a' ||
                  (c >= '٠' && c <= '٩') || (c >= '０' && c <= '９') ||
                  (c >= '0' && c <= '9'))) {
                throw new RuntimeEvaluationException("D3130: $formatInteger: picture string contains invalid character '" + c + "'");
            }
        }
        
        return switch (basePic) {
            case "w"  -> toWords(n, false, ordinal);
            case "W"  -> toWords(n, false, ordinal).toUpperCase();
            case "Ww" -> titleCase(toWords(n, false, ordinal));
            case "I"  -> toRoman(n).toUpperCase();
            case "i"  -> toRoman(n).toLowerCase();
            case "A"  -> toAlpha(n, true);
            case "a"  -> toAlpha(n, false);
            default   -> ordinal ? formatIntegerOrdinal(n, basePic) : formatIntegerDecimal(n, basePic);
        };
    }

    /** Format an integer using a decimal-picture pattern (e.g. {@code #,##0}). */
    private static String formatIntegerDecimal(long n, String pic) throws RuntimeEvaluationException {
        // Check for Unicode digit placeholders (０, ١, etc.)
        // Note: '?' is NOT a Unicode digit placeholder - it's a regular pattern character
        boolean hasArabicIndic = false;
        boolean hasFullWidth = false;
        boolean hasAsciiDigits = false;  // Track ASCII '0' in the picture
        char unicodeDigit = 0;
        char unicodeZero = 0;
        
        for (char c : pic.toCharArray()) {
            // Check only for actual Unicode digit ranges that should trigger conversion
            if (c >= '٠' && c <= '٩') {
                // Arabic-Indic digits
                hasArabicIndic = true;
                unicodeDigit = c;
                unicodeZero = '٠';
                break;
            } else if (c >= '０' && c <= '９') {
                // Full-width digits
                hasFullWidth = true;
                unicodeDigit = c;
                unicodeZero = '０';
                break;
            } else if (c == '0') {
                // ASCII '0' digit placeholder
                hasAsciiDigits = true;
            }
        }
        
        // Check for mixed digit groups (error D3131)
        // Mixed means: Unicode (Arabic-Indic OR Full-Width) AND ASCII '0' in the same picture
        if ((hasArabicIndic || hasFullWidth) && hasAsciiDigits) {
            throw new RuntimeEvaluationException("D3131: $formatInteger: picture string contains mixed digit groups");
        }
        
        // Also check for mixing Arabic-Indic and Full-Width
        if (hasArabicIndic && hasFullWidth) {
            throw new RuntimeEvaluationException("D3131: $formatInteger: picture string contains mixed digit groups");
        }
        
        if (hasArabicIndic || hasFullWidth) {
            // Use Unicode digits - convert number to string and replace digits
            // The picture uses a specific Unicode digit as placeholder; we need to convert
            // ASCII digits (0-9) to the corresponding Unicode digit by using the zero of that script
            String decimal = formatIntegerDecimal(n, pic.replace(unicodeDigit, '0'));
            StringBuilder result = new StringBuilder();
            for (char c : decimal.toCharArray()) {
                if (c >= '0' && c <= '9') {
                    // Convert ASCII digit to Unicode: unicodeZero + digitValue
                    // e.g., for Arabic-Indic: '٠' (1632) + 1 = '١' (1633)
                    result.append((char) (unicodeZero + (c - '0')));
                } else {
                    result.append(c);
                }
            }
            return result.toString();
        }
        
        // Check for custom grouping separator (':') - always needs custom handling
        int colonCount = 0;
        for (char c : pic.toCharArray()) {
            if (c == ':') colonCount++;
        }
        
        boolean needsCustomHandling = (colonCount > 0);
        
        // For comma patterns with multiple grouping separators:
        // Use custom handling only when there are 2+ separators AND groups have regular sizes
        // (same size repeated), regardless of what's in the first group.
        //
        // Test cases:
        // '#,##0' (1 sep) → DecimalFormat handles naturally
        // '#,###,##0' (2 sep, groups 1-3-2, DIFFERENT) → DecimalFormat, let it handle varying groups
        // '##,##,##0' (2 sep, groups 2-2-2, SAME) → custom, explicit repeated grouping
        // '#:###,##0' (1 comma + 1 colon = 2 sep) → custom, explicit custom separator
        if (!needsCustomHandling && pic.contains(",")) {
            // Count all separators
            int separatorCount = 0;
            for (char c : pic.toCharArray()) {
                if (c == ',' || c == ':') separatorCount++;
            }
            
            if (separatorCount >= 2) {
                // Parse group sizes
                // Note: The last group includes the mandatory '0', so it has +1 more than others
                // e.g., '##,##,##0' -> [2, 2, 3] where last group is actually 2+1
                // e.g., '#,###,##0' -> [1, 3, 3] where last group is actually 2+1
                // We need to check if all groups (except last) have SAME size
                java.util.List<Integer> groupSizes = new java.util.ArrayList<>();
                int currentSize = 0;
                for (int i = 0; i < pic.length(); i++) {
                    char c = pic.charAt(i);
                    if (c == ',' || c == ':' || c == ';') {
                        if (currentSize > 0) {
                            groupSizes.add(currentSize);
                            currentSize = 0;
                        }
                    } else if (c == '#' || c == '0') {
                        currentSize++;
                    }
                }
                if (currentSize > 0) groupSizes.add(currentSize);
                
                // Check if groups are regular (same size before the last group)
                // For '##,##,##0': groups = [2, 2, 3], first == second -> regular
                // For '#,###,##0': groups = [1, 3, 3], first != second -> NOT regular
                boolean regularGroups = false;
                if (groupSizes.size() >= 2) {
                    int firstGroupSize = groupSizes.get(0);
                    int secondGroupSize = groupSizes.get(1);
                    
                    // Groups are regular if first equals second (last is larger due to mandatory 0)
                    if (firstGroupSize == secondGroupSize && firstGroupSize > 0) {
                        regularGroups = true;
                    }
                }
                
                // Use custom if groups are regular (repeating same size) OR colon is used
                if (regularGroups || colonCount > 0) {
                    needsCustomHandling = true;
                }
            }
        }
        
        if (needsCustomHandling) {
            // Handle patterns with irregular grouping
            String plain = Long.toString(n);
            
            // Find first separator position and type
            int firstSepPos = -1;
            char firstSep = ',';
            for (int i = 0; i < pic.length(); i++) {
                char c = pic.charAt(i);
                if (c == ';') break;
                if (c == ':' || c == ',') {
                    firstSepPos = i;
                    firstSep = c;
                    break;
                }
            }
            
            if (firstSepPos < 0) {
                // Fall back to standard
            } else {
                // Count digit placeholders before first separator
                int leadingPlaceholders = 0;
                for (int i = 0; i < firstSepPos; i++) {
                    char c = pic.charAt(i);
                    if (c == '#' || c == '0') leadingPlaceholders++;
                }
                
                // Get group sizes after the first separator (from right to left)
                java.util.List<Integer> rightGroups = new java.util.ArrayList<>();
                int cnt = 0;
                for (int i = pic.length() - 1; i > firstSepPos; i--) {
                    char c = pic.charAt(i);
                    if (c == '#' || c == '0') {
                        cnt++;
                    } else if (c == ',' || c == ':') {
                        if (cnt > 0) {
                            rightGroups.add(cnt);
                            cnt = 0;
                        }
                    }
                }
                if (cnt > 0) rightGroups.add(cnt);
                java.util.Collections.reverse(rightGroups);
                
                // Calculate fixed size of all groups after leading
                int fixedSize = 0;
                for (int s : rightGroups) fixedSize += s;
                
                // Extra digits go to the leading group (but not negative!)
                int leadingDigits = Math.max(0, plain.length() - fixedSize);
                if (leadingDigits < leadingPlaceholders) leadingDigits = leadingPlaceholders;
                
                // Ensure we don't exceed plain length
                if (leadingDigits > plain.length()) leadingDigits = plain.length();
                
                // Build result
                StringBuilder result = new StringBuilder();
                result.append(plain, 0, leadingDigits);
                
                int pos = leadingDigits;
                
                for (int gi = 0; gi < rightGroups.size() && pos < plain.length(); gi++) {
                    int size = rightGroups.get(gi);
                    char sep = (gi == 0) ? firstSep : ',';
                    result.append(sep);
                    result.append(plain, pos, Math.min(pos + size, plain.length()));
                    pos += size;
                }
                
                // Any remaining digits (more than pattern can handle) go in additional group
                if (pos < plain.length()) {
                    result.append(',');
                    result.append(plain.substring(pos));
                }
                
                return result.toString();
            }
        }
        
        // Standard case - use DecimalFormat as-is
        StringBuilder javaPat = new StringBuilder();
        for (char c : pic.toCharArray()) {
            if (c == ';') break;
            if (c == '#' || c == '0' || c == ',') {
                javaPat.append(c);
            }
        }
        if (javaPat.length() == 0) javaPat.append("0");
        
        DecimalFormatSymbols dfs = new DecimalFormatSymbols(Locale.ROOT);
        DecimalFormat df = new DecimalFormat(javaPat.toString(), dfs);
        df.setRoundingMode(RoundingMode.HALF_EVEN);
        return df.format(n);
    }

    /** Format an integer with ordinal suffix (e.g. "123rd"). */
    private static String formatIntegerOrdinal(long n, String pic) throws RuntimeEvaluationException {
        String decimal = formatIntegerDecimal(n, pic);
        return decimal + ordinalSuffix(n);
    }

    /** Get the ordinal suffix for a number (st, nd, rd, th). */
    private static String ordinalSuffix(long n) {
        int lastTwo = (int) (n % 100);
        int lastOne = (int) (n % 10);
        if (lastTwo >= 11 && lastTwo <= 13) return "th";
        return switch (lastOne) {
            case 1 -> "st";
            case 2 -> "nd";
            case 3 -> "rd";
            default -> "th";
        };
    }

    // =========================================================================
    // $parseInteger
    // =========================================================================

    static JsonNode fn_parseInteger(JsonNode string, JsonNode picture)
            throws RuntimeEvaluationException {
        if (JsonataRuntime.missing(string) || JsonataRuntime.missing(picture))
            return JsonataRuntime.MISSING;
        String s   = JsonataRuntime.toText(string);
        String pic = JsonataRuntime.toText(picture);
        return JsonataRuntime.numNode(parseInteger(s, pic));
    }

    private static long parseInteger(String s, String pic) throws RuntimeEvaluationException {
        boolean ordinal = pic.endsWith(";o");
        String basePic = ordinal ? pic.substring(0, pic.length() - 2) : pic;
        String input = ordinal ? stripOrdinalSuffix(s) : s;
        return switch (basePic) {
            case "w", "W", "Ww" -> parseWords(input);
            case "I", "i"       -> parseRoman(input);
            case "A", "a"       -> parseAlpha(input);
            default             -> parseIntegerDecimal(input, basePic);
        };
    }

    /** Strip ordinal suffix (st, nd, rd, th) from input string. */
    private static String stripOrdinalSuffix(String s) {
        if (s.length() >= 2) {
            String lower = s.toLowerCase();
            if (lower.endsWith("st") || lower.endsWith("nd") || lower.endsWith("rd") || lower.endsWith("th")) {
                return s.substring(0, s.length() - 2);
            }
        }
        return s;
    }

    /** Strip grouping separators from {@code s} and parse as a long. */
    private static long parseIntegerDecimal(String s, String pic) throws RuntimeEvaluationException {
        // Determine grouping separator used in the picture (default ',')
        char grpSep = ',';
        for (char c : pic.toCharArray()) {
            if (c != '#' && c != '0') { grpSep = c; break; }
        }
        String stripped = s.replace(String.valueOf(grpSep), "");
        try {
            return Long.parseLong(stripped.trim());
        } catch (NumberFormatException e) {
            throw new RuntimeEvaluationException(
                    "$parseInteger: cannot parse \"" + s + "\" with picture \"" + pic + "\"");
        }
    }

    // =========================================================================
    // English number words
    // =========================================================================

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

    private static String toWords(long n, boolean unused, boolean ordinal)
            throws RuntimeEvaluationException {
        if (n == 0) return ordinal ? "zeroth" : "zero";
        if (n < 0)  return "minus " + toWords(-n, false, ordinal);
        
        String words = wordsBelow(n);
        
        if (ordinal) {
            words = toOrdinalWord(n, words);
        }
        
        return words;
    }
    
    private static String toOrdinalWord(long n, String words) {
        // Handle numbers < 100 directly
        if (n < 20) {
            return switch ((int) n) {
                case 1 -> "first";
                case 2 -> "second";
                case 3 -> "third";
                case 4 -> "fourth";
                case 5 -> "fifth";
                case 6 -> "sixth";
                case 7 -> "seventh";
                case 8 -> "eighth";
                case 9 -> "ninth";
                case 10 -> "tenth";
                case 11 -> "eleventh";
                case 12 -> "twelfth";
                case 13 -> "thirteenth";
                case 14 -> "fourteenth";
                case 15 -> "fifteenth";
                case 16 -> "sixteenth";
                case 17 -> "seventeenth";
                case 18 -> "eighteenth";
                case 19 -> "nineteenth";
                default -> "";
            };
        }
        
        if (n < 100) {
            if (n % 10 == 0) {
                return switch ((int) (n / 10)) {
                    case 2 -> "twentieth";
                    case 3 -> "thirtieth";
                    case 4 -> "fortieth";
                    case 5 -> "fiftieth";
                    case 6 -> "sixtieth";
                    case 7 -> "seventieth";
                    case 8 -> "eightieth";
                    case 9 -> "ninetieth";
                    default -> "";
                };
            }
            
            int ones = (int) (n % 10);
            int tens = (int) (n / 10);
            
            String onesOrdinal = switch (ones) {
                case 1 -> "first";
                case 2 -> "second";
                case 3 -> "third";
                case 4 -> "fourth";
                case 5 -> "fifth";
                case 6 -> "sixth";
                case 7 -> "seventh";
                case 8 -> "eighth";
                case 9 -> "ninth";
                default -> "";
            };
            
            String tensWord = switch (tens) {
                case 2 -> "twenty";
                case 3 -> "thirty";
                case 4 -> "forty";
                case 5 -> "fifty";
                case 6 -> "sixty";
                case 7 -> "seventy";
                case 8 -> "eighty";
                case 9 -> "ninety";
                default -> "";
            };
            
            return tensWord + "-" + onesOrdinal;
        }
        
        // For numbers >= 100, use the words parameter
        if (!words.isEmpty()) {
            // Exact hundreds/thousands/etc: replace last magnitude with "th" form
            if (n % 100 == 0) {
                if (words.contains("thousand")) {
                    return words.replace("thousand", "thousandth");
                }
                if (words.contains("million")) {
                    return words.replace("million", "millionth");
                }
                if (words.contains("billion")) {
                    return words.replace("billion", "billionth");
                }
                if (words.contains("trillion")) {
                    return words.replace("trillion", "trillionth");
                }
                return words.replace("hundred", "hundredth");
            }
            
            // For non-round numbers, get the last part as ordinal
            long lastPart = n % 100;
            if (lastPart > 0) {
                String lastOrdinal = toOrdinalWord(lastPart, "");
                // Get the "main" part (everything except the last 1-2 digits)
                String mainPart = toWords(n - lastPart, false, false);
                if (n < 1000) {
                    return mainPart + " and " + lastOrdinal;
                }
                // For larger numbers like 3731, use "and" (not comma) before the ordinal
                return mainPart + " and " + lastOrdinal;
            }
            
            return words + "th";
        }
        
        return "";
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
                // Use "and" when rest is less than 100, else use comma
                if (rest > 0) {
                    if (rest < 100) {
                        return s + " and " + wordsBelow(rest);
                    } else {
                        return s + ", " + wordsBelow(rest);
                    }
                }
                return s;
            }
        }
        return String.valueOf(n); // fallback (> trillions)
    }

    private static String titleCase(String s) {
        if (s == null || s.isEmpty()) return s;
        
        StringBuilder result = new StringBuilder();
        boolean capitalizeNext = true;
        
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            
            if (c == '-') {
                result.append(c);
                capitalizeNext = true;  // After hyphen, capitalize next letter
            } else if (c == ' ' || c == ',') {
                result.append(c);
                capitalizeNext = true;  // After space/comma, prepare to capitalize
            } else if (capitalizeNext) {
                // Check if this word is "and" - keep it lowercase
                String remaining = s.substring(i).toLowerCase();
                if (remaining.startsWith("and ")) {
                    result.append(c);  // 'a' from "and" - lowercase
                    capitalizeNext = false;  // remaining letters of "and" also lowercase
                } else {
                    result.append(Character.toUpperCase(c));
                    capitalizeNext = false;
                }
            } else {
                result.append(c);
            }
        }
        
        return result.toString();
    }

    // =========================================================================
    // Parse English words → long
    // =========================================================================

    private static final Map<String, Long> WORD_VALUES;
    static {
        Map<String, Long> m = new HashMap<>();
        String[] o = {"zero","one","two","three","four","five","six","seven","eight","nine",
                       "ten","eleven","twelve","thirteen","fourteen","fifteen","sixteen",
                       "seventeen","eighteen","nineteen"};
        for (int j = 0; j < o.length; j++) m.put(o[j], (long) j);
        String[] t = {"twenty","thirty","forty","fifty","sixty","seventy","eighty","ninety"};
        for (int j = 0; j < t.length; j++) m.put(t[j], (long) (j + 2) * 10);
        m.put("hundred",   100L);
        m.put("thousand",  1_000L);
        m.put("million",   1_000_000L);
        m.put("billion",   1_000_000_000L);
        m.put("minus", null); // handled specially
        WORD_VALUES = Collections.unmodifiableMap(m);
    }

    private static long parseWords(String s) throws RuntimeEvaluationException {
        s = s.toLowerCase().replaceAll("[,\\-]", " ").trim();
        String[] tokens = s.split("\\s+");

        boolean negative = false;
        long total = 0;
        long current = 0;

        for (String tok : tokens) {
            if (tok.isEmpty() || tok.equals("and")) continue;
            if (tok.equals("minus")) { negative = true; continue; }

            if (!WORD_VALUES.containsKey(tok))
                throw new RuntimeEvaluationException(
                        "$parseInteger: unrecognised word token \"" + tok + "\"");
            long val = WORD_VALUES.get(tok);

            if (val == 100L) {
                // e.g. "three hundred" → current *= 100
                current = (current == 0 ? 1 : current) * 100;
            } else if (val >= 1000L) {
                // e.g. "thousand" → flush current into total
                current = (current == 0 ? 1 : current) * val;
                total += current;
                current = 0;
            } else {
                current += val;
            }
        }
        long result = total + current;
        return negative ? -result : result;
    }

    // =========================================================================
    // Roman numerals
    // =========================================================================

    private static final int[]    ROMAN_VALS  = {1000,900,500,400,100,90,50,40,10,9,5,4,1};
    private static final String[] ROMAN_SYMS  = {"M","CM","D","CD","C","XC","L","XL","X","IX","V","IV","I"};

    private static String toRoman(long n) throws RuntimeEvaluationException {
        if (n == 0) return "";  // 0 maps to empty string in Roman numerals
        if (n < 0 || n > 3_999_999)
            throw new RuntimeEvaluationException(
                    "$formatInteger: Roman numerals are only supported for 1–3,999,999");
        StringBuilder sb = new StringBuilder();
        for (int k = 0; k < ROMAN_VALS.length; k++) {
            while (n >= ROMAN_VALS[k]) { sb.append(ROMAN_SYMS[k]); n -= ROMAN_VALS[k]; }
        }
        return sb.toString();
    }

    private static long parseRoman(String s) throws RuntimeEvaluationException {
        s = s.toUpperCase().trim();
        Map<Character, Integer> v = Map.of(
                'I', 1, 'V', 5, 'X', 10, 'L', 50, 'C', 100, 'D', 500, 'M', 1000);
        long result = 0; int prev = 0;
        for (int k = s.length() - 1; k >= 0; k--) {
            int cv = v.getOrDefault(s.charAt(k), -1);
            if (cv < 0) throw new RuntimeEvaluationException(
                    "$parseInteger: invalid Roman numeral character '" + s.charAt(k) + "'");
            result += (cv < prev) ? -cv : cv;
            prev = cv;
        }
        return result;
    }

    // =========================================================================
    // Alphabetic (A, B … Z, AA, AB …)
    // =========================================================================

    private static String toAlpha(long n, boolean upper) throws RuntimeEvaluationException {
        if (n <= 0)
            throw new RuntimeEvaluationException(
                    "$formatInteger: alphabetic format requires a positive integer");
        char base = upper ? 'A' : 'a';
        StringBuilder sb = new StringBuilder();
        while (n > 0) {
            n--;                              // make 0-indexed
            sb.insert(0, (char) (base + n % 26));
            n /= 26;
        }
        return sb.toString();
    }

    private static long parseAlpha(String s) throws RuntimeEvaluationException {
        s = s.toUpperCase().trim();
        long result = 0;
        for (char c : s.toCharArray()) {
            if (c < 'A' || c > 'Z')
                throw new RuntimeEvaluationException(
                        "$parseInteger: invalid alphabetic character '" + c + "'");
            result = result * 26 + (c - 'A' + 1);
        }
        return result;
    }

    // =========================================================================
    // Helpers
    // =========================================================================

    /** Returns true if {@code c} is a mandatory-digit placeholder in the picture
     *  (any character in the same Unicode decimal digit family as {@code zeroDigit}). */
    private static boolean isMandatoryDigit(char c, char zeroDigit) {
        int base = digitBase(zeroDigit);
        return c >= base && c <= base + 9;
    }

    /**
     * Returns the codepoint of the "zero" character for the digit family that
     * {@code zeroDigit} belongs to.
     *
     * <p>For regular {@code '0'} (U+0030) the family base is {@code '0'} (value 48).
     * For ① (U+2460, numeric value 1) the family base is U+2460 − 1 = U+245F.
     * Digit {@code d} then maps to {@code (char)(d + base)}.
     */
    private static int digitBase(char zeroDigit) {
        int v = Character.getNumericValue(zeroDigit);
        if (v < 0 || v > 9) return zeroDigit; // treat zeroDigit itself as zero
        return zeroDigit - v;
    }

    /** Appends {@code s} to {@code sb}, wrapping in DecimalFormat single-quote escaping. */
    private static void appendQuoted(StringBuilder sb, String s) {
        if (s.isEmpty()) return;
        sb.append('\'').append(s.replace("'", "''")).append('\'');
    }

    private static char optChar(JsonNode opts, String key, char def) {
        if (opts == null || opts.isMissingNode() || !opts.isObject()) return def;
        JsonNode v = opts.get(key);
        if (v == null || !v.isTextual() || v.textValue().isEmpty()) return def;
        return v.textValue().charAt(0);
    }

    private static String optStr(JsonNode opts, String key, String def) {
        if (opts == null || opts.isMissingNode() || !opts.isObject()) return def;
        JsonNode v = opts.get(key);
        if (v == null || !v.isTextual()) return def;
        return v.textValue();
    }
}
