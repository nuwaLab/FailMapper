package org.failmapper.search;

import java.util.List;

/**
 * Early-termination checks — port of {@code check_termination}
 * ({@code fa_mcts.py:2390-2424}).
 *
 * <p>Checks in Python source order:
 * <ol>
 *   <li>{@code iteration >= max_iterations} (C4).</li>
 *   <li>{@code current_coverage >= 101.0 and bugs_found > 0} (C34) — the 101.0 target
 *       is unreachable for a 0-100 percentage, so this rule is effectively disabled;
 *       ported verbatim per the iron rule.</li>
 *   <li>{@code bugs_found >= bugs_threshold} (C3; effectively disabled at the default 100).</li>
 *   <li>No-progress window (C35): requires {@code iteration > 5} (STRICT) and
 *       {@code len(history) >= 15}; terminates when every one of the last 5 recorded
 *       rewards differs from the FIRST of those 5 by strictly less than 0.001
 *       ({@code all(abs(last[0] - r) < 0.001 for r in last[1:])}, {@code fa_mcts.py:2417-2419}).
 *       Comparison stays in double math (contract N16); Python records rewards rounded
 *       to 5 places (half-even) into history, so callers feed the recorded values.</li>
 * </ol>
 */
public final class TerminationPolicy {

    private final SearchConfig config;

    public TerminationPolicy(SearchConfig config) {
        this.config = config;
    }

    /**
     * @param iteration       current iteration index
     * @param currentCoverage best coverage so far (0-100 percentage scale)
     * @param bugsFound       bugs found so far
     * @param rewardHistory   the per-iteration recorded rewards ({@code entry["reward"]}
     *                        of the history list), oldest first
     * @return true if the search should stop
     */
    public boolean shouldTerminate(int iteration, double currentCoverage, int bugsFound,
                                   List<Double> rewardHistory) {
        // Maximum iterations reached (fa_mcts.py:2401).
        if (iteration >= config.maxIterations) {
            return true;
        }

        // Target coverage with bugs (fa_mcts.py:2405-2408) — 101.0, effectively disabled.
        if (currentCoverage >= config.terminationTargetCoverage && bugsFound > 0) {
            return true;
        }

        // Enough bugs found (fa_mcts.py:2412).
        if (bugsFound >= config.bugsThreshold) {
            return true;
        }

        // No progress in the last 5 iterations (fa_mcts.py:2417-2421).
        if (iteration > config.noProgressMinIteration
                && rewardHistory.size() >= config.noProgressMinHistory) {
            List<Double> lastRewards = rewardHistory.subList(
                    rewardHistory.size() - config.noProgressWindow, rewardHistory.size());
            double first = lastRewards.get(0);
            boolean allSame = true;
            for (int i = 1; i < lastRewards.size(); i++) {
                // Python: abs(last[0] - r) < 0.001 — STRICT less-than.
                if (!(Math.abs(first - lastRewards.get(i)) < config.noProgressEpsilon)) {
                    allSame = false;
                    break;
                }
            }
            if (allSame) {
                return true;
            }
        }

        return false;
    }
}
