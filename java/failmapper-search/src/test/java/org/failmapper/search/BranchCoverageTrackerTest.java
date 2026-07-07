package org.failmapper.search;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Set;
import org.failmapper.core.model.BoundaryCondition;
import org.failmapper.core.model.CoverageSnapshot;
import org.failmapper.core.model.FailureModel;
import org.junit.jupiter.api.Test;

/** I3 registered replacement of F10 — JaCoCo-fact branch-condition coverage. */
class BranchCoverageTrackerTest {

    private final BranchCoverageTracker tracker = new BranchCoverageTracker();

    private static CoverageSnapshot snapshot(Set<Integer> uncoveredLines) {
        return new CoverageSnapshot("com.example.Foo", 80.0, 60.0, 8, 2, 3, 2, uncoveredLines);
    }

    private static BoundaryCondition cond(String method, int line) {
        return new BoundaryCondition(method, line, "if_condition", "x > 0");
    }

    @Test
    void coveredWhenLineExecutedAndNoBranchData() {
        assertTrue(tracker.conditionCovered(cond("calc", 20), snapshot(Set.of(10))));
    }

    @Test
    void notCoveredWhenLineUncovered() {
        assertFalse(tracker.conditionCovered(cond("calc", 10), snapshot(Set.of(10))));
    }

    @Test
    void branchDataRefinesExecutedLine() {
        BranchCoverageTracker.LineBranchData noBranchCovered = new BranchCoverageTracker.LineBranchData() {
            @Override
            public boolean hasBranchData(int line) {
                return line == 20;
            }

            @Override
            public boolean anyBranchCovered(int line) {
                return false;
            }
        };
        // Line executed but ZERO of its branches taken → not covered under I3.
        assertFalse(tracker.conditionCovered(cond("calc", 20), snapshot(Set.of()), noBranchCovered));
        // No branch data at line 30 → falls back to the line-execution fact.
        assertTrue(tracker.conditionCovered(cond("calc", 30), snapshot(Set.of()), noBranchCovered));
    }

    @Test
    void nullCoverageMeansNoFactsMeansNotCovered() {
        assertFalse(tracker.conditionCovered(cond("calc", 20), null));
    }

    @Test
    void trackAddsMethodLineIds() {
        // Ids keep the "{method}_{line}" vocabulary (test_state.py:565) so all
        // downstream consumers (F4/C17, F6 branch reward, D3 counting) are unchanged.
        FailureModel model = new FailureModel("com.example.Foo",
                List.of(cond("calc", 20), cond("calc", 10), cond("parse", 33)),
                List.of(), List.of(), java.util.Map.of());
        FaTestState state = new FaTestState("", model, null);

        tracker.track(state, snapshot(Set.of(10)));

        assertEquals(Set.of("calc_20", "parse_33"), state.coveredBranchConditions);
        assertFalse(state.coveredBranchConditions.contains("calc_10"));
    }

    @Test
    void trackSkipsAlreadyCoveredIdsMonotone() {
        // Carried-forward covered ids are skipped (test_state.py:570-572): even if the
        // new run left the line uncovered, the set stays monotone like the baseline.
        FailureModel model = new FailureModel("com.example.Foo",
                List.of(cond("calc", 10)), List.of(), List.of(), java.util.Map.of());
        FaTestState state = new FaTestState("", model, null);
        state.coveredBranchConditions.add("calc_10");

        tracker.track(state, snapshot(Set.of(10)));

        assertTrue(state.coveredBranchConditions.contains("calc_10"));
    }

    @Test
    void noModelIsNoOp() {
        FaTestState state = new FaTestState("", null, null);
        tracker.track(state, snapshot(Set.of()));
        assertTrue(state.coveredBranchConditions.isEmpty());
    }

    @Test
    void f10FallbackIsGone() {
        // The replaced F10 "assume min(2, n) covered when tests look good" fallback
        // must NOT fire: good-looking tests with zero executed condition lines cover 0.
        FailureModel model = new FailureModel("com.example.Foo",
                List.of(cond("calc", 10), cond("parse", 11)), List.of(), List.of(), java.util.Map.of());
        FaTestState state = new FaTestState("", model, null);
        state.hasAssertions = true;
        state.testMethods.add(new TestMethod("t1", "assertTrue(x);"));
        state.testMethods.add(new TestMethod("t2", "assertTrue(y);"));
        state.testMethods.add(new TestMethod("t3", "assertTrue(z);"));

        tracker.track(state, snapshot(Set.of(10, 11)));

        assertTrue(state.coveredBranchConditions.isEmpty());
    }
}
