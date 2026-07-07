package org.failmapper.search;

import java.util.Collections;
import java.util.List;

/**
 * Narrow input surface for {@link RewardCalculator} — everything
 * {@code calculate_failure_aware_reward} ({@code fa_mcts.py:3156-3360}) reads from the
 * state, its parent state, and the surrounding {@code FA_MCTS} instance, pre-digested.
 *
 * <p>Default methods encode Python's attribute-absent / empty-falsy semantics
 * (contract S4) so implementations only override what their state actually carries.
 * The predicate D13 ({@code _bug_matches_predicted_issue}) that produces
 * {@link #matchedBusinessLogicIssueConfidences()} needs bug/issue text and belongs to
 * the orchestrator layer; here it arrives pre-evaluated.
 */
public interface RewardInputs {

    /** {@code state.coverage} — a percentage on the 0-100 scale ({@code fa_mcts.py:3190}). */
    double coverage();

    /**
     * {@code parent_state.coverage}, or null when there is no parent state or it lacks
     * a coverage attribute ({@code fa_mcts.py:3201}) — null skips the whole
     * improvement/stagnation block.
     */
    default Double parentCoverage() {
        return null;
    }

    /** Truthiness of {@code state.compilation_errors} ({@code fa_mcts.py:3171}): true iff present AND non-empty. */
    default boolean hasCompilationErrors() {
        return false;
    }

    /**
     * {@code state.metadata.get("action", {}).get("type")} ({@code fa_mcts.py:3174}),
     * or null when metadata/action/type is absent.
     */
    default String actionType() {
        return null;
    }

    /** Truthiness of {@code state.previous_compilation_errors} ({@code fa_mcts.py:3176}). */
    default boolean hadErrorsBefore() {
        return false;
    }

    /** Truthiness of {@code state.detected_bugs} ({@code fa_mcts.py:3218}). */
    default boolean hasDetectedBugs() {
        return false;
    }

    /**
     * One entry per detected bug that matched a predicted business-logic issue
     * ({@code fa_mcts.py:3219-3227}): Python iterates {@code state.detected_bugs}; for
     * each bug the FIRST matching issue (D13) contributes
     * {@code issue.get('confidence', 0.5)} and then {@code break}s. The 0.5 default for
     * a missing confidence must be applied by the implementation. Entries must be
     * non-null. Only consumed when {@link #hasDetectedBugs()} is true.
     */
    default List<Double> matchedBusinessLogicIssueConfidences() {
        return Collections.emptyList();
    }

    /** {@code state.has_bugs} ({@code fa_mcts.py:3233}). */
    default boolean hasLogicalBugs() {
        return false;
    }

    /** {@code state.count_logical_bugs()} ({@code fa_mcts.py:3234}). */
    default int logicalBugCount() {
        return 0;
    }

    /**
     * {@code bug.get("bug_type", "")} for each entry of {@code state.logical_bugs},
     * in list order ({@code fa_mcts.py:3238-3239}). Entries may be empty but not null.
     */
    default List<String> logicalBugTypes() {
        return Collections.emptyList();
    }

    /** {@code hasattr(state, "covered_failures")} ({@code fa_mcts.py:3247}) — always true for FATestState. */
    default boolean trackCoveredFailures() {
        return true;
    }

    /** {@code len(state.covered_failures)} ({@code fa_mcts.py:3253}). */
    default int currentPatternCount() {
        return 0;
    }

    /** {@code len(parent_state.covered_failures)}, or 0 when there is no parent state / attribute ({@code fa_mcts.py:3249-3251}). */
    default int previousPatternCount() {
        return 0;
    }

    /**
     * Number of NEWLY covered pattern ids whose type is a high-risk failure pattern
     * ({@code fa_mcts.py:3270-3283}). NOTE the Python subtlety: {@code newly_covered}
     * is computed only when the parent state exists and has {@code covered_failures};
     * with no parent this must be 0 even if {@code new_patterns > 0}.
     */
    default int newHighRiskPatternCount() {
        return 0;
    }

    /** {@code len(self.failures)}; return 0 to model a None/empty failures list ({@code fa_mcts.py:3256}: truthiness guard). */
    default int totalFailures() {
        return 0;
    }

    /** {@code hasattr(state, "covered_branch_conditions") and self.f_model} ({@code fa_mcts.py:3288}). */
    default boolean trackBranchConditions() {
        return true;
    }

    /** {@code len(state.covered_branch_conditions)} ({@code fa_mcts.py:3294}). */
    default int currentBranchCount() {
        return 0;
    }

    /** {@code len(parent_state.covered_branch_conditions)}, or 0 when absent ({@code fa_mcts.py:3290-3292}). */
    default int previousBranchCount() {
        return 0;
    }

    /** {@code len(self.f_model.boundary_conditions)}; return 0 to model a None/empty list ({@code fa_mcts.py:3297}: truthiness guard). */
    default int totalBoundaryConditions() {
        return 0;
    }

    /** {@code state.has_boundary_tests} ({@code fa_mcts.py:3311}). */
    default boolean hasBoundaryTests() {
        return false;
    }

    /** {@code state.has_boolean_bug_tests} ({@code fa_mcts.py:3313}). */
    default boolean hasBooleanBugTests() {
        return false;
    }

    /** {@code state.has_state_transition_tests} ({@code fa_mcts.py:3315}). */
    default boolean hasStateTransitionTests() {
        return false;
    }

    /** {@code state.has_exception_path_tests} ({@code fa_mcts.py:3317}). */
    default boolean hasExceptionPathTests() {
        return false;
    }

    /**
     * {@code state.stagnant_coverage_iterations} on entry (lazily initialized to 0 in
     * Python, {@code fa_mcts.py:3193-3194}). The calculator MUTATES this counter via
     * {@link #setStagnantCoverageIterations(int)} exactly where Python does
     * (increment/reset at 3205/3209, reset on new patterns at 3264) — the reward
     * function has this deliberate side effect, and the exploration bonus reads the
     * LIVE value afterwards ({@code fa_mcts.py:3324}).
     */
    default int stagnantCoverageIterations() {
        return 0;
    }

    /** Write-back for the stagnation counter; must persist on the state between calls. */
    default void setStagnantCoverageIterations(int value) {
    }
}
