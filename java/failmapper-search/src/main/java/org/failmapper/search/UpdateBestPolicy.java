package org.failmapper.search;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.LinkedHashMap;

/**
 * D14 — port of the best-test update rules of {@code update_best_tests}
 * ({@code fa_mcts.py:2150-2234}).
 *
 * <p>Rule order is load-bearing (if/elif chain):
 * <ol>
 *   <li>STRICTLY higher coverage → new best; {@code best_reward = max(reward, best_reward)}
 *       ({@code fa_mcts.py:2169-2177});</li>
 *   <li>EQUAL coverage (exact double ==) and strictly higher reward → new best
 *       ({@code fa_mcts.py:2180-2184});</li>
 *   <li>strictly higher reward alone → new best only when
 *       {@code coverage >= 0.8 * best_coverage} (C36, {@code fa_mcts.py:2187-2193});</li>
 *   <li>independent of 1-3: a copy of the test is saved into {@code high_coverage_tests}
 *       whenever {@code coverage >= 0.9 * best_coverage} (C36, {@code fa_mcts.py:2231-2234}),
 *       keyed by the two-decimal coverage string with {@code "."} replaced by {@code "_"}.</li>
 * </ol>
 *
 * <p>The key format round-trips through {@code float(key.replace("_", "."))} at
 * {@code fa_mcts.py:3868} (contract N8), so formatting uses HALF_EVEN (Python's
 * {@code :.2f} rounding, registered I12) on the exact binary double and is
 * locale-independent (BigDecimal emits '.'), never {@code String.format} (HALF_UP +
 * locale decimal separator).
 *
 * <p>{@code high_coverage_tests} is insertion-ordered (LinkedHashMap) because the
 * integrated-test base selection iterates it taking strictly-greater coverage — ties
 * resolve by insertion order (contract O7).
 *
 * <p>Initial values per {@code FA_MCTS.__init__} ({@code fa_mcts.py:645-651}):
 * {@code current_coverage = initial_coverage}, {@code best_test = initial_test_code},
 * {@code best_reward = 0.0}, {@code best_state = None}.
 *
 * <p>Also records {@code iterations_to_high_coverage} at the 80% threshold
 * (C37, {@code fa_mcts.py:2196-2198}); the deeper metrics of that block
 * (high-risk-pattern / boundary-condition counts, {@code fa_mcts.py:2200-2228})
 * need the failures/f_model context and stay with the orchestrator.
 */
public final class UpdateBestPolicy {

    private final SearchConfig config;

    /** {@code self.best_state} — null until a state wins. */
    public FaTestState bestState = null;

    /** {@code self.best_test}. */
    public String bestTest;

    /** {@code self.best_reward}. */
    public double bestReward = 0.0;

    /** {@code self.current_coverage} — the best coverage seen so far (0-100 scale). */
    public double currentCoverage;

    /** {@code self.metrics["iterations_to_high_coverage"]} — null until recorded (C37). */
    public Integer iterationsToHighCoverage = null;

    /** {@code self.high_coverage_tests} — insertion-ordered (O7). */
    public final LinkedHashMap<String, String> highCoverageTests = new LinkedHashMap<>();

    public UpdateBestPolicy(SearchConfig config, String initialTestCode, double initialCoverage) {
        this.config = config;
        this.bestTest = initialTestCode;
        this.currentCoverage = initialCoverage;
    }

    /**
     * Apply the D14 update for one simulated state.
     *
     * @param state     the evaluated state; null is a no-op ({@code fa_mcts.py:2160-2161})
     * @param reward    the simulation reward
     * @param iteration current iteration (recorded for the C37 metric)
     */
    public void updateBest(FaTestState state, double reward, int iteration) {
        if (state == null) {
            return;
        }

        // fa_mcts.py:2164-2166 — non-positive coverage is treated as 0.0.
        double currentStateCoverage = state.coverage > 0 ? state.coverage : 0.0;

        if (currentStateCoverage > currentCoverage) {
            currentCoverage = currentStateCoverage;
            bestState = state;
            bestTest = state.testCode;
            bestReward = Math.max(reward, bestReward); // fa_mcts.py:2176
        } else if (currentStateCoverage == currentCoverage && reward > bestReward) {
            bestState = state;
            bestTest = state.testCode;
            bestReward = reward;
        } else if (reward > bestReward
                && currentStateCoverage >= currentCoverage * config.updateBestCoverageGuard) {
            bestState = state;
            bestTest = state.testCode;
            bestReward = reward;
        }

        // C37 — record the first iteration reaching high coverage (fa_mcts.py:2196-2198).
        if (currentCoverage >= config.highCoverageMetricThreshold && iterationsToHighCoverage == null) {
            iterationsToHighCoverage = iteration;
        }

        // C36 save guard — keep a copy of sufficiently-high-coverage tests (fa_mcts.py:2231-2234).
        if (currentStateCoverage >= currentCoverage * config.highCoverageSaveGuard) {
            highCoverageTests.put(coverageKey(currentStateCoverage), state.testCode);
        }
    }

    /**
     * {@code f"{coverage:.2f}".replace(".", "_")} ({@code fa_mcts.py:2233}) with Python's
     * half-even rounding of the exact binary double (I12/N7/N8).
     */
    public static String coverageKey(double coverage) {
        return new BigDecimal(coverage).setScale(2, RoundingMode.HALF_EVEN)
                .toPlainString().replace(".", "_");
    }
}
