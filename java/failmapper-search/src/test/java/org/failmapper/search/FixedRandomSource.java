package org.failmapper.search;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;

/**
 * Strict scripted {@link RandomSource} for deterministic tests: every draw must be
 * scripted in advance; drawing from an empty queue throws, so tests also verify that
 * code paths consume EXACTLY the randomness they are supposed to (e.g. non-forced
 * selection iterations must consume none).
 */
final class FixedRandomSource implements RandomSource {

    private final Deque<Double> doubles = new ArrayDeque<>();
    private final Deque<Integer> ints = new ArrayDeque<>();

    FixedRandomSource doubles(double... values) {
        for (double v : values) {
            doubles.add(v);
        }
        return this;
    }

    FixedRandomSource ints(int... values) {
        for (int v : values) {
            ints.add(v);
        }
        return this;
    }

    boolean exhausted() {
        return doubles.isEmpty() && ints.isEmpty();
    }

    @Override
    public double nextDouble() {
        if (doubles.isEmpty()) {
            throw new IllegalStateException("unexpected nextDouble() draw — script exhausted");
        }
        return doubles.poll();
    }

    @Override
    public int randintInclusive(int lowInclusive, int highInclusive) {
        if (ints.isEmpty()) {
            throw new IllegalStateException("unexpected randintInclusive() draw — script exhausted");
        }
        int v = ints.poll();
        if (v < lowInclusive || v > highInclusive) {
            throw new IllegalStateException(
                    "scripted int " + v + " outside [" + lowInclusive + ", " + highInclusive + "]");
        }
        return v;
    }

    @Override
    public <T> T choice(List<T> list) {
        // Consumes one scripted int as the index, keeping choice deterministic.
        return list.get(randintInclusive(0, list.size() - 1));
    }

    @Override
    public <T> List<T> sample(List<T> population, int k) {
        if (k < 0 || k > population.size()) {
            throw new IllegalArgumentException("sample size out of range");
        }
        return List.copyOf(population.subList(0, k));
    }

    @Override
    public <T> T choicesWeighted(List<T> list, List<Double> weights) {
        return list.get(0);
    }
}
