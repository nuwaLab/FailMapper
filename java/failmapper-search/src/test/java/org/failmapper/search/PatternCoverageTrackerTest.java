package org.failmapper.search;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.failmapper.core.model.FailureScenario;
import org.failmapper.core.model.RiskLevel;
import org.junit.jupiter.api.Test;

/** F9 — track_logic_scenario_coverage (test_state.py:387-546), formula-exact per I4 v1. */
class PatternCoverageTrackerTest {

    private final PatternCoverageTracker tracker = new PatternCoverageTracker();

    private static FailureScenario pattern(String type, int line, RiskLevel risk) {
        return new FailureScenario(type, null, line, risk, "", "");
    }

    private static FaTestState stateWith(String testCode, FailureScenario... failures) {
        return new FaTestState(testCode, null, List.of(failures));
    }

    @Test
    void thresholdsByRisk() {
        // C38: high 0.8 / medium 0.6 / low 0.5; default 0.6 ('critical' unmapped)
        assertEquals(0.8, PatternCoverageTracker.thresholdFor("high"), 0.0);
        assertEquals(0.6, PatternCoverageTracker.thresholdFor("medium"), 0.0);
        assertEquals(0.5, PatternCoverageTracker.thresholdFor("low"), 0.0);
        assertEquals(0.6, PatternCoverageTracker.thresholdFor("critical"), 0.0);
        assertEquals(0.6, PatternCoverageTracker.thresholdFor("bogus"), 0.0);
    }

    @Test
    void keywordBoostAloneBelowMediumThreshold() {
        // boundary_condition keywords: ["boundary", "edge case", "边界条件"]; the code
        // matches 2 → keyword boost = min(0.5, 0.1*2) = 0.2 < 0.6 → not covered.
        FaTestState s = stateWith("void t() { /* boundary edge case */ }",
                pattern("boundary_condition", 12, RiskLevel.MEDIUM));
        tracker.track(s);
        assertFalse(s.coveredFailures.contains("boundary_condition_12"));
        assertEquals(0.2, s.coveredFailuresScores.get("boundary_condition_12"), 1e-12);
    }

    @Test
    void directLineMatchPlusKeywordCovers() {
        // 0.7 (contains "line 12") + min(0.5, 0.1*1) ("boundary") = 0.8 >= 0.6 → covered
        FaTestState s = stateWith("void t() { /* covers line 12 boundary */ }",
                pattern("boundary_condition", 12, RiskLevel.MEDIUM));
        tracker.track(s);
        assertTrue(s.coveredFailures.contains("boundary_condition_12"));
        assertEquals(0.8, s.coveredFailuresScores.get("boundary_condition_12"), 1e-12);
    }

    @Test
    void keywordBoostCapsAtHalf() {
        // null_handling has 5 keywords: null/nullpointer/nullpointerexception/assertnull/
        // nullcheck — all 5 substrings present → min(0.5, 0.1*5) = 0.5 exactly.
        FaTestState s = stateWith("nullpointerexception assertnull nullcheck",
                pattern("null_handling", 3, RiskLevel.MEDIUM));
        tracker.track(s);
        // 0.5 < 0.6 → not covered at medium risk
        assertFalse(s.coveredFailures.contains("null_handling_3"));
        assertEquals(0.5, s.coveredFailuresScores.get("null_handling_3"), 1e-12);
    }

    @Test
    void bugEvidenceAndMethodNameReachHighThreshold() {
        // high risk threshold 0.8:
        //   keyword: test code contains "null" → 0.1
        //   bug evidence: logical bug error contains keyword "null" → +0.4 (first match only)
        //   method name: "testNullHandling" contains "nullhandling" (type sans '_') → +0.3
        //   total = 0.8 >= 0.8 → covered
        FaTestState s = stateWith("void testNullHandling() { obj.doIt(null); }",
                pattern("null_handling", 3, RiskLevel.HIGH));
        s.testMethods.add(new TestMethod("testNullHandling", "obj.doIt(null);"));
        DetectedBug bug = new DetectedBug("runtime_error", "",
                "testNullHandling", "NullPointerException at Foo", "medium");
        s.logicalBugs.add(bug);
        tracker.track(s);
        assertTrue(s.coveredFailures.contains("null_handling_3"));
        assertEquals(0.8, s.coveredFailuresScores.get("null_handling_3"), 1e-12);
    }

    @Test
    void bugEvidenceCountsOnceDespiteMultipleBugs() {
        // The +0.4 boost breaks after the FIRST matching bug (test_state.py:487-490).
        FaTestState s = stateWith("",
                pattern("null_handling", 3, RiskLevel.LOW));
        s.logicalBugs.add(new DetectedBug("t", "", "a", "NullPointerException", "m"));
        s.logicalBugs.add(new DetectedBug("t", "", "b", "null again", "m"));
        tracker.track(s);
        // keywords in "" → 0; evidence 0.4; method names none → 0.4 total
        assertEquals(0.4, s.coveredFailuresScores.get("null_handling_3"), 1e-12);
        assertFalse(s.coveredFailures.contains("null_handling_3")); // 0.4 < 0.5 (low)
    }

    @Test
    void unknownPatternTypeUsesFallbackKeywords() {
        // Fallback keyword list is [type, "bug", "test", "error"] (test_state.py:465);
        // code contains "test" → 1 match → 0.1.
        FaTestState s = stateWith("void testStuff() {}",
                pattern("weird_thing", 1, RiskLevel.LOW));
        tracker.track(s);
        assertEquals(0.1, s.coveredFailuresScores.get("weird_thing_1"), 1e-12);
        // But the BUG-evidence fallback is the EMPTY list (test_state.py:485): a bug
        // containing "test" adds nothing.
        FaTestState s2 = stateWith("void testStuff() {}",
                pattern("weird_thing", 1, RiskLevel.LOW));
        s2.logicalBugs.add(new DetectedBug("t", "test failure text", "a", "", "m"));
        tracker.track(s2);
        assertEquals(0.1, s2.coveredFailuresScores.get("weird_thing_1"), 1e-12);
    }

    @Test
    void carriedForwardCoverageDecaysAwayWithoutEvidence() {
        // Real "pattern becomes uncovered" path: the covered SET is carried forward
        // (D6) but the score map is not → current_confidence = 0, boost = 0 →
        // new_confidence 0 < threshold, was_covered → REMOVED.
        FaTestState s = stateWith("", pattern("copy_paste", 8, RiskLevel.MEDIUM));
        s.coveredFailures.add("copy_paste_8");
        tracker.track(s);
        assertFalse(s.coveredFailures.contains("copy_paste_8"));
        assertEquals(0.0, s.coveredFailuresScores.get("copy_paste_8"), 0.0);
    }

    @Test
    void decayIsTransientFinalScoreUsesPreDecayBase() {
        // Iron-rule subtlety (test_state.py:426-516): decay writes current*0.95 but the
        // final store is min(1.0, PRE-decay + boost). Pre-seed 0.65, no evidence:
        //   decay writes 0.6175 (>= 0.6 → stays covered mid-loop)
        //   final score = 0.65 + 0 = 0.65 (the decayed value is overwritten)
        FaTestState s = stateWith("", pattern("boundary_condition", 12, RiskLevel.MEDIUM));
        s.coveredFailuresScores.put("boundary_condition_12", 0.65);
        s.coveredFailures.add("boundary_condition_12");
        tracker.track(s);
        assertEquals(0.65, s.coveredFailuresScores.get("boundary_condition_12"), 1e-12);
        assertTrue(s.coveredFailures.contains("boundary_condition_12"));
    }

    @Test
    void midLoopDecayRemovalThenReAdd() {
        // Pre-seed 0.61 (covered): decay 0.61*0.95 = 0.5795 < 0.6 → removed mid-loop;
        // final new_confidence = 0.61 >= 0.6 → re-added. Net: covered, score 0.61.
        FaTestState s = stateWith("", pattern("boundary_condition", 12, RiskLevel.MEDIUM));
        s.coveredFailuresScores.put("boundary_condition_12", 0.61);
        s.coveredFailures.add("boundary_condition_12");
        tracker.track(s);
        assertTrue(s.coveredFailures.contains("boundary_condition_12"));
        assertEquals(0.61, s.coveredFailuresScores.get("boundary_condition_12"), 1e-12);
    }

    @Test
    void confidenceCapsAtOne() {
        // Pre-seed 0.9 + line match 0.7 → min(1.0, 1.6) = 1.0
        FaTestState s = stateWith("line 12", pattern("boundary_condition", 12, RiskLevel.MEDIUM));
        s.coveredFailuresScores.put("boundary_condition_12", 0.9);
        s.coveredFailures.add("boundary_condition_12");
        tracker.track(s);
        assertEquals(1.0, s.coveredFailuresScores.get("boundary_condition_12"), 0.0);
    }

    @Test
    void chineseLineMarkerAlsoMatches() {
        // test_state.py:442 also accepts "行 {line}" — kept verbatim.
        FaTestState s = stateWith("// 行 7 检查", pattern("copy_paste", 7, RiskLevel.LOW));
        tracker.track(s);
        // 0.7 line match + 0 keywords (copy/duplicate/paste absent) = 0.7 >= 0.5 → covered
        assertTrue(s.coveredFailures.contains("copy_paste_7"));
        assertEquals(0.7, s.coveredFailuresScores.get("copy_paste_7"), 1e-12);
    }

    @Test
    void noFailuresIsNoOp() {
        FaTestState s = new FaTestState("code", null, null);
        tracker.track(s); // null failures
        assertTrue(s.coveredFailuresScores.isEmpty());
        FaTestState s2 = new FaTestState("code", null, List.of());
        tracker.track(s2); // empty failures
        assertTrue(s2.coveredFailuresScores.isEmpty());
    }
}
