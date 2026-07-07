package org.failmapper.llm;

import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

/**
 * Pins {@link LlmMetrics} to the {@code feedback.py:53-98} semantics:
 * lazy start on first request, {@code len // 4} token estimates, and the
 * 0-defaults of {@code get_llm_metrics_summary} for empty lists.
 */
class LlmMetricsTest {

    /** Scripted clock: returns 10.0, 11.0, 12.0, ... seconds. */
    private static LlmMetrics scripted(AtomicInteger tick) {
        return new LlmMetrics(() -> 10.0 + tick.getAndIncrement());
    }

    @Test
    void tokenEstimateIsFloorCharsOverFour() {
        assertThat(LlmMetrics.estimateTokenSize("")).isZero();
        assertThat(LlmMetrics.estimateTokenSize("abc")).isZero();       // 3 // 4 == 0
        assertThat(LlmMetrics.estimateTokenSize("abcd")).isEqualTo(1);
        assertThat(LlmMetrics.estimateTokenSize("x".repeat(4099))).isEqualTo(1024);
    }

    @Test
    void firstRequestLazilyInitializesStartTime() {
        AtomicInteger tick = new AtomicInteger();
        LlmMetrics metrics = scripted(tick);
        metrics.recordRequest("p".repeat(40));   // start=10.0 (reset), returns 11.0
        metrics.recordRequest("q".repeat(100));  // returns 12.0
        LlmMetrics.Summary summary = metrics.summary(); // end=13.0

        assertThat(summary.totalRequests()).isEqualTo(2);
        assertThat(summary.maxTokenSize()).isEqualTo(25);
        assertThat(summary.minTokenSize()).isEqualTo(10);
        assertThat(summary.avgTokenSize()).isCloseTo(17.5, within(1e-12));
        assertThat(summary.totalTimeSeconds()).isCloseTo(3.0, within(1e-12));
        assertThat(summary.totalTimeMinutes()).isCloseTo(0.05, within(1e-12));
        assertThat(summary.avgRequestTime()).isZero(); // no request times recorded
    }

    @Test
    void requestTimesAverageWhenRecorded() {
        AtomicInteger tick = new AtomicInteger();
        LlmMetrics metrics = scripted(tick);
        double start = metrics.recordRequest("pppp"); // reset@10, start=11
        metrics.recordRequestTime(start);             // 12 - 11 = 1.0
        double start2 = metrics.recordRequest("qqqq"); // start2=13
        metrics.recordRequestTime(start2);             // 14 - 13 = 1.0
        assertThat(metrics.summary().avgRequestTime()).isCloseTo(1.0, within(1e-12));
    }

    @Test
    void emptyMetricsSummaryUsesZeroDefaults() {
        LlmMetrics metrics = scripted(new AtomicInteger());
        LlmMetrics.Summary summary = metrics.summary();
        assertThat(summary.totalRequests()).isZero();
        assertThat(summary.maxTokenSize()).isZero();
        assertThat(summary.minTokenSize()).isZero();
        assertThat(summary.avgTokenSize()).isZero();
        assertThat(summary.avgRequestTime()).isZero();
    }

    @Test
    void resetClearsCountersAndRestampsStart() {
        AtomicInteger tick = new AtomicInteger();
        LlmMetrics metrics = scripted(tick);
        metrics.recordRequest("pppp");
        metrics.reset();
        assertThat(metrics.requestCount()).isZero();
        assertThat(metrics.tokenSizes()).isEmpty();
    }
}
