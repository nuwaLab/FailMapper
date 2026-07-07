package org.failmapper.search;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * Default {@link RandomSource} backed by {@link java.util.Random} with an explicit seed
 * (registered improvement I9: the Python baseline never seeds; the Java port injects a
 * seeded source so runs are reproducible at the RNG level).
 *
 * <p>The underlying generator is java.util.Random's LCG, not CPython's Mersenne
 * Twister — draw-stream equivalence with the Python oracle is explicitly out of scope
 * (contract section 1, change type C).
 */
public final class SeededRandomSource implements RandomSource {

    private final Random random;

    public SeededRandomSource(long seed) {
        this.random = new Random(seed);
    }

    /** Wrap an existing generator (e.g. for tests that need to share a stream). */
    public SeededRandomSource(Random random) {
        this.random = random;
    }

    @Override
    public double nextDouble() {
        return random.nextDouble();
    }

    @Override
    public int randintInclusive(int lowInclusive, int highInclusive) {
        if (lowInclusive > highInclusive) {
            throw new IllegalArgumentException(
                    "empty range for randintInclusive(" + lowInclusive + ", " + highInclusive + ")");
        }
        // Both bounds inclusive, matching Python random.randint (contract R7/R13).
        // Note: assumes the range width fits in int (true for every search-kernel site,
        // where bounds are small child/action indices).
        return lowInclusive + random.nextInt(highInclusive - lowInclusive + 1);
    }

    @Override
    public <T> T choice(List<T> list) {
        if (list.isEmpty()) {
            throw new IllegalArgumentException("cannot choose from an empty sequence");
        }
        return list.get(random.nextInt(list.size()));
    }

    @Override
    public <T> List<T> sample(List<T> population, int k) {
        int n = population.size();
        if (k < 0 || k > n) {
            throw new IllegalArgumentException(
                    "sample size " + k + " out of range for population of " + n);
        }
        // Partial Fisher-Yates over a pool copy — CPython's selection-sampling branch:
        //   pool = list(population)
        //   for i in range(k): j = randbelow(n-i); result[i] = pool[j]; pool[j] = pool[n-i-1]
        // Selection order is preserved in the result, as Python does.
        List<T> pool = new ArrayList<>(population);
        List<T> result = new ArrayList<>(k);
        for (int i = 0; i < k; i++) {
            int j = random.nextInt(n - i);
            result.add(pool.get(j));
            pool.set(j, pool.get(n - i - 1));
        }
        return result;
    }

    @Override
    public <T> T choicesWeighted(List<T> list, List<Double> weights) {
        if (list.isEmpty()) {
            throw new IllegalArgumentException("cannot choose from an empty sequence");
        }
        if (list.size() != weights.size()) {
            throw new IllegalArgumentException(
                    "list size " + list.size() + " != weights size " + weights.size());
        }
        double total = 0.0;
        for (double w : weights) {
            total += w;
        }
        if (!(total > 0.0)) {
            throw new IllegalArgumentException("total of weights must be greater than zero");
        }
        // Python random.choices: bisect_right(cum_weights, random()*total) — the first
        // index whose cumulative weight is strictly greater than r.
        double r = random.nextDouble() * total;
        double cum = 0.0;
        for (int i = 0; i < list.size(); i++) {
            cum += weights.get(i);
            if (cum > r) {
                return list.get(i);
            }
        }
        // Floating-point tail guard (r can graze the last cumulative bound).
        return list.get(list.size() - 1);
    }
}
