package org.failmapper.search;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import org.failmapper.core.model.BoundaryCondition;
import org.failmapper.core.model.FailureModel;
import org.failmapper.core.model.FailureScenario;

/**
 * D3 — port of the ACTIVE {@code select_strategies}
 * ({@code test_generation_strategies.py:706-842}; the randomized variant at :477-704 is
 * COMMENTED OUT in the baseline and deliberately not ported, contract R14) plus the D2
 * pattern→strategy routing table ({@code _map_patterns_to_strategies}, :387-457).
 *
 * <p>The live selector is fully DETERMINISTIC. Ordering contract O4: the base-strategy
 * map is insertion-ordered (boundary_testing, expression, exception_handling,
 * data_validation, resource_management, state_transition, business_logic) and the final
 * sort is STABLE descending by weight — boundary_testing and expression both start at
 * 1.0, and their tie resolves to map insertion order, which then drives action
 * generation order in D1.
 *
 * <p>Condition ids use the FIXED {@code "{method}_{line}"} format
 * ({@code test_generation_strategies.py:760-762}: "Must match the '{method}_{line}'
 * format used by test_state.track_branch_condition_coverage").
 */
public final class StrategySelector {

    /** {@code self.failures}; may be null. */
    private final List<FailureScenario> failures;

    /** {@code self.f_model}; may be null. */
    private final FailureModel fModel;

    public StrategySelector(List<FailureScenario> failures, FailureModel fModel) {
        this.failures = failures;
        this.fModel = fModel;
    }

    // ------------------------------------------------------------------
    // D2 — pattern -> strategy routing table (test_generation_strategies.py:393-442)
    // ------------------------------------------------------------------

    /** D2 routing table, source order preserved ({@code test_generation_strategies.py:393-442}). */
    public static final Map<String, List<String>> PATTERN_MAPPINGS;

    static {
        Map<String, List<String>> m = new LinkedHashMap<>();
        // Logical bug patterns
        m.put("operator_precedence", List.of("expression"));
        m.put("bitwise_logical_confusion", List.of("expression"));
        m.put("off_by_one", List.of("boundary_testing"));
        m.put("boundary_condition", List.of("boundary_testing"));
        m.put("null_handling", List.of("null_empty_testing", "exception_handling"));
        m.put("string_comparison", List.of("data_validation"));
        m.put("boolean_bug", List.of("expression"));
        // String-index mappings
        m.put("string_index_bounds", List.of("string_operation_testing", "boundary_testing"));
        m.put("string_index_error", List.of("string_operation_testing", "exception_handling"));
        // Array-index mappings
        m.put("array_index_bounds", List.of("array_operation_testing", "boundary_testing"));
        m.put("array_index_error", List.of("array_operation_testing", "exception_handling"));
        // Resource management patterns
        m.put("resource_management", List.of("resource_lifecycle_testing", "exception_resource_testing"));
        m.put("resource_leak", List.of("resource_lifecycle_testing", "exception_resource_testing"));
        m.put("use_after_close", List.of("resource_lifecycle_testing"));
        // Data operation patterns
        m.put("data_operation", List.of("type_conversion_testing", "arithmetic_edge_testing"));
        m.put("integer_truncation", List.of("type_conversion_testing"));
        m.put("precision_loss", List.of("type_conversion_testing"));
        m.put("integer_division", List.of("arithmetic_edge_testing"));
        m.put("signed_unsigned_comparison", List.of("type_conversion_testing", "boundary_testing"));
        // Concurrency patterns
        m.put("concurrency", List.of("concurrency_testing"));
        m.put("unsynchronized_shared_state", List.of("concurrency_testing"));
        m.put("potential_deadlock", List.of("concurrency_testing"));
        // Exception handling patterns
        m.put("exception_handling", List.of("exception_handling", "error_propagation_testing"));
        m.put("empty_catch", List.of("error_propagation_testing"));
        m.put("swallowed_exception", List.of("error_propagation_testing"));
        // Validation patterns
        m.put("validation", List.of("data_validation", "null_empty_testing"));
        m.put("missing_null_check", List.of("null_empty_testing"));
        m.put("missing_empty_check", List.of("null_empty_testing"));
        // Security patterns
        m.put("security", List.of("security_testing"));
        m.put("sql_injection", List.of("security_testing"));
        m.put("hardcoded_credential", List.of("security_testing"));
        PATTERN_MAPPINGS = Collections.unmodifiableMap(m);
    }

    /**
     * D2 — port of {@code _map_patterns_to_strategies}
     * ({@code test_generation_strategies.py:444-457}): subtype match FIRST, then type
     * match; the Python defaultdict(list) APPENDS, so duplicate patterns accumulate
     * duplicate strategy entries — preserved. Missing subtype defaults to "unknown"
     * (never a mapping key). Returns an insertion-ordered map keyed by the matched
     * subtype/type.
     */
    public Map<String, List<String>> mapPatternsToStrategies() {
        Map<String, List<String>> patternToStrategies = new LinkedHashMap<>();
        if (failures == null) {
            return patternToStrategies;
        }
        for (FailureScenario pattern : failures) {
            String patternType = pattern.type() == null ? "unknown" : pattern.type();
            String patternSubtype = pattern.subtype() == null ? "unknown" : pattern.subtype();
            List<String> bySubtype = PATTERN_MAPPINGS.get(patternSubtype);
            if (bySubtype != null) {
                patternToStrategies.computeIfAbsent(patternSubtype, k -> new ArrayList<>()).addAll(bySubtype);
            }
            List<String> byType = PATTERN_MAPPINGS.get(patternType);
            if (byType != null) {
                patternToStrategies.computeIfAbsent(patternType, k -> new ArrayList<>()).addAll(byType);
            }
        }
        return patternToStrategies;
    }

    // ------------------------------------------------------------------
    // D3 — select_strategies (test_generation_strategies.py:706-842)
    // ------------------------------------------------------------------

    /** Mutable (name, weight) pair — the {@code all_strategies} entry dict. */
    private static final class Entry {
        final String name;
        double weight;

        Entry(String name, double weight) {
            this.name = name;
            this.weight = weight;
        }
    }

    /**
     * The live strategy selector. All Python truthiness gates are non-null AND non-empty.
     *
     * @param state                the current test state; may be null
     * @param coveredPatterns      already-covered pattern ids; the whole pattern-boost
     *                             block is SKIPPED when null/empty (Python
     *                             {@code if covered_patterns:} — with nothing covered
     *                             yet, NO uncovered-pattern boosts apply)
     * @param coveredConditions    covered condition ids {@code "{method}_{line}"};
     *                             block skipped when null/empty
     * @param businessLogicIssues  predicted issues; may be null
     * @return normalized (sum = 1) strategies, stable-sorted by weight descending
     */
    public List<Strategy> selectStrategies(FaTestState state,
                                           Set<String> coveredPatterns,
                                           Set<String> coveredConditions,
                                           List<BusinessLogicIssue> businessLogicIssues) {
        // Base strategies (C47) — insertion order is load-bearing (O4).
        Map<String, Entry> allStrategies = new LinkedHashMap<>();
        allStrategies.put("boundary_testing", new Entry("Boundary Value Testing", 1.0));
        allStrategies.put("expression", new Entry("Logical Expression Testing", 1.0));
        allStrategies.put("exception_handling", new Entry("Exception Path Testing", 0.7));
        allStrategies.put("data_validation", new Entry("Data Validation Testing", 0.6));
        allStrategies.put("resource_management", new Entry("Resource Management Testing", 0.5));
        allStrategies.put("state_transition", new Entry("State Transition Testing", 0.8));
        allStrategies.put("business_logic", new Entry("Business Logic Testing", 0.0));

        // Boost by uncovered pattern types (test_generation_strategies.py:731-751):
        // +0.2 per uncovered pattern, keyword-routed, elif chain.
        if (coveredPatterns != null && !coveredPatterns.isEmpty()) {
            List<FailureScenario> uncoveredPatterns = new ArrayList<>();
            if (failures != null) {
                for (FailureScenario p : failures) {
                    if (!coveredPatterns.contains(p.type() + "_" + p.line())) {
                        uncoveredPatterns.add(p);
                    }
                }
            }
            for (FailureScenario pattern : uncoveredPatterns) {
                String patternType = pattern.type() == null ? "" : pattern.type();
                if (patternType.contains("boundary") || patternType.contains("off_by_one")) {
                    allStrategies.get("boundary_testing").weight += 0.2;
                } else if (patternType.contains("boolean_bug") || patternType.contains("operator")) {
                    allStrategies.get("expression").weight += 0.2;
                } else if (patternType.contains("null") || patternType.contains("exception")) {
                    allStrategies.get("exception_handling").weight += 0.2;
                } else if (patternType.contains("resource") || patternType.contains("leak")) {
                    allStrategies.get("resource_management").weight += 0.2;
                } else if (patternType.contains("state")) {
                    allStrategies.get("state_transition").weight += 0.2;
                }
            }
        }

        // Boost by uncovered condition types (test_generation_strategies.py:754-775) —
        // FIXED "{method}_{line}" id format. NOTE the else-branch fires even when both
        // counters are 0 (boundary_testing +0.3).
        if (coveredConditions != null && !coveredConditions.isEmpty()
                && fModel != null && fModel.boundaryConditions() != null) {
            int uncoveredIf = 0;
            int uncoveredLoops = 0;
            for (BoundaryCondition cond : fModel.boundaryConditions()) {
                // Must match the "{method}_{line}" format used by branch tracking
                // (test_generation_strategies.py:760-762; cond.get('method','') / .get('line',0)).
                String conditionId = (cond.method() == null ? "" : cond.method()) + "_" + cond.line();
                String condType = cond.type() == null ? "" : cond.type();
                if (!coveredConditions.contains(conditionId)) {
                    if (condType.equals("if_condition")) {
                        uncoveredIf += 1;
                    } else if (condType.equals("while_loop") || condType.equals("for_loop")) {
                        uncoveredLoops += 1;
                    }
                }
            }
            if (uncoveredIf > uncoveredLoops) {
                allStrategies.get("expression").weight += 0.3;
            } else {
                allStrategies.get("boundary_testing").weight += 0.3;
            }
        }

        // Boost by business-logic issues (test_generation_strategies.py:778-798):
        // business_logic += 0.8*conf per issue, plus one keyword-routed boost (elif chain).
        if (businessLogicIssues != null && !businessLogicIssues.isEmpty()) {
            for (BusinessLogicIssue issue : businessLogicIssues) {
                String issueType = (issue.type() == null ? "" : issue.type()).toLowerCase(Locale.ROOT);
                double confidence = issue.confidenceOrZero(); // .get('confidence', 0)

                allStrategies.get("business_logic").weight += 0.8 * confidence;

                if (issueType.contains("boundary") || issueType.contains("index")) {
                    allStrategies.get("boundary_testing").weight += 0.5 * confidence;
                } else if (issueType.contains("logic") || issueType.contains("condition")) {
                    allStrategies.get("expression").weight += 0.6 * confidence;
                } else if (issueType.contains("null") || issueType.contains("exception")) {
                    allStrategies.get("exception_handling").weight += 0.5 * confidence;
                } else if (issueType.contains("validation") || issueType.contains("input")) {
                    allStrategies.get("data_validation").weight += 0.5 * confidence;
                } else if (issueType.contains("state") || issueType.contains("transition")) {
                    allStrategies.get("state_transition").weight += 0.5 * confidence;
                } else if (issueType.contains("resource") || issueType.contains("leak")) {
                    allStrategies.get("resource_management").weight += 0.5 * confidence;
                }
            }
        }

        // Stagnation boosts (test_generation_strategies.py:800-822, C48):
        // |current - parent| < 0.1 → business_logic +0.4, state_transition +0.3, and
        // per detected-bug TYPE (note: bug.get("type",""), NOT bug_type) +0.3 boosts.
        if (state != null && state.parentCoverage != null) {
            double parentCoverage = state.parentCoverage;
            double currentCoverage = state.coverage;
            if (Math.abs(currentCoverage - parentCoverage) < 0.1) {
                allStrategies.get("business_logic").weight += 0.4;
                allStrategies.get("state_transition").weight += 0.3;

                if (!state.detectedBugs.isEmpty()) {
                    // Python builds a set(); iteration order of a Python str-set is
                    // arbitrary, but each type adds a fixed +0.3 to a single accumulator,
                    // so the result is order-independent. First-seen order here.
                    Set<String> bugTypes = new LinkedHashSet<>();
                    for (DetectedBug bug : state.detectedBugs) {
                        bugTypes.add(bug.type == null ? "" : bug.type);
                    }
                    for (String bugType : bugTypes) {
                        String lower = bugType.toLowerCase(Locale.ROOT);
                        if (lower.contains("boundary")) {
                            allStrategies.get("boundary_testing").weight += 0.3;
                        } else if (lower.contains("logic")) {
                            allStrategies.get("expression").weight += 0.3;
                        } else if (lower.contains("resource")) {
                            allStrategies.get("resource_management").weight += 0.3;
                        }
                    }
                }
            }
        }

        // Normalize to sum = 1 and emit in insertion order (test_generation_strategies.py:824-834).
        double totalWeight = 0.0;
        for (Entry entry : allStrategies.values()) {
            totalWeight += entry.weight;
        }
        List<Strategy> strategies = new ArrayList<>();
        for (Map.Entry<String, Entry> e : allStrategies.entrySet()) {
            double normalized = totalWeight > 0 ? e.getValue().weight / totalWeight : 0.0;
            strategies.add(new Strategy(e.getKey(), e.getValue().name, normalized));
        }

        // Stable sort, weight descending (test_generation_strategies.py:837) — ties keep
        // insertion order, matching Python's stable reverse=True sort (O4).
        strategies.sort((a, b) -> Double.compare(b.weight(), a.weight()));

        return strategies;
    }
}
