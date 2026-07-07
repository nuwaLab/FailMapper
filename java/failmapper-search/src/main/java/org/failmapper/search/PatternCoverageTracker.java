package org.failmapper.search;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import org.failmapper.core.model.FailureScenario;

/**
 * F9 — port of {@code track_logic_scenario_coverage} ({@code test_state.py:387-546}),
 * the confidence-thresholded pattern-coverage model. Kept FORMULA-EXACT per registered
 * improvement I4 v1 ("公式原样保留, 只换更准的输入").
 *
 * <p>Per-pattern confidence lives in {@code state.coveredFailuresScores}
 * (Python {@code self.covered_failures_scores}); covered ids
 * ({@code "{type}_{line}"}) in {@code state.coveredFailures}.
 *
 * <p>Sequence per pattern (order is load-bearing):
 * <ol>
 *   <li>0.95 decay of an untouched positive score ({@code test_state.py:430-439}, C39) —
 *       may remove the id from the covered set mid-loop. NOTE the faithful subtlety:
 *       the final score written in step 3 uses the PRE-decay value as its base
 *       ({@code test_state.py:508} reads {@code current_confidence} captured before the
 *       decay), so the decayed number itself is transient; what persists from the decay
 *       is only its covered-set removal, which step 4's {@code was_covered} then
 *       observes. Ported exactly as written (iron rule).</li>
 *   <li>evidence boosts (C40): +0.7 direct {@code "line N"}/{@code "行 N"} text match;
 *       +min(0.5, 0.1*keywordMatches); +0.4 first matching logical-bug evidence;
 *       +0.3 test-method-name match.</li>
 *   <li>{@code new_confidence = min(1.0, pre_decay_confidence + boost)} stored.</li>
 *   <li>covered iff {@code new_confidence >= threshold[risk]} — high 0.8 / medium 0.6 /
 *       low 0.5, default 0.6 (C38; 'critical' is unmapped and gets the 0.6 default).
 *       Patterns CAN become uncovered.</li>
 * </ol>
 */
public final class PatternCoverageTracker {

    /** C38 — {@code confidence_thresholds} ({@code test_state.py:410-414}); default 0.6 via {@code .get(risk, 0.6)}. */
    public static double thresholdFor(String riskLevel) {
        if ("high".equals(riskLevel)) {
            return 0.8;
        }
        if ("medium".equals(riskLevel)) {
            return 0.6;
        }
        if ("low".equals(riskLevel)) {
            return 0.5;
        }
        return 0.6;
    }

    /**
     * {@code pattern_keywords} ({@code test_state.py:449-462}) — keyword table mirrored
     * verbatim (keywords are matched as lowercase SUBSTRINGS of the lowercased test code;
     * that is the Python "tokenization": plain {@code in} containment, no word splitting).
     * Insertion order preserved for parity although only membership is consulted.
     */
    private static final Map<String, List<String>> PATTERN_KEYWORDS;

    static {
        Map<String, List<String>> m = new LinkedHashMap<>();
        m.put("null_handling", List.of("null", "nullpointer", "nullpointerexception", "assertnull", "nullcheck"));
        m.put("array_index_bounds", List.of("index", "bounds", "outofbounds", "array", "arrayindexoutofbounds"));
        m.put("off_by_one", List.of("boundar", "off by one", "off-by-one", "boundary"));
        m.put("string_comparison", List.of("string", "equals", "compare", "assertion"));
        m.put("boolean_bug", List.of("boolean", "logic", "boolean expression", "logical"));
        m.put("boundary_condition", List.of("boundary", "edge case", "边界条件"));
        m.put("resource_leak", List.of("resource", "leak", "close"));
        m.put("operator_precedence", List.of("operator", "precedence"));
        m.put("copy_paste", List.of("duplicate", "copy", "paste"));
        m.put("integer_overflow", List.of("overflow", "integer"));
        m.put("bitwise_logical_confusion", List.of("bitwise", "logical"));
        PATTERN_KEYWORDS = m;
    }

    /**
     * Keyword list for the type-keyword boost — Python
     * {@code pattern_keywords.get(pattern_type, [pattern_type, "bug", "test", "error"])}
     * ({@code test_state.py:465}).
     */
    public static List<String> keywordsFor(String patternType) {
        List<String> known = PATTERN_KEYWORDS.get(patternType);
        return known != null ? known : List.of(patternType, "bug", "test", "error");
    }

    /**
     * Keyword list for the bug-evidence boost — Python
     * {@code pattern_keywords.get(pattern_type, [])} ({@code test_state.py:485}):
     * NOTE the different default (empty, not the 4-element fallback).
     */
    private static List<String> bugEvidenceKeywordsFor(String patternType) {
        List<String> known = PATTERN_KEYWORDS.get(patternType);
        return known != null ? known : List.of();
    }

    /**
     * Run one tracking pass over {@code state.failures}, mutating
     * {@code state.coveredFailures} / {@code state.coveredFailuresScores}.
     */
    public void track(FaTestState state) {
        List<FailureScenario> failures = state.failures;
        if (failures == null || failures.isEmpty()) {
            return; // test_state.py:389-390 `if not self.failures`
        }

        // test_state.py:407 — lowercase whole-test-code haystack; "" for falsy test_code.
        String allTestCode = (state.testCode == null || state.testCode.isEmpty())
                ? ""
                : state.testCode.toLowerCase(Locale.ROOT);

        Set<String> updatedPatterns = new LinkedHashSet<>(); // test_state.py:417

        for (FailureScenario pattern : failures) {
            // test_state.py:420-423. pattern_id uses the raw type (KeyError-strict in
            // Python); pattern_type falls back to "unknown" for the keyword logic.
            String rawType = pattern.type();
            String patternId = rawType + "_" + pattern.line();
            String patternType = rawType == null ? "unknown" : rawType;
            int patternLocation = pattern.line();
            String patternRisk = pattern.riskLevel() == null ? "medium" : pattern.riskLevel().wire();

            double currentConfidence = state.coveredFailuresScores.getOrDefault(patternId, 0.0);

            // (1) decay — test_state.py:430-439 (C39). Transient: overwritten in step (3).
            if (!updatedPatterns.contains(patternId) && currentConfidence > 0) {
                double decayed = currentConfidence * 0.95;
                state.coveredFailuresScores.put(patternId, decayed);
                if (decayed < thresholdFor(patternRisk)) {
                    state.coveredFailures.remove(patternId); // membership-checked remove in Python; idempotent here
                }
            }

            // (2a) direct line-number match — test_state.py:442-446 (0.7).
            double confidenceBoost =
                    (allTestCode.contains("line " + patternLocation)
                            || allTestCode.contains("行 " + patternLocation)) ? 0.7 : 0.0;

            // (2b) keyword matches — test_state.py:465-470: min(0.5, 0.1*matches).
            int keywordMatches = 0;
            for (String keyword : keywordsFor(patternType)) {
                if (allTestCode.contains(keyword)) {
                    keywordMatches += 1;
                }
            }
            confidenceBoost += Math.min(0.5, 0.1 * keywordMatches);

            // (2c) logical-bug evidence — test_state.py:473-490: +0.4 for the FIRST match.
            if (!state.logicalBugs.isEmpty()) {
                String typeNoUnderscore = patternType.replace("_", "");
                for (DetectedBug bug : state.logicalBugs) {
                    String bugDescription = bug.descriptionOrEmpty().toLowerCase(Locale.ROOT);
                    String bugError = bug.errorOrEmpty().toLowerCase(Locale.ROOT);
                    String bugType = (bug.bugType == null ? "unknown" : bug.bugType).toLowerCase(Locale.ROOT);

                    boolean patternInBug = bugDescription.contains(patternType) || bugError.contains(patternType);
                    boolean patternRelatedToBugType = bugType.contains(typeNoUnderscore);
                    boolean keywordInBug = false;
                    for (String keyword : bugEvidenceKeywordsFor(patternType)) {
                        if (bugDescription.contains(keyword) || bugError.contains(keyword)) {
                            keywordInBug = true;
                            break;
                        }
                    }
                    if (patternInBug || patternRelatedToBugType || keywordInBug) {
                        confidenceBoost += 0.4;
                        break;
                    }
                }
            }

            // (2d) test-method-name evidence — test_state.py:493-502: +0.3 for the first match.
            double methodConfidence = 0.0;
            String typeNoUnderscore = patternType.replace("_", "");
            for (TestMethod method : state.testMethods) {
                if (method.name() != null) { // Python: isinstance(dict) and "name" in method
                    String methodName = method.name().toLowerCase(Locale.ROOT);
                    if (methodName.contains(patternType) || methodName.contains(typeNoUnderscore)) {
                        methodConfidence = 0.3;
                        break;
                    }
                }
            }
            confidenceBoost += methodConfidence;

            // (3) accumulate on the PRE-decay base — test_state.py:508.
            double newConfidence = Math.min(1.0, currentConfidence + confidenceBoost);

            // (4) threshold decision — test_state.py:510-525.
            double threshold = thresholdFor(patternRisk);
            boolean wasCovered = state.coveredFailures.contains(patternId);
            boolean shouldBeCovered = newConfidence >= threshold;

            state.coveredFailuresScores.put(patternId, newConfidence);
            updatedPatterns.add(patternId);

            if (shouldBeCovered && !wasCovered) {
                state.coveredFailures.add(patternId);
            } else if (wasCovered && !shouldBeCovered) {
                state.coveredFailures.remove(patternId);
            }
        }
    }
}
