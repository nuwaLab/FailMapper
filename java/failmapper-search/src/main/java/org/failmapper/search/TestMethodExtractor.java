package org.failmapper.search;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Port of {@code _extract_method_from_test_code} ({@code fa_mcts.py:1465-1488}) — extract
 * one named method's full text from a test class.
 *
 * <p>Python matches the header plus a 4-level nested-brace backtracking body pattern.
 * Per contract X7 that pattern is a StackOverflowError hazard in java.util.regex (Java's
 * backtracking is stack-recursive and the resulting {@code Error} would escape a
 * {@code catch (Exception)}); the contract's sanctioned resolution is "guard or rewrite
 * with a brace counter". This port matches the Python HEADER regex up to and including
 * the opening brace, then walks braces with a counter. DOCUMENTED DIVERGENCE (in the
 * X7-sanctioned direction): methods nested deeper than 4 brace levels, which the Python
 * regex silently fails to match (returning ""), ARE extracted here.
 *
 * <p>Header regex kept verbatim from {@code fa_mcts.py:1479} (with
 * UNICODE_CHARACTER_CLASS for Python's Unicode-aware {@code \w}/{@code \s} — contract
 * X2), including its quirks: the optional-modifier group followed by {@code \s+}
 * requires at least one whitespace character before the method signature.
 */
public final class TestMethodExtractor {

    private TestMethodExtractor() {
    }

    /**
     * @return the extracted method text (header through matching closing brace), or
     *         {@code ""} when not found — Python returns {@code ""} on both no-match
     *         and internal error
     */
    public static String extract(String testCode, String methodName) {
        if (testCode == null || methodName == null) {
            return "";
        }
        try {
            // fa_mcts.py:1479 header portion, through the opening '{'.
            Pattern header = Pattern.compile(
                    "(public|private|protected)?\\s+(?:static\\s+)?(?:final\\s+)?(?:[\\w\\<\\>\\[\\]]+\\s+)?"
                            + Pattern.quote(methodName)
                            + "\\s*\\([^\\)]*\\)\\s*(?:throws\\s+[\\w\\.,\\s]+)?\\s*\\{",
                    Pattern.UNICODE_CHARACTER_CLASS);
            Matcher m = header.matcher(testCode);
            if (!m.find()) {
                return "";
            }
            int start = m.start();
            int depth = 1; // the '{' consumed by the header match
            for (int i = m.end(); i < testCode.length(); i++) {
                char c = testCode.charAt(i);
                if (c == '{') {
                    depth++;
                } else if (c == '}') {
                    depth--;
                    if (depth == 0) {
                        return testCode.substring(start, i + 1);
                    }
                }
            }
            return ""; // unbalanced braces — Python's regex would not match either
        } catch (RuntimeException e) {
            return ""; // fa_mcts.py:1486-1488 — any error yields ""
        }
    }
}
