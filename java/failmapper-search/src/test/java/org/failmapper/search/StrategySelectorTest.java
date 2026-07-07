package org.failmapper.search;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import java.util.Set;
import org.failmapper.core.model.BoundaryCondition;
import org.failmapper.core.model.FailureModel;
import org.failmapper.core.model.FailureScenario;
import org.failmapper.core.model.RiskLevel;
import org.junit.jupiter.api.Test;

/**
 * D3 — the ACTIVE select_strategies (test_generation_strategies.py:706-842) and the D2
 * routing table (:393-457). Weights are hand-computed in comments.
 */
class StrategySelectorTest {

    private static FailureScenario pattern(String type, int line) {
        return new FailureScenario(type, null, line, RiskLevel.MEDIUM, "", "");
    }

    private static double weightOf(List<Strategy> strategies, String id) {
        return strategies.stream().filter(s -> s.id().equals(id)).findFirst().orElseThrow().weight();
    }

    @Test
    void baseWeightsNormalizedAndStableSorted() {
        // C47 base: bt 1.0, ex 1.0, eh 0.7, dv 0.6, rm 0.5, st 0.8, bl 0.0; total 4.6.
        StrategySelector selector = new StrategySelector(null, null);
        List<Strategy> strategies = selector.selectStrategies(null, null, null, null);

        assertEquals(7, strategies.size());
        // Stable sort desc: bt and ex tie at 1.0/4.6 — insertion order keeps bt first (O4).
        assertEquals(List.of("boundary_testing", "expression", "state_transition",
                        "exception_handling", "data_validation", "resource_management", "business_logic"),
                strategies.stream().map(Strategy::id).toList());
        assertEquals(1.0 / 4.6, strategies.get(0).weight(), 1e-12);
        assertEquals(0.8 / 4.6, weightOf(strategies, "state_transition"), 1e-12);
        assertEquals(0.0, weightOf(strategies, "business_logic"), 0.0);

        double sum = strategies.stream().mapToDouble(Strategy::weight).sum();
        assertEquals(1.0, sum, 1e-12);
    }

    @Test
    void uncoveredPatternBoostsRequireNonEmptyCoveredSet() {
        // Python gate `if covered_patterns:` — an EMPTY covered set skips ALL pattern
        // boosts even though every pattern is uncovered.
        List<FailureScenario> failures = List.of(pattern("off_by_one", 5), pattern("null_handling", 9));
        StrategySelector selector = new StrategySelector(failures, null);

        List<Strategy> without = selector.selectStrategies(null, Set.of(), null, null);
        assertEquals(1.0 / 4.6, weightOf(without, "boundary_testing"), 1e-12); // no boost

        // With one covered id: off_by_one_5 covered; null_handling_9 uncovered →
        // eh += 0.2 → weights bt 1.0, ex 1.0, eh 0.9, dv 0.6, rm 0.5, st 0.8, bl 0 → total 4.8
        List<Strategy> with = selector.selectStrategies(null, Set.of("off_by_one_5"), null, null);
        assertEquals(0.9 / 4.8, weightOf(with, "exception_handling"), 1e-12);
        assertEquals(1.0 / 4.8, weightOf(with, "boundary_testing"), 1e-12);
    }

    @Test
    void patternKeywordRoutingElifChain() {
        // 5 uncovered patterns route down the elif chain (+0.2 each):
        //   boundary_condition → bt; boolean_bug → ex; null_handling → eh;
        //   resource_leak → rm; state_corruption → st
        // weights: bt 1.2, ex 1.2, eh 0.9, dv 0.6, rm 0.7, st 1.0, bl 0 → total 5.6
        List<FailureScenario> failures = List.of(
                pattern("boundary_condition", 1), pattern("boolean_bug", 2),
                pattern("null_handling", 3), pattern("resource_leak", 4),
                pattern("state_corruption", 5));
        StrategySelector selector = new StrategySelector(failures, null);
        List<Strategy> s = selector.selectStrategies(null, Set.of("something_0"), null, null);
        assertEquals(1.2 / 5.6, weightOf(s, "boundary_testing"), 1e-12);
        assertEquals(1.2 / 5.6, weightOf(s, "expression"), 1e-12);
        assertEquals(0.9 / 5.6, weightOf(s, "exception_handling"), 1e-12);
        assertEquals(0.7 / 5.6, weightOf(s, "resource_management"), 1e-12);
        assertEquals(1.0 / 5.6, weightOf(s, "state_transition"), 1e-12);
    }

    @Test
    void uncoveredConditionCountingUsesFixedMethodLineIds() {
        // Conditions: calc_10 (if, COVERED — matched via the fixed "{method}_{line}" id),
        // calc_20 (if, uncovered), parse_30 (for_loop, uncovered), parse_40 (if, uncovered)
        // → uncovered_if = 2 > uncovered_loops = 1 → expression += 0.3
        // weights: bt 1.0, ex 1.3, eh 0.7, dv 0.6, rm 0.5, st 0.8, bl 0 → total 4.9
        FailureModel model = new FailureModel("Foo", List.of(
                new BoundaryCondition("calc", 10, "if_condition", "a>0"),
                new BoundaryCondition("calc", 20, "if_condition", "b>0"),
                new BoundaryCondition("parse", 30, "for_loop", "i<n"),
                new BoundaryCondition("parse", 40, "if_condition", "c>0")),
                List.of(), List.of(), Map.of());
        StrategySelector selector = new StrategySelector(null, model);
        List<Strategy> s = selector.selectStrategies(null, null, Set.of("calc_10"), null);
        assertEquals(1.3 / 4.9, weightOf(s, "expression"), 1e-12);
        assertEquals(1.0 / 4.9, weightOf(s, "boundary_testing"), 1e-12);
    }

    @Test
    void conditionElseBranchBoostsBoundaryEvenAtZeroCounts() {
        // All conditions covered → uncovered_if == uncovered_loops == 0 → Python's else
        // still fires: boundary_testing += 0.3 → total 4.9.
        FailureModel model = new FailureModel("Foo", List.of(
                new BoundaryCondition("calc", 10, "if_condition", "a>0")),
                List.of(), List.of(), Map.of());
        StrategySelector selector = new StrategySelector(null, model);
        List<Strategy> s = selector.selectStrategies(null, null, Set.of("calc_10"), null);
        assertEquals(1.3 / 4.9, weightOf(s, "boundary_testing"), 1e-12);
    }

    @Test
    void businessLogicIssueBoosts() {
        // issue1: type "boundary_check", conf 0.5 → bl += 0.4, bt += 0.25
        // issue2: type "state_transition_gap", conf 1.0 → bl += 0.8, st += 0.5
        // weights: bt 1.25, ex 1.0, eh 0.7, dv 0.6, rm 0.5, st 1.3, bl 1.2 → total 6.55
        StrategySelector selector = new StrategySelector(null, null);
        List<Strategy> s = selector.selectStrategies(null, null, null, List.of(
                new BusinessLogicIssue("boundary_check", "m1", "d1", 0.5),
                new BusinessLogicIssue("state_transition_gap", "m2", "d2", 1.0)));
        assertEquals(1.25 / 6.55, weightOf(s, "boundary_testing"), 1e-12);
        assertEquals(1.3 / 6.55, weightOf(s, "state_transition"), 1e-12);
        assertEquals(1.2 / 6.55, weightOf(s, "business_logic"), 1e-12);
        // Sorted head: state_transition (0.1984...) > business_logic (0.1832...)
        assertEquals("state_transition", s.get(0).id());
    }

    @Test
    void missingIssueConfidenceDefaultsToZero() {
        // Python issue.get('confidence', 0) — null confidence adds nothing.
        StrategySelector selector = new StrategySelector(null, null);
        List<Strategy> s = selector.selectStrategies(null, null, null, List.of(
                new BusinessLogicIssue("boundary_check", "m", "d", null)));
        assertEquals(0.0, weightOf(s, "business_logic"), 0.0);
        assertEquals(1.0 / 4.6, weightOf(s, "boundary_testing"), 1e-12);
    }

    @Test
    void stagnationBoostsWithBugTypeRouting() {
        // |50.05 - 50.0| = 0.05 < 0.1 → bl += 0.4, st += 0.3; detected bug types:
        // "boundary_check" → bt += 0.3, "logic_error" → ex += 0.3 (bug.get("type"), NOT bug_type)
        // weights: bt 1.3, ex 1.3, eh 0.7, dv 0.6, rm 0.5, st 1.1, bl 0.4 → total 5.9
        FaTestState state = new FaTestState("", null, null);
        state.coverage = 50.05;
        state.parentCoverage = 50.0;
        state.detectedBugs.add(new DetectedBug("boundary_check", "", "t1", "", "m"));
        state.detectedBugs.add(new DetectedBug("logic_error", "", "t2", "", "m"));

        StrategySelector selector = new StrategySelector(null, null);
        List<Strategy> s = selector.selectStrategies(state, null, null, null);
        assertEquals(1.3 / 5.9, weightOf(s, "boundary_testing"), 1e-12);
        assertEquals(1.3 / 5.9, weightOf(s, "expression"), 1e-12);
        assertEquals(1.1 / 5.9, weightOf(s, "state_transition"), 1e-12);
        assertEquals(0.4 / 5.9, weightOf(s, "business_logic"), 1e-12);
        // tie at 1.3 → insertion order: boundary_testing before expression (O4)
        assertEquals(List.of("boundary_testing", "expression"),
                List.of(s.get(0).id(), s.get(1).id()));
    }

    @Test
    void noStagnationWhenDeltaAtLeastPointOne() {
        FaTestState state = new FaTestState("", null, null);
        state.coverage = 50.1;
        state.parentCoverage = 50.0; // |delta| == 0.1 → NOT < 0.1 → no boost
        StrategySelector selector = new StrategySelector(null, null);
        List<Strategy> s = selector.selectStrategies(state, null, null, null);
        assertEquals(0.0, weightOf(s, "business_logic"), 0.0);
    }

    @Test
    void duplicateBugTypesBoostOnce() {
        // Python collects bug types into a SET first — two "boundary_check" bugs boost once.
        FaTestState state = new FaTestState("", null, null);
        state.coverage = 10.0;
        state.parentCoverage = 10.0;
        state.detectedBugs.add(new DetectedBug("boundary_check", "", "t1", "", "m"));
        state.detectedBugs.add(new DetectedBug("boundary_check", "", "t2", "", "m"));
        StrategySelector selector = new StrategySelector(null, null);
        List<Strategy> s = selector.selectStrategies(state, null, null, null);
        // bt 1.3, ex 1.0, eh 0.7, dv 0.6, rm 0.5, st 1.1, bl 0.4 → total 5.6
        assertEquals(1.3 / 5.6, weightOf(s, "boundary_testing"), 1e-12);
    }

    @Test
    void d2RoutingTableContents() {
        assertEquals(List.of("expression"), StrategySelector.PATTERN_MAPPINGS.get("operator_precedence"));
        assertEquals(List.of("null_empty_testing", "exception_handling"),
                StrategySelector.PATTERN_MAPPINGS.get("null_handling"));
        assertEquals(List.of("string_operation_testing", "boundary_testing"),
                StrategySelector.PATTERN_MAPPINGS.get("string_index_bounds"));
        assertEquals(List.of("resource_lifecycle_testing", "exception_resource_testing"),
                StrategySelector.PATTERN_MAPPINGS.get("resource_leak"));
        assertEquals(List.of("type_conversion_testing", "arithmetic_edge_testing"),
                StrategySelector.PATTERN_MAPPINGS.get("data_operation"));
        assertEquals(List.of("concurrency_testing"), StrategySelector.PATTERN_MAPPINGS.get("concurrency"));
        assertEquals(List.of("exception_handling", "error_propagation_testing"),
                StrategySelector.PATTERN_MAPPINGS.get("exception_handling"));
        assertEquals(List.of("data_validation", "null_empty_testing"),
                StrategySelector.PATTERN_MAPPINGS.get("validation"));
        assertEquals(List.of("security_testing"), StrategySelector.PATTERN_MAPPINGS.get("security"));
        assertEquals(31, StrategySelector.PATTERN_MAPPINGS.size());
    }

    @Test
    void mapPatternsToStrategiesAccumulatesDuplicates() {
        // defaultdict(list) append semantics: two null_handling patterns → doubled list.
        StrategySelector selector = new StrategySelector(List.of(
                pattern("null_handling", 1), pattern("null_handling", 2),
                pattern("boundary_condition", 3), pattern("unmapped_type", 4)), null);
        Map<String, List<String>> map = selector.mapPatternsToStrategies();
        assertEquals(List.of("null_empty_testing", "exception_handling",
                "null_empty_testing", "exception_handling"), map.get("null_handling"));
        assertEquals(List.of("boundary_testing"), map.get("boundary_condition"));
        assertTrue(!map.containsKey("unmapped_type"));
    }

    @Test
    void subtypeMatchedBeforeType() {
        // A pattern with a mapped subtype registers under BOTH keys (Python applies
        // subtype first, then type — both `if`s, no elif).
        StrategySelector selector = new StrategySelector(List.of(
                new FailureScenario("exception_handling", "empty_catch", 7, RiskLevel.MEDIUM, "", "")), null);
        Map<String, List<String>> map = selector.mapPatternsToStrategies();
        assertEquals(List.of("error_propagation_testing"), map.get("empty_catch"));
        assertEquals(List.of("exception_handling", "error_propagation_testing"),
                map.get("exception_handling"));
    }
}
