package org.failmapper.search;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * FaTestState — {@code analyze_test_logic_properties} (test_state.py:227-310, incl. F12)
 * and the D6 carry-forward block (fa_mcts.py:2715-2755).
 */
class FaTestStateTest {

    private static FaTestState state(TestMethod... methods) {
        FaTestState s = new FaTestState("", null, null);
        s.testMethods.addAll(List.of(methods));
        return s;
    }

    @Test
    void booleanBugFlagFromAndOrPair() {
        // ("&&" and "||") triggers; assertTrue/assertFalse pair also triggers.
        FaTestState s = state(new TestMethod("t1", "if (a && b || c) {}"));
        s.analyzeTestLogicProperties();
        assertTrue(s.hasBooleanBugTests);

        FaTestState s2 = state(new TestMethod("t1", "assertTrue(x); assertFalse(y);"));
        s2.analyzeTestLogicProperties();
        assertTrue(s2.hasBooleanBugTests);

        FaTestState s3 = state(new TestMethod("t1", "a && b")); // && without || and no assert pair
        s3.analyzeTestLogicProperties();
        assertFalse(s3.hasBooleanBugTests);
    }

    @Test
    void boundaryFlagFromComparisonOperators() {
        for (String op : List.of(">=", "<=", "==", "!=")) {
            FaTestState s = state(new TestMethod("t", "check(a " + op + " b);"));
            s.analyzeTestLogicProperties();
            assertTrue(s.hasBoundaryTests, op);
        }
        FaTestState s = state(new TestMethod("t", "check(a > b);")); // bare > does not count
        s.analyzeTestLogicProperties();
        assertFalse(s.hasBoundaryTests);
    }

    @Test
    void stateTransitionFlagNeedsMoreThanFiveDots() {
        // count(".") == 6 > 5 → true (test_state.py:245)
        FaTestState s = state(new TestMethod("t", "a.b().c().d()")); // 6 dots? a.b().c().d() has 3 dots
        s.analyzeTestLogicProperties();
        assertFalse(s.hasStateTransitionTests); // 3 dots — not enough

        FaTestState s2 = state(new TestMethod("t", "a.b.c.d.e.f.g")); // 6 dots > 5
        s2.analyzeTestLogicProperties();
        assertTrue(s2.hasStateTransitionTests);
    }

    @Test
    void exceptionPathFlagPythonPrecedence() {
        // "assertThrows" OR ("try" AND "catch") — test_state.py:257-261.
        FaTestState s = state(new TestMethod("t", "assertThrows(X.class, () -> f());"));
        s.analyzeTestLogicProperties();
        assertTrue(s.hasExceptionPathTests);

        FaTestState s2 = state(new TestMethod("t", "try { f(); } catch (Exception e) {}"));
        s2.analyzeTestLogicProperties();
        assertTrue(s2.hasExceptionPathTests);

        FaTestState s3 = state(new TestMethod("t", "try { f(); } finally {}")); // try without catch
        s3.analyzeTestLogicProperties();
        assertFalse(s3.hasExceptionPathTests);
    }

    @Test
    void booleanExpressionAndBoundaryValueExtraction() {
        FaTestState s = state(new TestMethod("t",
                "assertTrue(a && b || c); assertEquals(x >= 10, true);"));
        s.analyzeTestLogicProperties();
        // regex test_state.py:268 captures the &&/|| expression inside the assertion
        assertEquals(List.of("a && b || c"), s.booleanExpressionsTested);
        // regex test_state.py:273 captures (operator, value) — value stripped
        assertEquals(1, s.boundaryValuesTested.size());
        assertEquals(">=", s.boundaryValuesTested.get(0).operator());
        assertEquals("10", s.boundaryValuesTested.get(0).value());
    }

    @Test
    void extractionListsAccumulateAcrossCalls() {
        // Python calls analyze in __init__ AND evaluate; lists extend each time.
        FaTestState s = state(new TestMethod("t", "assertTrue(a && b || c);"));
        s.analyzeTestLogicProperties();
        s.analyzeTestLogicProperties();
        assertEquals(2, s.booleanExpressionsTested.size());
    }

    @Test
    void f12DepthAndQualityAllFiveSignals() {
        // depth: boundary values(1) + boolean exprs(1) + exception(1) + complex &&/||/!(1)
        //        + mutation MAX_VALUE(1) = 5 → quality = min(1.0, 5/5.0) = 1.0
        FaTestState s = state(
                new TestMethod("t1", "assertTrue(a && b || c); assertEquals(x >= 10, y);"),
                new TestMethod("t2", "assertThrows(X.class, () -> f());"),
                new TestMethod("t3", "if (a && b || !c) {}"),
                new TestMethod("t4", "int m = Integer.MAX_VALUE;"));
        s.analyzeTestLogicProperties();
        assertEquals(5, s.logicCoverageDepth);
        assertEquals(1.0, s.logicTestQuality, 0.0);
    }

    @Test
    void f12PartialDepth() {
        // Only exception path present → depth 1 → quality = 1/5.0 = 0.2
        FaTestState s = state(new TestMethod("t", "assertThrows(X.class, () -> f());"));
        s.analyzeTestLogicProperties();
        assertEquals(1, s.logicCoverageDepth);
        assertEquals(0.2, s.logicTestQuality, 1e-12);
    }

    @Test
    void carryForwardCopiesCoverageErrorsAndSets() {
        FaTestState parent = new FaTestState("parent code", null, null);
        parent.coverage = 42.5;
        parent.compilationErrors = new java.util.ArrayList<>(List.of("err1"));
        parent.coveredFailures.add("boundary_condition_5");
        parent.coveredBranchConditions.add("calc_10");
        parent.coveredFailuresScores.put("boundary_condition_5", 0.9);
        parent.businessLogicIssues = List.of(new BusinessLogicIssue("x", "m", "d", 0.5));

        FaTestState child = new FaTestState("child code", null, null);
        SearchAction action = SearchAction.of("boundary_test");
        child.carryForwardFrom(parent, action);

        assertEquals(42.5, child.parentCoverage, 0.0);
        assertEquals(action, child.metadataAction);
        assertEquals(42.5, child.coverage, 0.0);              // pre-seeded (fa_mcts.py:2748-2749)
        assertEquals(List.of("err1"), child.previousCompilationErrors);
        assertTrue(child.coveredFailures.contains("boundary_condition_5"));
        assertTrue(child.coveredBranchConditions.contains("calc_10"));
        // confidence-score map is NOT carried (fa_mcts.py:2751-2755 copies only the sets)
        assertTrue(child.coveredFailuresScores.isEmpty());
        assertEquals(parent.businessLogicIssues, child.businessLogicIssues);
    }

    @Test
    void carryForwardZeroCoverageParentDoesNotPreSeed() {
        FaTestState parent = new FaTestState("p", null, null); // coverage 0.0
        FaTestState child = new FaTestState("c", null, null);
        child.carryForwardFrom(parent, null);
        assertEquals(0.0, child.coverage, 0.0);     // `if previous_coverage > 0` gate
        assertEquals(0.0, child.parentCoverage, 0.0); // but metadata still records 0.0
        assertTrue(child.previousCompilationErrors.isEmpty()); // empty errors not copied (truthiness)
    }

    @Test
    void riskMetricsHighRiskPatternCoverage() {
        // 2 high-risk patterns, 1 covered → 1/2*100 = 50.0
        var failures = List.of(
                new org.failmapper.core.model.FailureScenario("off_by_one", null, 5,
                        org.failmapper.core.model.RiskLevel.HIGH, "", ""),
                new org.failmapper.core.model.FailureScenario("null_handling", null, 9,
                        org.failmapper.core.model.RiskLevel.HIGH, "", ""),
                new org.failmapper.core.model.FailureScenario("copy_paste", null, 11,
                        org.failmapper.core.model.RiskLevel.LOW, "", ""));
        FaTestState s = new FaTestState("", null, failures);
        s.coveredFailures.add("off_by_one_5");
        s.calculateRiskMetrics();
        assertEquals(50.0, s.highRiskPatternCoverage, 0.0);
    }

    @Test
    void metadataDefaultsNull() {
        FaTestState s = new FaTestState("x", null, null);
        assertNull(s.parentCoverage); // "parent_coverage" key absent on root states
        assertNull(s.metadataAction);
        assertFalse(s.hasAssertions); // never set true in the Python baseline (inert)
    }
}
