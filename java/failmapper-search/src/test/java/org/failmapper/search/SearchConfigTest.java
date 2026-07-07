package org.failmapper.search;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Pins every SearchConfig default to the contract section 2.2 value (layer A: each of
 * the 39 kernel constants C1-C37/C41-C42 covered), and pins the FaMctsNode inline
 * literals (C8-C17) to the config documentation values so the two cannot drift apart.
 */
class SearchConfigTest {

    private final SearchConfig c = SearchConfig.defaults();

    @Test
    void defaultsMatchPythonBaseline() {
        assertThat(c.explorationWeight).isEqualTo(1.0);                    // C1
        assertThat(c.fWeight).isEqualTo(2.0);                              // C2
        assertThat(c.bugsThreshold).isEqualTo(100);                        // C3
        assertThat(c.maxIterations).isEqualTo(20);                         // C4
        assertThat(c.verifyBugsMode).isEqualTo(VerifyBugsMode.BATCH);      // C5
        assertThat(c.focusOnBugs).isTrue();                                // C6
        assertThat(c.maxFixAttempts).isEqualTo(10);                        // C7
        assertThat(c.noveltyBonus).isEqualTo(0.2);                         // C8
        assertThat(c.visitsDecayFactor).isEqualTo(0.1);                    // C9
        assertThat(c.failurePenaltyFloor).isEqualTo(0.3);                  // C10
        assertThat(c.failurePenaltySlope).isEqualTo(0.2);                  // C10
        assertThat(c.ucbDiversityBonus).isEqualTo(0.15);                   // C11
        assertThat(c.winsDecayOnFailure).isEqualTo(0.9);                   // C12
        assertThat(c.failureRewardSignalThreshold).isEqualTo(0.1);         // C13
        assertThat(c.logicBugRewardIncrement).isEqualTo(1.0);              // C14
        assertThat(c.highRiskPatternRewardIncrement).isEqualTo(0.8);       // C15
        assertThat(c.patternCoverageNormalizer).isEqualTo(10.0);           // C16
        assertThat(c.branchCoverageNormalizer).isEqualTo(20.0);            // C17
        assertThat(c.strategyWeightCutoff).isEqualTo(0.1);                 // C18
        assertThat(c.generalExplorationProbability).isEqualTo(0.2);        // C19
        assertThat(c.uncoveredLineSampleSize).isEqualTo(5);                // C20
        assertThat(c.conditionSampleSize).isEqualTo(2);                    // C21
        assertThat(c.highRiskPatternSampleSize).isEqualTo(2);              // C22
        assertThat(c.rewardWeightsFocusBugs)                               // C23
                .isEqualTo(new RewardWeights(0.2, 0.15, 0.3, 0.20, 0.25, 0.05, 0.05));
        assertThat(c.rewardWeightsCoverageFocus)                           // C24
                .isEqualTo(new RewardWeights(0.35, 0.2, 0.1, 0.0, 0.2, 0.05, 0.05));
        assertThat(c.coverageRewardNormalizer).isEqualTo(100.0);           // C25
        assertThat(c.coverageImprovementScaler).isEqualTo(5.0);            // C26
        assertThat(c.stagnationThreshold).isEqualTo(3);                    // C27
        assertThat(c.explorationBonusCap).isEqualTo(0.5);                  // C28
        assertThat(c.explorationBonusSlope).isEqualTo(0.1);                // C28
        assertThat(c.bugRewardBase).isEqualTo(0.5);                        // C29
        assertThat(c.logicalBugRewardPerBug).isEqualTo(0.4);               // C29
        assertThat(c.highValueBugBonusTier1).isEqualTo(0.3);               // C29
        assertThat(c.highValueBugBonusTier2).isEqualTo(0.4);               // C29
        assertThat(c.patternCoveragePctWeight).isEqualTo(0.8);             // C30
        assertThat(c.newPatternReward).isEqualTo(0.6);                     // C30
        assertThat(c.newHighRiskPatternReward).isEqualTo(0.4);             // C30
        assertThat(c.branchCoverageRatioWeight).isEqualTo(0.5);            // C31
        assertThat(c.newBranchReward).isEqualTo(0.2);                      // C31
        assertThat(c.qualityRewardPerFlag).isEqualTo(0.1);                 // C32
        assertThat(c.fixSuccessReward).isEqualTo(2.0);                     // C33
        assertThat(c.fixFailedReward).isEqualTo(0.1);                      // C33
        assertThat(c.compilationErrorReward).isEqualTo(0.05);              // C33
        assertThat(c.terminationTargetCoverage).isEqualTo(101.0);          // C34
        assertThat(c.noProgressMinIteration).isEqualTo(5);                 // C35
        assertThat(c.noProgressMinHistory).isEqualTo(15);                  // C35
        assertThat(c.noProgressWindow).isEqualTo(5);                       // C35
        assertThat(c.noProgressEpsilon).isEqualTo(0.001);                  // C35
        assertThat(c.updateBestCoverageGuard).isEqualTo(0.8);              // C36
        assertThat(c.highCoverageSaveGuard).isEqualTo(0.9);                // C36
        assertThat(c.highCoverageMetricThreshold).isEqualTo(80.0);         // C37
        assertThat(c.forceExplorationCadence).isEqualTo(3);                // C41
        assertThat(c.selectionRandomFactorScale).isEqualTo(0.3);           // C42
        assertThat(c.selectionDiversityBonus).isEqualTo(0.2);              // C42
        assertThat(c.selectionTopK).isEqualTo(3);                          // C42
        assertThat(c.consecutiveSameExplorationMultiplier).isEqualTo(1.5); // C42
    }

    @Test
    void builderOverridesSingleFieldsLeavingOthersAtDefaults() {
        SearchConfig custom = SearchConfig.builder()
                .maxIterations(50)
                .focusOnBugs(false)
                .verifyBugsMode(VerifyBugsMode.IMMEDIATE)
                .build();
        assertThat(custom.maxIterations).isEqualTo(50);
        assertThat(custom.focusOnBugs).isFalse();
        assertThat(custom.verifyBugsMode).isEqualTo(VerifyBugsMode.IMMEDIATE);
        assertThat(custom.explorationWeight).isEqualTo(1.0);
        assertThat(custom.fWeight).isEqualTo(2.0);
        assertThat(custom.bugsThreshold).isEqualTo(100);
    }

    /**
     * FaMctsNode inlines the C8-C17 literals exactly as the Python source does inside
     * best_child/update. This test derives each literal from observed node behavior and
     * compares it with the config documentation fields, so an edit to either side
     * without the other fails here.
     */
    @Test
    void nodeInlineLiteralsAgreeWithConfigDocumentationFields() {
        // --- C8 novelty + C9 visits decay via ucbScore ---
        FaMctsNode parent = new FaMctsNode("p");
        parent.visits = 1;
        FaMctsNode child = parent.addChild("c", SearchAction.of("a"));
        child.visits = 1; // exploration = 2.0 exactly (2*sqrt(1)); exploitation 0
        child.isNovel = true;
        // score = 2.0 + f * (novelty * 1/(1 + decay*1)); with f=1:
        double bonus = parent.ucbScore(child, 1.0, 1.0) - 2.0;
        // bonus = novelty / (1 + decay); with the documented C8=0.2, C9=0.1: 0.2/1.1
        assertThat(bonus).isCloseTo(c.noveltyBonus / (1.0 + c.visitsDecayFactor), offset());

        // --- C10 penalty floor/slope ---
        child.isNovel = false;
        child.logicBugRewards = 1.0;
        child.consecutiveFailures = 1;
        double withOneFailure = parent.ucbScore(child, 1.0, 1.0) - 2.0;
        // = (1/(1+0.1)) * (1 - slope*1)
        assertThat(withOneFailure)
                .isCloseTo((1.0 / (1.0 + c.visitsDecayFactor)) * (1.0 - c.failurePenaltySlope), offset());
        child.consecutiveFailures = 100;
        double floored = parent.ucbScore(child, 1.0, 1.0) - 2.0;
        assertThat(floored)
                .isCloseTo((1.0 / (1.0 + c.visitsDecayFactor)) * c.failurePenaltyFloor, offset());

        // --- C11 diversity ---
        parent.lastActionType = "other";
        child.logicBugRewards = 0.0;
        child.consecutiveFailures = 0;
        double diversity = parent.ucbScore(child, 1.0, 1.0) - 2.0;
        assertThat(diversity).isCloseTo(c.ucbDiversityBonus, offset());

        // --- C12 wins decay, C13 threshold via update ---
        FaMctsNode n = new FaMctsNode("s");
        n.update(0.0, null, -1, -1); // reward 0 < threshold -> wins = 0*decay = 0, cf=1
        assertThat(n.consecutiveFailures).isEqualTo(1);
        n = new FaMctsNode("s");
        n.update(c.failureRewardSignalThreshold, null, -1, -1); // == threshold -> success
        assertThat(n.consecutiveFailures).isZero();
        n = new FaMctsNode("s");
        n.update(1.0, null, -1, -1, true);
        assertThat(n.wins).isCloseTo(c.winsDecayOnFailure, offset()); // 1.0 * 0.9

        // --- C14/C15 increments, C16/C17 normalizers ---
        n = new FaMctsNode("s");
        n.update(0.5, "logical_x", -1, -1);
        assertThat(n.logicBugRewards).isCloseTo(c.logicBugRewardIncrement, offset());
        n.update(0.5, "high_risk_x", -1, -1);
        assertThat(n.highRiskPatternRewards).isCloseTo(c.highRiskPatternRewardIncrement, offset());
        n = new FaMctsNode("s");
        n.update(0.5, null, 1, 0);
        assertThat(n.failureCoverageRewards).isCloseTo(1.0 / c.patternCoverageNormalizer, offset());
        n = new FaMctsNode("s");
        n.update(0.5, null, 0, 1);
        assertThat(n.failureCoverageRewards).isCloseTo(1.0 / c.branchCoverageNormalizer, offset());
    }

    private static org.assertj.core.data.Offset<Double> offset() {
        return org.assertj.core.data.Offset.offset(1e-12);
    }
}
