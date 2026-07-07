package org.failmapper.search;

/**
 * A predicted business-logic issue — the Java counterpart of the Python
 * {@code business_logic_analysis["potential_bugs"]} entry dict
 * ({@code fa_mcts.py:174-212}, {@code test_generation_strategies.py:778-798}).
 *
 * <p>{@code confidence} is a nullable Double because the Python read sites apply
 * DIFFERENT defaults for an absent key and each consumer must replicate its own:
 * <ul>
 *   <li>{@code issue.get('confidence', 0)} — action generation ({@code fa_mcts.py:209})
 *       and strategy weighting ({@code test_generation_strategies.py:781});</li>
 *   <li>{@code issue.get('confidence', 0.5)} — business-logic reward accrual
 *       ({@code fa_mcts.py:3225}).</li>
 * </ul>
 */
public record BusinessLogicIssue(String type, String method, String description, Double confidence) {

    /** Python {@code issue.get('type', ...)} with the caller-supplied default. */
    public String typeOr(String fallback) {
        return type == null ? fallback : type;
    }

    /** Python {@code issue.get('confidence', 0)}. */
    public double confidenceOrZero() {
        return confidence == null ? 0.0 : confidence;
    }

    /** Python {@code issue.get('confidence', 0.5)}. */
    public double confidenceOrHalf() {
        return confidence == null ? 0.5 : confidence;
    }
}
