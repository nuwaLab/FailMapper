package org.failmapper.search;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

/**
 * F1 + F2 differential tests for {@link FaMctsNode#bestChild} / {@link FaMctsNode#ucbScore}.
 * All expected values are hand-computed from the Python formulas at fa_mcts.py:406-451.
 */
class FaMctsNodeUcbTest {

    private static final double EPS = 1e-12;

    private static FaMctsNode root() {
        return new FaMctsNode("root-state");
    }

    // ---------------------------------------------------------------- unvisited

    @Test
    void unvisitedChildBeatsAnyVisitedChild() {
        FaMctsNode root = root();
        root.visits = 10;

        FaMctsNode visited = root.addChild("s1", SearchAction.of("boundary_test"));
        visited.visits = 5;
        visited.wins = 4.0;
        // visited score = 4/5 + 1.0*2*sqrt(10/5) + 0 = 0.8 + 2*sqrt(2) ≈ 3.62842712...
        FaMctsNode unvisited = root.addChild("s2", SearchAction.of("expression_test"));
        // unvisited score = +inf (fa_mcts.py:409)

        assertThat(root.ucbScore(visited, 1.0, 2.0))
                .isCloseTo(0.8 + 2.0 * Math.sqrt(2.0), within(EPS));
        assertThat(root.ucbScore(unvisited, 1.0, 2.0)).isEqualTo(Double.POSITIVE_INFINITY);
        assertThat(root.bestChild(1.0, 2.0)).isSameAs(unvisited);
    }

    @Test
    void allUnvisitedChildrenTieAtInfinityAndFirstInInsertionOrderWins() {
        // Contract O1: Python max() keeps the FIRST maximal element; both children
        // score +inf, so insertion order decides the tree policy.
        FaMctsNode root = root();
        root.visits = 3;
        FaMctsNode first = root.addChild("s1", SearchAction.of("a"));
        FaMctsNode second = root.addChild("s2", SearchAction.of("b"));

        assertThat(root.ucbScore(first, 1.0, 2.0)).isEqualTo(Double.POSITIVE_INFINITY);
        assertThat(root.ucbScore(second, 1.0, 2.0)).isEqualTo(Double.POSITIVE_INFINITY);
        assertThat(root.bestChild(1.0, 2.0)).isSameAs(first);
    }

    // ---------------------------------------------------------------- f_weight

    @Test
    void fWeightBonusChangesTheWinner() {
        FaMctsNode root = root();
        root.visits = 8;

        // a: pure exploitation. exploitation = 4/4 = 1.0; exploration = 2*sqrt(8/4) = 2*sqrt(2).
        FaMctsNode a = root.addChild("s1", SearchAction.of("a"));
        a.visits = 4;
        a.wins = 4.0;

        // b: weaker exploitation but carries logic-bug rewards.
        // exploitation = 2/4 = 0.5; exploration = 2*sqrt(2);
        // logic_bug_term = 4/4 = 1.0; visits_decay = 1/(1+0.1*4) = 1/1.4; penalty = 1.0.
        // logic_bonus = f * (1.0 * (1/1.4) * 1.0) = f * 0.714285714...
        FaMctsNode b = root.addChild("s2", SearchAction.of("b"));
        b.visits = 4;
        b.wins = 2.0;
        b.logicBugRewards = 4.0;

        double explor = 2.0 * Math.sqrt(2.0);
        // With f_weight = 0: a = 1.0 + explor = 3.8284; b = 0.5 + explor = 3.3284 -> a wins.
        assertThat(root.ucbScore(a, 1.0, 0.0)).isCloseTo(1.0 + explor, within(EPS));
        assertThat(root.ucbScore(b, 1.0, 0.0)).isCloseTo(0.5 + explor, within(EPS));
        assertThat(root.bestChild(1.0, 0.0)).isSameAs(a);

        // With f_weight = 2 (C2 default): b gains 2 * 1/1.4 = 1.428571... -> b wins.
        assertThat(root.ucbScore(b, 1.0, 2.0))
                .isCloseTo(0.5 + explor + 2.0 * (1.0 / 1.4), within(EPS));
        assertThat(root.bestChild(1.0, 2.0)).isSameAs(b);
    }

    // ---------------------------------------------------------------- diversity

    @Test
    void diversityBonusBreaksTieWhenLastActionTypeSet() {
        FaMctsNode root = root();
        root.visits = 4;
        root.lastActionType = "boundary_test"; // as stamped by selection (fa_mcts.py:2561)

        FaMctsNode same = root.addChild("s1", SearchAction.of("boundary_test"));
        same.visits = 2;
        same.wins = 1.0;
        FaMctsNode different = root.addChild("s2", SearchAction.of("expression_test"));
        different.visits = 2;
        different.wins = 1.0;

        // Identical base: exploitation 0.5, exploration 2*sqrt(2); diversity only for
        // 'different' (+0.15, added OUTSIDE the f_weight product — F2).
        double base = 0.5 + 2.0 * Math.sqrt(2.0);
        assertThat(root.ucbScore(same, 1.0, 2.0)).isCloseTo(base, within(EPS));
        assertThat(root.ucbScore(different, 1.0, 2.0)).isCloseTo(base + 0.15, within(EPS));
        assertThat(root.bestChild(1.0, 2.0)).isSameAs(different);
    }

    @Test
    void noDiversityBonusWhileLastActionTypeNeverSet() {
        // Python: hasattr(self, 'last_action_type') is False until selection stamps it
        // (fa_mcts.py:436) — no child may receive the 0.15 bonus, so the tie resolves
        // to the first child.
        FaMctsNode root = root();
        root.visits = 4;

        FaMctsNode first = root.addChild("s1", SearchAction.of("boundary_test"));
        first.visits = 2;
        first.wins = 1.0;
        FaMctsNode second = root.addChild("s2", SearchAction.of("expression_test"));
        second.visits = 2;
        second.wins = 1.0;

        assertThat(root.ucbScore(first, 1.0, 2.0))
                .isEqualTo(root.ucbScore(second, 1.0, 2.0));
        assertThat(root.bestChild(1.0, 2.0)).isSameAs(first);
    }

    // ---------------------------------------------------------------- exact ties

    @Test
    void exactScoreTieKeepsFirstMax() {
        FaMctsNode root = root();
        root.visits = 6;
        FaMctsNode a = root.addChild("s1", SearchAction.of("a"));
        a.visits = 3;
        a.wins = 1.5;
        FaMctsNode b = root.addChild("s2", SearchAction.of("b"));
        b.visits = 3;
        b.wins = 1.5;

        assertThat(root.bestChild(1.0, 2.0)).isSameAs(a);
    }

    // ---------------------------------------------------------------- F2 sub-terms

    @Test
    void noveltyBonusExactValue() {
        FaMctsNode root = root();
        root.visits = 4;

        FaMctsNode plain = root.addChild("s1", SearchAction.of("a"));
        plain.visits = 2;
        plain.wins = 1.0;
        FaMctsNode novel = root.addChild("s2", SearchAction.of("b"));
        novel.visits = 2;
        novel.wins = 1.0;
        novel.isNovel = true;

        // novelty 0.2 (C8) * visits_decay 1/(1+0.2)=1/1.2 * penalty 1.0, times f=2:
        // bonus = 2 * (0.2 / 1.2) = 0.33333...
        double base = 0.5 + 2.0 * Math.sqrt(2.0);
        assertThat(root.ucbScore(novel, 1.0, 2.0))
                .isCloseTo(base + 2.0 * (0.2 / 1.2), within(EPS));
        assertThat(root.bestChild(1.0, 2.0)).isSameAs(novel);
    }

    @Test
    void failurePenaltySlopeAndFloor() {
        FaMctsNode root = root();
        root.visits = 1;

        // child: visits=1, wins=0, logicBugRewards=1 -> term 1.0; decay 1/1.1;
        // exploration = 1.0 * 2*sqrt(1/1) = 2.0; exploitation 0.
        FaMctsNode child = root.addChild("s1", SearchAction.of("a"));
        child.visits = 1;
        child.logicBugRewards = 1.0;

        // consecutive_failures = 2 -> penalty = max(0.3, 1 - 0.2*2) = 0.6 (C10 slope).
        child.consecutiveFailures = 2;
        assertThat(root.ucbScore(child, 1.0, 2.0))
                .isCloseTo(2.0 + 2.0 * ((1.0 / 1.1) * 0.6), within(EPS));

        // consecutive_failures = 10 -> 1 - 2.0 = -1.0, floored at 0.3 (C10 floor).
        child.consecutiveFailures = 10;
        assertThat(root.ucbScore(child, 1.0, 2.0))
                .isCloseTo(2.0 + 2.0 * ((1.0 / 1.1) * 0.3), within(EPS));
    }

    @Test
    void explorationTermIsNonStandardSqrtOfVisitRatioWithoutLog() {
        // IRON RULE check: exploration = w * 2 * sqrt(N_parent / n_child) — NO log (F1).
        FaMctsNode root = root();
        root.visits = 9;
        FaMctsNode child = root.addChild("s1", SearchAction.of("a"));
        child.visits = 1;
        child.wins = 0.0;
        // reward < 0.1 never happened; all logic terms zero. Score = 0 + 1.5*2*sqrt(9) = 9.
        assertThat(root.ucbScore(child, 1.5, 2.0)).isCloseTo(9.0, within(EPS));
    }

    @Test
    void bestChildReturnsNullWithoutChildren() {
        assertThat(root().bestChild(1.0, 2.0)).isNull();
    }
}
