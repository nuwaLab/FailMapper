package org.failmapper.llm;

import java.util.Optional;

import org.failmapper.analysis.SourceAnalyzer;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Layer-D regression for I17 / M5_BENCHMARK §3.4: the strict single-shot extractor
 * crashed 2 of 8 pilot cells ("no parseable Java in the initial LLM reply").
 * {@link CodeExtractor} now degrades through the Python baseline's
 * {@code extract_java_code} fallback chain ({@code feedback.py:417-500}): a reply
 * containing anything class-like must yield SOMETHING compilable-ish (the M4 fix loop
 * repairs the rest); only pure prose may yield empty.
 *
 * <p>The strict-first cases pin the pre-fix behavior (parse-validated fences still
 * win, returned byte-for-byte).
 */
class CodeExtractorTest {

    private final CodeExtractor extractor = new CodeExtractor();

    private static final String VALID_CLASS = """
            package com.acme;

            import java.util.List;

            public class FooTest {
                void t() {
                    int x = 1;
                }
            }""";

    // ------------------------------------------------------------------
    // Strict pass — existing behavior, unchanged by the M5 fix
    // ------------------------------------------------------------------

    @Test
    void strictJavaFenceParseValidatedWinsByteForByte() {
        String reply = "Here is the test:\n```java\n" + VALID_CLASS + "\n```\nEnjoy!";
        assertThat(extractor.extract(reply)).contains(VALID_CLASS + "\n");
    }

    @Test
    void strictAnyFenceUsedWhenNoJavaFenceParses() {
        String reply = "Result:\n```\n" + VALID_CLASS + "\n```\n";
        assertThat(extractor.extract(reply)).contains(VALID_CLASS + "\n");
    }

    @Test
    void strictWholeReplyReturnedWhenItParses() {
        assertThat(extractor.extract(VALID_CLASS)).contains(VALID_CLASS);
    }

    @Test
    void strictSkipsUnparseableFenceInFavorOfLaterParseableOne() {
        String reply = "```java\nnot java at all !!!\n```\nand then\n```java\n"
                + VALID_CLASS + "\n```\n";
        assertThat(extractor.extract(reply)).contains(VALID_CLASS + "\n");
    }

    @Test
    void nullAndBlankRepliesYieldEmpty() {
        assertThat(extractor.extract(null)).isEmpty();
        assertThat(extractor.extract("   \n\t ")).isEmpty();
    }

    // ------------------------------------------------------------------
    // Fallback chain — feedback.py:417-500 semantics (M5 §3.4 regression)
    // ------------------------------------------------------------------

    @Test
    void truncatedJavaFenceIsSalvagedWithImportsAndClosingBraces() {
        // An LLM reply cut off mid-method with the fence never closed: the pre-fix
        // extractor returned empty and crashed the run (M5 §3.4). The salvage keeps
        // package/imports and appends the missing closing braces.
        String reply = """
                Sure, here is a thorough test class:
                ```java
                package com.acme;

                import org.junit.jupiter.api.Test;

                public class FooTest {
                    @Test
                    void addsNumbers() {
                        int sum = 1 + 1;""";

        Optional<String> extracted = extractor.extract(reply);

        assertThat(extracted).isPresent();
        String code = extracted.get();
        assertThat(code).contains("import org.junit.jupiter.api.Test;");
        assertThat(code).contains("public class FooTest {");
        assertThat(code.chars().filter(c -> c == '{').count())
                .isEqualTo(code.chars().filter(c -> c == '}').count());
        // compilable-ish: the salvaged unit actually parses
        assertThat(new SourceAnalyzer().parse(code)).isPresent();
    }

    @Test
    void proseWrappedClassWithoutFencesIsExtractedByBraceScan() {
        // feedback.py:468-494 (L4): no fences at all — scan from "public class" and
        // stop at the balancing brace, dropping the surrounding prose.
        String classBody = """
                public class FooTest {
                    void t() {
                        int x = 1;
                    }
                }""";
        String reply = "Of course! The following should cover it.\n\n" + classBody
                + "\nLet me know if you need more cases.";

        assertThat(extractor.extract(reply)).contains(classBody);
    }

    @Test
    void pureProseReplyYieldsEmpty() {
        // The feedback.py:468 gate: nothing Java-like at all -> empty (deliberate
        // tightening of feedback.py:500, registered under I17) so the I17 retry can
        // re-sample instead of feeding prose to the compiler forever.
        assertThat(extractor.extract(
                "I'm sorry, but I need to see the source before writing tests."))
                .isEmpty();
    }

    @Test
    void completeLookingJavaFenceReturnedEvenWhenUnparseable() {
        // feedback.py:420-429 (L1): a fence that starts at the class declaration and
        // "looks complete" is returned as-is even if JavaParser rejects it — the
        // compile-fix loop owns the repair.
        String broken = "public class FooTest {\n    void broken( {\n    }\n}";
        String reply = "```java\n" + broken + "\n```";

        assertThat(extractor.extract(reply)).contains(broken);
    }

    @Test
    void multipleIncompleteJavaFencesFallBackToLongestBlock() {
        // feedback.py:441-452 (L2): several ```java fragments, none complete, the
        // "\n\n" join is not complete either -> the longest fragment wins.
        String shortBlock = "int a = 1;";
        String longBlock = "int b = 2;\nint c = 3;\nint d = 4;";
        String reply = "First:\n```java\n" + shortBlock + "\n```\nthen:\n```java\n"
                + longBlock + "\n```\n";

        assertThat(extractor.extract(reply)).contains(longBlock);
    }

    @Test
    void anyFenceWithCompleteClassPreferredOverLongerFragment() {
        // feedback.py:455-465 (L3): untagged fences — the first complete class wins
        // over a longer non-class block.
        String completeClass = "class Foo {\n    void t() {}\n}";
        String longerFragment = "some pseudo code that is definitely longer than the class"
                + " but has no type declaration at all";
        String reply = "```text\n" + longerFragment + "\n```\nand\n```text\n"
                + completeClass + "\n```\n";

        Optional<String> extracted = extractor.extract(reply);

        assertThat(extracted).isPresent();
        assertThat(extracted.get()).contains("class Foo");
    }

    @Test
    void wholeReplyIsLastResortWhenJavaLikeButNothingExtractable() {
        // feedback.py:496-497 (L5): imports but no fence and no class declaration —
        // hand the whole reply to the compile loop rather than nothing.
        String reply = "import org.junit.jupiter.api.Test;\nplus some prose the model"
                + " added that ruins parsing";

        assertThat(extractor.extract(reply)).contains(reply);
    }
}
