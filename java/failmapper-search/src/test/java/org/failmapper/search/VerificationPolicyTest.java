package org.failmapper.search;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/** D10 pre-filters + F15 confidence math (verify_bug_with_llm.py:32-75, 131-153, 159-223). */
class VerificationPolicyTest {

    private final VerificationPolicy policy = new VerificationPolicy();

    // ---------------- D10 pre-filters ----------------

    @Test
    void nullExpectationInNullNamedTestIsFalsePositive() {
        VerificationPolicy.Verdict v = policy.preFilter("assertion_failure",
                "org.opentest4j.AssertionFailedError: expected: <null> but was: <5>",
                0.5, "public void testNullInput() { assertNull(x); }");
        assertFalse(v.isRealBug());
        assertEquals(0.9, v.confidence(), 0.0);
    }

    @Test
    void emptyListAndEmptyStringExpectationsAlsoPreFiltered() {
        assertFalse(policy.preFilter("assertion_failure",
                "expected: <[]> but was: <[1]>", 0.5, "testEmptyList").isRealBug());
        assertFalse(policy.preFilter("assertion_failure",
                "expected: <> but was: <x>", 0.5, "testEmptyString").isRealBug());
    }

    @Test
    void trivialExpectationWithoutNullEmptyNameIsNotPreFiltered() {
        // Method text lacks "null"/"empty" → filter 1 does not fire; nothing else does.
        assertNull(policy.preFilter("assertion_failure",
                "expected: <null> but was: <5>", 0.5, "testBasicCase"));
    }

    @Test
    void memoryErrorsAreRealAt095() {
        assertTrue(policy.preFilter("memory_error", "", 0.5, "testX").isRealBug());
        assertEquals(0.95, policy.preFilter("memory_error", "", 0.5, "testX").confidence(), 0.0);
        assertEquals(0.95, policy.preFilter("runtime_error",
                "java.lang.OutOfMemoryError: heap", 0.5, "testX").confidence(), 0.0);
        assertEquals(0.95, policy.preFilter("runtime_error",
                "java.lang.StackOverflowError", 0.5, "testX").confidence(), 0.0);
    }

    @Test
    void assertionFilterRunsBeforeMemoryFilter() {
        // Source order: the assertion FP check precedes the memory check — an error text
        // hitting both resolves to FALSE POSITIVE 0.9.
        VerificationPolicy.Verdict v = policy.preFilter("assertion_failure",
                "expected: <null> but was: <OutOfMemoryError>", 0.5, "testNullThing");
        assertFalse(v.isRealBug());
        assertEquals(0.9, v.confidence(), 0.0);
    }

    @Test
    void highIncomingConfidenceSkipsVerification() {
        VerificationPolicy.Verdict v = policy.preFilter("boundary_error", "", 0.95, "testX");
        assertTrue(v.isRealBug());
        assertEquals(0.95, v.confidence(), 0.0);
        // strictly > 0.9: exactly 0.9 does NOT skip
        assertNull(policy.preFilter("boundary_error", "", 0.9, "testX"));
    }

    // ---------------- defaults ----------------

    @Test
    void apiFailureDefaultThresholdIsStrictPointSeven() {
        assertTrue(policy.apiFailureDefault(0.8).isRealBug());
        assertFalse(policy.apiFailureDefault(0.7).isRealBug()); // strict >
        assertEquals(0.7, policy.apiFailureDefault(0.7).confidence(), 0.0);
        assertTrue(policy.insufficientInput(0.75).isRealBug());
        assertFalse(policy.insufficientInput(0.5).isRealBug());
    }

    @Test
    void insufficientResponseUnderFiftyChars() {
        assertTrue(policy.insufficientResponse(null));
        assertTrue(policy.insufficientResponse("x".repeat(49)));
        assertFalse(policy.insufficientResponse("x".repeat(50)));
    }

    // ---------------- F15 structured parsing ----------------

    @Test
    void structuredRealBugWithConfidence() {
        VerificationPolicy.Verdict v = policy.parseResponse(
                "VERDICT: REAL BUG\nCONFIDENCE: 8\nREASONING: The method drops the last element.");
        assertTrue(v.isRealBug());
        assertEquals(0.8, v.confidence(), 1e-12); // min(8/10.0, 0.95)
        assertEquals("The method drops the last element.", v.reasoning());
    }

    @Test
    void structuredConfidenceCappedAt095() {
        // min(10/10.0, 0.95) = 0.95 (F15 hard cap)
        VerificationPolicy.Verdict v = policy.parseResponse(
                "VERDICT: REAL BUG\nCONFIDENCE: 10\nREASONING: sure");
        assertEquals(0.95, v.confidence(), 0.0);
        assertEquals(0.95, VerificationPolicy.structuredConfidence(10.0), 0.0);
        assertEquals(0.75, VerificationPolicy.structuredConfidence(7.5), 1e-12);
    }

    @Test
    void structuredFalsePositiveQuotedCaseInsensitive() {
        VerificationPolicy.Verdict v = policy.parseResponse(
                "verdict: \"false positive\"\nCONFIDENCE: 9\nREASONING: test bug");
        assertFalse(v.isRealBug());
        assertEquals(0.9, v.confidence(), 1e-12);
    }

    @Test
    void missingConfidenceDefaultsToPointSeven() {
        VerificationPolicy.Verdict v = policy.parseResponse(
                "VERDICT: FALSE POSITIVE\nREASONING: environment issue");
        assertEquals(0.7, v.confidence(), 0.0);
    }

    @Test
    void reasoningCaptureStopsAtNextMarkerAndEndOfString() {
        // Lookahead (?=VERDICT:|CONFIDENCE:|\z) — Python \Z ported as Java \z (X1).
        VerificationPolicy.Verdict v = policy.parseResponse(
                "REASONING: because of the loop bound\nVERDICT: REAL BUG\nCONFIDENCE: 9");
        assertEquals("because of the loop bound", v.reasoning());

        // Trailing newline stays inside the capture with \z and is stripped after.
        VerificationPolicy.Verdict v2 = policy.parseResponse(
                "VERDICT: REAL BUG\nCONFIDENCE: 9\nREASONING: tail reasoning\n");
        assertEquals("tail reasoning", v2.reasoning());
    }

    // ---------------- F15 unstructured fallbacks ----------------

    @Test
    void plainRealBugSubstringWinsEvenWhenNegated() {
        // Iron-rule quirk: the positive or-chain checks the bare "real bug" substring
        // BEFORE the negative branch — "not a real bug" classifies as REAL at 0.8.
        VerificationPolicy.Verdict v = policy.parseResponse(
                "In my assessment the reported behavior is not a real bug at all.");
        assertTrue(v.isRealBug());
        assertEquals(0.8, v.confidence(), 0.0);
    }

    @Test
    void explicitNotARealBugRegexBranch() {
        // "this is not a real bug" — the negative REGEX branch would match, but the
        // positive substring check still runs first; verify the FALSE branch with a
        // response containing "false positive" and NO "real bug" substring.
        VerificationPolicy.Verdict v = policy.parseResponse(
                "This looks like a false positive caused by the harness.");
        assertFalse(v.isRealBug());
        assertEquals(0.8, v.confidence(), 0.0);
    }

    @Test
    void signalCountingPositiveDominant() {
        // pos: "exposes a problem", "code defect", "vulnerability" = 3; neg = 0
        // confidence = 0.6 + min(0.3, 0.05*3) = 0.75
        VerificationPolicy.Verdict v = policy.parseResponse(
                "The test exposes a problem; there is a code defect and a vulnerability.");
        assertTrue(v.isRealBug());
        assertEquals(0.75, v.confidence(), 1e-12);
    }

    @Test
    void signalCountingNegativeAndTie() {
        // neg: "expected behavior", "by design" = 2; pos = 0 → false, 0.6 + 0.1 = 0.7
        VerificationPolicy.Verdict v = policy.parseResponse(
                "That output is expected behavior and by design of the class.");
        assertFalse(v.isRealBug());
        assertEquals(0.7, v.confidence(), 1e-12);

        // tie 1-1 → Python else branch → false, 0.6 + min(0.3, 0) = 0.6
        VerificationPolicy.Verdict tie = policy.parseResponse(
                "It is expected behavior, yet the finding should be fixed eventually.");
        assertFalse(tie.isRealBug());
        assertEquals(0.6, tie.confidence(), 1e-12);
    }

    @Test
    void signalConfidenceCapAtPointThree() {
        // 7 positive signals, 0 negative → 0.6 + min(0.3, 0.35) = 0.9
        VerificationPolicy.Verdict v = policy.parseResponse(
                "real issue actual bug code defect exposes a problem "
                        + "defect in the class vulnerability should be fixed");
        assertTrue(v.isRealBug());
        assertEquals(0.9, v.confidence(), 1e-12);
    }

    @Test
    void emptyReasoningFallsBackToDefaultText() {
        // REASONING present but empty → strip() → "" → Python falsy → default message.
        VerificationPolicy.Verdict v = policy.parseResponse(
                "REASONING:\nVERDICT: REAL BUG\nCONFIDENCE: 9");
        assertEquals("No detailed reasoning provided", v.reasoning());
    }
}
