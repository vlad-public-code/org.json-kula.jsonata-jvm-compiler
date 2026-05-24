package org.json_kula.jsonata_jvm.runtime.string;

import org.json_kula.jsonata_jvm.runtime.RuntimeEvaluationException;

import java.nio.ByteBuffer;
import java.nio.charset.CharsetDecoder;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;

/**
 * Percent-encoding and decoding for {@code $encodeUrl}, {@code $encodeUrlComponent},
 * {@code $decodeUrl}, {@code $decodeUrlComponent}.
 *
 * <p>Fixes vs the original {@code StringBuiltins}:
 * <ul>
 *   <li>{@code String.format} in the per-byte loop replaced with a pre-allocated HEX table.
 *   <li>Literal non-ASCII bytes in the decode input now throw {@code D3140} instead of
 *       silently truncating the high bits.
 * </ul>
 */
final class UrlCodec {

    private UrlCodec() {}

    private static final char[] HEX = "0123456789ABCDEF".toCharArray();

    /**
     * Percent-encodes {@code s} per RFC 3986.
     *
     * @param preserveReserved if {@code true}, keep RFC 3986 reserved characters unencoded
     *                         (for full-URL encoding); if {@code false}, only keep unreserved
     *                         characters (for URL component encoding)
     */
    static String encode(String s, boolean preserveReserved) throws RuntimeEvaluationException {
        for (int k = 0; k < s.length(); k++) {
            char c = s.charAt(k);
            if (Character.isHighSurrogate(c)) {
                if (k + 1 >= s.length() || !Character.isLowSurrogate(s.charAt(k + 1)))
                    throw new RuntimeEvaluationException("D3140",
                            "$encodeUrl/Component: the string contains an invalid Unicode character");
                k++;
            } else if (Character.isLowSurrogate(c)) {
                throw new RuntimeEvaluationException("D3140",
                        "$encodeUrl/Component: the string contains an invalid Unicode character");
            }
        }
        byte[] bytes = s.getBytes(StandardCharsets.UTF_8);
        StringBuilder sb = new StringBuilder(bytes.length * 3 / 2);
        for (byte b : bytes) {
            int v = b & 0xFF;
            if (isUnreserved(v) || (preserveReserved && isReserved(v))) {
                sb.append((char) v);
            } else {
                sb.append('%').append(HEX[v >> 4]).append(HEX[v & 0xF]);
            }
        }
        return sb.toString();
    }

    /**
     * Decodes percent-encoded sequences. Throws {@code D3140} on malformed input
     * (incomplete sequences, invalid hex digits, or literal non-ASCII characters).
     */
    static String decode(String s) throws RuntimeEvaluationException {
        byte[] bytes = new byte[s.length()];
        int len = 0;
        int i = 0;
        while (i < s.length()) {
            char c = s.charAt(i);
            if (c > 0x7F)
                throw new RuntimeEvaluationException("D3140",
                        "Malformed URL: non-ASCII character at position " + i);
            if (c == '%') {
                if (i + 2 >= s.length())
                    throw new RuntimeEvaluationException("D3140",
                            "Malformed URL: incomplete percent sequence at position " + i);
                int hi = Character.digit(s.charAt(i + 1), 16);
                int lo = Character.digit(s.charAt(i + 2), 16);
                if (hi < 0 || lo < 0)
                    throw new RuntimeEvaluationException("D3140",
                            "Malformed URL: invalid percent sequence '%" + s.charAt(i + 1) + s.charAt(i + 2) + "'");
                bytes[len++] = (byte) (hi * 16 + lo);
                i += 3;
            } else {
                bytes[len++] = (byte) c;
                i++;
            }
        }
        CharsetDecoder decoder = StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT);
        try {
            return decoder.decode(ByteBuffer.wrap(bytes, 0, len)).toString();
        } catch (java.nio.charset.CharacterCodingException e) {
            throw new RuntimeEvaluationException("D3140", "Malformed URL: invalid UTF-8 sequence");
        }
    }

    /** RFC 3986 unreserved characters: {@code A-Za-z0-9 - _ . ~} */
    static boolean isUnreserved(int c) {
        return (c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z') || (c >= '0' && c <= '9')
                || c == '-' || c == '_' || c == '.' || c == '~';
    }

    /** RFC 3986 reserved characters (kept by {@code $encodeUrl}, encoded by {@code $encodeUrlComponent}). */
    static boolean isReserved(int c) {
        return c == ':' || c == '/' || c == '?' || c == '#' || c == '[' || c == ']'
                || c == '@' || c == '!' || c == '$' || c == '&' || c == '\''
                || c == '(' || c == ')' || c == '*' || c == '+' || c == ',' || c == ';' || c == '=';
    }
}
