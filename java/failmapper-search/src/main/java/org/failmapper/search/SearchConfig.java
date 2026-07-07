package org.failmapper.search;

/**
 * Immutable configuration holding every behavior constant of the failure-aware MCTS
 * search core, per doc/JAVA_PORT_CONTRACT.md section 2.2 (contract entries C1-C37,
 * C41, C42 — 39 contract constants, 56 fields; several contract entries bundle
 * multiple scalars).
 *
 * <p>DEFAULTS ARE THE CONTRACT. Every default below must equal the Python baseline
 * value (commit d2baa9e). Per the contract's iron rule (section 1), changing any of
 * them is an algorithm-semantics change that requires registration in section 4.
 *
 * <p>Constants C38-C40 (pattern-confidence thresholds/decay/boosts, {@code test_state.py})
 * belong to the state coverage-tracking component (F9) and are deliberately NOT here.
 *
 * <p>Note: {@link FaMctsNode} inlines the UCB/update sub-constants (C8-C17) as literals,
 * mirroring the Python source which hardcodes them inside {@code best_child}/{@code update}
 * rather than reading instance config. The fields here document them and serve the
 * layer-A differential-testing fixtures; {@code SearchConfigTest} pins both in sync.
 */
public final class SearchConfig {

    /** C1 — exploration_weight, default {@code 1.0} ({@code fa_mcts.py:587}; passed to best_child at 1152/2539/2546/2552). Weight on the UCB exploration term. */
    public final double explorationWeight;

    /**
     * C2 — f_weight, default {@code 2.0} ({@code fa_mcts.py:589}). Multiplier on the entire
     * failure-aware logic bonus in the UCB score. NOTE: {@code best_child}'s own signature
     * default is 1.0 ({@code fa_mcts.py:392}) but callers always pass {@code self.f_weight}=2.0.
     */
    public final double fWeight;

    /** C3 — bugs_threshold, default {@code 100} ({@code fa_mcts.py:590}; used in check_termination at 2412). Early stop once bugsFound &gt;= threshold (effectively disabled at 100). */
    public final int bugsThreshold;

    /** C4 — max_iterations, default {@code 20} ({@code fa_mcts.py:587}; loop at 1123, termination at 2401). */
    public final int maxIterations;

    /** C5 — verify_bugs_mode, default {@code BATCH} ({@code fa_mcts.py:588}). */
    public final VerifyBugsMode verifyBugsMode;

    /** C6 — focus_on_bugs, default {@code true} ({@code fa_mcts.py:588}). Selects the bug-weighted reward composition (F7) over the coverage-focused one (F8). */
    public final boolean focusOnBugs;

    /** C7 — MAX_FIX_ATTEMPTS, default {@code 10} ({@code fa_mcts.py:130}). Global cap on compilation-fix actions; beyond it the fix action is not generated. */
    public final int maxFixAttempts;

    /** C8 — novelty bonus, default {@code 0.2} ({@code fa_mcts.py:424}). Added inside the logic-bonus numerator when the child is novel (F2). */
    public final double noveltyBonus;

    /** C9 — visits-decay factor, default {@code 0.1} in {@code visits_decay = 1/(1 + 0.1*child.visits)} ({@code fa_mcts.py:427}, F2). */
    public final double visitsDecayFactor;

    /** C10 (floor) — failure-penalty floor, default {@code 0.3} in {@code failure_penalty = max(0.3, 1 - 0.2*consecutive_failures)} ({@code fa_mcts.py:432}, F2). */
    public final double failurePenaltyFloor;

    /** C10 (slope) — failure-penalty slope, default {@code 0.2} ({@code fa_mcts.py:432}, F2). */
    public final double failurePenaltySlope;

    /** C11 — UCB diversity bonus, default {@code 0.15} ({@code fa_mcts.py:439}, F2). Added when the child's action type differs from the parent's lastActionType. */
    public final double ucbDiversityBonus;

    /** C12 — wins decay on failure, default {@code 0.9} ({@code fa_mcts.py:535}, F3). Accumulated wins multiplied by 0.9 when a simulation counts as a failure. */
    public final double winsDecayOnFailure;

    /** C13 — failure reward-signal threshold, default {@code 0.1} ({@code fa_mcts.py:532}, F3): {@code reward < 0.1} (strict) counts as a failure signal. */
    public final double failureRewardSignalThreshold;

    /** C14 — logic_bug_rewards increment, default {@code 1.0} per {@code logical_}-prefixed bug type ({@code fa_mcts.py:550}, F4). */
    public final double logicBugRewardIncrement;

    /** C15 — high_risk_pattern_rewards increment, default {@code 0.8} per {@code high_risk_}-prefixed bug type ({@code fa_mcts.py:554}, F4). */
    public final double highRiskPatternRewardIncrement;

    /** C16 — pattern coverage normalizer, default {@code 10.0}: {@code failure_coverage_rewards += min(len(covered_failures)/10.0, 1.0)} ({@code fa_mcts.py:560}, F4). */
    public final double patternCoverageNormalizer;

    /** C17 — branch coverage normalizer, default {@code 20.0}: {@code failure_coverage_rewards += min(len(covered_branch_conditions)/20.0, 1.0)} ({@code fa_mcts.py:569}, F4). */
    public final double branchCoverageNormalizer;

    /** C18 — strategy weight cutoff, default {@code 0.1} ({@code fa_mcts.py:250}). Strategies with weight &lt; 0.1 are skipped in action generation (D1 step 4). */
    public final double strategyWeightCutoff;

    /** C19 — general exploration probability, default {@code 0.2} ({@code fa_mcts.py:367}). 20% chance to add a general_exploration action (or always, when no other actions exist). */
    public final double generalExplorationProbability;

    /** C20 — uncovered-line sample size, default {@code 5}: {@code random.sample(uncovered_lines, min(5, N))} ({@code fa_mcts.py:222-225}). */
    public final int uncoveredLineSampleSize;

    /** C21 — boundary/expression condition sample size, default {@code 2}: {@code min(2, N)} at BOTH sites {@code fa_mcts.py:259-262} (boundary_conditions) and {@code fa_mcts.py:285-288} (operations). */
    public final int conditionSampleSize;

    /** C22 — high-risk pattern action sample size, default {@code 2}: {@code min(2, N)} ({@code fa_mcts.py:348-351}). */
    public final int highRiskPatternSampleSize;

    /** C23 — reward weight vector when focusOnBugs=true ({@code fa_mcts.py:3331-3340}, F7): 0.2/0.15/0.3/0.20/0.25/0.05/0.05 + UNWEIGHTED exploration bonus. */
    public final RewardWeights rewardWeightsFocusBugs;

    /** C24 — reward weight vector when focusOnBugs=false ({@code fa_mcts.py:3343-3351}, F8): 0.35/0.2/0.1/—/0.2/0.05/0.05; businessLogic weight 0.0 encodes the absent term. */
    public final RewardWeights rewardWeightsCoverageFocus;

    /** C25 — coverage reward normalizer, default {@code 100.0}: {@code coverage_reward = state.coverage/100.0} ({@code fa_mcts.py:3190}, F6). */
    public final double coverageRewardNormalizer;

    /** C26 — coverage improvement scaler, default {@code 5.0}: {@code coverage_improvement = coverage_delta/5.0} when delta &gt; 0 ({@code fa_mcts.py:3206}, F6). */
    public final double coverageImprovementScaler;

    /** C27 — stagnation threshold, default {@code 3}: stagnant flag set when the non-improving iteration counter is &gt; 3 (strict) ({@code fa_mcts.py:3210}). */
    public final int stagnationThreshold;

    /** C28 (cap) — exploration bonus cap, default {@code 0.5} in {@code min(0.5, 0.1*stagnant_iterations)} ({@code fa_mcts.py:3324}, F6). */
    public final double explorationBonusCap;

    /** C28 (slope) — exploration bonus slope, default {@code 0.1} ({@code fa_mcts.py:3324}, F6). */
    public final double explorationBonusSlope;

    /** C29 (base) — bug reward base, default {@code 0.5} when any detected bugs exist ({@code fa_mcts.py:3229}, F6). */
    public final double bugRewardBase;

    /** C29 (per-logical-bug) — default {@code 0.4} times {@code count_logical_bugs()} ({@code fa_mcts.py:3235}, F6). */
    public final double logicalBugRewardPerBug;

    /** C29 (tier-1 bonus) — default {@code 0.3} per logical bug of type boundary_error/boolean_bug/operator_logic ({@code fa_mcts.py:3240-3241}, F6). */
    public final double highValueBugBonusTier1;

    /** C29 (tier-2 bonus) — default {@code 0.4} per logical bug of type resource_leak/concurrency_issue/state_corruption ({@code fa_mcts.py:3242-3243}, F6). */
    public final double highValueBugBonusTier2;

    /** C30 (pct weight) — pattern coverage percentage weight, default {@code 0.8}: {@code (current_pattern_count/len(failures))*0.8} ({@code fa_mcts.py:3257-3258}, F6). */
    public final double patternCoveragePctWeight;

    /** C30 (new pattern) — reward per newly covered pattern, default {@code 0.6} ({@code fa_mcts.py:3267}, F6). */
    public final double newPatternReward;

    /** C30 (new high-risk) — extra reward per newly covered high-risk pattern, default {@code 0.4} ({@code fa_mcts.py:3283}, F6). */
    public final double newHighRiskPatternReward;

    /** C31 (ratio weight) — branch coverage ratio weight, default {@code 0.5}: {@code (current_branch_count/len(boundary_conditions))*0.5} ({@code fa_mcts.py:3298-3299}, F6). */
    public final double branchCoverageRatioWeight;

    /** C31 (new branch) — reward per newly covered branch, default {@code 0.2} ({@code fa_mcts.py:3305}, F6). */
    public final double newBranchReward;

    /** C32 — quality reward per flag, default {@code 0.1} for each of has_boundary_tests/has_boolean_bug_tests/has_state_transition_tests/has_exception_path_tests ({@code fa_mcts.py:3311-3318}, F6). */
    public final double qualityRewardPerFlag;

    /** C33 (success) — fix-compilation success reward, default {@code 2.0} ({@code fa_mcts.py:3179}, F5). */
    public final double fixSuccessReward;

    /** C33 (failed) — fix-compilation failed reward, default {@code 0.1} ({@code fa_mcts.py:3182}, F5). */
    public final double fixFailedReward;

    /** C33 (compile error) — reward for any non-fix state with compilation errors, default {@code 0.05} ({@code fa_mcts.py:3187}, F5). */
    public final double compilationErrorReward;

    /** C34 — termination target coverage, default {@code 101.0} ({@code fa_mcts.py:2405}) — unreachable, effectively disabled. Kept verbatim per the iron rule. */
    public final double terminationTargetCoverage;

    /** C35 (min iteration) — no-progress check requires {@code iteration > 5} (strict) ({@code fa_mcts.py:2417}). */
    public final int noProgressMinIteration;

    /** C35 (min history) — no-progress check requires {@code len(history) >= 15} ({@code fa_mcts.py:2417}). */
    public final int noProgressMinHistory;

    /** C35 (window) — the last {@code 5} rewards are compared ({@code fa_mcts.py:2418}). */
    public final int noProgressWindow;

    /** C35 (epsilon) — pairwise-to-first difference threshold {@code abs(first - r) < 0.001} (strict) ({@code fa_mcts.py:2419}). */
    public final double noProgressEpsilon;

    /** C36 (reward path) — reward-based best update requires {@code coverage >= 0.8 * best_coverage} ({@code fa_mcts.py:2187}, D14). */
    public final double updateBestCoverageGuard;

    /** C36 (save copy) — high-coverage test copies saved when {@code coverage >= 0.9 * best_coverage} ({@code fa_mcts.py:2231}, D14). */
    public final double highCoverageSaveGuard;

    /** C37 — high-coverage metric threshold, default {@code 80.0}%: records iterations_to_high_coverage when current coverage &gt;= 80 ({@code fa_mcts.py:2196}). */
    public final double highCoverageMetricThreshold;

    /** C41 — force-exploration cadence, default {@code 3}: forced diversified selection when {@code current_iteration % 3 == 0} ({@code fa_mcts.py:2484}, D4). Note: iteration 0 forces. */
    public final int forceExplorationCadence;

    /** C42 (random factor) — selection random perturbation scale, default {@code 0.3}: {@code random.random() * 0.3} ({@code fa_mcts.py:2505}, D4). */
    public final double selectionRandomFactorScale;

    /** C42 (diversity) — selection diversity bonus, default {@code 0.2} when the child's action type is not among the last two selected types — or ALWAYS when the history is empty ({@code fa_mcts.py:2513-2515}, contract S3). */
    public final double selectionDiversityBonus;

    /** C42 (top-k) — forced exploration picks uniformly among the top {@code 3} scored children: {@code random.randint(0, min(2, n-1))} INCLUSIVE ({@code fa_mcts.py:2523-2526}, R7). */
    public final int selectionTopK;

    /** C42 (consecutive-same multiplier) — temporary exploration-weight multiplier {@code 1.5} when the last two selected action types are identical ({@code fa_mcts.py:2538}, D4). */
    public final double consecutiveSameExplorationMultiplier;

    private SearchConfig(Builder b) {
        this.explorationWeight = b.explorationWeight;
        this.fWeight = b.fWeight;
        this.bugsThreshold = b.bugsThreshold;
        this.maxIterations = b.maxIterations;
        this.verifyBugsMode = b.verifyBugsMode;
        this.focusOnBugs = b.focusOnBugs;
        this.maxFixAttempts = b.maxFixAttempts;
        this.noveltyBonus = b.noveltyBonus;
        this.visitsDecayFactor = b.visitsDecayFactor;
        this.failurePenaltyFloor = b.failurePenaltyFloor;
        this.failurePenaltySlope = b.failurePenaltySlope;
        this.ucbDiversityBonus = b.ucbDiversityBonus;
        this.winsDecayOnFailure = b.winsDecayOnFailure;
        this.failureRewardSignalThreshold = b.failureRewardSignalThreshold;
        this.logicBugRewardIncrement = b.logicBugRewardIncrement;
        this.highRiskPatternRewardIncrement = b.highRiskPatternRewardIncrement;
        this.patternCoverageNormalizer = b.patternCoverageNormalizer;
        this.branchCoverageNormalizer = b.branchCoverageNormalizer;
        this.strategyWeightCutoff = b.strategyWeightCutoff;
        this.generalExplorationProbability = b.generalExplorationProbability;
        this.uncoveredLineSampleSize = b.uncoveredLineSampleSize;
        this.conditionSampleSize = b.conditionSampleSize;
        this.highRiskPatternSampleSize = b.highRiskPatternSampleSize;
        this.rewardWeightsFocusBugs = b.rewardWeightsFocusBugs;
        this.rewardWeightsCoverageFocus = b.rewardWeightsCoverageFocus;
        this.coverageRewardNormalizer = b.coverageRewardNormalizer;
        this.coverageImprovementScaler = b.coverageImprovementScaler;
        this.stagnationThreshold = b.stagnationThreshold;
        this.explorationBonusCap = b.explorationBonusCap;
        this.explorationBonusSlope = b.explorationBonusSlope;
        this.bugRewardBase = b.bugRewardBase;
        this.logicalBugRewardPerBug = b.logicalBugRewardPerBug;
        this.highValueBugBonusTier1 = b.highValueBugBonusTier1;
        this.highValueBugBonusTier2 = b.highValueBugBonusTier2;
        this.patternCoveragePctWeight = b.patternCoveragePctWeight;
        this.newPatternReward = b.newPatternReward;
        this.newHighRiskPatternReward = b.newHighRiskPatternReward;
        this.branchCoverageRatioWeight = b.branchCoverageRatioWeight;
        this.newBranchReward = b.newBranchReward;
        this.qualityRewardPerFlag = b.qualityRewardPerFlag;
        this.fixSuccessReward = b.fixSuccessReward;
        this.fixFailedReward = b.fixFailedReward;
        this.compilationErrorReward = b.compilationErrorReward;
        this.terminationTargetCoverage = b.terminationTargetCoverage;
        this.noProgressMinIteration = b.noProgressMinIteration;
        this.noProgressMinHistory = b.noProgressMinHistory;
        this.noProgressWindow = b.noProgressWindow;
        this.noProgressEpsilon = b.noProgressEpsilon;
        this.updateBestCoverageGuard = b.updateBestCoverageGuard;
        this.highCoverageSaveGuard = b.highCoverageSaveGuard;
        this.highCoverageMetricThreshold = b.highCoverageMetricThreshold;
        this.forceExplorationCadence = b.forceExplorationCadence;
        this.selectionRandomFactorScale = b.selectionRandomFactorScale;
        this.selectionDiversityBonus = b.selectionDiversityBonus;
        this.selectionTopK = b.selectionTopK;
        this.consecutiveSameExplorationMultiplier = b.consecutiveSameExplorationMultiplier;
    }

    /** Python-baseline defaults (commit d2baa9e). */
    public static SearchConfig defaults() {
        return builder().build();
    }

    public static Builder builder() {
        return new Builder();
    }

    /** Builder preloaded with the Python-baseline defaults. */
    public static final class Builder {
        private double explorationWeight = 1.0;
        private double fWeight = 2.0;
        private int bugsThreshold = 100;
        private int maxIterations = 20;
        private VerifyBugsMode verifyBugsMode = VerifyBugsMode.BATCH;
        private boolean focusOnBugs = true;
        private int maxFixAttempts = 10;
        private double noveltyBonus = 0.2;
        private double visitsDecayFactor = 0.1;
        private double failurePenaltyFloor = 0.3;
        private double failurePenaltySlope = 0.2;
        private double ucbDiversityBonus = 0.15;
        private double winsDecayOnFailure = 0.9;
        private double failureRewardSignalThreshold = 0.1;
        private double logicBugRewardIncrement = 1.0;
        private double highRiskPatternRewardIncrement = 0.8;
        private double patternCoverageNormalizer = 10.0;
        private double branchCoverageNormalizer = 20.0;
        private double strategyWeightCutoff = 0.1;
        private double generalExplorationProbability = 0.2;
        private int uncoveredLineSampleSize = 5;
        private int conditionSampleSize = 2;
        private int highRiskPatternSampleSize = 2;
        private RewardWeights rewardWeightsFocusBugs =
                new RewardWeights(0.2, 0.15, 0.3, 0.20, 0.25, 0.05, 0.05);
        private RewardWeights rewardWeightsCoverageFocus =
                new RewardWeights(0.35, 0.2, 0.1, 0.0, 0.2, 0.05, 0.05);
        private double coverageRewardNormalizer = 100.0;
        private double coverageImprovementScaler = 5.0;
        private int stagnationThreshold = 3;
        private double explorationBonusCap = 0.5;
        private double explorationBonusSlope = 0.1;
        private double bugRewardBase = 0.5;
        private double logicalBugRewardPerBug = 0.4;
        private double highValueBugBonusTier1 = 0.3;
        private double highValueBugBonusTier2 = 0.4;
        private double patternCoveragePctWeight = 0.8;
        private double newPatternReward = 0.6;
        private double newHighRiskPatternReward = 0.4;
        private double branchCoverageRatioWeight = 0.5;
        private double newBranchReward = 0.2;
        private double qualityRewardPerFlag = 0.1;
        private double fixSuccessReward = 2.0;
        private double fixFailedReward = 0.1;
        private double compilationErrorReward = 0.05;
        private double terminationTargetCoverage = 101.0;
        private int noProgressMinIteration = 5;
        private int noProgressMinHistory = 15;
        private int noProgressWindow = 5;
        private double noProgressEpsilon = 0.001;
        private double updateBestCoverageGuard = 0.8;
        private double highCoverageSaveGuard = 0.9;
        private double highCoverageMetricThreshold = 80.0;
        private int forceExplorationCadence = 3;
        private double selectionRandomFactorScale = 0.3;
        private double selectionDiversityBonus = 0.2;
        private int selectionTopK = 3;
        private double consecutiveSameExplorationMultiplier = 1.5;

        private Builder() {
        }

        public Builder explorationWeight(double v) { this.explorationWeight = v; return this; }
        public Builder fWeight(double v) { this.fWeight = v; return this; }
        public Builder bugsThreshold(int v) { this.bugsThreshold = v; return this; }
        public Builder maxIterations(int v) { this.maxIterations = v; return this; }
        public Builder verifyBugsMode(VerifyBugsMode v) { this.verifyBugsMode = v; return this; }
        public Builder focusOnBugs(boolean v) { this.focusOnBugs = v; return this; }
        public Builder maxFixAttempts(int v) { this.maxFixAttempts = v; return this; }
        public Builder noveltyBonus(double v) { this.noveltyBonus = v; return this; }
        public Builder visitsDecayFactor(double v) { this.visitsDecayFactor = v; return this; }
        public Builder failurePenaltyFloor(double v) { this.failurePenaltyFloor = v; return this; }
        public Builder failurePenaltySlope(double v) { this.failurePenaltySlope = v; return this; }
        public Builder ucbDiversityBonus(double v) { this.ucbDiversityBonus = v; return this; }
        public Builder winsDecayOnFailure(double v) { this.winsDecayOnFailure = v; return this; }
        public Builder failureRewardSignalThreshold(double v) { this.failureRewardSignalThreshold = v; return this; }
        public Builder logicBugRewardIncrement(double v) { this.logicBugRewardIncrement = v; return this; }
        public Builder highRiskPatternRewardIncrement(double v) { this.highRiskPatternRewardIncrement = v; return this; }
        public Builder patternCoverageNormalizer(double v) { this.patternCoverageNormalizer = v; return this; }
        public Builder branchCoverageNormalizer(double v) { this.branchCoverageNormalizer = v; return this; }
        public Builder strategyWeightCutoff(double v) { this.strategyWeightCutoff = v; return this; }
        public Builder generalExplorationProbability(double v) { this.generalExplorationProbability = v; return this; }
        public Builder uncoveredLineSampleSize(int v) { this.uncoveredLineSampleSize = v; return this; }
        public Builder conditionSampleSize(int v) { this.conditionSampleSize = v; return this; }
        public Builder highRiskPatternSampleSize(int v) { this.highRiskPatternSampleSize = v; return this; }
        public Builder rewardWeightsFocusBugs(RewardWeights v) { this.rewardWeightsFocusBugs = v; return this; }
        public Builder rewardWeightsCoverageFocus(RewardWeights v) { this.rewardWeightsCoverageFocus = v; return this; }
        public Builder coverageRewardNormalizer(double v) { this.coverageRewardNormalizer = v; return this; }
        public Builder coverageImprovementScaler(double v) { this.coverageImprovementScaler = v; return this; }
        public Builder stagnationThreshold(int v) { this.stagnationThreshold = v; return this; }
        public Builder explorationBonusCap(double v) { this.explorationBonusCap = v; return this; }
        public Builder explorationBonusSlope(double v) { this.explorationBonusSlope = v; return this; }
        public Builder bugRewardBase(double v) { this.bugRewardBase = v; return this; }
        public Builder logicalBugRewardPerBug(double v) { this.logicalBugRewardPerBug = v; return this; }
        public Builder highValueBugBonusTier1(double v) { this.highValueBugBonusTier1 = v; return this; }
        public Builder highValueBugBonusTier2(double v) { this.highValueBugBonusTier2 = v; return this; }
        public Builder patternCoveragePctWeight(double v) { this.patternCoveragePctWeight = v; return this; }
        public Builder newPatternReward(double v) { this.newPatternReward = v; return this; }
        public Builder newHighRiskPatternReward(double v) { this.newHighRiskPatternReward = v; return this; }
        public Builder branchCoverageRatioWeight(double v) { this.branchCoverageRatioWeight = v; return this; }
        public Builder newBranchReward(double v) { this.newBranchReward = v; return this; }
        public Builder qualityRewardPerFlag(double v) { this.qualityRewardPerFlag = v; return this; }
        public Builder fixSuccessReward(double v) { this.fixSuccessReward = v; return this; }
        public Builder fixFailedReward(double v) { this.fixFailedReward = v; return this; }
        public Builder compilationErrorReward(double v) { this.compilationErrorReward = v; return this; }
        public Builder terminationTargetCoverage(double v) { this.terminationTargetCoverage = v; return this; }
        public Builder noProgressMinIteration(int v) { this.noProgressMinIteration = v; return this; }
        public Builder noProgressMinHistory(int v) { this.noProgressMinHistory = v; return this; }
        public Builder noProgressWindow(int v) { this.noProgressWindow = v; return this; }
        public Builder noProgressEpsilon(double v) { this.noProgressEpsilon = v; return this; }
        public Builder updateBestCoverageGuard(double v) { this.updateBestCoverageGuard = v; return this; }
        public Builder highCoverageSaveGuard(double v) { this.highCoverageSaveGuard = v; return this; }
        public Builder highCoverageMetricThreshold(double v) { this.highCoverageMetricThreshold = v; return this; }
        public Builder forceExplorationCadence(int v) { this.forceExplorationCadence = v; return this; }
        public Builder selectionRandomFactorScale(double v) { this.selectionRandomFactorScale = v; return this; }
        public Builder selectionDiversityBonus(double v) { this.selectionDiversityBonus = v; return this; }
        public Builder selectionTopK(int v) { this.selectionTopK = v; return this; }
        public Builder consecutiveSameExplorationMultiplier(double v) { this.consecutiveSameExplorationMultiplier = v; return this; }

        public SearchConfig build() {
            return new SearchConfig(this);
        }
    }
}
