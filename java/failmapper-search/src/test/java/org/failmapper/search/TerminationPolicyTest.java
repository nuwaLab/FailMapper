package org.failmapper.search;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests for {@link TerminationPolicy} (check_termination, fa_mcts.py:2390-2424;
 * constants C3, C4, C34, C35).
 */
class TerminationPolicyTest {

    private final TerminationPolicy policy = new TerminationPolicy(SearchConfig.defaults());

    /** History of {@code size} entries ending with the given last-5 rewards. */
    private static List<Double> history(int size, double... lastFive) {
        List<Double> h = new ArrayList<>();
        for (int i = 0; i < size - lastFive.length; i++) {
            h.add(0.1 * (i % 7)); // varied filler
        }
        for (double r : lastFive) {
            h.add(r);
        }
        return h;
    }

    @Test
    void maxIterationsTerminates() {
        assertThat(policy.shouldTerminate(20, 0.0, 0, List.of())).isTrue();  // == max (C4)
        assertThat(policy.shouldTerminate(21, 0.0, 0, List.of())).isTrue();
        assertThat(policy.shouldTerminate(19, 0.0, 0, List.of())).isFalse();
    }

    @Test
    void coverageTargetOf101IsEffectivelyDisabled() {
        // C34: 101.0 is unreachable for a 0-100 percentage — even perfect coverage
        // with bugs does NOT trigger this rule.
        assertThat(policy.shouldTerminate(1, 100.0, 5, List.of())).isFalse();
        // The rule still exists verbatim: a (hypothetical) >=101 coverage with bugs stops...
        assertThat(policy.shouldTerminate(1, 101.0, 1, List.of())).isTrue();
        // ...but not without bugs (bugs_found > 0 required).
        assertThat(policy.shouldTerminate(1, 101.0, 0, List.of())).isFalse();
    }

    @Test
    void bugsThresholdTerminates() {
        assertThat(policy.shouldTerminate(1, 0.0, 100, List.of())).isTrue();  // == threshold (C3)
        assertThat(policy.shouldTerminate(1, 0.0, 99, List.of())).isFalse();
    }

    @Test
    void noProgressWindowTerminatesWhenLastFiveRewardsAreFlat() {
        // iteration 6 > 5, history 15 >= 15, last 5 all within 0.001 of the first.
        List<Double> h = history(15, 0.5, 0.5, 0.5, 0.5, 0.5);
        assertThat(policy.shouldTerminate(6, 50.0, 0, h)).isTrue();
    }

    @Test
    void noProgressComparesAgainstFirstOfWindowWithStrictEpsilon() {
        // Python: all(abs(last[0] - r) < 0.001 for r in last[1:]).
        // 0.5009 - 0.5 = 0.0009 < 0.001 -> still "no progress".
        assertThat(policy.shouldTerminate(6, 0.0, 0,
                history(15, 0.5, 0.5009, 0.5, 0.5009, 0.5))).isTrue();
        // exactly 0.001 is NOT < 0.001 -> progress.
        assertThat(policy.shouldTerminate(6, 0.0, 0,
                history(15, 0.5, 0.501, 0.5, 0.5, 0.5))).isFalse();
        // Only the FIRST window entry anchors the comparison: pairwise drift between
        // later entries is irrelevant if each stays within 0.001 of last[0].
        assertThat(policy.shouldTerminate(6, 0.0, 0,
                history(15, 0.5, 0.5009, 0.4991, 0.5009, 0.4991))).isTrue();
    }

    @Test
    void noProgressRequiresIterationStrictlyAboveFiveAndHistoryAtLeastFifteen() {
        List<Double> flat15 = history(15, 0.5, 0.5, 0.5, 0.5, 0.5);
        // iteration == 5 (not > 5) -> continue (C35 strict).
        assertThat(policy.shouldTerminate(5, 0.0, 0, flat15)).isFalse();
        // history == 14 -> continue.
        List<Double> flat14 = history(14, 0.5, 0.5, 0.5, 0.5, 0.5);
        assertThat(policy.shouldTerminate(6, 0.0, 0, flat14)).isFalse();
    }

    @Test
    void progressingRewardsContinue() {
        List<Double> h = history(15, 0.5, 0.6, 0.7, 0.8, 0.9);
        assertThat(policy.shouldTerminate(10, 50.0, 3, h)).isFalse();
    }
}
