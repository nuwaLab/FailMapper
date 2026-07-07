package org.failmapper.analysis;

import java.util.Optional;

import org.junit.jupiter.api.Test;

import com.github.javaparser.ast.CompilationUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link JavadocExtractor} (I18): class doc, documented/undocumented methods,
 * overload merging, marker stripping, absence semantics.
 */
class JavadocExtractorTest {

    private static final String FIXTURE = """
            package com.acme;

            /**
             * Validates option strings.
             *
             * <p>Rules apply to every public method of this class.
             */
            public class OptionValidator {

                /**
                 * Checks whether a character may start an option.
                 *
                 * @param c the character to check
                 * @return true when the character is valid
                 * @throws IllegalArgumentException when c cannot be mapped
                 */
                public boolean isValidChar(char c) {
                    return true;
                }

                public String undocumented(String s) {
                    return s;
                }

                /** Strips one leading hyphen. */
                public String strip(String s) {
                    return s;
                }

                /** Strips up to n leading hyphens. */
                public String strip(String s, int n) {
                    return s;
                }
            }
            """;

    private final JavadocExtractor extractor = new JavadocExtractor();

    @Test
    void classLevelJavadocExtractedAsPlainText() {
        JavadocExtractor.ClassJavadocs docs =
                extractor.extract(FIXTURE, "com.acme.OptionValidator");

        assertThat(docs.classDoc()).isPresent();
        // Markers (/** * */) stripped, prose kept verbatim including the blank line.
        assertThat(docs.classDoc().get()).isEqualTo(
                "Validates option strings.\n\n<p>Rules apply to every public method of this class.");
    }

    @Test
    void documentedMethodKeepsProseAndBlockTagLines() {
        JavadocExtractor.ClassJavadocs docs =
                extractor.extract(FIXTURE, "com.acme.OptionValidator");

        assertThat(docs.methodDocs()).containsKey("isValidChar");
        assertThat(docs.methodDocs().get("isValidChar")).isEqualTo("""
                Checks whether a character may start an option.
                @param c the character to check
                @return true when the character is valid
                @throws IllegalArgumentException when c cannot be mapped""");
    }

    @Test
    void undocumentedMethodHasNoEntry() {
        JavadocExtractor.ClassJavadocs docs =
                extractor.extract(FIXTURE, "com.acme.OptionValidator");

        assertThat(docs.methodDocs()).doesNotContainKey("undocumented");
        assertThat(docs.methodDocs()).containsOnlyKeys("isValidChar", "strip");
    }

    @Test
    void overloadsMergeJoinedWithBlankLineInSourceOrder() {
        JavadocExtractor.ClassJavadocs docs =
                extractor.extract(FIXTURE, "com.acme.OptionValidator");

        assertThat(docs.methodDocs().get("strip")).isEqualTo(
                "Strips one leading hyphen.\n\nStrips up to n leading hyphens.");
    }

    @Test
    void compilationUnitOverloadMatchesStringOverload() {
        CompilationUnit cu = new SourceAnalyzer().parse(FIXTURE).orElseThrow();
        JavadocExtractor.ClassJavadocs fromCu =
                extractor.extract(cu, "com.acme.OptionValidator");
        JavadocExtractor.ClassJavadocs fromSource =
                extractor.extract(FIXTURE, "com.acme.OptionValidator");

        assertThat(fromCu.classDoc()).isEqualTo(fromSource.classDoc());
        assertThat(fromCu.methodDocs()).isEqualTo(fromSource.methodDocs());
    }

    @Test
    void classWithoutJavadocYieldsEmptyOptionalAndMap() {
        String bare = "package p; public class Bare { void run() {} }";
        JavadocExtractor.ClassJavadocs docs = extractor.extract(bare, "p.Bare");

        assertThat(docs.classDoc()).isEmpty();
        assertThat(docs.methodDocs()).isEmpty();
    }

    @Test
    void unknownFqnYieldsEmptyResult() {
        JavadocExtractor.ClassJavadocs docs = extractor.extract(FIXTURE, "com.acme.Missing");

        assertThat(docs.classDoc()).isEmpty();
        assertThat(docs.methodDocs()).isEmpty();
    }

    @Test
    void simpleNameFallbackFindsTheType() {
        // FQN mismatch on the package but exact simple-name match (pre-resolved callers).
        JavadocExtractor.ClassJavadocs docs = extractor.extract(FIXTURE, "OptionValidator");

        assertThat(docs.classDoc()).isPresent();
        assertThat(docs.methodDocs()).containsKey("isValidChar");
    }

    @Test
    void unparseableSourceYieldsEmptyResult() {
        JavadocExtractor.ClassJavadocs docs =
                extractor.extract("not java at all {{{", "p.X");

        assertThat(docs.classDoc()).isEmpty();
        assertThat(docs.methodDocs()).isEmpty();
    }

    @Test
    void nestedTypeReachableWithDotOrDollarSeparator() {
        String nested = """
                package p;
                public class Outer {
                    /** Inner contract. */
                    public static class Inner {
                        /** Runs. */
                        public void run() {}
                    }
                }
                """;
        assertThat(extractor.extract(nested, "p.Outer.Inner").classDoc())
                .isEqualTo(Optional.of("Inner contract."));
        assertThat(extractor.extract(nested, "p.Outer$Inner").methodDocs())
                .containsEntry("run", "Runs.");
    }
}
