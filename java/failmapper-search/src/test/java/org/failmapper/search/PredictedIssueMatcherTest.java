package org.failmapper.search;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/** D13 — _bug_matches_predicted_issue (fa_mcts.py:3363-3404). */
class PredictedIssueMatcherTest {

    private final PredictedIssueMatcher matcher = new PredictedIssueMatcher();

    private static DetectedBug bug(String testMethod, String error, String description) {
        return new DetectedBug("assertion_failure", description, testMethod, error, "medium");
    }

    @Test
    void matchesOnNameOverlapAndKeywordEvidence() {
        // simplified("testCalculateTotal") = "CalculateTotal" ⊇ "calculateTotal" (ci).
        // Keywords (\b\w{4,}\b of lower desc): {calculatetotal, method, drops, final,
        // item, when, summing, values} → 8 → threshold min(2, 8//2) = 2.
        // Error text contains "values", "final", "item" → 3 >= 2 → match.
        DetectedBug b = bug("testCalculateTotal",
                "expected sum of values but final item missing", "");
        BusinessLogicIssue issue = new BusinessLogicIssue("calculation_error", "calculateTotal",
                "The calculateTotal method drops the final item when summing values", 0.8);
        assertTrue(matcher.matches(b, issue));
    }

    @Test
    void failsWithoutMethodOverlap() {
        DetectedBug b = bug("testSomethingElse", "values final item", "");
        BusinessLogicIssue issue = new BusinessLogicIssue("calculation_error", "calculateTotal",
                "The calculateTotal method drops the final item when summing values", 0.8);
        assertFalse(matcher.matches(b, issue));
    }

    @Test
    void failsBelowKeywordThreshold() {
        // Only "values" appears → 1 < 2.
        DetectedBug b = bug("testCalculateTotal", "wrong values", "");
        BusinessLogicIssue issue = new BusinessLogicIssue("calculation_error", "calculateTotal",
                "The calculateTotal method drops the final item when summing values", 0.8);
        assertFalse(matcher.matches(b, issue));
    }

    @Test
    void emptyMethodNamesNeverMatch() {
        BusinessLogicIssue issue = new BusinessLogicIssue("t", "calc", "long description words here", 0.5);
        assertFalse(matcher.matches(bug("", "x", ""), issue));
        assertFalse(matcher.matches(bug("testCalc", "x", ""),
                new BusinessLogicIssue("t", "", "long description words here", 0.5)));
        assertFalse(matcher.matches(bug("testCalc", "x", ""),
                new BusinessLogicIssue("t", null, "long description words here", 0.5)));
    }

    @Test
    void noKeywordsInDescriptionNeverMatches() {
        // All words < 4 chars → keyword set empty → False (fa_mcts.py:3396-3397).
        DetectedBug b = bug("testCalc", "abc", "");
        BusinessLogicIssue issue = new BusinessLogicIssue("t", "calc", "a bb ccc", 0.5);
        assertFalse(matcher.matches(b, issue));
    }

    @Test
    void singleKeywordQuirkThresholdZeroAlwaysMatches() {
        // Python quirk kept: 1 keyword → min(2, 1//2) = 0 → `matches >= 0` is ALWAYS
        // true once the method names overlap, even with ZERO keyword hits.
        DetectedBug b = bug("testCalc", "completely unrelated text", "");
        BusinessLogicIssue issue = new BusinessLogicIssue("t", "calc", "boundary", 0.5);
        assertTrue(matcher.matches(b, issue));
    }

    @Test
    void testPrefixStrippedOnlyOnce() {
        // "testtestFoo" → simplified "testFoo"; issue method "foo" ⊆ "testfoo" → overlap.
        DetectedBug b = bug("testtestFoo", "boundary boundary", "");
        BusinessLogicIssue issue = new BusinessLogicIssue("t", "foo", "boundary check logic", 0.5);
        // keywords {boundary, check, logic} → threshold min(2, 1) = 1; "boundary" hits → match
        assertTrue(matcher.matches(b, issue));
    }

    @Test
    void keywordsDedupedAsSet() {
        // "values values values" → set {values} (size 1) → threshold min(2, 0) = 0.
        DetectedBug b = bug("testCalc", "no hits at all", "");
        BusinessLogicIssue issue = new BusinessLogicIssue("t", "calc", "values values values", 0.5);
        assertTrue(matcher.matches(b, issue)); // threshold 0 quirk again
    }

    @Test
    void errorAndDescriptionBothSearched() {
        // bug_error = error + " " + description (fa_mcts.py:3391).
        DetectedBug b = bug("testCalc", "final", "values item");
        BusinessLogicIssue issue = new BusinessLogicIssue("t", "calc",
                "drops the final item when summing values", 0.5);
        // keywords {drops, final, item, when, summing, values} → threshold 2;
        // hits: final, values, item → 3 → match
        assertTrue(matcher.matches(b, issue));
    }
}
