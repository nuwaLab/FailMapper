package org.failmapper.search;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

/**
 * Tests for {@link SeededRandomSource} — Python random-module semantics
 * (contract 3.1 / I9), especially randint's DOUBLE-INCLUSIVE bounds (R7/R13).
 */
class SeededRandomSourceTest {

    @Test
    void randintInclusiveCoversBothEndpointsAndNothingElse() {
        SeededRandomSource r = new SeededRandomSource(42L);
        Set<Integer> seen = new HashSet<>();
        for (int i = 0; i < 2000; i++) {
            int v = r.randintInclusive(0, 2);
            assertThat(v).isBetween(0, 2);
            seen.add(v);
        }
        // Upper bound 2 MUST be reachable — the Java nextInt-exclusive off-by-one trap.
        assertThat(seen).containsExactlyInAnyOrder(0, 1, 2);
    }

    @Test
    void randintInclusiveDegenerateRangeAndNegativeBounds() {
        SeededRandomSource r = new SeededRandomSource(7L);
        for (int i = 0; i < 20; i++) {
            assertThat(r.randintInclusive(3, 3)).isEqualTo(3); // randint(3,3) == 3 always
        }
        Set<Integer> seen = new HashSet<>();
        for (int i = 0; i < 2000; i++) {
            seen.add(r.randintInclusive(-2, 1));
        }
        assertThat(seen).containsExactlyInAnyOrder(-2, -1, 0, 1);
    }

    @Test
    void randintInclusiveRejectsEmptyRange() {
        SeededRandomSource r = new SeededRandomSource(1L);
        assertThatIllegalArgumentException().isThrownBy(() -> r.randintInclusive(2, 1));
    }

    @Test
    void nextDoubleIsInUnitIntervalHalfOpen() {
        SeededRandomSource r = new SeededRandomSource(99L);
        for (int i = 0; i < 1000; i++) {
            double v = r.nextDouble();
            assertThat(v).isGreaterThanOrEqualTo(0.0).isLessThan(1.0);
        }
    }

    @Test
    void sampleReturnsKDistinctElementsInSelectionOrder() {
        SeededRandomSource r = new SeededRandomSource(5L);
        List<String> population = List.of("a", "b", "c", "d", "e");
        List<String> sample = r.sample(population, 3);
        assertThat(sample).hasSize(3).doesNotHaveDuplicates();
        assertThat(population).containsAll(sample);
    }

    @Test
    void sampleOfFullPopulationIsAPermutation() {
        SeededRandomSource r = new SeededRandomSource(11L);
        List<Integer> population = List.of(1, 2, 3, 4, 5, 6);
        assertThat(r.sample(population, 6)).containsExactlyInAnyOrderElementsOf(population);
    }

    @Test
    void sampleEdgeCasesMatchPythonValueErrors() {
        SeededRandomSource r = new SeededRandomSource(3L);
        assertThat(r.sample(List.of("x"), 0)).isEmpty();
        assertThatIllegalArgumentException().isThrownBy(() -> r.sample(List.of("x"), 2));
        assertThatIllegalArgumentException().isThrownBy(() -> r.sample(List.of("x"), -1));
    }

    @Test
    void samplePreservesDuplicatePositions() {
        // Python samples POSITIONS, so duplicated values can appear twice in the result.
        SeededRandomSource r = new SeededRandomSource(17L);
        List<String> sample = r.sample(List.of("dup", "dup"), 2);
        assertThat(sample).containsExactly("dup", "dup");
    }

    @Test
    void choiceReturnsElementsFromTheListOnly() {
        SeededRandomSource r = new SeededRandomSource(23L);
        List<String> list = List.of("p", "q", "r");
        Set<String> seen = new HashSet<>();
        for (int i = 0; i < 500; i++) {
            String v = r.choice(list);
            assertThat(list).contains(v);
            seen.add(v);
        }
        assertThat(seen).containsExactlyInAnyOrder("p", "q", "r");
        assertThatIllegalArgumentException().isThrownBy(() -> r.choice(List.of()));
    }

    @Test
    void choicesWeightedNeverPicksZeroWeightAndHonorsCumulativeBounds() {
        SeededRandomSource r = new SeededRandomSource(31L);
        List<String> list = List.of("a", "b", "c");
        List<Double> weights = List.of(1.0, 0.0, 2.0);
        Set<String> seen = new HashSet<>();
        for (int i = 0; i < 1000; i++) {
            seen.add(r.choicesWeighted(list, weights));
        }
        assertThat(seen).containsExactlyInAnyOrder("a", "c"); // "b" (weight 0) never
    }

    @Test
    void choicesWeightedSingleNonZeroWeightIsDeterministic() {
        SeededRandomSource r = new SeededRandomSource(37L);
        List<String> list = List.of("a", "b", "c");
        for (int i = 0; i < 100; i++) {
            assertThat(r.choicesWeighted(list, List.of(0.0, 5.0, 0.0))).isEqualTo("b");
        }
    }

    @Test
    void choicesWeightedRejectsBadInputs() {
        SeededRandomSource r = new SeededRandomSource(41L);
        assertThatIllegalArgumentException()
                .isThrownBy(() -> r.choicesWeighted(List.of(), List.of()));
        assertThatIllegalArgumentException()
                .isThrownBy(() -> r.choicesWeighted(List.of("a"), List.of(1.0, 2.0)));
        assertThatIllegalArgumentException()
                .isThrownBy(() -> r.choicesWeighted(List.of("a", "b"), List.of(0.0, 0.0)));
    }

    @Test
    void sameSeedYieldsIdenticalStreams() {
        // I9: run-level reproducibility under a fixed seed.
        SeededRandomSource r1 = new SeededRandomSource(1234L);
        SeededRandomSource r2 = new SeededRandomSource(1234L);
        List<Object> s1 = new ArrayList<>();
        List<Object> s2 = new ArrayList<>();
        List<String> pop = List.of("a", "b", "c", "d");
        for (int i = 0; i < 50; i++) {
            s1.add(r1.nextDouble());
            s2.add(r2.nextDouble());
            s1.add(r1.randintInclusive(0, 9));
            s2.add(r2.randintInclusive(0, 9));
            s1.add(r1.choice(pop));
            s2.add(r2.choice(pop));
            s1.add(r1.sample(pop, 2));
            s2.add(r2.sample(pop, 2));
        }
        assertThat(s1).isEqualTo(s2);
    }
}
