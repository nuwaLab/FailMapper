package org.failmapper.search;

import java.util.ArrayList;

import org.failmapper.core.model.CoverageSnapshot;
import org.failmapper.core.model.Diagnostic;
import org.failmapper.core.model.TestFailure;
import org.failmapper.core.model.TestRunResult;

/**
 * Adapter {@link Evaluator}: applies a typed M2 execution outcome
 * ({@link TestRunResult} + {@link CoverageSnapshot}) to a {@link FaTestState},
 * mirroring the post-run flow of {@code FATestState.evaluate}
 * ({@code test_state.py:101-225}):
 * <ol>
 *   <li>compilation errors from {@link Diagnostic}s of kind ERROR; when present the
 *       evaluation STOPS with {@code coverage = 0.0} ({@code test_state.py:132-147});</li>
 *   <li>coverage from {@code lineCoverage} with the keep-previous rule: a zero new
 *       coverage keeps a positive previous value ({@code test_state.py:150-163});</li>
 *   <li>detected bugs from failing tests: assertion failures are pre-marked
 *       verified/is_real_bug=true, bug_category=logical, bug_type=incorrect_behavior
 *       (D11 note, {@code test_state.py:168-198}) — the test method comes TYPED from
 *       {@link TestFailure} instead of the Python regex over console text (layer-B
 *       input improvement); non-assertion failures enter as unverified bugs whose
 *       error text carries the throwable FQN so the D11 classifier can type them;
 *       both dedupe by test-method name against existing detected bugs;</li>
 *   <li>the tracking sequence in Python order ({@code test_state.py:200-216}):
 *       {@code analyze_test_logic_properties} → D11 classifier → F9 pattern tracker →
 *       I3 branch tracker → {@code calculate_risk_metrics}.</li>
 * </ol>
 */
public final class DefaultEvaluator implements Evaluator {

    private final TestRunResult runResult;
    private final CoverageSnapshot coverage;
    private final BranchCoverageTracker.LineBranchData lineBranchData;

    private final LogicalBugClassifier classifier = new LogicalBugClassifier();
    private final PatternCoverageTracker patternTracker = new PatternCoverageTracker();
    private final BranchCoverageTracker branchTracker = new BranchCoverageTracker();

    public DefaultEvaluator(TestRunResult runResult, CoverageSnapshot coverage) {
        this(runResult, coverage, null);
    }

    /** Overload with per-line branch detail for the I3 predicate. */
    public DefaultEvaluator(TestRunResult runResult, CoverageSnapshot coverage,
                            BranchCoverageTracker.LineBranchData lineBranchData) {
        this.runResult = runResult;
        this.coverage = coverage;
        this.lineBranchData = lineBranchData;
    }

    @Override
    public void evaluate(FaTestState state) {
        // Preserve the current coverage in case the run failed (test_state.py:119).
        double previousCoverage = state.coverage;

        // (1) Compilation errors — test_state.py:132-147.
        state.compilationErrors = new ArrayList<>();
        if (runResult != null && runResult.diagnostics() != null) {
            for (Diagnostic d : runResult.diagnostics()) {
                if (d.kind() == Diagnostic.Kind.ERROR) {
                    state.compilationErrors.add(d.message());
                }
            }
        }
        if (!state.compilationErrors.isEmpty()) {
            state.executed = true;
            state.coverage = 0.0;
            return;
        }

        // (2) Coverage with keep-previous rule — test_state.py:150-163.
        double newCoverage = coverage == null ? 0.0 : coverage.lineCoverage();
        if (newCoverage > 0) {
            state.coverage = newCoverage;
        } else if (previousCoverage > 0) {
            state.coverage = previousCoverage;
        } else {
            state.coverage = 0.0;
        }

        state.executed = true;

        // (3) Detected bugs from failing tests — test_state.py:168-198.
        if (runResult != null && runResult.failures() != null) {
            for (TestFailure failure : runResult.failures()) {
                String methodName = failure.testMethod();
                if (methodName == null || methodName.isEmpty()) {
                    continue;
                }
                boolean alreadyDetected = false;
                for (DetectedBug existing : state.detectedBugs) {
                    if (methodName.equals(existing.testMethod)) {
                        alreadyDetected = true;
                        break;
                    }
                }
                if (alreadyDetected) {
                    continue;
                }
                if (failure.assertionFailure()) {
                    // Pre-verified assertion-failure bug (test_state.py:175-185).
                    DetectedBug bug = new DetectedBug(
                            "assertion_failure",
                            failure.message() == null ? "" : failure.message(),
                            methodName,
                            "AssertionError",
                            "medium");
                    bug.verified = true;
                    bug.isRealBug = Boolean.TRUE;
                    bug.bugCategory = "logical";
                    bug.bugType = "incorrect_behavior";
                    state.detectedBugs.add(bug);
                    state.logicalBugs.add(bug);
                    state.hasBugs = true;
                    state.assertionFailures.add(new FaTestState.AssertionFailureNote(
                            methodName, failure.message() == null ? "" : failure.message()));
                } else {
                    // Unverified runtime failure; the throwable FQN in the error text
                    // feeds the D11 regex table (e.g. NullPointerException → null_reference).
                    String throwable = failure.throwableClass() == null ? "" : failure.throwableClass();
                    DetectedBug bug = new DetectedBug(
                            "runtime_error",
                            failure.message() == null ? "" : failure.message(),
                            methodName,
                            throwable,
                            "medium");
                    state.detectedBugs.add(bug);
                }
            }
        }

        // (4) Tracking sequence in Python order (test_state.py:200-216).
        state.analyzeTestLogicProperties();
        classifier.classify(state);
        patternTracker.track(state);
        branchTracker.track(state, coverage, lineBranchData);
        state.calculateRiskMetrics();
    }
}
