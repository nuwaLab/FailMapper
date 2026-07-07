package org.failmapper.search;

import java.util.Set;

/**
 * F5 + F6 + F7 + F8 — port of {@code calculate_failure_aware_reward}
 * ({@code fa_mcts.py:3156-3360}).
 *
 * <p>Evaluation order is load-bearing and preserved exactly:
 * <ol>
 *   <li>F5 compilation short-circuits FIRST: a {@code fix_compilation_errors} action
 *       returns 2.0 on success / 0.1 on failure — but FALLS THROUGH to the normal
 *       computation when there were no errors before and none now; then ANY state with
 *       compilation errors returns 0.05.</li>
 *   <li>F6 component rewards, including the stagnation-counter side effect on the
 *       state (see {@link RewardInputs#setStagnantCoverageIterations}).</li>
 *   <li>F7 (focusOnBugs=true, C23) or F8 (false, C24) weighted sum; the exploration
 *       bonus is added UNWEIGHTED in both (F7 note).</li>
 * </ol>
 *
 * <p>Subtle Python behavior preserved: the stagnation flag {@code is_stagnant} is
 * decided in the coverage-improvement block, but the bonus magnitude reads the LIVE
 * counter at the end ({@code fa_mcts.py:3324}) — a new-pattern discovery in between
 * resets the counter to 0 ({@code fa_mcts.py:3264}), zeroing the bonus even though the
 * flag stayed true.
 */
public final class RewardCalculator {

    /** {@code fa_mcts.py:3240} — logical bug types worth +0.3 (C29 tier 1). */
    private static final Set<String> HIGH_VALUE_BUG_TYPES_TIER1 =
            Set.of("boundary_error", "boolean_bug", "operator_logic");

    /** {@code fa_mcts.py:3242} — logical bug types worth +0.4 (C29 tier 2). */
    private static final Set<String> HIGH_VALUE_BUG_TYPES_TIER2 =
            Set.of("resource_leak", "concurrency_issue", "state_corruption");

    /** {@code fa_mcts.py:3174} — the action type triggering the F5 fix-reward branch. */
    public static final String FIX_COMPILATION_ERRORS_ACTION = "fix_compilation_errors";

    private final SearchConfig config;

    public RewardCalculator(SearchConfig config) {
        this.config = config;
    }

    /**
     * Compute the simulation reward. A null input models {@code if not state: return 0.0}
     * ({@code fa_mcts.py:3167-3168}).
     */
    public double calculate(RewardInputs in) {
        if (in == null) {
            return 0.0;
        }

        boolean hasCompilationErrors = in.hasCompilationErrors();

        // --- F5: fix-compilation-errors action rewards (fa_mcts.py:3174-3182) ---
        if (FIX_COMPILATION_ERRORS_ACTION.equals(in.actionType())) {
            if (in.hadErrorsBefore() && !hasCompilationErrors) {
                return config.fixSuccessReward; // 2.0 (C33)
            } else if (hasCompilationErrors) {
                return config.fixFailedReward; // 0.1 (C33)
            }
            // else: fix action with no errors before and none now — FALL THROUGH
            // to the normal reward computation (contract F5 note).
        }

        // --- F5: hard penalty for any state with compilation errors (fa_mcts.py:3185-3187) ---
        if (hasCompilationErrors) {
            return config.compilationErrorReward; // 0.05 (C33)
        }

        // --- F6: base components ---
        double coverageReward = in.coverage() / config.coverageRewardNormalizer; // C25

        // Stagnation tracking (fa_mcts.py:3192-3211). `stagnant` mirrors the live value
        // of state.stagnant_coverage_iterations through every mutation.
        int stagnant = in.stagnantCoverageIterations();
        boolean isStagnant = false;
        double coverageImprovement = 0.0;
        Double parentCoverage = in.parentCoverage();
        if (parentCoverage != null) {
            double coverageDelta = in.coverage() - parentCoverage;
            if (coverageDelta > 0) {
                stagnant = 0;
                in.setStagnantCoverageIterations(0);
                coverageImprovement = coverageDelta / config.coverageImprovementScaler; // C26
            } else {
                stagnant += 1;
                in.setStagnantCoverageIterations(stagnant);
                if (stagnant > config.stagnationThreshold) { // strict > 3 (C27)
                    isStagnant = true;
                }
            }
        }

        // Business-logic + bug rewards (fa_mcts.py:3214-3243). Both accrue ONLY when
        // detected_bugs is truthy.
        double businessLogicReward = 0.0;
        double bugReward = 0.0;
        if (in.hasDetectedBugs()) {
            for (double confidence : in.matchedBusinessLogicIssueConfidences()) {
                businessLogicReward += 1.0 * confidence; // fa_mcts.py:3225
            }
            bugReward = config.bugRewardBase; // 0.5 (C29)
            if (in.hasLogicalBugs()) {
                bugReward += config.logicalBugRewardPerBug * in.logicalBugCount(); // 0.4*count (C29)
                for (String rawType : in.logicalBugTypes()) {
                    String bugType = rawType == null ? "" : rawType;
                    if (HIGH_VALUE_BUG_TYPES_TIER1.contains(bugType)) {
                        bugReward += config.highValueBugBonusTier1; // 0.3 (C29)
                    } else if (HIGH_VALUE_BUG_TYPES_TIER2.contains(bugType)) {
                        bugReward += config.highValueBugBonusTier2; // 0.4 (C29)
                    }
                }
            }
        }

        // Failure-scenario coverage rewards (fa_mcts.py:3246-3283).
        double failureCoverageReward = 0.0;
        if (in.trackCoveredFailures()) {
            int previousPatternCount = in.previousPatternCount();
            int currentPatternCount = in.currentPatternCount();

            if (in.totalFailures() > 0) { // Python truthiness guard `if self.failures:` (N13)
                double patternCoveragePct = currentPatternCount / (double) in.totalFailures();
                failureCoverageReward += patternCoveragePct * config.patternCoveragePctWeight; // *0.8 (C30)
            }

            int newPatterns = currentPatternCount - previousPatternCount;
            if (newPatterns > 0) {
                // Reset stagnation counter on new pattern discovery (fa_mcts.py:3264).
                // NOTE: is_stagnant is NOT recomputed, but the bonus below reads the
                // live counter — so this reset zeroes the bonus magnitude.
                stagnant = 0;
                in.setStagnantCoverageIterations(0);

                failureCoverageReward += newPatterns * config.newPatternReward; // *0.6 (C30)
                failureCoverageReward +=
                        config.newHighRiskPatternReward * in.newHighRiskPatternCount(); // +0.4 each (C30)
            }
        }

        // Branch condition rewards (fa_mcts.py:3286-3305).
        double branchReward = 0.0;
        if (in.trackBranchConditions()) {
            int previousBranchCount = in.previousBranchCount();
            int currentBranchCount = in.currentBranchCount();

            if (in.totalBoundaryConditions() > 0) { // truthiness guard (N13)
                double coveredRatio = currentBranchCount / (double) in.totalBoundaryConditions();
                branchReward = coveredRatio * config.branchCoverageRatioWeight; // *0.5 (C31)
            }

            int newBranches = currentBranchCount - previousBranchCount;
            if (newBranches > 0) {
                branchReward += newBranches * config.newBranchReward; // *0.2 (C31)
            }
        }

        // Test quality rewards (fa_mcts.py:3308-3318): 0.1 per flag (C32).
        double qualityReward = 0.0;
        if (in.hasBoundaryTests()) {
            qualityReward += config.qualityRewardPerFlag;
        }
        if (in.hasBooleanBugTests()) {
            qualityReward += config.qualityRewardPerFlag;
        }
        if (in.hasStateTransitionTests()) {
            qualityReward += config.qualityRewardPerFlag;
        }
        if (in.hasExceptionPathTests()) {
            qualityReward += config.qualityRewardPerFlag;
        }

        // Exploration bonus for stagnant coverage (fa_mcts.py:3320-3326): reads the
        // LIVE counter value, possibly reset above.
        double explorationBonus = 0.0;
        if (isStagnant) {
            explorationBonus = Math.min(config.explorationBonusCap,
                    config.explorationBonusSlope * stagnant); // min(0.5, 0.1*n) (C28)
        }

        // --- F7 / F8: weighted composition; exploration bonus UNWEIGHTED ---
        if (config.focusOnBugs) {
            RewardWeights w = config.rewardWeightsFocusBugs; // C23
            return w.coverage() * coverageReward
                    + w.improvement() * coverageImprovement
                    + w.bug() * bugReward
                    + w.businessLogic() * businessLogicReward
                    + w.failureCoverage() * failureCoverageReward
                    + w.branch() * branchReward
                    + w.quality() * qualityReward
                    + explorationBonus;
        } else {
            RewardWeights w = config.rewardWeightsCoverageFocus; // C24
            // F8 has NO business-logic term (fa_mcts.py:3343-3351) — omitted, not zero-weighted,
            // to keep the floating-point summation order identical to Python.
            return w.coverage() * coverageReward
                    + w.improvement() * coverageImprovement
                    + w.bug() * bugReward
                    + w.failureCoverage() * failureCoverageReward
                    + w.branch() * branchReward
                    + w.quality() * qualityReward
                    + explorationBonus;
        }
    }
}
