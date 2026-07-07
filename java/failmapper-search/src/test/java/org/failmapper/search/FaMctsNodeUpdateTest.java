package org.failmapper.search;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

/**
 * F3 + F4 tests for {@link FaMctsNode#update} (fa_mcts.py:513-574): win accumulation,
 * the 0.9 failure decay, consecutive-failure tracking, prefix-routed logic rewards and
 * the /10 & /20 coverage normalizers.
 */
class FaMctsNodeUpdateTest {

    private static final double EPS = 1e-12;

    private static FaMctsNode node() {
        return new FaMctsNode("state");
    }

    @Test
    void successfulUpdateAccumulatesAllComponents() {
        FaMctsNode n = node();
        // update(0.5, "logical_boundary_error", covered=3, branches=4):
        //   visits 0->1; wins 0+0.5=0.5; reward 0.5 >= 0.1 -> consecutive reset to 0;
        //   logical_ prefix -> logicBugRewards += 1.0 (C14), bugsFound += 1;
        //   failureCoverageRewards += min(3/10,1) + min(4/20,1) = 0.3 + 0.2 = 0.5 (C16/C17).
        n.update(0.5, "logical_boundary_error", 3, 4);

        assertThat(n.visits).isEqualTo(1);
        assertThat(n.wins).isCloseTo(0.5, within(EPS));
        assertThat(n.consecutiveFailures).isZero();
        assertThat(n.logicBugRewards).isCloseTo(1.0, within(EPS));
        assertThat(n.bugsFound).isEqualTo(1);
        assertThat(n.highRiskPatternRewards).isCloseTo(0.0, within(EPS));
        assertThat(n.failureCoverageRewards).isCloseTo(0.5, within(EPS));
    }

    @Test
    void lowRewardDecaysWinsAndCountsConsecutiveFailures() {
        FaMctsNode n = node();
        // update(0.05): 0.05 < 0.1 (C13) -> failure signal.
        //   wins = (0 + 0.05) * 0.9 = 0.045 (C12); consecutive = 1.
        n.update(0.05, null, -1, -1);
        assertThat(n.visits).isEqualTo(1);
        assertThat(n.wins).isCloseTo(0.05 * 0.9, within(EPS));
        assertThat(n.consecutiveFailures).isEqualTo(1);

        // Second failure: wins = (0.045 + 0.05) * 0.9 = 0.0855; consecutive = 2.
        n.update(0.05, null, -1, -1);
        assertThat(n.wins).isCloseTo((0.05 * 0.9 + 0.05) * 0.9, within(EPS));
        assertThat(n.consecutiveFailures).isEqualTo(2);
    }

    @Test
    void rewardExactlyAtThresholdIsNotAFailure() {
        // C13 is STRICT: reward < 0.1; reward == 0.1 is a success.
        FaMctsNode n = node();
        n.update(0.1, null, -1, -1);
        assertThat(n.wins).isCloseTo(0.1, within(EPS));
        assertThat(n.consecutiveFailures).isZero();
    }

    @Test
    void hasErrorDecaysEvenWithHighReward() {
        // F3: has_error OR reward < 0.1 — an execution error decays wins regardless of reward.
        FaMctsNode n = node();
        n.update(1.0, null, -1, -1, true);
        assertThat(n.wins).isCloseTo(1.0 * 0.9, within(EPS));
        assertThat(n.consecutiveFailures).isEqualTo(1);
    }

    @Test
    void successResetsConsecutiveFailures() {
        FaMctsNode n = node();
        n.update(0.05, null, -1, -1);
        n.update(0.05, null, -1, -1);
        assertThat(n.consecutiveFailures).isEqualTo(2);
        n.update(0.5, null, -1, -1);
        assertThat(n.consecutiveFailures).isZero();
        // wins = ((0.045 + 0.05)*0.9 + 0.5) — no decay on the success.
        assertThat(n.wins).isCloseTo((0.05 * 0.9 + 0.05) * 0.9 + 0.5, within(EPS));
    }

    @Test
    void highRiskPrefixAccruesPointEightAndNoBugCount() {
        FaMctsNode n = node();
        n.update(0.5, "high_risk_off_by_one", -1, -1);
        assertThat(n.highRiskPatternRewards).isCloseTo(0.8, within(EPS)); // C15
        assertThat(n.logicBugRewards).isCloseTo(0.0, within(EPS));
        assertThat(n.bugsFound).isZero();
    }

    @Test
    void unrelatedOrEmptyBugTypeAccruesNothing() {
        FaMctsNode n = node();
        n.update(0.5, "some_other_bug", -1, -1);
        n.update(0.5, "", -1, -1); // Python `if bug_type:` — empty string is falsy
        n.update(0.5, null, -1, -1);
        assertThat(n.logicBugRewards).isCloseTo(0.0, within(EPS));
        assertThat(n.highRiskPatternRewards).isCloseTo(0.0, within(EPS));
        assertThat(n.bugsFound).isZero();
    }

    @Test
    void coverageNormalizersSaturateAtOne() {
        FaMctsNode n = node();
        // min(25/10, 1) + min(100/20, 1) = 1.0 + 1.0 = 2.0 (C16/C17 caps).
        n.update(0.5, null, 25, 100);
        assertThat(n.failureCoverageRewards).isCloseTo(2.0, within(EPS));
    }

    @Test
    void zeroCountsAddZeroAndNegativeCountsSkipAccrual() {
        FaMctsNode n = node();
        n.update(0.5, null, 0, 0); // len()==0 -> += 0.0 twice
        assertThat(n.failureCoverageRewards).isCloseTo(0.0, within(EPS));
        n.update(0.5, null, -1, 7); // negative models a state without the attribute
        assertThat(n.failureCoverageRewards).isCloseTo(7 / 20.0, within(EPS));
    }

    @Test
    void historyWinsReportsWinsDirectlyPerRegisteredI11() {
        // Layer-D regression for I11: the Python and/or chain (fa_mcts.py:2344) would
        // fall through past wins==0.0 to a (nonexistent) node.value and yield 0.0 —
        // same number, different mechanism. The registered Java semantics: wins, always.
        FaMctsNode n = node();
        assertThat(n.historyWins()).isEqualTo(0.0);
        n.update(0.7, null, -1, -1);
        assertThat(n.historyWins()).isCloseTo(0.7, within(EPS));
    }
}
