package org.failmapper.search;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/** D11 — classify_logical_bugs (test_state.py:313-384). */
class LogicalBugClassifierTest {

    private final LogicalBugClassifier classifier = new LogicalBugClassifier();

    @Test
    void tableHas20RulesInSourceOrder() {
        assertEquals(20, LogicalBugClassifier.LOGICAL_BUG_PATTERNS.size());
        assertEquals("incorrect_value", LogicalBugClassifier.LOGICAL_BUG_PATTERNS.get(0).bugType());
        assertEquals("logical_assertion", LogicalBugClassifier.LOGICAL_BUG_PATTERNS.get(19).bugType());
    }

    @Test
    void incorrectValueFromExpectedButWas() {
        var c = classifier.classifyMessage("AssertionError expected: <5> but was: <4>");
        assertTrue(c.isLogical());
        assertEquals("incorrect_value", c.bugType());
        assertEquals(0.7, c.confidence(), 0.0);
    }

    @Test
    void higherConfidencePatternWins() {
        // "expected: true ... but was: false" matches BOTH row 1 (0.7 incorrect_value)
        // and row 2 (0.9 incorrect_boolean); 0.9 > 0.7 → incorrect_boolean.
        var c = classifier.classifyMessage("expected: true but was: false");
        assertEquals("incorrect_boolean", c.bugType());
        assertEquals(0.9, c.confidence(), 0.0);
    }

    @Test
    void equalConfidenceTieGoesToEarlierRule() {
        // IndexOutOfBoundsException (row 4, 0.8) and IllegalStateException (row 9, 0.8)
        // both match; the strict `>` update keeps the EARLIER rule (contract O12).
        var c = classifier.classifyMessage(
                "IndexOutOfBoundsException after IllegalStateException");
        assertEquals("index_error", c.bugType());
        assertEquals(0.8, c.confidence(), 0.0);
    }

    @Test
    void caseInsensitiveMatching() {
        // Python re.IGNORECASE → CASE_INSENSITIVE | UNICODE_CASE (contract X3)
        var c = classifier.classifyMessage("nullpointerexception in Foo.bar");
        assertEquals("null_reference", c.bugType());
        assertEquals(0.6, c.confidence(), 0.0);
    }

    @Test
    void boundaryKeywordsScoreHighest() {
        // "off.by.one" row (0.9) — the '.' matches the '-' separator
        var c = classifier.classifyMessage("looks like an off-by-one in the loop");
        assertEquals("boundary_error", c.bugType());
        assertEquals(0.9, c.confidence(), 0.0);
    }

    @Test
    void nonLogicalMessage() {
        var c = classifier.classifyMessage("SomeUnrelatedFailure happened");
        assertFalse(c.isLogical());
        assertEquals("unknown", c.bugType());
        assertEquals(0.0, c.confidence(), 0.0);
    }

    @Test
    void classifyStateMarksLogicalAndGeneral() {
        FaTestState state = new FaTestState("", null, null);
        DetectedBug logical = new DetectedBug("runtime_error", "boom",
                "testA", "java.lang.NullPointerException", "medium");
        DetectedBug general = new DetectedBug("runtime_error", "weird failure",
                "testB", "SomethingElse", "medium");
        state.detectedBugs.add(logical);
        state.detectedBugs.add(general);

        classifier.classify(state);

        assertEquals("logical", logical.bugCategory);
        assertEquals("null_reference", logical.bugType);
        assertEquals(0.6, logical.logicConfidence, 0.0);
        assertEquals("general", general.bugCategory);
        assertEquals(1, state.logicalBugs.size());
        assertTrue(state.hasBugs);
    }

    @Test
    void secondBugWithSameMethodIsSkippedEntirely() {
        // added_methods dedup (test_state.py:351-353): the second bug for testA is
        // skipped BEFORE analysis — even its bug_category stays unset.
        FaTestState state = new FaTestState("", null, null);
        DetectedBug first = new DetectedBug("runtime_error", "",
                "testA", "NullPointerException", "medium");
        DetectedBug second = new DetectedBug("runtime_error", "",
                "testA", "IndexOutOfBoundsException", "medium");
        state.detectedBugs.add(first);
        state.detectedBugs.add(second);

        classifier.classify(state);

        assertEquals("logical", first.bugCategory);
        assertNull(second.bugCategory); // untouched — Python `continue`
        assertEquals(1, state.logicalBugs.size());
    }

    @Test
    void dedupOnlyAppliesToLogicalMatches() {
        // A non-logical bug does NOT reserve its method name: a later logical bug with
        // the same method still classifies (added_methods only grows on matches).
        FaTestState state = new FaTestState("", null, null);
        DetectedBug general = new DetectedBug("runtime_error", "",
                "testA", "UnmatchedError", "medium");
        DetectedBug logical = new DetectedBug("runtime_error", "",
                "testA", "NullPointerException", "medium");
        state.detectedBugs.add(general);
        state.detectedBugs.add(logical);

        classifier.classify(state);

        assertEquals("general", general.bugCategory);
        assertEquals("logical", logical.bugCategory);
    }

    @Test
    void preAddedLogicalBugIsAppendedAgain() {
        // Iron-rule duplicate: an assertion-failure bug pre-appended to logical_bugs by
        // evaluate (test_state.py:190) is appended AGAIN by classify (test_state.py:375)
        // when its message matches a rule — count_logical_bugs sees 2.
        FaTestState state = new FaTestState("", null, null);
        DetectedBug bug = new DetectedBug("assertion_failure",
                "expected: <1> but was: <2>", "testX", "AssertionError", "medium");
        bug.bugCategory = "logical";
        bug.bugType = "incorrect_behavior";
        state.detectedBugs.add(bug);
        state.logicalBugs.add(bug); // evaluate() pre-add

        classifier.classify(state);

        assertEquals(2, state.countLogicalBugs());
        assertEquals("incorrect_value", bug.bugType); // re-typed by the classifier
    }

    @Test
    void emptyDetectedBugsIsNoOp() {
        FaTestState state = new FaTestState("", null, null);
        state.hasBugs = true; // must remain untouched (early return, test_state.py:315-316)
        classifier.classify(state);
        assertTrue(state.hasBugs);
    }
}
