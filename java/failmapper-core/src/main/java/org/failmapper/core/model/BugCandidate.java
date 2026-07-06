package org.failmapper.core.model;

/**
 * A potential bug surfaced by a failing test.
 *
 * {@code isRealBug} is deliberately a nullable Boolean — tri-state semantics
 * per contract S-register (test_state.py:684): null = unverified,
 * false = verified false positive, true = verified real bug. Do not collapse
 * to primitive boolean.
 */
public record BugCandidate(
        String testMethod,
        String bugType,
        String severity,
        double confidence,
        String errorMessage,
        Boolean isRealBug,
        Double verificationConfidence) {

    public boolean verifiedReal() {
        return Boolean.TRUE.equals(isRealBug);
    }
}
