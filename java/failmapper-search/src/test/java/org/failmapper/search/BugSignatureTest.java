package org.failmapper.search;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/**
 * {@link BugSignature} against CPython-oracle values: every expected string below was
 * produced by executing the genuine {@code _create_bug_signature}
 * ({@code fa_mcts.py:3407-3442}) under CPython 3 (hashlib.md5 over str.encode()).
 */
class BugSignatureTest {

    @Test
    void assertionFailureReducesToExpectedButWasCore() {
        assertThat(BugSignature.create("testAdd",
                "org.opentest4j.AssertionFailedError: expected: <5> but was: <4>"))
                .isEqualTo("testAdd:0cd5c944d95f");
    }

    @Test
    void memoryAddressesAreStrippedBeforeHashing() {
        assertThat(BugSignature.create("testNull",
                "java.lang.NullPointerException: Cannot invoke method on null object @1a2b3c"))
                .isEqualTo("testNull:1941c51769e4");
    }

    @Test
    void addressStrippingAppliesMidText() {
        assertThat(BugSignature.create("testWeird",
                "some odd failure text with object com.foo.Bar@deadbeef here"))
                .isEqualTo("testWeird:5bb4da4d5428");
    }

    @Test
    void emptyErrorHashesEmptyString() {
        assertThat(BugSignature.create("testEmpty", ""))
                .isEqualTo("testEmpty:d41d8cd98f00");
    }

    @Test
    void missingMethodNameDefaultsToUnknown() {
        assertThat(BugSignature.create(null, "no method key"))
                .isEqualTo("unknown:c65f7dedefb9");
    }

    @Test
    void newlineBetweenMarkersDefeatsTheReduction() {
        // Non-DOTALL '.' cannot cross the newline, so the FULL cleaned text is hashed
        // (CPython behavior confirmed; UNIX_LINES keeps Java's '.' semantics aligned, X9).
        assertThat(BugSignature.create("testMultiline", "expected:\n<a> but was:\n<b>"))
                .isEqualTo("testMultiline:73d2ccbd7738");
    }

    @Test
    void exceptionTypeIsTheCoreWhenPresent() {
        assertThat(BugSignature.create("testExc", "wrapped in CustomFooException: boom"))
                .isEqualTo("testExc:04c3bedf5c2e");
    }

    @Test
    void sameErrorDifferentAddressesCollide() {
        String a = BugSignature.create("t", "IllegalStateException at Foo@1a2b3c");
        String b = BugSignature.create("t", "IllegalStateException at Foo@ffff00");
        assertThat(a).isEqualTo(b);
    }
}
