package org.failmapper.exec;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.TimeUnit;

import com.fasterxml.jackson.databind.ObjectMapper;

import org.failmapper.core.model.TestFailure;
import org.failmapper.core.model.TestRunResult;

/**
 * Runs one test class in a FORKED JVM (the current JVM's own {@code java}
 * binary) with a hard wall-clock timeout — the contract fix for the Python
 * port's un-timeout-ed subprocess hangs (root cause #4): no fork ever blocks
 * without an explicit deadline.
 *
 * <p>Mechanics: {@code java [javaAgentArg] -cp <spec.classpath + ForkClasspath
 * .currentModuleClasspath()> org.failmapper.exec.TestRunnerMain <testClassFqn>
 * <resultFile>}. Stdout/stderr are redirected to log files in {@code workDir}
 * (human debugging only — NEVER parsed for structured facts). The fork writes
 * a {@link TestRunResult} JSON result file; that file is the single source of
 * structured truth.</p>
 *
 * <p><b>Timeout convention (consumed by the reward layer):</b> when the fork
 * exceeds {@code spec.timeout()} it is {@link Process#destroyForcibly()
 * destroyed forcibly} and the returned result is synthetic:
 * {@code compiled=true, testsRun=0, testsPassed=0}, one
 * {@link TestFailure} with {@code throwableClass="TimeoutKill"} and
 * {@code assertionFailure=false}, and {@code executionTimeMillis} = real
 * elapsed wall time. A timeout is DATA, not an exception — the search must be
 * able to see (and penalize) hangs.</p>
 */
public final class ForkedTestRunner {

    /** Synthetic {@link TestFailure#throwableClass()} marking a timed-out, force-killed fork. */
    public static final String TIMEOUT_KILL = "TimeoutKill";

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /**
     * @param classpath    user/project classpath entries the test needs (compiled
     *                     test classes dir, code under test, its dependencies);
     *                     the runner appends {@link ForkClasspath#currentModuleClasspath()}
     * @param testClassFqn fully-qualified name of the test class to run (FQN-keyed, contract)
     * @param timeout      REQUIRED hard wall-clock limit for the whole fork
     * @param workDir      directory for the result file and fork stdout/stderr logs;
     *                     created if absent; must not be inside a user project's src/ tree
     * @param javaAgentArg optional complete {@code -javaagent:...} argument
     *                     (e.g. from failmapper-coverage's JacocoAgent); null for none
     */
    public record RunSpec(
            List<String> classpath,
            String testClassFqn,
            Duration timeout,
            Path workDir,
            String javaAgentArg) {

        public RunSpec {
            Objects.requireNonNull(classpath, "classpath");
            Objects.requireNonNull(testClassFqn, "testClassFqn");
            Objects.requireNonNull(timeout, "timeout — every fork MUST have an explicit timeout");
            Objects.requireNonNull(workDir, "workDir");
            if (timeout.isZero() || timeout.isNegative()) {
                throw new IllegalArgumentException("timeout must be positive, got " + timeout);
            }
            classpath = List.copyOf(classpath);
        }

        /** Spec without a java agent. */
        public RunSpec(List<String> classpath, String testClassFqn, Duration timeout, Path workDir) {
            this(classpath, testClassFqn, timeout, workDir, null);
        }
    }

    /**
     * @return the fork's {@link TestRunResult} (test failures and timeouts are data)
     * @throws ForkedTestRunnerException on infrastructure failure only (fork
     *                                   unstartable, abnormal exit, missing/unreadable result file)
     */
    public TestRunResult run(RunSpec spec) {
        Objects.requireNonNull(spec, "spec");
        try {
            Files.createDirectories(spec.workDir());
            Path resultFile = spec.workDir().resolve("fm-testrun-" + System.nanoTime() + ".json");
            Path stdoutLog = spec.workDir().resolve("fork-stdout.log");
            Path stderrLog = spec.workDir().resolve("fork-stderr.log");

            List<String> command = new ArrayList<>();
            command.add(javaBinary());
            if (spec.javaAgentArg() != null && !spec.javaAgentArg().isBlank()) {
                command.add(spec.javaAgentArg());
            }
            command.add("-cp");
            command.add(String.join(File.pathSeparator, assembleClasspath(spec)));
            command.add(TestRunnerMain.class.getName());
            command.add(spec.testClassFqn());
            command.add(resultFile.toString());

            ProcessBuilder builder = new ProcessBuilder(command)
                    .directory(spec.workDir().toFile())
                    .redirectOutput(stdoutLog.toFile())
                    .redirectError(stderrLog.toFile());

            long startNanos = System.nanoTime();
            Process process = builder.start();
            boolean finished = process.waitFor(spec.timeout().toMillis(), TimeUnit.MILLISECONDS);
            long elapsedMillis = (System.nanoTime() - startNanos) / 1_000_000L;

            if (!finished) {
                process.destroyForcibly();
                // Bounded reap — never block indefinitely, even on the kill.
                process.waitFor(10, TimeUnit.SECONDS);
                return timeoutResult(spec, elapsedMillis);
            }

            int exitCode = process.exitValue();
            if (!Files.isReadable(resultFile)) {
                throw new ForkedTestRunnerException(
                        "forked test run of " + spec.testClassFqn() + " exited with code " + exitCode
                                + " without writing a result file; fork logs: " + stdoutLog + ", " + stderrLog);
            }
            return MAPPER.readValue(resultFile.toFile(), TestRunResult.class);
        } catch (IOException e) {
            throw new ForkedTestRunnerException(
                    "failed to fork test run of " + spec.testClassFqn() + " in " + spec.workDir(), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ForkedTestRunnerException(
                    "interrupted while waiting for forked test run of " + spec.testClassFqn(), e);
        }
    }

    /** Caller-supplied entries first (they win lookups), then the runner's own; de-duplicated, order kept. */
    private static List<String> assembleClasspath(RunSpec spec) {
        LinkedHashSet<String> entries = new LinkedHashSet<>(spec.classpath());
        entries.addAll(ForkClasspath.currentModuleClasspath());
        return List.copyOf(entries);
    }

    private static String javaBinary() {
        String executable = System.getProperty("os.name", "").toLowerCase().contains("win")
                ? "java.exe" : "java";
        return Path.of(System.getProperty("java.home"), "bin", executable).toString();
    }

    private static TestRunResult timeoutResult(RunSpec spec, long elapsedMillis) {
        TestFailure kill = new TestFailure(
                spec.testClassFqn(),
                "*",
                false,
                TIMEOUT_KILL,
                "forked JVM exceeded the " + spec.timeout().toMillis()
                        + " ms timeout and was destroyed forcibly",
                "");
        return new TestRunResult(true, List.of(), 0, 0, List.of(kill), elapsedMillis);
    }
}
