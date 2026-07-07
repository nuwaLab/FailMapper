package org.failmapper.search;

import java.util.List;

/**
 * Injectable randomness abstraction for the search kernel, replacing the 15 unseeded
 * {@code random.*} call sites of the Python baseline (contract section 3.1, R1-R15;
 * registered improvement I9: RandomSource injection + fixed default seed).
 *
 * <p>Method contracts mirror CPython {@code random} module semantics. The exact draw
 * STREAM of CPython (Mersenne Twister) is NOT reproduced — the baseline was unseeded,
 * so trajectory equivalence was never possible; per I9 only run-level reproducibility
 * under a fixed seed is required.
 */
public interface RandomSource {

    /**
     * Python {@code random.random()}: uniform double in {@code [0.0, 1.0)}.
     * Sites: R5 ({@code fa_mcts.py:367}), R6 ({@code fa_mcts.py:2505}).
     */
    double nextDouble();

    /**
     * Python {@code random.randint(a, b)}: uniform integer with BOTH BOUNDS INCLUSIVE
     * — {@code a <= result <= b}. This differs from {@code java.util.Random.nextInt(bound)}
     * whose upper bound is EXCLUSIVE; the contract flags this as an off-by-one trap
     * (R7, R13: "inclusive bounds — Java Random.nextInt is exclusive").
     * Use this method wherever the Python source used {@code random.randint}.
     *
     * @throws IllegalArgumentException if {@code lowInclusive > highInclusive}
     *         (Python raises {@code ValueError} for an empty range)
     */
    int randintInclusive(int lowInclusive, int highInclusive);

    /**
     * Python {@code random.choice(seq)}: uniform pick of one element.
     * Sites: R8 ({@code fa_mcts.py:2530}), R9 ({@code fa_mcts.py:2608} — THE expansion
     * action selector; the result depends on list ORDER, so callers must pass
     * insertion-ordered lists, contract O1-O4).
     *
     * @throws IllegalArgumentException if the list is empty (Python raises IndexError)
     */
    <T> T choice(List<T> list);

    /**
     * Python {@code random.sample(population, k)}: {@code k} DISTINCT elements (distinct
     * positions — duplicates in the population can both be drawn), in selection order.
     * Sites: R1-R4 ({@code fa_mcts.py:222/259/285/348}); the sample's order also sets the
     * order of the generated actions in {@code possible_actions}.
     *
     * <p>Semantics: implementations must perform selection sampling over a copy of the
     * population (partial Fisher-Yates), preserving the order in which elements were
     * selected, like CPython's pool-based branch. DIVERGENCE (accepted per I9): CPython
     * switches to a set-based rejection algorithm for large n with small k, so the exact
     * mapping from underlying draws to picks differs; only the distributional contract
     * (uniform k-subsets, selection order) is preserved.
     *
     * @throws IllegalArgumentException if {@code k < 0} or {@code k > population.size()}
     *         (Python raises {@code ValueError})
     */
    <T> List<T> sample(List<T> population, int k);

    /**
     * Python {@code random.choices(seq, weights=w, k=1)[0]}: ONE weighted pick with
     * replacement via cumulative weights ({@code r = random()*total}; first index whose
     * cumulative weight exceeds {@code r} — bisect_right semantics). Site: R12
     * ({@code enhanced_mcts_test_generator.py:2617}); the contract notes Java has no
     * {@code random.choices} and requires hand-written cumulative-weight sampling.
     *
     * @throws IllegalArgumentException if sizes differ, the list is empty, or the total
     *         weight is not &gt; 0 (Python raises {@code ValueError})
     */
    <T> T choicesWeighted(List<T> list, List<Double> weights);
}
