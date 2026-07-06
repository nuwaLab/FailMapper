package org.failmapper.coverage;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Path;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;

import org.failmapper.core.model.CoverageSnapshot;
import org.jacoco.core.analysis.Analyzer;
import org.jacoco.core.analysis.CoverageBuilder;
import org.jacoco.core.analysis.IClassCoverage;
import org.jacoco.core.analysis.ICounter;
import org.jacoco.core.tools.ExecFileLoader;

/**
 * Reads a JaCoCo .exec file through the JaCoCo core API and attributes
 * coverage to the target class by EXACT fully-qualified-name match — the
 * contract fix for the Python port's substring matching of report rows
 * (which let {@code FixtureCalcHelper} pollute {@code FixtureCalc}'s
 * numbers). No XML/HTML/console report is ever parsed.
 */
public final class CoverageReader {

    /**
     * @param execFile       JaCoCo execution data written by the agent-instrumented fork
     * @param classesDir     root directory of the UNINSTRUMENTED .class files that were
     *                       on the fork's classpath (same bytes — JaCoCo matches by class id)
     * @param targetClassFqn fully-qualified name of the class to attribute, e.g. {@code com.acme.Calc}
     * @return the target's snapshot; {@link CoverageSnapshot#zero} when the target class
     *         does not appear under {@code classesDir} (documented: absence is zero
     *         coverage, never an exception and never a fuzzy fallback match)
     */
    public CoverageSnapshot read(Path execFile, Path classesDir, String targetClassFqn) {
        Objects.requireNonNull(execFile, "execFile");
        Objects.requireNonNull(classesDir, "classesDir");
        Objects.requireNonNull(targetClassFqn, "targetClassFqn");
        try {
            ExecFileLoader loader = new ExecFileLoader();
            loader.load(execFile.toFile());
            CoverageBuilder builder = new CoverageBuilder();
            Analyzer analyzer = new Analyzer(loader.getExecutionDataStore(), builder);
            analyzer.analyzeAll(classesDir.toFile());

            String vmName = targetClassFqn.replace('.', '/');
            for (IClassCoverage classCoverage : builder.getClasses()) {
                if (classCoverage.getName().equals(vmName)) { // EXACT match — no substring
                    return toSnapshot(targetClassFqn, classCoverage);
                }
            }
            return CoverageSnapshot.zero(targetClassFqn);
        } catch (IOException e) {
            throw new UncheckedIOException(
                    "failed to read coverage for " + targetClassFqn + " from " + execFile, e);
        }
    }

    private static CoverageSnapshot toSnapshot(String targetClassFqn, IClassCoverage coverage) {
        int coveredLines = coverage.getLineCounter().getCoveredCount();
        int missedLines = coverage.getLineCounter().getMissedCount();
        int coveredBranches = coverage.getBranchCounter().getCoveredCount();
        int missedBranches = coverage.getBranchCounter().getMissedCount();

        TreeSet<Integer> uncoveredLines = new TreeSet<>();
        for (int line = coverage.getFirstLine(); line <= coverage.getLastLine(); line++) {
            if (coverage.getLine(line).getStatus() == ICounter.NOT_COVERED) {
                uncoveredLines.add(line);
            }
        }
        return new CoverageSnapshot(
                targetClassFqn,
                percentage(coveredLines, missedLines),
                percentage(coveredBranches, missedBranches),
                coveredLines,
                missedLines,
                coveredBranches,
                missedBranches,
                // sorted set -> deterministic iteration/serialization order
                java.util.Collections.unmodifiableSortedSet(uncoveredLines));
    }

    private static double percentage(int covered, int missed) {
        int total = covered + missed;
        return total == 0 ? 0.0 : covered * 100.0 / total;
    }
}
