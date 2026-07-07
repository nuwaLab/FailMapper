package org.failmapper.llm;

import java.util.ArrayList;
import java.util.List;
import java.util.function.DoubleSupplier;

/**
 * Port of the {@code llm_metrics} counters in {@code feedback.py:53-98}.
 *
 * <p>Semantics preserved from the Python baseline:
 * <ul>
 *   <li>{@code request_count} increments once per API call;</li>
 *   <li>token sizes are estimated as {@code len(prompt) // 4} (characters, floor
 *       division — {@code _estimate_token_size}, feedback.py:96-98), NOT real
 *       tokenizer counts;</li>
 *   <li>{@code start_time} initializes lazily on the FIRST recorded request
 *       (feedback.py:124-125 {@code if llm_metrics["start_time"] is None: reset});</li>
 *   <li>the summary reports total/max/min/avg token size, wall time in seconds and
 *       minutes, and mean request time, with 0 defaults for empty lists
 *       (feedback.py:72-94);</li>
 *   <li>QUIRK kept from the baseline: {@code request_times} is only ever appended
 *       on the API-call EXCEPTION paths (feedback.py:195-196/296-297/388-389), so
 *       {@code avg_request_time} in Python measures failed requests only. Callers
 *       decide when to call {@link #recordRequestTime}; a faithful driver calls it
 *       only on failure, an improved one on every call (worth registering if the
 *       summary is ever compared byte-for-byte).</li>
 * </ul>
 *
 * <p>Token estimates count Java {@code String.length()} (UTF-16 units) — identical
 * to Python's code-point {@code len} for BMP-only prompts; the templates and the
 * embedded Java sources are BMP in practice.
 *
 * <p>Not thread-safe by design (the Python global is single-threaded); guard
 * externally if the search loop ever parallelizes LLM calls.
 */
public final class LlmMetrics {

    private final DoubleSupplier clockSeconds;

    private int requestCount = 0;
    private final List<Integer> tokenSizes = new ArrayList<>();
    private Double startTime = null;
    private Double endTime = null;
    private final List<Double> requestTimes = new ArrayList<>();

    public LlmMetrics() {
        this(() -> System.nanoTime() / 1e9);
    }

    /** @param clockSeconds injectable clock (seconds, like Python {@code time.time()}) */
    public LlmMetrics(DoubleSupplier clockSeconds) {
        this.clockSeconds = clockSeconds;
    }

    /** {@code _estimate_token_size} — {@code len(text) // 4}. */
    public static int estimateTokenSize(String text) {
        return text.length() / 4;
    }

    /** {@code reset_llm_metrics()} — clears counters and stamps {@code start_time}. */
    public void reset() {
        requestCount = 0;
        tokenSizes.clear();
        startTime = clockSeconds.getAsDouble();
        endTime = null;
        requestTimes.clear();
    }

    /**
     * Call once per API request with the outgoing prompt
     * (feedback.py:121-132): lazily initializes {@code start_time}, bumps
     * {@code request_count}, appends the chars/4 token estimate.
     *
     * @return the request start time in seconds, to be passed to
     *         {@link #recordRequestTime(double)} when the caller records duration
     */
    public double recordRequest(String prompt) {
        if (startTime == null) {
            reset();
        }
        requestCount++;
        tokenSizes.add(estimateTokenSize(prompt));
        return clockSeconds.getAsDouble();
    }

    /** Records {@code time.time() - request_start} for a request started at {@code requestStart}. */
    public void recordRequestTime(double requestStart) {
        requestTimes.add(clockSeconds.getAsDouble() - requestStart);
    }

    public int requestCount() {
        return requestCount;
    }

    public List<Integer> tokenSizes() {
        return List.copyOf(tokenSizes);
    }

    /** {@code get_llm_metrics_summary()} (feedback.py:72-94). */
    public Summary summary() {
        endTime = clockSeconds.getAsDouble();
        double start = startTime == null ? endTime : startTime;
        double totalTime = endTime - start;
        return new Summary(
                requestCount,
                tokenSizes.stream().mapToInt(Integer::intValue).max().orElse(0),
                tokenSizes.stream().mapToInt(Integer::intValue).min().orElse(0),
                tokenSizes.isEmpty() ? 0.0
                        : tokenSizes.stream().mapToInt(Integer::intValue).average().orElse(0.0),
                totalTime,
                totalTime / 60,
                requestTimes.isEmpty() ? 0.0
                        : requestTimes.stream().mapToDouble(Double::doubleValue).average().orElse(0.0));
    }

    /** Mirror of the Python summary dict (feedback.py:83-91). */
    public record Summary(int totalRequests,
                          int maxTokenSize,
                          int minTokenSize,
                          double avgTokenSize,
                          double totalTimeSeconds,
                          double totalTimeMinutes,
                          double avgRequestTime) {
    }
}
