package org.failmapper.search;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.failmapper.core.model.BoundaryCondition;
import org.failmapper.core.model.FailureModel;
import org.failmapper.core.model.FailureScenario;
import org.failmapper.core.model.LogicalOperation;
import org.failmapper.core.model.RiskLevel;
import org.junit.jupiter.api.Test;

/**
 * D1 — generate_possible_actions (fa_mcts.py:105-386). The strict FixedRandomSource
 * proves exactly which steps consume randomness (its sample() takes the first k in
 * order without consuming script entries; nextDouble()/ints are scripted).
 */
class ActionGeneratorTest {

    private final SearchConfig config = SearchConfig.defaults();

    private static FaTestState stateWithErrors(String... errors) {
        FaTestState s = new FaTestState("code", null, null);
        s.compilationErrors = new ArrayList<>(List.of(errors));
        return s;
    }

    @Test
    void compilationErrorsYieldSingleFixActionAndEarlyReturn() {
        FaTestState state = stateWithErrors("err1", "err2");
        FaMctsNode node = new FaMctsNode(state);
        CompilationFixTracker tracker = new CompilationFixTracker();
        FixedRandomSource random = new FixedRandomSource(); // no draws expected
        ActionGenerator gen = new ActionGenerator(config, random);

        List<SearchAction> actions = gen.generate(node, null, null, null, null, tracker);

        assertEquals(1, actions.size());
        SearchAction fix = actions.get(0);
        assertEquals("fix_compilation_errors", fix.type());
        assertEquals(List.of("err1", "err2"), fix.attributes().get("errors"));
        assertEquals(1, fix.attributes().get("attempt")); // global_attempts(0) + 1
        assertEquals("", fix.attributes().get("path_signature")); // root path
        assertEquals(1, tracker.globalAttempts()); // counter incremented
        assertTrue(random.exhausted());
    }

    @Test
    void failedFixPathFallsThroughToNormalActions() {
        FaTestState state = stateWithErrors("err1");
        FaMctsNode node = new FaMctsNode(state);
        CompilationFixTracker tracker = new CompilationFixTracker();
        tracker.markPathFailed(""); // this (root) path already failed
        // Normal generation: no selector → C46 defaults; fModel null → only
        // exception_handling emits; then one exploration draw (0.5 → no action).
        FixedRandomSource random = new FixedRandomSource().doubles(0.5);
        ActionGenerator gen = new ActionGenerator(config, random);

        List<SearchAction> actions = gen.generate(node, null, null, null, null, tracker);

        assertEquals(List.of("exception_test"), actions.stream().map(SearchAction::type).toList());
        assertEquals(0, tracker.globalAttempts()); // NOT incremented on the blocked path
        assertTrue(random.exhausted());
    }

    @Test
    void maxFixAttemptsExhaustedFallsThrough() {
        FaTestState state = stateWithErrors("err1");
        FaMctsNode node = new FaMctsNode(state);
        CompilationFixTracker tracker = new CompilationFixTracker();
        for (int i = 0; i < config.maxFixAttempts; i++) { // C7 = 10
            tracker.incrementGlobalAttempts();
        }
        FixedRandomSource random = new FixedRandomSource().doubles(0.5);
        ActionGenerator gen = new ActionGenerator(config, random);

        List<SearchAction> actions = gen.generate(node, null, null, null, null, tracker);

        assertFalse(actions.stream().anyMatch(a -> "fix_compilation_errors".equals(a.type())));
        assertEquals(10, tracker.globalAttempts());
    }

    @Test
    void nullTrackerEmitsUngatedFixActionWithoutPathSignature() {
        // Python fallback when the MCTS instance is unreachable (fa_mcts.py:162-172).
        FaTestState state = stateWithErrors("err1");
        FaMctsNode node = new FaMctsNode(state);
        ActionGenerator gen = new ActionGenerator(config, new FixedRandomSource());

        List<SearchAction> actions = gen.generate(node, null, null, null, null, null);

        assertEquals(1, actions.size());
        assertEquals("fix_compilation_errors", actions.get(0).type());
        assertEquals(1, actions.get(0).attributes().get("attempt"));
        assertNull(actions.get(0).attributes().get("path_signature")); // key absent
    }

    @Test
    void fullGenerationOrderIsPythonSourceOrder() {
        // state: 1 business-logic issue; uncovered lines 5, 6("}"), 7; fModel with 3
        // boundary conditions + 1 operation; 1 high-risk failure; exploration draw 0.19.
        FaTestState state = new FaTestState("code", null, null);
        state.businessLogicIssues = List.of(
                new BusinessLogicIssue("calculation_error", "sum", "Sum drops last item", 0.7));
        FaMctsNode node = new FaMctsNode(state);

        List<UncoveredLine> uncovered = List.of(
                new UncoveredLine(5, "int x = 1;"),
                new UncoveredLine(6, "}"),           // skipped: brace-only content
                new UncoveredLine(7, "y++;"));
        FailureModel model = new FailureModel("Foo",
                List.of(new BoundaryCondition("calc", 10, "if_condition", "a > 0"),
                        new BoundaryCondition("calc", 20, "if_condition", "b > 0"),
                        new BoundaryCondition("calc", 30, "if_condition", "c > 0")),
                List.of(new LogicalOperation("calc", 40, "a && b", List.of("&&"))),
                List.of(), Map.of());
        List<FailureScenario> failures = List.of(
                new FailureScenario("off_by_one", null, 50, RiskLevel.HIGH, "", "loop bound"),
                new FailureScenario("copy_paste", null, 60, RiskLevel.MEDIUM, "", ""));

        // sample() takes the first k: lines→[5,6,7]; conditions→[10,20]; operations→[40];
        // high-risk→[off_by_one]; then ONE double for the 20% exploration check.
        FixedRandomSource random = new FixedRandomSource().doubles(0.19);
        ActionGenerator gen = new ActionGenerator(config, random);

        List<SearchAction> actions = gen.generate(node, uncovered, model, failures, null, null);

        assertEquals(List.of(
                "business_logic_test",   // step 2 — one per issue
                "target_line",           // line 5 (line 6 skipped as "}")
                "target_line",           // line 7
                "boundary_test",         // condition line 10 (C21: min(2,3) sampled)
                "boundary_test",         // condition line 20
                "expression_test",       // operation line 40 (min(2,1)=1)
                "exception_test",        // C46 default strategy exception_handling (0.7)
                "bug_pattern_test",      // high-risk off_by_one (C22: min(2,1)=1)
                "general_exploration"),  // 0.19 < 0.2 (C19)
                actions.stream().map(SearchAction::type).toList());

        SearchAction businessLogic = actions.get(0);
        assertEquals("calculation_error", businessLogic.attributes().get("issue_type"));
        assertEquals("sum", businessLogic.attributes().get("method"));
        assertEquals(0.7, (Double) businessLogic.attributes().get("confidence"), 0.0);
        assertEquals(Boolean.TRUE, businessLogic.attributes().get("business_logic"));
        assertEquals("Test for potential business logic issue: Sum drops last item",
                businessLogic.attributes().get("description"));

        assertEquals(5, actions.get(1).attributes().get("line"));
        assertEquals("int x = 1;", actions.get(1).attributes().get("content"));
        assertEquals("Target uncovered line 5: int x = 1;...",
                actions.get(1).attributes().get("description"));

        assertEquals("a > 0", actions.get(3).attributes().get("condition"));
        assertEquals("boundary_testing", actions.get(3).attributes().get("strategy"));
        assertEquals("a && b", actions.get(5).attributes().get("operation"));
        assertEquals("off_by_one", actions.get(7).attributes().get("pattern_type"));
        assertEquals("Test for off_by_one bug pattern at line 50: loop bound...",
                actions.get(7).attributes().get("description"));

        assertTrue(random.exhausted());
    }

    @Test
    void strategyWeightCutoffIsStrictLessThan() {
        // C18 (fa_mcts.py:250): `if weight < 0.1: continue` — 0.1 SURVIVES, 0.09 does not.
        // Selector with many business-logic-only issues drives every other strategy
        // below 0.1 except boundary_testing... instead pin the semantics directly with
        // the default strategies path replaced by a selector: simplest is exact-weight
        // strategies via a stub — StrategySelector is concrete, so test via ActionGenerator
        // with a selector whose normalized exception_handling weight computes to >= 0.1.
        // Base weights: eh = 0.7/4.6 = 0.15217 → kept; business_logic = 0.0 < 0.1 → skipped
        // (and has no action branch anyway).
        FaTestState state = new FaTestState("code", null, null);
        FaMctsNode node = new FaMctsNode(state);
        StrategySelector selector = new StrategySelector(null, null);
        FixedRandomSource random = new FixedRandomSource().doubles(0.9);
        ActionGenerator gen = new ActionGenerator(config, random);

        List<SearchAction> actions = gen.generate(node, null, null, null, selector, null);

        // dv 0.6/4.6=0.130, rm 0.5/4.6=0.109, st 0.8/4.6=0.174 — all >= 0.1 → all emit;
        // bt/ex emit nothing without fModel. Selector order: bt, ex, st, eh, dv, rm, bl.
        assertEquals(List.of("state_transition_test", "exception_test",
                        "data_validation_test", "resource_management_test"),
                actions.stream().map(SearchAction::type).toList());
        assertTrue(random.exhausted());
    }

    @Test
    void generalExplorationAlwaysAddedWhenNoActionsWithoutConsumingRandomness() {
        // Python: `if not possible_actions or random.random() < 0.2` — the OR
        // short-circuits, so NO draw happens when the list is empty. Build emptiness:
        // 50 uncovered medium-risk boundary patterns push every fixed-action strategy
        // below the 0.1 cutoff; boundary_testing survives but the fModel has no
        // boundary conditions → zero actions before step 6.
        List<FailureScenario> failures = new ArrayList<>();
        for (int i = 0; i < 50; i++) {
            failures.add(new FailureScenario("boundary_condition", null, i, RiskLevel.MEDIUM, "", ""));
        }
        // bt = 1 + 0.2*50 = 11; total = 11+1+0.7+0.6+0.5+0.8+0 = 14.6
        // ex = 1/14.6 = 0.068 < 0.1; eh 0.048; dv 0.041; rm 0.034; st 0.055 → all skipped.
        StrategySelector selector = new StrategySelector(failures, new FailureModel(
                "Foo", List.of(), List.of(), List.of(), Map.of()));
        FaTestState state = new FaTestState("code", null, null);
        FaMctsNode node = new FaMctsNode(state);
        node.coveredPatterns.add("boundary_condition_999"); // non-empty gate opener

        FixedRandomSource random = new FixedRandomSource(); // strict: zero draws allowed
        ActionGenerator gen = new ActionGenerator(config, random);
        List<SearchAction> actions = gen.generate(node,
                null, new FailureModel("Foo", List.of(), List.of(), List.of(), Map.of()),
                failures, selector, null);

        assertEquals(List.of("general_exploration"),
                actions.stream().map(SearchAction::type).toList());
        assertTrue(random.exhausted());
    }

    @Test
    void usedActionsFilteredByValueEqualityAndCanEmptyTheResult() {
        // Contract S2: dedup by CONTENTS. The filter runs AFTER the exploration step,
        // so a fully-used action set returns [] (Python behavior).
        FaTestState state = new FaTestState("code", null, null);
        FaMctsNode node = new FaMctsNode(state);
        FixedRandomSource random = new FixedRandomSource().doubles(0.9); // no exploration
        ActionGenerator gen = new ActionGenerator(config, random);

        List<SearchAction> first = gen.generate(node, null, null, null, null, null);
        assertEquals(List.of("exception_test"), first.stream().map(SearchAction::type).toList());

        // Mark a VALUE-equal copy as used (fresh maps, same contents).
        node.usedActions.add(new SearchAction("exception_test", Map.of(
                "strategy", "exception_handling",
                "description", "Generate tests for exception paths")));

        FixedRandomSource random2 = new FixedRandomSource().doubles(0.9);
        ActionGenerator gen2 = new ActionGenerator(config, random2);
        List<SearchAction> second = gen2.generate(node, null, null, null, null, null);
        assertTrue(second.isEmpty());
    }

    @Test
    void pathSignatureJoinsActionTypesFromRoot() {
        FaMctsNode root = new FaMctsNode(null);
        FaMctsNode child = root.addChild(null, SearchAction.of("boundary_test"));
        FaMctsNode grandchild = child.addChild(null, SearchAction.of("expression_test"));
        assertEquals("", root.pathSignature());
        assertEquals("boundary_test", child.pathSignature());
        assertEquals("boundary_test->expression_test", grandchild.pathSignature());
    }

    @Test
    void emptyConditionStringsAreSkippedInsideSample() {
        // Python: `if not condition_str: continue` AFTER sampling — an empty expression
        // consumes a sample slot but emits nothing.
        FailureModel model = new FailureModel("Foo",
                List.of(new BoundaryCondition("calc", 10, "if_condition", ""),
                        new BoundaryCondition("calc", 20, "if_condition", "b > 0")),
                List.of(), List.of(), Map.of());
        FaTestState state = new FaTestState("code", null, null);
        FaMctsNode node = new FaMctsNode(state);
        FixedRandomSource random = new FixedRandomSource().doubles(0.9);
        ActionGenerator gen = new ActionGenerator(config, random);

        List<SearchAction> actions = gen.generate(node, null, model, null, null, null);

        // sample takes [line10(empty→skip), line20] → ONE boundary_test (line 20)
        List<SearchAction> boundary = actions.stream()
                .filter(a -> a.type().equals("boundary_test")).toList();
        assertEquals(1, boundary.size());
        assertEquals(20, boundary.get(0).attributes().get("line"));
    }
}
