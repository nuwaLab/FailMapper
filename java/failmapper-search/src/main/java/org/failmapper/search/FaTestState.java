package org.failmapper.search;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.failmapper.core.model.FailureModel;
import org.failmapper.core.model.FailureScenario;

/**
 * Failure-aware test state — the state-holding + tracking half of Python
 * {@code FATestState} ({@code test_state.py:28-310}).
 *
 * <p>SCOPE: this class holds state and performs the PURE analysis
 * ({@code analyze_test_logic_properties} incl. formula F12). The Maven/JaCoCo execution
 * half of Python {@code evaluate()} is NOT here — evaluation results arrive through an
 * injected {@link Evaluator} (see {@link DefaultEvaluator}), which applies the typed
 * M2 records ({@code TestRunResult}/{@code CoverageSnapshot}) and then runs the same
 * post-run tracking sequence as {@code test_state.py:200-216}.
 *
 * <p>Mutable public fields mirror the Python object's open attributes, exactly like
 * {@link FaMctsNode}. All collections are insertion-ordered (contract section 3.2):
 * {@code covered_failures}/{@code covered_branch_conditions} are Python sets iterated
 * for rewards and metrics — LinkedHashSet preserves discovery order;
 * {@code covered_failures_scores} is a Python dict — LinkedHashMap.
 *
 * <p>{@link #hasAssertions} note: Python initializes {@code self.has_assertions = False}
 * ({@code test_state.py:76}) and NEVER sets it true anywhere in the repository; its only
 * consumer was the F10 fallback ({@code test_state.py:622}) which is replaced by I3.
 * The field is kept for structural parity but is inert.
 */
public final class FaTestState {

    /** {@code self.test_code}. */
    public String testCode;

    /** {@code self.f_model} — failure model of the class under test; may be null. */
    public FailureModel fModel;

    /** {@code self.failures} — detected failure scenarios; may be null ({@code if not self.failures} guards). */
    public List<FailureScenario> failures;

    /** {@code self.coverage} — percentage 0..100 ({@code test_state.py:141/155}). */
    public double coverage = 0.0;

    /** {@code self.executed}. */
    public boolean executed = false;

    /** {@code self.compilation_errors} ({@code test_state.py:72/132}: {@code errors if errors else []}). */
    public List<String> compilationErrors = new ArrayList<>();

    /** {@code self.previous_compilation_errors} ({@code test_state.py:73}; carried forward at {@code fa_mcts.py:2744-2745}). */
    public List<String> previousCompilationErrors = new ArrayList<>();

    /** {@code self.test_methods} — parsed test methods (name + code dicts in Python). */
    public final List<TestMethod> testMethods = new ArrayList<>();

    /** {@code self.detected_bugs} (initialized by the Python base class). */
    public final List<DetectedBug> detectedBugs = new ArrayList<>();

    /**
     * {@code self.logical_bugs} ({@code test_state.py:53}). NOTE: Python appends the SAME
     * bug object here both from the assertion-failure branch of evaluate
     * ({@code test_state.py:190}) and again from {@code classify_logical_bugs}
     * ({@code test_state.py:375}) when the message also matches a classifier pattern —
     * duplicates are REAL Python behavior and inflate {@code count_logical_bugs()}
     * (feeds F6's {@code 0.4*count}); do not dedupe (iron rule).
     */
    public final List<DetectedBug> logicalBugs = new ArrayList<>();

    /** {@code self.has_bugs} ({@code test_state.py:54}; recomputed by D11 at {@code test_state.py:381}). */
    public boolean hasBugs = false;

    /** {@code self.covered_failures} — pattern ids {@code "{type}_{line}"} ({@code test_state.py:55/420}). */
    public final LinkedHashSet<String> coveredFailures = new LinkedHashSet<>();

    /** {@code self.covered_branch_conditions} — condition ids {@code "{method}_{line}"} ({@code test_state.py:56/565}). */
    public final LinkedHashSet<String> coveredBranchConditions = new LinkedHashSet<>();

    /**
     * {@code self.covered_failures_scores} — per-pattern confidence map for F9
     * ({@code test_state.py:396-397}, lazily created there; eagerly here). NOT carried
     * forward between states ({@code fa_mcts.py:2715-2755} copies only the covered SETS),
     * so each new state accrues confidence from zero.
     */
    public final LinkedHashMap<String, Double> coveredFailuresScores = new LinkedHashMap<>();

    // --- test-quality flags (test_state.py:60-64, 76; set by analyzeTestLogicProperties) ---

    /** {@code self.has_boundary_tests}. */
    public boolean hasBoundaryTests = false;

    /** {@code self.has_boolean_bug_tests}. */
    public boolean hasBooleanBugTests = false;

    /** {@code self.has_state_transition_tests}. */
    public boolean hasStateTransitionTests = false;

    /** {@code self.has_exception_path_tests}. */
    public boolean hasExceptionPathTests = false;

    /** {@code self.has_operator_precedence_tests} ({@code test_state.py:250}). */
    public boolean hasOperatorPrecedenceTests = false;

    /** {@code self.has_equivalence_class_tests} ({@code test_state.py:64}; never set true in Python). */
    public boolean hasEquivalenceClassTests = false;

    /** {@code self.has_assertions} — inert; see class doc. */
    public boolean hasAssertions = false;

    // --- pattern-extraction accumulators (test_state.py:79-80; EXTENDED on every analyze call) ---

    /** {@code self.boolean_expressions_tested}. */
    public final List<String> booleanExpressionsTested = new ArrayList<>();

    /** {@code self.boundary_values_tested} — entries are {@code {"operator":..., "value":...}} dicts in Python. */
    public final List<BoundaryValueTested> boundaryValuesTested = new ArrayList<>();

    /** {@code self.assertion_failures} ({@code test_state.py:83/195-198}). */
    public final List<AssertionFailureNote> assertionFailures = new ArrayList<>();

    /** {@code self.logic_coverage_depth} ({@code test_state.py:278}; reset each analyze call). */
    public int logicCoverageDepth = 0;

    /** F12: {@code self.logic_test_quality = min(1.0, logic_coverage_depth / 5.0)} ({@code test_state.py:310}). */
    public double logicTestQuality = 0.0;

    // --- risk metrics (test_state.py:67-69, 86-89) ---

    /** {@code self.risk_score}. */
    public double riskScore = 0.0;

    /** {@code self.high_risk_patterns_covered}. */
    public int highRiskPatternsCovered = 0;

    /** {@code self.critical_conditions_covered}. */
    public int criticalConditionsCovered = 0;

    /** {@code self.high_risk_pattern_coverage} (percentage; {@code test_state.py:645}). */
    public double highRiskPatternCoverage = 0.0;

    /** {@code self.method_complexity_coverage} (percentage; {@code test_state.py:662}). */
    public double methodComplexityCoverage = 0.0;

    // --- reward-loop bookkeeping ---

    /** {@code self.stagnant_coverage_iterations} — mutated by the reward calculator (F6 side effect). */
    public int stagnantCoverageIterations = 0;

    /** {@code self.metadata["parent_coverage"]} ({@code fa_mcts.py:2733-2737}); null = key absent (root state). */
    public Double parentCoverage = null;

    /** {@code self.metadata["action"]} — the action that generated this state; null = key absent. */
    public SearchAction metadataAction = null;

    /**
     * {@code self.business_logic_analysis["potential_bugs"]} ({@code fa_mcts.py:174-175, 887}).
     * Empty list models Python falsy (attribute absent or analysis empty).
     */
    public List<BusinessLogicIssue> businessLogicIssues = new ArrayList<>();

    /** Python boundary-value entry dict {@code {"operator": op, "value": value.strip()}} ({@code test_state.py:275}). */
    public record BoundaryValueTested(String operator, String value) {
    }

    /** Python assertion-failure entry dict {@code {"method": ..., "message": ...}} ({@code test_state.py:195-198}). */
    public record AssertionFailureNote(String method, String message) {
    }

    public FaTestState(String testCode, FailureModel fModel, List<FailureScenario> failures) {
        this.testCode = testCode;
        this.fModel = fModel;
        this.failures = failures;
    }

    /**
     * Child-state carry-forward — port of the state-copy block of {@code _apply_action}
     * ({@code fa_mcts.py:2715-2755}), the D6 step that shapes reward deltas:
     * <ul>
     *   <li>metadata: {@code action} + {@code parent_coverage} = parent's coverage
     *       (attribute default 0.0);</li>
     *   <li>{@code business_logic_analysis} reference copied;</li>
     *   <li>{@code previous_compilation_errors} = parent's {@code compilation_errors}
     *       only when truthy (non-empty);</li>
     *   <li>{@code coverage} pre-seeded from the parent when {@code > 0};</li>
     *   <li>{@code covered_failures}/{@code covered_branch_conditions} set COPIES when
     *       truthy — the confidence-score map is NOT carried (see field doc).</li>
     * </ul>
     */
    public void carryForwardFrom(FaTestState parent, SearchAction action) {
        double previousCoverage = parent == null ? 0.0 : parent.coverage;
        this.metadataAction = action;
        this.parentCoverage = previousCoverage;
        if (parent == null) {
            return;
        }
        this.businessLogicIssues = parent.businessLogicIssues;
        if (!parent.compilationErrors.isEmpty()) {
            this.previousCompilationErrors = new ArrayList<>(parent.compilationErrors);
        }
        if (previousCoverage > 0) {
            this.coverage = previousCoverage;
        }
        if (!parent.coveredFailures.isEmpty()) {
            this.coveredFailures.addAll(parent.coveredFailures);
        }
        if (!parent.coveredBranchConditions.isEmpty()) {
            this.coveredBranchConditions.addAll(parent.coveredBranchConditions);
        }
    }

    /** {@code count_logical_bugs} ({@code test_state.py:664-666}); duplicates count (see {@link #logicalBugs}). */
    public int countLogicalBugs() {
        return logicalBugs.size();
    }

    // ------------------------------------------------------------------
    // analyze_test_logic_properties (test_state.py:227-310) — pure string analysis
    // ------------------------------------------------------------------

    /**
     * {@code test_state.py:268} — boolean expressions inside assertions. Python
     * {@code re.findall} group 1; {@code \s} is Unicode-aware in Python, hence
     * UNICODE_CHARACTER_CLASS (contract X2).
     */
    private static final Pattern BOOLEAN_EXPR_PATTERN = Pattern.compile(
            "assert(?:True|False|Equals)\\s*\\(\\s*([^;]+?&&[^;]+|[^;]+?\\|\\|[^;]+?)\\s*[,\\)]",
            Pattern.UNICODE_CHARACTER_CLASS);

    /** {@code test_state.py:273} — boundary comparisons inside assertions (two groups: operator, value). */
    private static final Pattern BOUNDARY_TEST_PATTERN = Pattern.compile(
            "assert(?:True|False|Equals)\\s*\\(\\s*[^<>=!]+\\s*([<>=!]+)\\s*([^,\\)]+)",
            Pattern.UNICODE_CHARACTER_CLASS);

    /**
     * Port of {@code analyze_test_logic_properties} ({@code test_state.py:227-310}),
     * including formula F12 ({@code logic_coverage_depth} / {@code logic_test_quality}).
     *
     * <p>Flags are recomputed (overwritten) each call; the
     * {@code boolean_expressions_tested}/{@code boundary_values_tested} lists ACCUMULATE
     * across calls exactly like Python's {@code extend}/{@code append} (Python invokes
     * this both in {@code __init__} and in every {@code evaluate()}).
     */
    public void analyzeTestLogicProperties() {
        // Boolean-logic tests (test_state.py:230-234):
        // ("&&" and "||") or ("assertTrue" and "assertFalse")
        hasBooleanBugTests = false;
        for (TestMethod m : testMethods) {
            String code = m.codeOrEmpty();
            if ((code.contains("&&") && code.contains("||"))
                    || (code.contains("assertTrue") && code.contains("assertFalse"))) {
                hasBooleanBugTests = true;
                break;
            }
        }

        // Boundary-value tests (test_state.py:237-241): any of >= <= == !=
        hasBoundaryTests = false;
        for (TestMethod m : testMethods) {
            String code = m.codeOrEmpty();
            if (code.contains(">=") || code.contains("<=") || code.contains("==") || code.contains("!=")) {
                hasBoundaryTests = true;
                break;
            }
        }

        // State-transition tests (test_state.py:244-247): code.count(".") > 5
        hasStateTransitionTests = false;
        for (TestMethod m : testMethods) {
            if (countOccurrences(m.codeOrEmpty(), ".") > 5) {
                hasStateTransitionTests = true;
                break;
            }
        }

        // Operator-precedence tests (test_state.py:250-254): "(" and ")" and ("&&" or "||")
        hasOperatorPrecedenceTests = false;
        for (TestMethod m : testMethods) {
            String code = m.codeOrEmpty();
            if (code.contains("(") && code.contains(")")
                    && (code.contains("&&") || code.contains("||"))) {
                hasOperatorPrecedenceTests = true;
                break;
            }
        }

        // Exception-path tests (test_state.py:257-261). Python operator precedence:
        // "assertThrows" in code OR ("try" in code AND "catch" in code).
        hasExceptionPathTests = false;
        for (TestMethod m : testMethods) {
            String code = m.codeOrEmpty();
            if (code.contains("assertThrows") || (code.contains("try") && code.contains("catch"))) {
                hasExceptionPathTests = true;
                break;
            }
        }

        // Extract boolean expressions and boundary comparisons (test_state.py:264-275).
        for (TestMethod m : testMethods) {
            if (m.code() == null) {
                continue; // Python: `if isinstance(method, dict) and "code" in method`
            }
            String methodCode = m.code();
            Matcher boolMatcher = BOOLEAN_EXPR_PATTERN.matcher(methodCode);
            while (boolMatcher.find()) {
                booleanExpressionsTested.add(boolMatcher.group(1));
            }
            Matcher boundaryMatcher = BOUNDARY_TEST_PATTERN.matcher(methodCode);
            while (boundaryMatcher.find()) {
                // Python: value.strip() — String.strip() matches Python's Unicode strip (S7).
                boundaryValuesTested.add(new BoundaryValueTested(
                        boundaryMatcher.group(1), boundaryMatcher.group(2).strip()));
            }
        }

        // F12 — logic coverage depth (test_state.py:278-310).
        logicCoverageDepth = 0;
        if (!boundaryValuesTested.isEmpty()) {
            logicCoverageDepth += 1;
        }
        if (!booleanExpressionsTested.isEmpty()) {
            logicCoverageDepth += 1;
        }
        if (hasExceptionPathTests) {
            logicCoverageDepth += 1;
        }
        // Complex logic: "&&" and "||" and "!" all present (test_state.py:293-298).
        boolean complexLogic = false;
        for (TestMethod m : testMethods) {
            String code = m.codeOrEmpty();
            if (code.contains("&&") && code.contains("||") && code.contains("!")) {
                complexLogic = true;
                break;
            }
        }
        if (complexLogic) {
            logicCoverageDepth += 1;
        }
        // Mutation testing: ("+1" and "-1") or ("MIN_VALUE" or "MAX_VALUE") (test_state.py:301-307).
        boolean mutationTesting = false;
        for (TestMethod m : testMethods) {
            String code = m.codeOrEmpty();
            if ((code.contains("+1") && code.contains("-1"))
                    || (code.contains("MIN_VALUE") || code.contains("MAX_VALUE"))) {
                mutationTesting = true;
                break;
            }
        }
        if (mutationTesting) {
            logicCoverageDepth += 1;
        }

        logicTestQuality = Math.min(1.0, logicCoverageDepth / 5.0);
    }

    // ------------------------------------------------------------------
    // calculate_risk_metrics (test_state.py:634-662)
    // ------------------------------------------------------------------

    /**
     * Port of {@code calculate_risk_metrics} ({@code test_state.py:634-662}).
     * High-complexity filter inlines {@code get_high_complexity_methods(threshold=8)}
     * ({@code extractor.py:827-839}, C43): {@code cyclomatic > 8 OR cognitive > 8}.
     */
    public void calculateRiskMetrics() {
        if (failures != null && !failures.isEmpty()) {
            List<FailureScenario> highRisk = new ArrayList<>();
            for (FailureScenario p : failures) {
                if (p.riskLevel() != null && "high".equals(p.riskLevel().wire())) {
                    highRisk.add(p);
                }
            }
            if (!highRisk.isEmpty()) {
                int coveredHighRisk = 0;
                for (FailureScenario p : highRisk) {
                    if (coveredFailures.contains(p.patternId())) {
                        coveredHighRisk += 1;
                    }
                }
                highRiskPatternCoverage = (coveredHighRisk / (double) highRisk.size()) * 100;
            }
        }

        if (fModel != null && fModel.methodComplexity() != null) {
            List<String> complexMethods = new ArrayList<>();
            fModel.methodComplexity().forEach((name, complexity) -> {
                if (complexity.cyclomatic() > 8 || complexity.cognitive() > 8) {
                    complexMethods.add(name);
                }
            });
            if (!complexMethods.isEmpty()) {
                int coveredComplexMethods = 0;
                for (String methodName : complexMethods) {
                    String prefix = methodName + "_";
                    boolean anyCondition = false;
                    for (String condId : coveredBranchConditions) {
                        if (condId.startsWith(prefix)) {
                            anyCondition = true;
                            break;
                        }
                    }
                    if (anyCondition) {
                        coveredComplexMethods += 1;
                    }
                }
                methodComplexityCoverage = (coveredComplexMethods / (double) complexMethods.size()) * 100;
            }
        }
    }

    /** Python {@code str.count(sub)} — non-overlapping occurrence count. */
    private static int countOccurrences(String haystack, String needle) {
        int count = 0;
        int idx = 0;
        while ((idx = haystack.indexOf(needle, idx)) != -1) {
            count += 1;
            idx += needle.length();
        }
        return count;
    }
}
