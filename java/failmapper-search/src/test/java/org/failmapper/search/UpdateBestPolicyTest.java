package org.failmapper.search;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/** D14 — update_best_tests (fa_mcts.py:2150-2234). */
class UpdateBestPolicyTest {

    private static FaTestState state(String testCode, double coverage) {
        FaTestState s = new FaTestState(testCode, null, null);
        s.coverage = coverage;
        return s;
    }

    private UpdateBestPolicy freshPolicy() {
        return new UpdateBestPolicy(SearchConfig.defaults(), "initial test", 0.0);
    }

    @Test
    void initialValuesMatchPythonInit() {
        UpdateBestPolicy p = freshPolicy();
        assertNull(p.bestState);
        assertEquals("initial test", p.bestTest);
        assertEquals(0.0, p.bestReward, 0.0);
        assertEquals(0.0, p.currentCoverage, 0.0);
    }

    @Test
    void rule1HigherCoverageWinsAndKeepsMaxReward() {
        UpdateBestPolicy p = freshPolicy();
        p.updateBest(state("t1", 50.0), 0.3, 1);
        assertEquals(50.0, p.currentCoverage, 0.0);
        assertEquals("t1", p.bestTest);
        assertEquals(0.3, p.bestReward, 0.0);

        // Higher coverage with LOWER reward still wins the test slot, but
        // best_reward = max(reward, best_reward) keeps 0.3 (fa_mcts.py:2176).
        p.updateBest(state("t2", 60.0), 0.1, 2);
        assertEquals(60.0, p.currentCoverage, 0.0);
        assertEquals("t2", p.bestTest);
        assertEquals(0.3, p.bestReward, 0.0);
    }

    @Test
    void rule2EqualCoverageHigherReward() {
        UpdateBestPolicy p = freshPolicy();
        p.updateBest(state("t1", 60.0), 0.3, 1);
        p.updateBest(state("t2", 60.0), 0.5, 2); // equal coverage, 0.5 > 0.3
        assertEquals("t2", p.bestTest);
        assertEquals(0.5, p.bestReward, 0.0);

        p.updateBest(state("t3", 60.0), 0.4, 3); // equal coverage, 0.4 < 0.5 → no change
        assertEquals("t2", p.bestTest);
    }

    @Test
    void rule3RewardOnlyWithCoverageGuard() {
        UpdateBestPolicy p = freshPolicy();
        p.updateBest(state("t1", 60.0), 0.5, 1);

        // coverage 50 >= 0.8*60 = 48 and reward 0.6 > 0.5 → best switches, but
        // current_coverage STAYS 60 (only rules 1 updates it).
        p.updateBest(state("t2", 50.0), 0.6, 2);
        assertEquals("t2", p.bestTest);
        assertEquals(0.6, p.bestReward, 0.0);
        assertEquals(60.0, p.currentCoverage, 0.0);

        // coverage 40 < 48 → blocked even with a huge reward.
        p.updateBest(state("t3", 40.0), 5.0, 3);
        assertEquals("t2", p.bestTest);
        assertEquals(0.6, p.bestReward, 0.0);
    }

    @Test
    void highCoverageSaveGuardAtNinetyPercent() {
        UpdateBestPolicy p = freshPolicy();
        p.updateBest(state("t1", 60.0), 0.5, 1);

        // 55 >= 0.9*60 = 54 → saved under key "55_00" although not the best test.
        p.updateBest(state("t2", 55.0), 0.1, 2);
        assertTrue(p.highCoverageTests.containsKey("55_00"));
        assertEquals("t2", p.highCoverageTests.get("55_00"));
        assertEquals("t1", p.bestTest);

        // 40 < 54 → not saved.
        p.updateBest(state("t3", 40.0), 0.1, 3);
        assertFalse(p.highCoverageTests.containsKey("40_00"));
    }

    @Test
    void nullOrNonPositiveCoverageStates() {
        UpdateBestPolicy p = freshPolicy();
        p.updateBest(null, 9.9, 1); // no-op
        assertEquals("initial test", p.bestTest);

        // coverage <= 0 → treated as 0.0; equal to initial 0.0 coverage, reward 0.2 > 0
        // → rule 2 fires.
        FaTestState s = state("t1", -5.0);
        p.updateBest(s, 0.2, 2);
        assertEquals("t1", p.bestTest);
        assertEquals(0.2, p.bestReward, 0.0);
        assertEquals(0.0, p.currentCoverage, 0.0);
    }

    @Test
    void iterationsToHighCoverageRecordedOnceAtEighty() {
        UpdateBestPolicy p = freshPolicy();
        p.updateBest(state("t1", 79.9), 0.1, 3);
        assertNull(p.iterationsToHighCoverage);
        p.updateBest(state("t2", 80.0), 0.2, 4); // C37: >= 80.0
        assertEquals(4, p.iterationsToHighCoverage);
        p.updateBest(state("t3", 90.0), 0.3, 5); // not overwritten
        assertEquals(4, p.iterationsToHighCoverage);
    }

    @Test
    void coverageKeyUsesHalfEvenAndUnderscore() {
        // Python f"{x:.2f}".replace(".","_") rounds the exact binary double half-even
        // (verified against CPython):
        //   83.125 (exactly representable midpoint, 2 even) → "83.12" (HALF_UP gives 83.13)
        //   83.135 (binary = 83.135000000000005...) → "83.14" (above the midpoint)
        //   85.0 → "85.00"
        //   0.375 (exact midpoint, 7 odd → up) → "0.38"
        assertEquals("83_12", UpdateBestPolicy.coverageKey(83.125));
        assertEquals("83_14", UpdateBestPolicy.coverageKey(83.135));
        assertEquals("85_00", UpdateBestPolicy.coverageKey(85.0));
        assertEquals("0_38", UpdateBestPolicy.coverageKey(0.375));
    }

    @Test
    void zeroCoverageStateIsSavedUnderInitialZeroBest() {
        // With current_coverage 0.0: 0.0 >= 0.0*0.9 → the save guard fires and stores
        // key "0_00" (Python does exactly this on the first zero-coverage state).
        UpdateBestPolicy p = freshPolicy();
        p.updateBest(state("t0", 0.0), 0.0, 1);
        assertTrue(p.highCoverageTests.containsKey("0_00"));
    }
}
