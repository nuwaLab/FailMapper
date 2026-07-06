package org.failmapper.exec;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;

import org.failmapper.core.model.TestFailure;
import org.failmapper.core.model.TestRunResult;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * End-to-end: compile a test class in-memory (JUnit Jupiter API taken from the
 * current module classpath via {@link ForkClasspath}), run it in a forked JVM,
 * and assert the structured result.
 */
class ForkedTestRunnerTest {

    private final InMemoryCompiler compiler = new InMemoryCompiler();
    private final ForkedTestRunner runner = new ForkedTestRunner();

    @Test
    void runsCompiledTestClassInForkAndClassifiesFailuresTyped(@TempDir Path tempDir) {
        Path testClasses = tempDir.resolve("test-classes");
        CompileResult compiled = compiler.compile(
                "fmtest.SampleTest",
                """
                package fmtest;
                import org.junit.jupiter.api.Test;
                import static org.junit.jupiter.api.Assertions.assertEquals;
                public class SampleTest {
                    @Test void passes() { assertEquals(2, 1 + 1); }
                    @Test void failsAssertion() { assertEquals(3, 1 + 1); }
                    @Test void throwsRuntime() { throw new IllegalStateException("boom"); }
                }
                """,
                ForkClasspath.currentModuleClasspath(),
                testClasses);
        assertThat(compiled.success())
                .as("test source must compile against the module classpath: %s", compiled.diagnostics())
                .isTrue();

        TestRunResult result = runner.run(new ForkedTestRunner.RunSpec(
                List.of(testClasses.toString()),
                "fmtest.SampleTest",
                Duration.ofSeconds(60),
                tempDir.resolve("work")));

        assertThat(result.compiled()).isTrue();
        assertThat(result.testsRun()).isEqualTo(3);
        assertThat(result.testsPassed()).isEqualTo(1);
        assertThat(result.executionTimeMillis()).isGreaterThan(0L);
        assertThat(result.failures()).hasSize(2);

        TestFailure assertionFailure = result.failures().stream()
                .filter(f -> f.testMethod().equals("failsAssertion"))
                .findFirst().orElseThrow();
        assertThat(assertionFailure.testClass()).isEqualTo("fmtest.SampleTest");
        assertThat(assertionFailure.assertionFailure())
                .as("opentest4j AssertionFailedError extends AssertionError -> typed classification")
                .isTrue();
        assertThat(assertionFailure.throwableClass()).isEqualTo("org.opentest4j.AssertionFailedError");

        TestFailure runtimeFailure = result.failures().stream()
                .filter(f -> f.testMethod().equals("throwsRuntime"))
                .findFirst().orElseThrow();
        assertThat(runtimeFailure.assertionFailure()).isFalse();
        assertThat(runtimeFailure.throwableClass()).isEqualTo("java.lang.IllegalStateException");
        assertThat(runtimeFailure.message()).isEqualTo("boom");
        assertThat(runtimeFailure.stackTrace()).contains("fmtest.SampleTest.throwsRuntime");
    }

    @Test
    void messageTextNeverDrivesClassification(@TempDir Path tempDir) {
        // The Python port classified failures containing 'expected' as assertion
        // failures. This runtime exception embeds that keyword — it must still be
        // classified as NON-assertion because classification is instanceof-typed.
        Path testClasses = tempDir.resolve("test-classes");
        CompileResult compiled = compiler.compile(
                "fmtest.KeywordTrapTest",
                """
                package fmtest;
                import org.junit.jupiter.api.Test;
                public class KeywordTrapTest {
                    @Test void trap() { throw new RuntimeException("expected value but was absent"); }
                }
                """,
                ForkClasspath.currentModuleClasspath(),
                testClasses);
        assertThat(compiled.success()).isTrue();

        TestRunResult result = runner.run(new ForkedTestRunner.RunSpec(
                List.of(testClasses.toString()),
                "fmtest.KeywordTrapTest",
                Duration.ofSeconds(60),
                tempDir.resolve("work")));

        assertThat(result.failures()).hasSize(1);
        assertThat(result.failures().get(0).assertionFailure()).isFalse();
        assertThat(result.failures().get(0).message()).contains("expected");
    }
}
