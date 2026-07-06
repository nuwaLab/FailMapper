package org.failmapper.exec;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;

import org.failmapper.core.model.TestRunResult;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Hang containment (contract root cause #4): a test that never returns must
 * not hang FailMapper — the fork is destroyed at the deadline and the timeout
 * surfaces as DATA (synthetic TimeoutKill failure), not as an exception.
 */
class ForkedTestRunnerTimeoutTest {

    private static final Duration TIMEOUT = Duration.ofSeconds(3);
    /** JVM startup + forcible-kill reap headroom on a slow machine. */
    private static final Duration CONTAINMENT_CEILING = Duration.ofSeconds(20);

    @Test
    void infiniteLoopIsKilledAtTimeoutAndReportedAsTimeoutKill(@TempDir Path tempDir) {
        Path testClasses = tempDir.resolve("test-classes");
        CompileResult compiled = new InMemoryCompiler().compile(
                "fmtest.HangingTest",
                """
                package fmtest;
                import org.junit.jupiter.api.Test;
                public class HangingTest {
                    @SuppressWarnings("all")
                    @Test void hangsForever() { while (true) { } }
                }
                """,
                ForkClasspath.currentModuleClasspath(),
                testClasses);
        assertThat(compiled.success()).isTrue();

        long startNanos = System.nanoTime();
        TestRunResult result = new ForkedTestRunner().run(new ForkedTestRunner.RunSpec(
                List.of(testClasses.toString()),
                "fmtest.HangingTest",
                TIMEOUT,
                tempDir.resolve("work")));
        long elapsedMillis = (System.nanoTime() - startNanos) / 1_000_000L;
        System.out.println("[timeout-containment] limit=" + TIMEOUT.toMillis()
                + "ms elapsed=" + elapsedMillis + "ms");

        // Containment: returned close to the deadline, nowhere near "forever".
        assertThat(elapsedMillis)
                .as("runner must wait out the full timeout (the fork is genuinely hung)")
                .isGreaterThanOrEqualTo(TIMEOUT.toMillis())
                .as("runner must return promptly after destroyForcibly, elapsed=%dms", elapsedMillis)
                .isLessThan(CONTAINMENT_CEILING.toMillis());

        // Documented synthetic-result convention for the reward layer.
        assertThat(result.compiled()).isTrue();
        assertThat(result.testsRun()).isZero();
        assertThat(result.testsPassed()).isZero();
        assertThat(result.failures()).hasSize(1);
        assertThat(result.failures().get(0).throwableClass()).isEqualTo(ForkedTestRunner.TIMEOUT_KILL);
        assertThat(result.failures().get(0).assertionFailure()).isFalse();
        assertThat(result.executionTimeMillis()).isGreaterThanOrEqualTo(TIMEOUT.toMillis());
    }
}
