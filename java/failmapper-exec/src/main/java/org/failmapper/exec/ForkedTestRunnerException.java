package org.failmapper.exec;

/**
 * Infrastructure failure of a forked test run (fork could not start, exited
 * abnormally, or produced no result file). Test failures and timeouts are NOT
 * exceptions — they come back as {@link org.failmapper.core.model.TestRunResult}
 * data.
 */
public class ForkedTestRunnerException extends RuntimeException {

    public ForkedTestRunnerException(String message) {
        super(message);
    }

    public ForkedTestRunnerException(String message, Throwable cause) {
        super(message, cause);
    }
}
