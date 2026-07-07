package org.failmapper.llm.prompt;

import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Structured-verdict parser for the single-bug verification response
 * ({@code verify_bug_with_llm.py:159-224}), returning a typed verdict per
 * register entry I1 (structured verdicts as the primary protocol).
 *
 * <p>Regex dialect fixes applied per contract:
 * <ul>
 *   <li><b>X1</b>: Python {@code \Z} is Java {@code \z} — Java's {@code \Z}
 *       tolerates a final line terminator, which would change the captured
 *       REASONING boundary when the response ends with a newline;</li>
 *   <li><b>X9</b>: {@link Pattern#UNIX_LINES} so {@code .} (and {@code $} in the
 *       batch parser) treats only {@code \n} as a line terminator, like CPython —
 *       Java's default {@code .} additionally excludes {@code \r}, U+2028/U+2029.</li>
 * </ul>
 *
 * <p>The verdict pattern's {@code ["\'"]?} character class in Python is just
 * {@code ["']} (the third quote is a duplicate). Only the STRUCTURED branch is
 * modeled here; the keyword-counting fallback ({@code verify_bug_with_llm.py:164-196})
 * is heuristic plumbing outside the structured protocol — callers get
 * {@link Optional#empty()} when no VERDICT line exists.
 */
public final class VerdictParser {

    /** verify_bug_with_llm.py:159. */
    static final Pattern VERDICT = Pattern.compile(
            "VERDICT:\\s*[\"']?(REAL BUG|FALSE POSITIVE)[\"']?",
            Pattern.CASE_INSENSITIVE | Pattern.UNIX_LINES);

    /** verify_bug_with_llm.py:160. */
    static final Pattern CONFIDENCE = Pattern.compile(
            "CONFIDENCE:\\s*(\\d+(?:\\.\\d+)?)",
            Pattern.UNIX_LINES);

    /** verify_bug_with_llm.py:161 — {@code \Z} ported as {@code \z} (X1). */
    static final Pattern REASONING = Pattern.compile(
            "REASONING:(.+?)(?=VERDICT:|CONFIDENCE:|\\z)",
            Pattern.DOTALL | Pattern.UNIX_LINES);

    /**
     * I18 — the OPTIONAL SPEC_BASIS line appended by the spec-grounded verification
     * mode (no Python counterpart; the legacy REASONING capture above is untouched,
     * so in spec-grounded responses the SPEC_BASIS line may also appear inside the
     * reasoning text — harmless, and legacy parsing stays byte-faithful).
     */
    static final Pattern SPEC_BASIS = Pattern.compile(
            "SPEC_BASIS:(.+?)(?=VERDICT:|CONFIDENCE:|REASONING:|\\z)",
            Pattern.DOTALL | Pattern.UNIX_LINES);

    /** I1 — verdict as an enum instead of stringly-typed matching. */
    public enum VerdictType {
        REAL_BUG,
        FALSE_POSITIVE
    }

    /**
     * @param verdict    the structured verdict
     * @param confidence 0-1 scale: {@code min(llm_confidence / 10, 0.95)}, or the
     *                   0.7 default when no CONFIDENCE line matched
     * @param reasoning  stripped REASONING capture, or the first 500 chars of the
     *                   response when absent; never longer than 500 chars
     * @param specBasis  I18 — stripped SPEC_BASIS capture (the documented statement a
     *                   REAL verdict cites), or null when the line is absent; the
     *                   unsubstantiated-REAL downgrade rule lives in the caller
     *                   ({@code LlmBugVerifier}), not here
     */
    public record Verdict(VerdictType verdict, double confidence, String reasoning,
                          String specBasis) {

        /** Legacy three-field shape (pre-I18 callers and tests). */
        public Verdict(VerdictType verdict, double confidence, String reasoning) {
            this(verdict, confidence, reasoning, null);
        }

        public boolean isRealBug() {
            return verdict == VerdictType.REAL_BUG;
        }
    }

    private VerdictParser() {
    }

    /**
     * The structured branch of the response parsing ({@code verify_bug_with_llm.py:198-224}).
     * Empty when the response has no parseable VERDICT (the Python code then falls
     * back to keyword counting).
     */
    public static Optional<Verdict> parse(String response) {
        Matcher verdictMatch = VERDICT.matcher(response);
        if (!verdictMatch.find()) {
            return Optional.empty();
        }
        boolean isRealBug = "REAL BUG".equals(verdictMatch.group(1).toUpperCase(java.util.Locale.ROOT));

        double confidence = 0.7; // default for both verdicts (verify_bug_with_llm.py:205)
        Matcher confidenceMatch = CONFIDENCE.matcher(response);
        if (confidenceMatch.find()) {
            confidence = Math.min(Double.parseDouble(confidenceMatch.group(1)) / 10, 0.95);
        }

        String reasoning;
        Matcher reasoningMatch = REASONING.matcher(response);
        if (reasoningMatch.find()) {
            reasoning = reasoningMatch.group(1).strip();
        } else {
            reasoning = response.substring(0, Math.min(500, response.length()));
        }
        if (reasoning.isEmpty()) {
            reasoning = "No detailed reasoning provided"; // verify_bug_with_llm.py:221 falsy guard
        } else if (reasoning.length() > 500) {
            reasoning = reasoning.substring(0, 500);
        }

        return Optional.of(new Verdict(
                isRealBug ? VerdictType.REAL_BUG : VerdictType.FALSE_POSITIVE,
                confidence,
                reasoning,
                parseSpecBasis(response)));
    }

    /**
     * I18 — extracts the optional SPEC_BASIS field of a spec-grounded response.
     *
     * @return the stripped SPEC_BASIS text, or null when no SPEC_BASIS line exists
     *         (legacy responses); may be empty when the line is present but blank
     */
    public static String parseSpecBasis(String response) {
        if (response == null) {
            return null;
        }
        Matcher matcher = SPEC_BASIS.matcher(response);
        return matcher.find() ? matcher.group(1).strip() : null;
    }
}
