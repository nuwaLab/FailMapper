package org.failmapper.search;

import java.util.List;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

/**
 * F5-F8 tests for {@link RewardCalculator} against hand-computed values
 * (fa_mcts.py:3156-3360). Each case shows the full arithmetic in comments.
 */
class RewardCalculatorTest {

    private static final double EPS = 1e-9;

    /** Mutable inputs fixture; defaults mirror the interface's attribute-absent semantics. */
    private static final class Inputs implements RewardInputs {
        double coverage;
        Double parentCoverage;
        boolean hasCompilationErrors;
        String actionType;
        boolean hadErrorsBefore;
        boolean hasDetectedBugs;
        List<Double> matchedConfidences = List.of();
        boolean hasLogicalBugs;
        int logicalBugCount;
        List<String> logicalBugTypes = List.of();
        int currentPatternCount;
        int previousPatternCount;
        int newHighRiskPatternCount;
        int totalFailures;
        int currentBranchCount;
        int previousBranchCount;
        int totalBoundaryConditions;
        boolean hasBoundaryTests;
        boolean hasBooleanBugTests;
        boolean hasStateTransitionTests;
        boolean hasExceptionPathTests;
        int stagnantCoverageIterations;

        @Override public double coverage() { return coverage; }
        @Override public Double parentCoverage() { return parentCoverage; }
        @Override public boolean hasCompilationErrors() { return hasCompilationErrors; }
        @Override public String actionType() { return actionType; }
        @Override public boolean hadErrorsBefore() { return hadErrorsBefore; }
        @Override public boolean hasDetectedBugs() { return hasDetectedBugs; }
        @Override public List<Double> matchedBusinessLogicIssueConfidences() { return matchedConfidences; }
        @Override public boolean hasLogicalBugs() { return hasLogicalBugs; }
        @Override public int logicalBugCount() { return logicalBugCount; }
        @Override public List<String> logicalBugTypes() { return logicalBugTypes; }
        @Override public int currentPatternCount() { return currentPatternCount; }
        @Override public int previousPatternCount() { return previousPatternCount; }
        @Override public int newHighRiskPatternCount() { return newHighRiskPatternCount; }
        @Override public int totalFailures() { return totalFailures; }
        @Override public int currentBranchCount() { return currentBranchCount; }
        @Override public int previousBranchCount() { return previousBranchCount; }
        @Override public int totalBoundaryConditions() { return totalBoundaryConditions; }
        @Override public boolean hasBoundaryTests() { return hasBoundaryTests; }
        @Override public boolean hasBooleanBugTests() { return hasBooleanBugTests; }
        @Override public boolean hasStateTransitionTests() { return hasStateTransitionTests; }
        @Override public boolean hasExceptionPathTests() { return hasExceptionPathTests; }
        @Override public int stagnantCoverageIterations() { return stagnantCoverageIterations; }
        @Override public void setStagnantCoverageIterations(int value) { this.stagnantCoverageIterations = value; }
    }

    private final RewardCalculator calculator = new RewardCalculator(SearchConfig.defaults());

    // ---------------------------------------------------------------- F5 short-circuits

    @Test
    void nullStateReturnsZero() {
        // fa_mcts.py:3167-3168: if not state: return 0.0
        assertThat(calculator.calculate(null)).isEqualTo(0.0);
    }

    @Test
    void fixCompilationSuccessReturnsTwo() {
        Inputs in = new Inputs();
        in.actionType = "fix_compilation_errors";
        in.hadErrorsBefore = true;
        in.hasCompilationErrors = false;
        in.coverage = 90.0; // must be ignored — short-circuit
        assertThat(calculator.calculate(in)).isEqualTo(2.0); // C33
    }

    @Test
    void fixCompilationStillBrokenReturnsPointOne() {
        Inputs in = new Inputs();
        in.actionType = "fix_compilation_errors";
        in.hadErrorsBefore = true;
        in.hasCompilationErrors = true;
        assertThat(calculator.calculate(in)).isEqualTo(0.1); // C33
    }

    @Test
    void anyCompilationErrorReturnsPointZeroFive() {
        Inputs in = new Inputs();
        in.actionType = "boundary_test";
        in.hasCompilationErrors = true;
        in.coverage = 75.0;
        assertThat(calculator.calculate(in)).isEqualTo(0.05); // C33
    }

    @Test
    void fixActionWithNoErrorsBeforeAndNoneNowFallsThroughToNormalReward() {
        // Contract F5 note: "0.1 if has_errors else FALLTHROUGH" — a fix action on a
        // clean state is scored normally.
        Inputs in = new Inputs();
        in.actionType = "fix_compilation_errors";
        in.hadErrorsBefore = false;
        in.hasCompilationErrors = false;
        in.coverage = 50.0;
        // coverage_reward = 50/100 = 0.5; every other component 0.
        // combined (F7) = 0.2*0.5 = 0.10
        assertThat(calculator.calculate(in)).isCloseTo(0.10, within(EPS));
    }

    // ---------------------------------------------------------------- F6/F7 components

    @Test
    void coverageImprovementScaledByFive() {
        Inputs in = new Inputs();
        in.coverage = 45.0;
        in.parentCoverage = 40.0;
        // coverage_reward = 45/100 = 0.45
        // delta = 5.0 > 0 -> improvement = 5/5 = 1.0 (C26); stagnant counter reset to 0
        // combined (F7) = 0.2*0.45 + 0.15*1.0 = 0.09 + 0.15 = 0.24
        assertThat(calculator.calculate(in)).isCloseTo(0.24, within(EPS));
        assertThat(in.stagnantCoverageIterations).isZero();
    }

    @Test
    void bugRichStateFullComposition() {
        Inputs in = new Inputs();
        in.coverage = 60.0;                      // coverage_reward = 0.6; no parent -> improvement 0
        in.hasDetectedBugs = true;
        in.matchedConfidences = List.of(0.8);    // business_logic_reward = 1.0*0.8 = 0.8
        in.hasLogicalBugs = true;
        in.logicalBugCount = 2;                  // bug_reward = 0.5 + 0.4*2 = 1.3
        in.logicalBugTypes = List.of("boundary_error", "resource_leak");
        //                                          +0.3 (tier1) +0.4 (tier2) -> bug_reward = 2.0
        in.currentPatternCount = 4;
        in.previousPatternCount = 1;
        in.totalFailures = 8;                    // (4/8)*0.8 = 0.4
        //                                          new = 3 -> +3*0.6 = 1.8
        in.newHighRiskPatternCount = 1;          //  +0.4*1 -> failure_coverage_reward = 2.6
        in.currentBranchCount = 6;
        in.previousBranchCount = 6;              // new branches = 0
        in.totalBoundaryConditions = 12;         // branch_reward = (6/12)*0.5 = 0.25
        in.hasBoundaryTests = true;
        in.hasExceptionPathTests = true;         // quality_reward = 0.2
        // combined (F7, focus_on_bugs=True):
        //   0.2*0.6   = 0.12
        // + 0.15*0    = 0
        // + 0.3*2.0   = 0.60
        // + 0.20*0.8  = 0.16
        // + 0.25*2.6  = 0.65
        // + 0.05*0.25 = 0.0125
        // + 0.05*0.2  = 0.01
        // + 0 (no exploration bonus)            = 1.5525
        assertThat(calculator.calculate(in)).isCloseTo(1.5525, within(EPS));
    }

    @Test
    void coverageFocusVariantDropsBusinessLogicTerm() {
        Inputs in = new Inputs();
        in.coverage = 60.0;
        in.hasDetectedBugs = true;
        in.matchedConfidences = List.of(0.8); // MUST be ignored by F8
        in.hasLogicalBugs = true;
        in.logicalBugCount = 2;
        in.logicalBugTypes = List.of("boundary_error", "resource_leak"); // bug_reward = 2.0
        in.currentPatternCount = 4;
        in.previousPatternCount = 1;
        in.totalFailures = 8;
        in.newHighRiskPatternCount = 1;       // failure_coverage_reward = 2.6
        in.currentBranchCount = 6;
        in.previousBranchCount = 6;
        in.totalBoundaryConditions = 12;      // branch_reward = 0.25
        in.hasBoundaryTests = true;
        in.hasExceptionPathTests = true;      // quality_reward = 0.2

        RewardCalculator coverageFocus =
                new RewardCalculator(SearchConfig.builder().focusOnBugs(false).build());
        // combined (F8): 0.35*0.6 + 0.2*0 + 0.1*2.0 + 0.2*2.6 + 0.05*0.25 + 0.05*0.2
        //              = 0.21 + 0 + 0.20 + 0.52 + 0.0125 + 0.01 = 0.9525
        assertThat(coverageFocus.calculate(in)).isCloseTo(0.9525, within(EPS));
    }

    @Test
    void detectedBugsGateBothBugAndBusinessLogicRewards() {
        // fa_mcts.py:3218: matched confidences and the 0.5 base accrue ONLY inside
        // `if state.detected_bugs:` — without detected bugs both stay 0.
        Inputs in = new Inputs();
        in.coverage = 50.0;
        in.hasDetectedBugs = false;
        in.matchedConfidences = List.of(0.9);
        in.hasLogicalBugs = true;
        in.logicalBugCount = 3;
        in.logicalBugTypes = List.of("boundary_error");
        // combined = 0.2*0.5 = 0.1 only.
        assertThat(calculator.calculate(in)).isCloseTo(0.10, within(EPS));
    }

    // ---------------------------------------------------------------- stagnation

    @Test
    void stagnationBonusAfterMoreThanThreeStagnantIterations() {
        Inputs in = new Inputs();
        in.coverage = 50.0;
        in.parentCoverage = 50.0;            // delta = 0 -> NOT an improvement (strict >)
        in.stagnantCoverageIterations = 3;   // increments to 4; 4 > 3 (C27) -> stagnant
        // exploration_bonus = min(0.5, 0.1*4) = 0.4 (C28), added UNWEIGHTED (F7 note)
        // combined = 0.2*0.5 + 0.4 = 0.5
        assertThat(calculator.calculate(in)).isCloseTo(0.5, within(EPS));
        assertThat(in.stagnantCoverageIterations).isEqualTo(4);
    }

    @Test
    void stagnationBonusIsCappedAtPointFive() {
        Inputs in = new Inputs();
        in.coverage = 50.0;
        in.parentCoverage = 50.0;
        in.stagnantCoverageIterations = 9;   // -> 10; bonus = min(0.5, 1.0) = 0.5
        assertThat(calculator.calculate(in)).isCloseTo(0.2 * 0.5 + 0.5, within(EPS));
    }

    @Test
    void exactlyThreeStagnantIterationsIsNotStagnantYet() {
        Inputs in = new Inputs();
        in.coverage = 50.0;
        in.parentCoverage = 50.0;
        in.stagnantCoverageIterations = 2;   // -> 3; NOT > 3 -> no bonus (strict, C27)
        assertThat(calculator.calculate(in)).isCloseTo(0.10, within(EPS));
        assertThat(in.stagnantCoverageIterations).isEqualTo(3);
    }

    @Test
    void newPatternDiscoveryResetsCounterAndZeroesTheBonusMagnitude() {
        // The subtle fa_mcts.py ordering: is_stagnant is decided at 3210-3211, the
        // new-pattern block at 3264 then resets the counter, and the bonus at 3324
        // reads the LIVE counter -> min(0.5, 0.1*0) = 0.0 despite is_stagnant=True.
        Inputs in = new Inputs();
        in.coverage = 50.0;
        in.parentCoverage = 50.0;            // delta 0 -> counter 4 -> 5 -> stagnant
        in.stagnantCoverageIterations = 4;
        in.currentPatternCount = 2;
        in.previousPatternCount = 0;         // new_patterns = 2 -> counter reset to 0
        in.totalFailures = 0;                // empty failures list -> pct term skipped (N13)
        // failure_coverage_reward = 2*0.6 = 1.2; exploration_bonus = 0.1*0 = 0.0
        // combined = 0.2*0.5 + 0.25*1.2 = 0.1 + 0.3 = 0.4
        assertThat(calculator.calculate(in)).isCloseTo(0.4, within(EPS));
        assertThat(in.stagnantCoverageIterations).isZero();
    }

    @Test
    void coverageRegressionAlsoIncrementsStagnation() {
        Inputs in = new Inputs();
        in.coverage = 38.0;
        in.parentCoverage = 40.0;            // delta = -2 -> counter 0 -> 1; not stagnant
        // improvement stays 0 (only positive deltas scale — C26)
        // combined = 0.2*0.38 = 0.076
        assertThat(calculator.calculate(in)).isCloseTo(0.076, within(EPS));
        assertThat(in.stagnantCoverageIterations).isEqualTo(1);
    }

    // ---------------------------------------------------------------- guards

    @Test
    void zeroTotalsSkipRatioTermsButNewCountsStillReward() {
        Inputs in = new Inputs();
        in.coverage = 0.0;
        in.currentPatternCount = 3;
        in.previousPatternCount = 3;         // no new patterns
        in.totalFailures = 0;                // `if self.failures:` falsy -> no pct term
        in.currentBranchCount = 5;
        in.previousBranchCount = 2;          // 3 new branches -> +3*0.2 = 0.6
        in.totalBoundaryConditions = 0;      // falsy -> no ratio term
        // branch_reward = 0.6; combined = 0.05*0.6 = 0.03
        assertThat(calculator.calculate(in)).isCloseTo(0.03, within(EPS));
    }

    @Test
    void qualityFlagsAddTenthEach() {
        Inputs in = new Inputs();
        in.coverage = 0.0;
        in.hasBoundaryTests = true;
        in.hasBooleanBugTests = true;
        in.hasStateTransitionTests = true;
        in.hasExceptionPathTests = true;
        // quality_reward = 0.4 (C32); combined = 0.05*0.4 = 0.02
        assertThat(calculator.calculate(in)).isCloseTo(0.02, within(EPS));
    }
}
