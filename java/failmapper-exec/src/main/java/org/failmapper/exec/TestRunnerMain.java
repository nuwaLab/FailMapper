package org.failmapper.exec;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import com.fasterxml.jackson.databind.ObjectMapper;

import org.failmapper.core.model.TestFailure;
import org.failmapper.core.model.TestRunResult;
import org.junit.platform.engine.TestExecutionResult;
import org.junit.platform.engine.discovery.DiscoverySelectors;
import org.junit.platform.launcher.Launcher;
import org.junit.platform.launcher.LauncherDiscoveryRequest;
import org.junit.platform.launcher.TestExecutionListener;
import org.junit.platform.launcher.TestIdentifier;
import org.junit.platform.launcher.core.LauncherDiscoveryRequestBuilder;
import org.junit.platform.launcher.core.LauncherFactory;

/**
 * Entry point that runs INSIDE the forked JVM (launched by
 * {@link ForkedTestRunner}, never invoked in-process).
 *
 * <p>Args: {@code <testClassFqn> <resultFilePath>}. Discovers and executes the
 * test class via the JUnit Platform Launcher, collects per-test results with a
 * typed listener, and serializes a {@link TestRunResult} as JSON to the result
 * file.</p>
 *
 * <p><b>Classification contract:</b> {@code assertionFailure} is decided by
 * {@code throwable instanceof AssertionError} — typed, never by matching
 * failure text (the fix for the Python port's 'expected'-keyword
 * misclassification). {@code executionTimeMillis} is real wall time.</p>
 *
 * <p><b>Exit codes:</b> 0 whenever a result file was written — test failures
 * are DATA in the result, not process errors. Non-zero (2) only for
 * infrastructure problems (bad args, undiscoverable class, unwritable result
 * file), in which case no result file exists and {@link ForkedTestRunner}
 * raises {@link ForkedTestRunnerException}.</p>
 */
public final class TestRunnerMain {

    private TestRunnerMain() {
    }

    public static void main(String[] args) {
        if (args.length != 2) {
            System.err.println("usage: TestRunnerMain <testClassFqn> <resultFilePath>");
            System.exit(2);
        }
        String testClassFqn = args[0];
        Path resultFile = Path.of(args[1]);
        try {
            LauncherDiscoveryRequest request = LauncherDiscoveryRequestBuilder.request()
                    .selectors(DiscoverySelectors.selectClass(testClassFqn))
                    .build();
            Launcher launcher = LauncherFactory.create();
            CollectingListener listener = new CollectingListener(testClassFqn);

            long startNanos = System.nanoTime();
            launcher.execute(request, listener);
            long elapsedMillis = Math.max(1L, (System.nanoTime() - startNanos) / 1_000_000L);

            TestRunResult result = new TestRunResult(
                    true,
                    List.of(),
                    listener.testsRun,
                    listener.testsPassed,
                    List.copyOf(listener.failures),
                    elapsedMillis);
            if (resultFile.getParent() != null) {
                Files.createDirectories(resultFile.getParent());
            }
            new ObjectMapper().writeValue(resultFile.toFile(), result);
            // Normal return -> exit 0 even when tests failed: failures are data.
        } catch (Exception e) {
            e.printStackTrace();
            System.exit(2);
        }
    }

    /** Collects per-test outcomes; classification is typed (instanceof), never textual. */
    private static final class CollectingListener implements TestExecutionListener {
        private final String defaultClassName;
        private int testsRun;
        private int testsPassed;
        private final List<TestFailure> failures = new ArrayList<>();

        CollectingListener(String defaultClassName) {
            this.defaultClassName = defaultClassName;
        }

        @Override
        public void executionFinished(TestIdentifier identifier, TestExecutionResult result) {
            boolean failed = result.getStatus() == TestExecutionResult.Status.FAILED;
            if (identifier.isTest()) {
                testsRun++;
                if (result.getStatus() == TestExecutionResult.Status.SUCCESSFUL) {
                    testsPassed++;
                }
            } else if (!failed) {
                return; // successful containers are not results
            }
            if (failed) {
                failures.add(toFailure(identifier, result));
            }
        }

        private TestFailure toFailure(TestIdentifier identifier, TestExecutionResult result) {
            Throwable throwable = result.getThrowable().orElse(null);
            String className = defaultClassName;
            String methodName = identifier.isTest() ? identifier.getDisplayName() : "<container>";
            if (identifier.getSource().orElse(null)
                    instanceof org.junit.platform.engine.support.descriptor.MethodSource method) {
                className = method.getClassName();
                methodName = method.getMethodName();
            } else if (identifier.getSource().orElse(null)
                    instanceof org.junit.platform.engine.support.descriptor.ClassSource cls) {
                className = cls.getClassName();
            }
            return new TestFailure(
                    className,
                    methodName,
                    throwable instanceof AssertionError, // TYPED classification — contract fix
                    throwable == null ? "<unknown>" : throwable.getClass().getName(),
                    throwable == null || throwable.getMessage() == null ? "" : throwable.getMessage(),
                    stackTraceOf(throwable));
        }

        private static String stackTraceOf(Throwable throwable) {
            if (throwable == null) {
                return "";
            }
            StringWriter out = new StringWriter();
            throwable.printStackTrace(new PrintWriter(out));
            String trace = out.toString();
            return trace.length() > 20_000 ? trace.substring(0, 20_000) : trace;
        }
    }
}
