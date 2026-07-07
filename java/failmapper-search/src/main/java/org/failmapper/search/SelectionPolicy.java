package org.failmapper.search;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;

/**
 * D4 — tree-policy selection, port of {@code FA_MCTS.selection}
 * ({@code fa_mcts.py:2465-2576}).
 *
 * <p>Descends while the current node is fully expanded and has children:
 * <ul>
 *   <li>FORCED EXPLORATION — when {@code currentIteration % 3 == 0} (C41; note
 *       iteration 0 forces) AND the node has MORE THAN ONE child ({@code fa_mcts.py:2489}
 *       — a single-child node falls to the regular path even on forced iterations):
 *       each visited child is scored
 *       {@code exploitation + exploration + logicBonus + random()*0.3 + diversity},
 *       where the logic bonus here is the SELECTION REPLICA
 *       {@code f_weight * (logic_bug_term + logic_coverage_term)} — deliberately
 *       DIFFERENT from {@code best_child}'s F2 (no high-risk term, no novelty, no decay,
 *       no failure penalty; {@code fa_mcts.py:2497-2502}); unvisited children score
 *       +infinity. The 0.2 diversity bonus applies when the child's action type is not
 *       among the last two selected types — or ALWAYS when the history is empty
 *       (contract S3: the Python conditional expression makes the empty-history case
 *       literally True). Children are then STABLY sorted by score descending (O2 — ties
 *       keep child order) and one of the top 3 is picked via
 *       {@code randintInclusive(0, min(2, n-1))} (R7); with fewer than 3 children a
 *       uniform {@code choice} over the sorted list is used (R8).</li>
 *   <li>REGULAR — if the last two selected action types are identical, calls
 *       {@code bestChild(explorationWeight * 1.5, fWeight)} (C42); otherwise the normal
 *       {@code bestChild(explorationWeight, fWeight)}.</li>
 * </ul>
 * After each step the chosen child's action type is appended to the per-call history and
 * stamped on the parent's {@link FaMctsNode#lastActionType} ({@code fa_mcts.py:2556-2561})
 * — which is what arms the 0.15 UCB diversity bonus (F2/C11) for later descents.
 */
public final class SelectionPolicy {

    private final SearchConfig config;
    private final RandomSource random;

    public SelectionPolicy(SearchConfig config, RandomSource random) {
        this.config = config;
        this.random = random;
    }

    private record ScoredChild(FaMctsNode child, double score) {
    }

    /**
     * Select a promising node for expansion, starting from {@code node}.
     *
     * @param node             the root of the descent (usually the tree root)
     * @param currentIteration the current MCTS iteration ({@code self.current_iteration},
     *                         default 0 in Python via getattr — so iteration 0 forces
     *                         exploration)
     */
    public FaMctsNode select(FaMctsNode node, int currentIteration) {
        FaMctsNode current = node;

        // Per-call strategy history (fa_mcts.py:2480).
        List<String> actionTypeHistory = new ArrayList<>();

        boolean forceExploration = currentIteration % config.forceExplorationCadence == 0; // C41

        while (current.isFullyExpanded() && !current.children.isEmpty()) {
            if (forceExploration && current.children.size() > 1) {
                List<ScoredChild> childScores = new ArrayList<>();
                for (FaMctsNode child : current.children) {
                    if (child.visits > 0) {
                        double exploitation = child.wins / child.visits;
                        double exploration = config.explorationWeight
                                * (2.0 * Math.sqrt((double) current.visits / (double) child.visits));

                        // Selection-replica logic bonus (fa_mcts.py:2498-2502) — only
                        // the two rate terms, no decay/penalty/novelty by design.
                        double logicBugTerm = child.logicBugRewards / child.visits;
                        double logicCoverageTerm = child.failureCoverageRewards / child.visits;
                        double logicBonus = config.fWeight * (logicBugTerm + logicCoverageTerm);

                        double randomFactor = random.nextDouble() * config.selectionRandomFactorScale; // R6

                        String actionType = "unknown";
                        if (child.action != null && child.action.type() != null) {
                            actionType = child.action.type();
                        }

                        // Contract S3: `(action_type not in history[-2:]) if history else True`
                        // — empty history ALWAYS grants the bonus.
                        double diversityBonus = 0.0;
                        boolean diverse = actionTypeHistory.isEmpty()
                                || !lastTwo(actionTypeHistory).contains(actionType);
                        if (diverse) {
                            diversityBonus = config.selectionDiversityBonus; // 0.2 (C42)
                        }

                        childScores.add(new ScoredChild(child,
                                exploitation + exploration + logicBonus + randomFactor + diversityBonus));
                    } else {
                        // Unvisited children get the highest score (fa_mcts.py:2520, N2).
                        childScores.add(new ScoredChild(child, Double.POSITIVE_INFINITY));
                    }
                }

                // Stable sort descending (O2): ties keep children insertion order,
                // matching Python's stable sorted(..., reverse=True).
                List<ScoredChild> sorted = new ArrayList<>(childScores);
                sorted.sort(Comparator.comparingDouble(ScoredChild::score).reversed());

                if (sorted.size() >= config.selectionTopK) {
                    // random.randint(0, min(2, len-1)) — BOTH bounds inclusive (R7).
                    int idx = random.randintInclusive(0,
                            Math.min(config.selectionTopK - 1, sorted.size() - 1));
                    current = sorted.get(idx).child();
                } else {
                    // Fewer than topK children: uniform pick (R8).
                    List<FaMctsNode> pool = new ArrayList<>();
                    for (ScoredChild sc : sorted) {
                        pool.add(sc.child());
                    }
                    current = random.choice(pool);
                }
            } else {
                if (actionTypeHistory.size() >= 2) {
                    List<String> recentActions = lastTwo(actionTypeHistory);
                    if (new LinkedHashSet<>(recentActions).size() == 1) {
                        // Same strategy twice in a row: temporarily boost exploration (C42).
                        double tempExplorationWeight =
                                config.explorationWeight * config.consecutiveSameExplorationMultiplier;
                        current = current.bestChild(tempExplorationWeight, config.fWeight);
                    } else {
                        current = current.bestChild(config.explorationWeight, config.fWeight);
                    }
                } else {
                    current = current.bestChild(config.explorationWeight, config.fWeight);
                }
            }

            // Record the chosen action type and stamp the parent for the F2 diversity
            // bonus (fa_mcts.py:2556-2561). Skipped entirely when the action has no type,
            // exactly like the Python isinstance/'type' guard.
            if (current.action != null && current.action.type() != null) {
                String actionType = current.action.type();
                actionTypeHistory.add(actionType);
                current.parent.lastActionType = actionType;
            }
        }

        return current;
    }

    /** Python {@code history[-2:]} — the last two entries (or fewer). */
    private static List<String> lastTwo(List<String> history) {
        return history.subList(Math.max(0, history.size() - 2), history.size());
    }
}
