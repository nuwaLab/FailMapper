package org.failmapper.search;

/**
 * One selected test-generation strategy — the Java counterpart of the Python strategy
 * dict {@code {"id": ..., "name": ..., "weight": ...}} returned by
 * {@code select_strategies} ({@code test_generation_strategies.py:830-834}) and consumed
 * by action generation ({@code fa_mcts.py:245-251}: {@code strategy.get("id", "unknown")},
 * {@code strategy.get("weight", 1.0)}).
 */
public record Strategy(String id, String name, double weight) {

    /** Python {@code strategy.get("id", "unknown")}. */
    public String idOrUnknown() {
        return id == null ? "unknown" : id;
    }
}
