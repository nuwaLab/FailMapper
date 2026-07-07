package org.failmapper.search;

/**
 * Injected evaluation seam — the Java replacement for {@code FATestState.evaluate}'s
 * Maven/JaCoCo execution half ({@code test_state.py:101-225}). The search kernel calls
 * {@code evaluate(state)} exactly where Python called {@code state.evaluate(...)}
 * ({@code fa_mcts.py:2759}); implementations run/compile the test (M2 modules) or replay
 * recorded results, then fill the state and run the tracking sequence.
 *
 * @see DefaultEvaluator
 */
public interface Evaluator {

    /** Evaluate the state in place: fill coverage/errors/bugs, then run tracking. */
    void evaluate(FaTestState state);
}
