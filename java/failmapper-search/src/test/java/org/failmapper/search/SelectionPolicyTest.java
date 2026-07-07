package org.failmapper.search;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * D4 tests for {@link SelectionPolicy} (fa_mcts.py:2465-2576) with a strictly scripted
 * {@link FixedRandomSource} — the stub throws on any unscripted draw, so these tests
 * also pin WHICH paths consume randomness.
 */
class SelectionPolicyTest {

    private final SearchConfig config = SearchConfig.defaults();

    /**
     * Root with three visited children, each carrying distinct exploitation:
     * root.visits=9; c1: 3/3.0 (exploit 1.0), c2: 3/1.5 (0.5), c3: 3/0.3 (0.1).
     * Common terms (per child, with scripted random=0.0):
     *   exploration = 1.0 * 2*sqrt(9/3) = 2*sqrt(3) = 3.4641016...
     *   logic bonus = 0 (no rewards); diversity = 0.2 (history empty -> ALWAYS, S3)
     * Forced scores: c1 = 4.6641, c2 = 4.1641, c3 = 3.7641 -> sorted [c1, c2, c3].
     */
    private static FaMctsNode threeChildRoot() {
        FaMctsNode root = new FaMctsNode("root");
        root.visits = 9;
        root.expanded = true;
        double[] wins = {3.0, 1.5, 0.3};
        String[] types = {"boundary_test", "expression_test", "exception_test"};
        for (int i = 0; i < 3; i++) {
            FaMctsNode c = root.addChild("s" + i, SearchAction.of(types[i]));
            c.visits = 3;
            c.wins = wins[i];
        }
        return root;
    }

    @Test
    void forcedExplorationPicksAmongTopThreeViaRandintInclusive() {
        FaMctsNode root = threeChildRoot();
        // iteration 3 -> 3 % 3 == 0 -> forced (C41). Three nextDouble draws (one per
        // visited child, R6) then one randint over [0, min(2, 2)] (R7) returning 1 ->
        // the SECOND-best child (c2).
        FixedRandomSource random = new FixedRandomSource().doubles(0.0, 0.0, 0.0).ints(1);
        FaMctsNode selected = new SelectionPolicy(config, random).select(root, 3);
        assertThat(selected).isSameAs(root.children.get(1));
        assertThat(random.exhausted()).isTrue();
        // The chosen child's action type is stamped on the parent (fa_mcts.py:2561).
        assertThat(root.lastActionType).isEqualTo("expression_test");
    }

    @Test
    void iterationZeroForcesExploration() {
        // Python: getattr(self, 'current_iteration', 0) % 3 == 0 -> iteration 0 forces.
        FaMctsNode root = threeChildRoot();
        FixedRandomSource random = new FixedRandomSource().doubles(0.0, 0.0, 0.0).ints(0);
        FaMctsNode selected = new SelectionPolicy(config, random).select(root, 0);
        assertThat(selected).isSameAs(root.children.get(0)); // top-scored c1 at index 0
        assertThat(random.exhausted()).isTrue();
    }

    @Test
    void nonForcedIterationUsesBestChildAndConsumesNoRandomness() {
        FaMctsNode root = threeChildRoot();
        // iteration 1 -> not forced; strict stub with EMPTY scripts proves no draws.
        FixedRandomSource random = new FixedRandomSource();
        FaMctsNode selected = new SelectionPolicy(config, random).select(root, 1);
        // bestChild(1.0, 2.0): exploitation decides (equal exploration, no logic terms,
        // no diversity because root.lastActionType is null) -> c1.
        assertThat(selected).isSameAs(root.children.get(0));
        assertThat(root.lastActionType).isEqualTo("boundary_test");
    }

    @Test
    void forcedExplorationCadenceIsEveryThirdIteration() {
        // C41: iterations 0,3,6 force (consume randomness); 1,2,4 do not.
        for (int it : new int[]{0, 3, 6}) {
            FaMctsNode root = threeChildRoot();
            FixedRandomSource random = new FixedRandomSource().doubles(0.0, 0.0, 0.0).ints(0);
            new SelectionPolicy(config, random).select(root, it);
            assertThat(random.exhausted()).as("iteration %d must draw", it).isTrue();
        }
        for (int it : new int[]{1, 2, 4}) {
            FaMctsNode root = threeChildRoot();
            FixedRandomSource random = new FixedRandomSource(); // throws on any draw
            new SelectionPolicy(config, random).select(root, it);
        }
    }

    @Test
    void randomPerturbationCanReorderForcedScores() {
        FaMctsNode root = threeChildRoot();
        // Scripted random factors (scale 0.3, C42): c1 +0.0, c2 +0.9*0.3=0.27,
        // c3 +1.0*0.3(≈)... use 0.99 -> 0.297. Base scores 4.6641/4.1641/3.7641 ->
        // perturbed 4.6641/4.4341/4.0611: order unchanged here, but pick index 2 ->
        // c3 proves the randint indexes the SORTED list, not the child list.
        FixedRandomSource random = new FixedRandomSource().doubles(0.0, 0.9, 0.99).ints(2);
        FaMctsNode selected = new SelectionPolicy(config, random).select(root, 3);
        assertThat(selected).isSameAs(root.children.get(2));
    }

    @Test
    void unvisitedChildScoresInfinityAndSortsFirstInForcedMode() {
        FaMctsNode root = threeChildRoot();
        FaMctsNode fresh = root.addChild("s3", SearchAction.of("bug_pattern_test"));
        assertThat(fresh.visits).isZero();
        // Three visited children draw one double each; the unvisited one draws nothing
        // (fa_mcts.py:2520) and ties at +inf -> sorted first. randint 0 -> fresh.
        FixedRandomSource random = new FixedRandomSource().doubles(0.0, 0.0, 0.0).ints(0);
        FaMctsNode selected = new SelectionPolicy(config, random).select(root, 3);
        assertThat(selected).isSameAs(fresh);
        assertThat(random.exhausted()).isTrue();
    }

    @Test
    void singleChildNodeSkipsForcedExplorationEvenOnForcedIteration() {
        // fa_mcts.py:2489: forced branch requires len(children) > 1.
        FaMctsNode root = new FaMctsNode("root");
        root.visits = 4;
        root.expanded = true;
        FaMctsNode only = root.addChild("s", SearchAction.of("boundary_test"));
        only.visits = 2;
        only.wins = 1.0;
        FixedRandomSource random = new FixedRandomSource(); // no draws allowed
        FaMctsNode selected = new SelectionPolicy(config, random).select(root, 3);
        assertThat(selected).isSameAs(only);
    }

    /**
     * Three-level descent tree: root -> c1 -> c2 -> {g1, g2}. c1/c2 actions both type
     * "A", so after two steps the history is [A, A] and the third step must use
     * exploration_weight * 1.5 (C42).
     *
     * g-stats chosen so the winner FLIPS with the multiplier (c2.visits = 5):
     *   g1: visits=1, wins=0.2  -> exploit 0.2, exploration w*2*sqrt(5)   = 4.4721w
     *   g2: visits=4, wins=12.0 -> exploit 3.0, exploration w*2*sqrt(5/4) = 2.2360w
     *   w=1.0: g1 = 4.6721 < g2 = 5.2360  -> g2
     *   w=1.5: g1 = 6.9082 > g2 = 6.3541  -> g1
     */
    private static FaMctsNode deepTree(String c2ActionType) {
        FaMctsNode root = new FaMctsNode("root");
        root.visits = 7;
        root.expanded = true;
        FaMctsNode c1 = root.addChild("s1", SearchAction.of("A"));
        c1.visits = 6;
        c1.wins = 3.0;
        c1.expanded = true;
        FaMctsNode c2 = c1.addChild("s2", SearchAction.of(c2ActionType));
        c2.visits = 5;
        c2.wins = 2.5;
        c2.expanded = true;
        FaMctsNode g1 = c2.addChild("s3", SearchAction.of("B"));
        g1.visits = 1;
        g1.wins = 0.2;
        FaMctsNode g2 = c2.addChild("s4", SearchAction.of("C"));
        g2.visits = 4;
        g2.wins = 12.0;
        return root;
    }

    @Test
    void twoConsecutiveIdenticalActionTypesBoostExplorationWeight() {
        FaMctsNode root = deepTree("A"); // history becomes [A, A]
        FixedRandomSource random = new FixedRandomSource(); // non-forced iteration, no draws
        FaMctsNode selected = new SelectionPolicy(config, random).select(root, 1);
        FaMctsNode g1 = root.children.get(0).children.get(0).children.get(0);
        assertThat(selected).isSameAs(g1); // 1.5x exploration flips the winner to g1
    }

    @Test
    void distinctRecentActionTypesUseNormalWeight() {
        FaMctsNode root = deepTree("Z"); // history becomes [A, Z]
        FixedRandomSource random = new FixedRandomSource();
        FaMctsNode selected = new SelectionPolicy(config, random).select(root, 1);
        FaMctsNode g2 = root.children.get(0).children.get(0).children.get(1);
        assertThat(selected).isSameAs(g2); // normal weight keeps g2 on top
    }

    @Test
    void descentStampsLastActionTypeOnEachParent() {
        FaMctsNode root = deepTree("A");
        new SelectionPolicy(config, new FixedRandomSource()).select(root, 1);
        FaMctsNode c1 = root.children.get(0);
        FaMctsNode c2 = c1.children.get(0);
        assertThat(root.lastActionType).isEqualTo("A");
        assertThat(c1.lastActionType).isEqualTo("A");
        assertThat(c2.lastActionType).isEqualTo("B"); // g1 (type B) was selected from c2
    }

    @Test
    void forcedDiversityBonusUsesLastTwoHistoryEntriesAndFewerThanThreeChildrenUseChoice() {
        // root -> c1 (type "A") -> {c2a (type "A"), c2b (type "B")}, both visited equally:
        //   c1.visits=5; both children visits=2, wins=1.0 -> exploit 0.5,
        //   exploration 2*sqrt(5/2) = 3.16227766...
        // At the c1 step the history is [A]; last-2 = [A]:
        //   c2a (type A) IS in history -> no diversity; c2b (type B) -> +0.2 (C42/S3).
        // Scripted doubles 0.0/0.0 keep the diversity bonus decisive: sorted [c2b, c2a].
        // Two children < topK=3 -> uniform choice over the SORTED list (R8): index 0 -> c2b.
        FaMctsNode root = new FaMctsNode("root");
        root.visits = 6;
        root.expanded = true;
        FaMctsNode c1 = root.addChild("s1", SearchAction.of("A"));
        c1.visits = 5;
        c1.wins = 2.0;
        c1.expanded = true;
        FaMctsNode c2a = c1.addChild("s2", SearchAction.of("A"));
        c2a.visits = 2;
        c2a.wins = 1.0;
        FaMctsNode c2b = c1.addChild("s3", SearchAction.of("B"));
        c2b.visits = 2;
        c2b.wins = 1.0;

        // Iteration 6 -> forced. Root has ONE child -> regular bestChild (no draws),
        // history [A]; then c1 has two children -> forced scoring: two doubles + one
        // choice (via randintInclusive(0,1)) -> scripted 0 picks sorted[0] = c2b.
        FixedRandomSource random = new FixedRandomSource().doubles(0.0, 0.0).ints(0);
        FaMctsNode selected = new SelectionPolicy(config, random).select(root, 6);
        assertThat(selected).isSameAs(c2b);
        assertThat(random.exhausted()).isTrue();
    }

    @Test
    void unexpandedRootReturnsImmediately() {
        FaMctsNode root = new FaMctsNode("root");
        root.addChild("s", SearchAction.of("A"));
        assertThat(root.isFullyExpanded()).isFalse();
        FaMctsNode selected =
                new SelectionPolicy(config, new FixedRandomSource()).select(root, 3);
        assertThat(selected).isSameAs(root);
    }
}
