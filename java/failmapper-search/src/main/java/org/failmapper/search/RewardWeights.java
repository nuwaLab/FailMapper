package org.failmapper.search;

/**
 * One reward-composition weight vector for {@link RewardCalculator} (contract F7/F8).
 *
 * <p>Two instances live in {@link SearchConfig}:
 * <ul>
 *   <li>C23 focus-on-bugs vector ({@code fa_mcts.py:3331-3340}):
 *       coverage 0.2 / improvement 0.15 / bug 0.3 / businessLogic 0.20 /
 *       failureCoverage 0.25 / branch 0.05 / quality 0.05.</li>
 *   <li>C24 coverage-focus vector ({@code fa_mcts.py:3343-3351}):
 *       coverage 0.35 / improvement 0.2 / bug 0.1 / businessLogic 0.0 (the Python
 *       F8 sum has NO business-logic term at all; 0.0 here encodes that absence) /
 *       failureCoverage 0.2 / branch 0.05 / quality 0.05.</li>
 * </ul>
 *
 * <p>The exploration bonus (F6/C28) is deliberately NOT part of this vector: it is
 * added UNWEIGHTED after the weighted sum (contract F7 note, {@code fa_mcts.py:3339/3350}).
 */
public record RewardWeights(
        double coverage,
        double improvement,
        double bug,
        double businessLogic,
        double failureCoverage,
        double branch,
        double quality) {
}
