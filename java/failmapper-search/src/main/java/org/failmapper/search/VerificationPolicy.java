package org.failmapper.search;

import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * D10 (pure parts) + F15 — the LLM-free decision logic of {@code verify_bug_with_llm}
 * ({@code verify_bug_with_llm.py:32-75} pre-filters, {@code :131-153} failure defaults,
 * {@code :159-223} response parsing). The LLM transport itself stays in the M4 module;
 * per registered improvement I15 the pre-filters are algorithm, kept verbatim.
 *
 * <p>Regex dialect notes (contract 3.3):
 * <ul>
 *   <li>X1: Python {@code \Z} = Java {@code \z} (Java's {@code \Z} tolerates a trailing
 *       newline and would change the captured REASONING boundary);</li>
 *   <li>X3: Python {@code re.IGNORECASE} = {@code CASE_INSENSITIVE | UNICODE_CASE};</li>
 *   <li>X2 accepted divergence: the CONFIDENCE digits pattern keeps ASCII {@code \d}
 *       (Java default) because Python's {@code float()} and Java's parse already differ
 *       on non-ASCII digits (N10/N14) and LLM verdict digits are ASCII in practice.</li>
 * </ul>
 */
public final class VerificationPolicy {

    /** Verification outcome — {@code {"is_real_bug", "confidence", "reasoning"}}. */
    public record Verdict(boolean isRealBug, double confidence, String reasoning) {
    }

    /** C45 — pre-verified confidence above which verification is skipped ({@code verify_bug_with_llm.py:69}). */
    public static final double SKIP_VERIFICATION_CONFIDENCE = 0.9;

    /** C45 — API-failure / insufficient-data default threshold: {@code is_real_bug = confidence > 0.7}. */
    public static final double DEFAULT_REAL_BUG_THRESHOLD = 0.7;

    /** {@code verify_bug_with_llm.py:159} — VERDICT line ({@code re.IGNORECASE}). */
    private static final Pattern VERDICT_PATTERN = Pattern.compile(
            "VERDICT:\\s*[\"']?(REAL BUG|FALSE POSITIVE)[\"']?",
            Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE | Pattern.UNICODE_CHARACTER_CLASS);

    /** {@code verify_bug_with_llm.py:160} — CONFIDENCE number (case-sensitive in Python). */
    private static final Pattern CONFIDENCE_PATTERN = Pattern.compile(
            "CONFIDENCE:\\s*(\\d+(?:\\.\\d+)?)");

    /** {@code verify_bug_with_llm.py:161} — REASONING capture ({@code re.DOTALL}; {@code \Z}→{@code \z}, X1). */
    private static final Pattern REASONING_PATTERN = Pattern.compile(
            "REASONING:(.+?)(?=VERDICT:|CONFIDENCE:|\\z)", Pattern.DOTALL);

    /** {@code verify_bug_with_llm.py:166} — applied to the LOWERCASED response. */
    private static final Pattern EXPLICIT_REAL_PATTERN = Pattern.compile(
            "(this|it)\\s+(is|appears to be)\\s+a\\s+real\\s+bug", Pattern.UNICODE_CHARACTER_CLASS);

    /** {@code verify_bug_with_llm.py:171} — applied to the LOWERCASED response. */
    private static final Pattern EXPLICIT_NOT_REAL_PATTERN = Pattern.compile(
            "(this|it)\\s+(is|appears to be)\\s+not\\s+a\\s+real\\s+bug", Pattern.UNICODE_CHARACTER_CLASS);

    /** {@code verify_bug_with_llm.py:178-179} — positive signals, source order. */
    static final List<String> POSITIVE_SIGNALS = List.of(
            "real issue", "actual bug", "code defect", "exposes a problem",
            "defect in the class", "vulnerability", "should be fixed");

    /** {@code verify_bug_with_llm.py:180-182} — negative signals, source order. */
    static final List<String> NEGATIVE_SIGNALS = List.of(
            "unreasonable test", "test method issue", "test environment problem",
            "not a bug", "expected behavior", "by design", "unreasonable expectation",
            "edge case that", "not realistic", "documented limitation");

    /**
     * Missing-input guard ({@code verify_bug_with_llm.py:32-39}): with no test method or
     * source code, fall back to the pre-verification confidence.
     * {@code incomingConfidence} is Python {@code bug_info.get("confidence", 0.5)}.
     */
    public Verdict insufficientInput(double incomingConfidence) {
        return new Verdict(incomingConfidence > DEFAULT_REAL_BUG_THRESHOLD, incomingConfidence,
                "Insufficient data for verification");
    }

    /**
     * D10 heuristic pre-filters ({@code verify_bug_with_llm.py:46-75}), evaluated in
     * source order BEFORE any LLM call; returns null when none fires (LLM needed).
     * <ol>
     *   <li>assertion_failure expecting {@code <null>}/{@code <[]>}/{@code <>} in a test
     *       whose name contains "null"/"empty" → FALSE POSITIVE 0.9;</li>
     *   <li>memory_error type, or OutOfMemoryError/StackOverflowError in the error text
     *       → REAL BUG 0.95;</li>
     *   <li>incoming confidence &gt; 0.9 → REAL BUG at that confidence (skip).</li>
     * </ol>
     *
     * @param bugType            {@code bug_info.get("type", "unknown")}
     * @param errorMessage       {@code bug_info.get("error", "")}
     * @param incomingConfidence {@code bug_info.get("confidence", 0.5)}
     * @param testMethod         the test method source text
     */
    public Verdict preFilter(String bugType, String errorMessage, double incomingConfidence,
                             String testMethod) {
        String type = bugType == null ? "unknown" : bugType;
        String error = errorMessage == null ? "" : errorMessage;
        String method = testMethod == null ? "" : testMethod;

        if (type.equals("assertion_failure")) {
            boolean trivialExpectation =
                    (error.contains("expected: <null>") && error.contains("but was: <"))
                            || (error.contains("expected: <[]>") && error.contains("but was: <"))
                            || (error.contains("expected: <>") && error.contains("but was: <"));
            if (trivialExpectation) {
                String methodLower = method.toLowerCase(Locale.ROOT);
                if (methodLower.contains("null") || methodLower.contains("empty")) {
                    return new Verdict(false, 0.9,
                            "This is a common false positive for empty/null tests - "
                                    + "the test expectation is likely incorrect");
                }
            }
        }

        if (type.equals("memory_error") || error.contains("OutOfMemoryError")
                || error.contains("StackOverflowError")) {
            return new Verdict(true, 0.95,
                    "Memory errors are almost always real bugs, typically indicating "
                            + "infinite recursion or excessive memory allocation");
        }

        if (incomingConfidence > SKIP_VERIFICATION_CONFIDENCE) {
            return new Verdict(true, incomingConfidence, "High confidence pre-verification");
        }

        return null;
    }

    /**
     * API-failure / insufficient-response default ({@code verify_bug_with_llm.py:131-137,
     * 146-153}): rely on the pre-verification confidence; {@code is_real_bug = conf > 0.7}.
     */
    public Verdict apiFailureDefault(double incomingConfidence) {
        return new Verdict(incomingConfidence > DEFAULT_REAL_BUG_THRESHOLD, incomingConfidence,
                "Unable to perform LLM verification");
    }

    /** {@code verify_bug_with_llm.py:131}: a null/short response (&lt; 50 chars) is unusable. */
    public boolean insufficientResponse(String response) {
        return response == null || response.length() < 50;
    }

    /** F15 structured-confidence math: {@code min(llm_confidence / 10, 0.95)} (N10 — float division). */
    public static double structuredConfidence(double llmConfidence) {
        return Math.min(llmConfidence / 10.0, 0.95);
    }

    /**
     * F15 — full port of the response parsing ({@code verify_bug_with_llm.py:159-223}).
     *
     * <p>Branch order preserved:
     * <ul>
     *   <li>structured VERDICT found → {@code is_real = verdict.upper() == "REAL BUG"};
     *       confidence {@code min(llm/10, 0.95)} or 0.7 default; reasoning from the
     *       REASONING capture (stripped) or the first 500 chars;</li>
     *   <li>no VERDICT → explicit-statement matching on the lowercased response. NOTE the
     *       Python or-chain quirk kept verbatim: a plain {@code "real bug"} substring hits
     *       the POSITIVE branch first, so a response containing only "not a real bug"
     *       still classifies as REAL (its check comes second);</li>
     *   <li>else signal counting: pos &gt; neg → REAL at {@code 0.6 + min(0.3,
     *       0.05*(pos-neg))}; otherwise (ties included) FALSE POSITIVE at
     *       {@code 0.6 + min(0.3, 0.05*(neg-pos))}.</li>
     * </ul>
     * Reasoning is truncated to 500 chars; an EMPTY reasoning falls back to
     * "No detailed reasoning provided" (Python truthiness at {@code :221}).
     */
    public Verdict parseResponse(String response) {
        String text = response == null ? "" : response;
        String lower = text.toLowerCase(Locale.ROOT);

        Matcher verdictMatch = VERDICT_PATTERN.matcher(text);
        boolean isRealBug;
        double verificationConfidence;
        String reasoning;

        if (!verdictMatch.find()) {
            if (EXPLICIT_REAL_PATTERN.matcher(lower).find()
                    || lower.contains("yes, this is a real bug")
                    || lower.contains("real bug")) {
                isRealBug = true;
                verificationConfidence = 0.8;
            } else if (EXPLICIT_NOT_REAL_PATTERN.matcher(lower).find()
                    || lower.contains("not a real bug")
                    || lower.contains("false positive")) {
                isRealBug = false;
                verificationConfidence = 0.8;
            } else {
                int posCount = 0;
                for (String signal : POSITIVE_SIGNALS) {
                    if (lower.contains(signal)) {
                        posCount += 1;
                    }
                }
                int negCount = 0;
                for (String signal : NEGATIVE_SIGNALS) {
                    if (lower.contains(signal)) {
                        negCount += 1;
                    }
                }
                if (posCount > negCount) {
                    isRealBug = true;
                    verificationConfidence = 0.6 + Math.min(0.3, 0.05 * (posCount - negCount));
                } else {
                    isRealBug = false;
                    verificationConfidence = 0.6 + Math.min(0.3, 0.05 * (negCount - posCount));
                }
            }
            reasoning = truncate(text, 500);
        } else {
            isRealBug = verdictMatch.group(1).toUpperCase(Locale.ROOT).equals("REAL BUG");

            Matcher confidenceMatch = CONFIDENCE_PATTERN.matcher(text);
            if (confidenceMatch.find()) {
                double llmConfidence = Double.parseDouble(confidenceMatch.group(1));
                verificationConfidence = structuredConfidence(llmConfidence);
            } else {
                // Python: `0.7 if is_real_bug else 0.7` — both branches 0.7, kept as-is.
                verificationConfidence = 0.7;
            }

            Matcher reasoningMatch = REASONING_PATTERN.matcher(text);
            if (reasoningMatch.find()) {
                reasoning = reasoningMatch.group(1).strip(); // Python .strip() = Unicode strip (S7)
            } else {
                reasoning = truncate(text, 500);
            }
        }

        String finalReasoning = (reasoning == null || reasoning.isEmpty())
                ? "No detailed reasoning provided"
                : truncate(reasoning, 500);
        return new Verdict(isRealBug, verificationConfidence, finalReasoning);
    }

    /** Python {@code s[:n]}. */
    private static String truncate(String s, int n) {
        return s.length() <= n ? s : s.substring(0, n);
    }
}
