package org.failmapper.search;

import org.failmapper.core.model.BoundaryCondition;
import org.failmapper.core.model.CoverageSnapshot;

/**
 * Branch-condition coverage tracking — REGISTERED REPLACEMENT I3 (contract section 4)
 * of formula F10 ({@code track_branch_condition_coverage},
 * {@code test_state.py:548-631}).
 *
 * <p><b>Replaced original (F10, documented for the record, NOT ported):</b> a condition
 * counted as covered when (a) the test method NAME contained the condition's method
 * name, or the test content contained {@code "line N"}, or an {@code if_condition} had
 * {@code "condition"} in a method name / a loop had {@code "loop"} in a method name
 * ({@code test_state.py:578-592}); or (b) {@code if_condition} with
 * {@code has_boolean_bug_tests} / loop with {@code has_boundary_tests}
 * ({@code test_state.py:595-601}); or (c) a related logical-bug type was present
 * ({@code test_state.py:605-612}); with a final fallback assuming
 * {@code min(len(conditions), 2)} conditions covered whenever no condition matched but
 * {@code has_assertions and len(test_methods) > 2} ({@code test_state.py:622-629}).
 *
 * <p><b>Registered replacement (I3, "最大的一处有意变形"):</b> the reward signal moves
 * from keyword guessing to JaCoCo FACT — a condition is covered iff its line was
 * actually executed, refined by per-line branch data when available:
 * <pre>
 * covered(condition) = condition.line NOT IN coverage.uncoveredLineNumbers
 *                      AND (branchData present at line ? at least one branch covered : true)
 * </pre>
 * The id vocabulary is unchanged: {@code "{method}_{line}"}
 * ({@link BoundaryCondition#conditionId()}), so every downstream consumer (F4/C17
 * accrual, F6 branch reward, D3 uncovered-condition counting, risk metrics) is
 * untouched. Layer-C attribution: any behavior delta traces to this registered input
 * improvement, not to formula drift.
 */
public final class BranchCoverageTracker {

    /**
     * Optional per-line branch detail (JaCoCo {@code ICounter} level, wired by the
     * coverage module). {@link CoverageSnapshot} alone carries only line facts, in which
     * case the line-execution test decides by itself.
     */
    public interface LineBranchData {

        /** True if JaCoCo reports branch counters for this line. */
        boolean hasBranchData(int line);

        /** True if at least one branch at this line was covered. */
        boolean anyBranchCovered(int line);
    }

    /** I3 predicate without per-line branch detail: line-execution fact only. */
    public boolean conditionCovered(BoundaryCondition condition, CoverageSnapshot coverage) {
        return conditionCovered(condition, coverage, null);
    }

    /** I3 predicate; see class doc for the registered formula. Null coverage = no facts = not covered. */
    public boolean conditionCovered(BoundaryCondition condition, CoverageSnapshot coverage,
                                    LineBranchData branchData) {
        if (condition == null || coverage == null) {
            return false;
        }
        if (coverage.uncoveredLineNumbers() != null
                && coverage.uncoveredLineNumbers().contains(condition.line())) {
            return false;
        }
        if (branchData != null && branchData.hasBranchData(condition.line())) {
            return branchData.anyBranchCovered(condition.line());
        }
        return true;
    }

    /** Tracking pass without per-line branch detail. */
    public void track(FaTestState state, CoverageSnapshot coverage) {
        track(state, coverage, null);
    }

    /**
     * Tracking pass over {@code state.fModel.boundaryConditions}, mutating
     * {@code state.coveredBranchConditions}. Structure kept from the Python method:
     * <ul>
     *   <li>no failure model / no boundary conditions → return
     *       ({@code test_state.py:550-552});</li>
     *   <li>ids already covered are SKIPPED, keeping the carried-forward covered set
     *       monotone across states ({@code test_state.py:570-572} — carried sets are
     *       copied forward per D6, {@code fa_mcts.py:2754-2755});</li>
     *   <li>the F10 keyword heuristics and the "assume 2 covered" fallback are replaced
     *       by the I3 predicate — see class doc.</li>
     * </ul>
     */
    public void track(FaTestState state, CoverageSnapshot coverage, LineBranchData branchData) {
        if (state.fModel == null || state.fModel.boundaryConditions() == null) {
            return;
        }
        for (BoundaryCondition condition : state.fModel.boundaryConditions()) {
            String conditionId = condition.conditionId();
            if (state.coveredBranchConditions.contains(conditionId)) {
                continue;
            }
            if (conditionCovered(condition, coverage, branchData)) {
                state.coveredBranchConditions.add(conditionId);
            }
        }
    }
}
