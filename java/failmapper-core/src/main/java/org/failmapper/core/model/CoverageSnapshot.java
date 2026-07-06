package org.failmapper.core.model;

import java.util.Set;

/**
 * Per-target-class coverage from JaCoCo core API — exact attribution by FQN
 * (replaces substring matching of report rows). Percentages are 0..100.
 */
public record CoverageSnapshot(
        String targetClassFqn,
        double lineCoverage,
        double branchCoverage,
        int coveredLines,
        int missedLines,
        int coveredBranches,
        int missedBranches,
        Set<Integer> uncoveredLineNumbers) {

    public static CoverageSnapshot zero(String targetClassFqn) {
        return new CoverageSnapshot(targetClassFqn, 0.0, 0.0, 0, 0, 0, 0, Set.of());
    }
}
