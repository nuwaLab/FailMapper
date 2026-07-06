package org.failmapper.core.model;

import java.util.List;

/**
 * Outcome of compiling + running one generated test class.
 * Compilation errors and test failures are disjoint by construction:
 * if {@code compiled} is false, {@code failures} is empty and diagnostics
 * carry the errors; executionTimeMillis is real wall time (the Python port
 * hardcoded 0.0 — contract I-register).
 */
public record TestRunResult(
        boolean compiled,
        List<Diagnostic> diagnostics,
        int testsRun,
        int testsPassed,
        List<TestFailure> failures,
        long executionTimeMillis) {

    public boolean hasCompilationErrors() {
        return !compiled;
    }
}
