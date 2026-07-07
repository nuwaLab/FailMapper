package org.failmapper.llm.prompt;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

/**
 * Pins {@link VerdictParser} and {@link BatchVerdictParser} to the genuine Python
 * regex behavior of {@code verify_bug_with_llm.py} (:159-161 single verdict;
 * :393-457 batch parsing). Every expected value below was produced by running the
 * REAL Python patterns over the same responses (see the M4 Layer-P work notes).
 *
 * <p>Dialect fixes under test: X1 ({@code \Z} -> {@code \z}) and X9 (UNIX_LINES).
 */
class VerdictParserTest {

    // ------------------------------------------------------------------
    // single verdict (verify_bug_with_llm.py:159-224)
    // ------------------------------------------------------------------

    @Test
    void quotedLowercaseVerdictAndDecimalConfidence() {
        Optional<VerdictParser.Verdict> v =
                VerdictParser.parse("verdict: \"real bug\"\nCONFIDENCE: 7.5\nREASONING: fine.\n");
        assertThat(v).isPresent();
        assertThat(v.get().isRealBug()).isTrue();
        assertThat(v.get().confidence()).isCloseTo(0.75, within(1e-12));
        assertThat(v.get().reasoning()).isEqualTo("fine.");
    }

    @Test
    void falsePositiveWithConfidenceCappedAt095() {
        Optional<VerdictParser.Verdict> v =
                VerdictParser.parse("VERDICT: 'FALSE POSITIVE'\nREASONING: r1\nCONFIDENCE: 10\n");
        assertThat(v).isPresent();
        assertThat(v.get().isRealBug()).isFalse();
        assertThat(v.get().confidence()).isCloseTo(0.95, within(1e-12));
        // reasoning capture stops at the CONFIDENCE: lookahead
        assertThat(v.get().reasoning()).isEqualTo("r1");
    }

    @Test
    void reasoningBeforeVerdictStopsAtVerdictLookahead() {
        Optional<VerdictParser.Verdict> v =
                VerdictParser.parse("REASONING: early\nVERDICT: REAL BUG\nCONFIDENCE: 12");
        assertThat(v).isPresent();
        assertThat(v.get().isRealBug()).isTrue();
        assertThat(v.get().confidence()).isCloseTo(0.95, within(1e-12)); // min(12/10, 0.95)
        assertThat(v.get().reasoning()).isEqualTo("early");
    }

    @Test
    void unstructuredResponseYieldsEmpty() {
        assertThat(VerdictParser.parse("no structure at all")).isEmpty();
    }

    @Test
    void emptyReasoningFallsBackToPlaceholder() {
        Optional<VerdictParser.Verdict> v =
                VerdictParser.parse("VERDICT: REAL BUG\nCONFIDENCE: 3\nREASONING:\n");
        assertThat(v).isPresent();
        assertThat(v.get().confidence()).isCloseTo(0.3, within(1e-12));
        assertThat(v.get().reasoning()).isEqualTo("No detailed reasoning provided");
    }

    @Test
    void missingConfidenceDefaultsTo07() {
        Optional<VerdictParser.Verdict> v =
                VerdictParser.parse("VERDICT: FALSE POSITIVE\nREASONING: because\n");
        assertThat(v).isPresent();
        assertThat(v.get().confidence()).isCloseTo(0.7, within(1e-12));
    }

    /**
     * X1: the reasoning pattern must use {@code \z}. With Java's {@code \Z} a
     * response ending in a newline would still match, but the lazy capture could
     * legally stop one character earlier; the observable contract here is that a
     * trailing-newline response parses and strips identically to CPython.
     */
    @Test
    void trailingNewlineReasoningMatchesCpythonBoundary() {
        Optional<VerdictParser.Verdict> v = VerdictParser.parse(
                "VERDICT: REAL BUG\nREASONING: multi\nline\nreasoning\n\n");
        assertThat(v).isPresent();
        assertThat(v.get().reasoning()).isEqualTo("multi\nline\nreasoning");
    }

    // ------------------------------------------------------------------
    // batch parsing (verify_bug_with_llm.py:389-457)
    // ------------------------------------------------------------------

    @Test
    void explicitRealBugsListParsesToZeroBasedSortedIndices() {
        assertThat(BatchVerdictParser.realBugIndices("blah\nREAL_BUGS: 2, 5, 8\n", 8))
                .containsExactly(1, 4, 7);
    }

    @Test
    void realBugsNoneYieldsEmptyAndDoesNotFallBack() {
        // Python: [\d,\s]+ backtracks to match the space, findall(\d+) finds nothing.
        assertThat(BatchVerdictParser.realBugIndices("REAL_BUGS: None", 5)).isEmpty();
    }

    @Test
    void duplicatesAreDedupedAndSorted() {
        assertThat(BatchVerdictParser.realBugIndices("REAL_BUGS: 3, 3, 1", 5))
                .containsExactly(0, 2);
    }

    @Test
    void outOfRangeNumbersAreDropped() {
        assertThat(BatchVerdictParser.realBugIndices("REAL_BUGS: 0, 6", 5)).isEmpty();
    }

    @Test
    void listFormatFallbackMatchesSameLineOnly() {
        assertThat(BatchVerdictParser.realBugIndices(
                "- Method 2 is testing a REAL BUG here\nMethod 3 looks like a real bug too", 5))
                .containsExactly(1, 2);
    }

    @Test
    void yesNoFallbackCountsOnlyYesAndTrue() {
        assertThat(BatchVerdictParser.realBugIndices(
                "Method 1: Yes\nMethod 2: No\nMethod 3 is True", 5))
                .containsExactly(0, 2);
    }

    @Test
    void confidenceScoresNormalizeToTenths() {
        Map<Integer, Double> scores = BatchVerdictParser.confidenceScores(
                "Method 1: Yes.\nConfidence: 8/10\nMethod 2: No\nconfidence 3\n", 3);
        assertThat(scores).containsOnlyKeys(0, 1);
        assertThat(scores.get(0)).isCloseTo(0.8, within(1e-12));
        assertThat(scores.get(1)).isCloseTo(0.3, within(1e-12));
    }

    /**
     * X9: CPython's {@code .} (no DOTALL) excludes only {@code \n}, so the
     * list-format fallback matches across a {@code \r} within one logical line.
     * Java's default {@code .} would exclude the {@code \r} and miss the match.
     */
    @Test
    void listFormatFallbackTreatsCarriageReturnLikeCpython() {
        assertThat(BatchVerdictParser.realBugIndices("Method 4 \r is a real bug", 5))
                .containsExactly(3);
    }

    @Test
    void realBugsTrailerPreferredOverFallbacks() {
        // With an explicit trailer, the Yes/No lines must be ignored entirely.
        assertThat(BatchVerdictParser.realBugIndices(
                "Method 1: Yes\nMethod 2: Yes\nREAL_BUGS: 2", 5))
                .containsExactly(1);
        assertThat(BatchVerdictParser.realBugIndices(
                List.of("Method 1: Yes", "REAL_BUGS: 1").get(1), 1))
                .containsExactly(0);
    }
}
