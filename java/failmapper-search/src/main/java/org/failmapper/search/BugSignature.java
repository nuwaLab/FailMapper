package org.failmapper.search;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * D7/D9 bug-signature for deduplication — port of {@code _create_bug_signature}
 * ({@code fa_mcts.py:3407-3442}).
 *
 * <p>Signature = {@code "{test_method}:{md5(cleaned_error)[:12]}"} where the error text
 * is cleaned in Python source order:
 * <ol>
 *   <li>strip memory addresses: {@code re.sub(r'@[0-9a-f]+', '', error)} — ASCII
 *       LOWERCASE hex only, exactly like the Python pattern;</li>
 *   <li>if the cleaned text contains BOTH {@code "expected:"} and {@code "but was:"},
 *       reduce to {@code "expected:{a}_but_was:{b}"} via
 *       {@code r'expected:.*?<([^>]+)>.*?but was:.*?<([^>]+)>'} — non-DOTALL, so the
 *       reduction silently DOESN'T apply when a newline separates the markers from the
 *       angle-bracket values (the full cleaned text is hashed instead);</li>
 *   <li>ELIF the text contains {@code "Exception"}, reduce to the first
 *       {@code ([A-Za-z]+Exception)} match.</li>
 * </ol>
 *
 * <p>Dialect notes: {@link Pattern#UNIX_LINES} on the dot-bearing pattern per contract
 * X9 (CPython's default {@code .} excludes only {@code \n}; Java's also excludes
 * {@code \r}/U+2028/U+2029). The md5 digest is over the UTF-8 bytes
 * ({@code str.encode()} default) and rendered as lowercase hex.
 */
public final class BugSignature {

    /** {@code fa_mcts.py:3424} — memory-address cleaner. */
    private static final Pattern ADDRESS = Pattern.compile("@[0-9a-f]+");

    /** {@code fa_mcts.py:3429} — assertion-failure core extractor (UNIX_LINES per X9). */
    private static final Pattern EXPECTED_BUT_WAS = Pattern.compile(
            "expected:.*?<([^>]+)>.*?but was:.*?<([^>]+)>", Pattern.UNIX_LINES);

    /** {@code fa_mcts.py:3435} — exception-type extractor. */
    private static final Pattern EXCEPTION_TYPE = Pattern.compile("([A-Za-z]+Exception)");

    private BugSignature() {
    }

    /**
     * @param testMethod {@code bug_info.get("test_method", "unknown")} — pass null to
     *                   model an absent key (maps to {@code "unknown"})
     * @param error      {@code bug_info.get("error", "")} — null maps to {@code ""}
     */
    public static String create(String testMethod, String error) {
        String methodName = testMethod == null ? "unknown" : testMethod;
        String errorMsg = error == null ? "" : error;

        String cleanedError = ADDRESS.matcher(errorMsg).replaceAll("");

        if (cleanedError.contains("expected:") && cleanedError.contains("but was:")) {
            Matcher m = EXPECTED_BUT_WAS.matcher(cleanedError);
            if (m.find()) {
                cleanedError = "expected:" + m.group(1) + "_but_was:" + m.group(2);
            }
        } else if (cleanedError.contains("Exception")) {
            Matcher m = EXCEPTION_TYPE.matcher(cleanedError);
            if (m.find()) {
                cleanedError = m.group(1);
            }
        }

        return methodName + ":" + md5Hex(cleanedError).substring(0, 12);
    }

    private static String md5Hex(String s) {
        try {
            MessageDigest md5 = MessageDigest.getInstance("MD5");
            byte[] digest = md5.digest(s.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(digest.length * 2);
            for (byte b : digest) {
                hex.append(Character.forDigit((b >> 4) & 0xF, 16));
                hex.append(Character.forDigit(b & 0xF, 16));
            }
            return hex.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("MD5 unavailable", e); // mandated by the JCA spec
        }
    }
}
