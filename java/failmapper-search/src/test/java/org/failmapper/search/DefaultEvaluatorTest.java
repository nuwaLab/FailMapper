package org.failmapper.search;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Set;
import org.failmapper.core.model.BoundaryCondition;
import org.failmapper.core.model.CoverageSnapshot;
import org.failmapper.core.model.Diagnostic;
import org.failmapper.core.model.FailureModel;
import org.failmapper.core.model.TestFailure;
import org.failmapper.core.model.TestRunResult;
import org.junit.jupiter.api.Test;

/**
 * DefaultEvaluator — the typed adapter for FATestState.evaluate's post-run flow
 * (test_state.py:101-225).
 */
class DefaultEvaluatorTest {

    private static CoverageSnapshot cov(double lineCoverage, Set<Integer> uncovered) {
        return new CoverageSnapshot("com.example.Foo", lineCoverage, 50.0, 10, 2, 1, 1, uncovered);
    }

    private static TestRunResult passedRun(List<TestFailure> failures) {
        return new TestRunResult(true, List.of(), 5, 5 - failures.size(), failures, 120);
    }

    @Test
    void compilationErrorsShortCircuitWithZeroCoverage() {
        TestRunResult run = new TestRunResult(false, List.of(
                new Diagnostic(Diagnostic.Kind.ERROR, "FooTest.java", 12, 3, "cannot find symbol"),
                new Diagnostic(Diagnostic.Kind.WARNING, "FooTest.java", 1, 1, "deprecated")),
                0, 0, List.of(), 30);
        FaTestState state = new FaTestState("code", null, null);
        state.coverage = 44.0; // previous coverage is DISCARDED on compile errors

        new DefaultEvaluator(run, cov(90.0, Set.of())).evaluate(state);

        assertEquals(List.of("cannot find symbol"), state.compilationErrors); // ERROR kind only
        assertEquals(0.0, state.coverage, 0.0); // test_state.py:141
        assertTrue(state.executed);
        assertTrue(state.detectedBugs.isEmpty()); // early return — no bug processing
    }

    @Test
    void coverageKeepPreviousRule() {
        // New coverage 0 but previous 50 → keep 50 (test_state.py:157-160).
        FaTestState state = new FaTestState("code", null, null);
        state.coverage = 50.0;
        new DefaultEvaluator(passedRun(List.of()), cov(0.0, Set.of())).evaluate(state);
        assertEquals(50.0, state.coverage, 0.0);

        // New coverage positive → overwrite.
        new DefaultEvaluator(passedRun(List.of()), cov(73.5, Set.of())).evaluate(state);
        assertEquals(73.5, state.coverage, 0.0);

        // Both zero → 0.0.
        FaTestState fresh = new FaTestState("code", null, null);
        new DefaultEvaluator(passedRun(List.of()), cov(0.0, Set.of())).evaluate(fresh);
        assertEquals(0.0, fresh.coverage, 0.0);
    }

    @Test
    void assertionFailureBecomesPreVerifiedLogicalBugAndClassifierDuplicatesIt() {
        TestFailure failure = new TestFailure("FooTest", "testAddBoundary", true,
                "org.opentest4j.AssertionFailedError", "expected: <5> but was: <4>", "");
        FaTestState state = new FaTestState("code", null, null);

        new DefaultEvaluator(passedRun(List.of(failure)), cov(80.0, Set.of())).evaluate(state);

        assertEquals(1, state.detectedBugs.size());
        DetectedBug bug = state.detectedBugs.get(0);
        assertEquals("assertion_failure", bug.type);
        assertEquals("testAddBoundary", bug.testMethod);
        assertEquals("AssertionError", bug.error);
        assertTrue(bug.verified);
        assertEquals(Boolean.TRUE, bug.isRealBug);
        assertEquals("logical", bug.bugCategory);
        // D11 re-typed it from the pre-marked "incorrect_behavior": the message
        // "AssertionError expected: <5> but was: <4>" matches incorrect_value (0.7).
        assertEquals("incorrect_value", bug.bugType);
        assertEquals(0.7, bug.logicConfidence, 0.0);

        // Iron-rule duplicate: pre-added by the evaluate branch AND appended again by
        // classify_logical_bugs → count 2 (feeds F6's 0.4*count exactly like Python).
        assertEquals(2, state.countLogicalBugs());
        assertTrue(state.hasBugs);
        assertEquals(1, state.assertionFailures.size());
        assertEquals("testAddBoundary", state.assertionFailures.get(0).method());
    }

    @Test
    void runtimeFailureClassifiedViaThrowableFqn() {
        TestFailure failure = new TestFailure("FooTest", "testLookup", false,
                "java.lang.NullPointerException", "Cannot invoke method on null", "");
        FaTestState state = new FaTestState("code", null, null);

        new DefaultEvaluator(passedRun(List.of(failure)), cov(70.0, Set.of())).evaluate(state);

        DetectedBug bug = state.detectedBugs.get(0);
        assertEquals("runtime_error", bug.type);
        assertFalse(bug.verified);
        assertNull(bug.isRealBug); // tri-state: unverified (S5)
        // D11: "java.lang.NullPointerException Cannot invoke..." → null_reference 0.6
        assertEquals("logical", bug.bugCategory);
        assertEquals("null_reference", bug.bugType);
        assertEquals(0.6, bug.logicConfidence, 0.0);
        assertEquals(1, state.countLogicalBugs());
    }

    @Test
    void duplicateTestMethodsNotReAdded() {
        // Dedup by test_method (test_state.py:188).
        TestFailure f1 = new TestFailure("FooTest", "testX", true, "A", "expected: <1> but was: <2>", "");
        TestFailure f2 = new TestFailure("FooTest", "testX", false, "B", "boom", "");
        FaTestState state = new FaTestState("code", null, null);

        new DefaultEvaluator(passedRun(List.of(f1, f2)), cov(70.0, Set.of())).evaluate(state);

        assertEquals(1, state.detectedBugs.size());
        assertEquals("assertion_failure", state.detectedBugs.get(0).type);
    }

    @Test
    void fullTrackingSequenceRuns() {
        // I3 branch tracking: calc_10 covered (line executed), calc_99 not (uncovered);
        // F9 pattern tracking + analyze flags also run.
        FailureModel model = new FailureModel("com.example.Foo",
                List.of(new BoundaryCondition("calc", 10, "if_condition", "a>0"),
                        new BoundaryCondition("calc", 99, "if_condition", "b>0")),
                List.of(), List.of(), java.util.Map.of());
        var failures = List.of(new org.failmapper.core.model.FailureScenario(
                "boundary_condition", null, 12, org.failmapper.core.model.RiskLevel.MEDIUM, "", ""));
        FaTestState state = new FaTestState("// covers line 12 boundary", model, failures);
        state.testMethods.add(new TestMethod("testBoundaryCheck", "assertTrue(x >= 1);"));

        new DefaultEvaluator(passedRun(List.of()), cov(60.0, Set.of(99))).evaluate(state);

        assertTrue(state.hasBoundaryTests); // ">=" in the method code
        // F9: 0.7 line match + 0.1 keyword ("boundary") = 0.8 >= 0.6 → covered
        assertTrue(state.coveredFailures.contains("boundary_condition_12"));
        // I3: executed line 10 covered; uncovered line 99 not
        assertEquals(Set.of("calc_10"), state.coveredBranchConditions);
        assertEquals(60.0, state.coverage, 0.0);
    }
}
