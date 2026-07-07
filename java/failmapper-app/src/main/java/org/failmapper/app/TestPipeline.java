package org.failmapper.app;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import org.failmapper.analysis.ClassModelExtractor;
import org.failmapper.analysis.SourceAnalyzer;
import org.failmapper.core.model.ClassModel;
import org.failmapper.core.model.CoverageSnapshot;
import org.failmapper.core.model.Diagnostic;
import org.failmapper.core.model.ModuleModel;
import org.failmapper.core.model.TestRunResult;
import org.failmapper.coverage.CoverageReader;
import org.failmapper.coverage.JacocoAgent;
import org.failmapper.exec.CompileResult;
import org.failmapper.exec.ForkedTestRunner;
import org.failmapper.exec.InMemoryCompiler;
import org.failmapper.search.DefaultEvaluator;
import org.failmapper.search.Evaluator;
import org.failmapper.search.FaTestState;
import org.failmapper.search.UncoveredLine;

/**
 * The EVALUATE stage of D6 — the Java replacement for the Maven/JaCoCo half of Python
 * {@code FATestState.evaluate}, composed from typed M2 facts:
 *
 * <ol>
 *   <li>{@link InMemoryCompiler} against the module's {@code outputDirectory} +
 *       transitive {@code testClasspath} (class files go ONLY to a per-evaluation temp
 *       dir under the caller's work root — never the user project tree);</li>
 *   <li>on success: {@link ForkedTestRunner} with the JaCoCo agent attached to OUR
 *       fork's command line (explicit timeout — a hang is data) +
 *       {@link CoverageReader} exact-FQN attribution for the target class;</li>
 *   <li>{@link DefaultEvaluator} applies the outcome to the {@link FaTestState}
 *       (compile errors → {@code compilationErrors} with coverage 0; the
 *       keep-parent-coverage restore of {@code fa_mcts.py:2772-2775} is the APPLIER's
 *       job, not this stage's);</li>
 *   <li>{@code state.uncoveredLines} filled from the coverage snapshot + target source
 *       text (feeds D1 {@code target_line} actions).</li>
 * </ol>
 */
public final class TestPipeline implements Evaluator {

    private final ModuleModel module;
    private final String targetFqn;
    private final Path workRoot;
    private final Duration forkTimeout;
    private final List<String> sourceLines;

    private final InMemoryCompiler compiler = new InMemoryCompiler();
    private final ForkedTestRunner runner = new ForkedTestRunner();
    private final CoverageReader coverageReader = new CoverageReader();
    private final SourceAnalyzer analyzer = new SourceAnalyzer();
    private final AtomicInteger evaluationCounter = new AtomicInteger();

    /**
     * @param module      the build-oracle module containing the target class
     * @param targetFqn   FQN of the class under test (exact coverage attribution)
     * @param workRoot    scratch root for class files / exec files / fork logs; must
     *                    NOT be inside the user project's source tree
     * @param forkTimeout hard wall-clock limit per test fork
     * @param sourceCode  the target class source (uncovered-line content lookup)
     */
    public TestPipeline(ModuleModel module, String targetFqn, Path workRoot,
                        Duration forkTimeout, String sourceCode) {
        this.module = module;
        this.targetFqn = targetFqn;
        this.workRoot = workRoot;
        this.forkTimeout = forkTimeout;
        this.sourceLines = sourceCode == null ? List.of() : sourceCode.lines().toList();
    }

    @Override
    public void evaluate(FaTestState state) {
        Path evalDir;
        try {
            Files.createDirectories(workRoot);
            evalDir = Files.createTempDirectory(workRoot,
                    "eval-" + evaluationCounter.incrementAndGet() + "-");
        } catch (IOException e) {
            throw new UncheckedIOException("cannot create evaluation work dir under " + workRoot, e);
        }

        String testFqn = testClassFqn(state.testCode);
        if (testFqn == null) {
            // Unparseable test code: surface as a compilation error (the Python path
            // would fail javac the same way; here we cannot even name the class).
            TestRunResult unparseable = new TestRunResult(false, List.of(new Diagnostic(
                    Diagnostic.Kind.ERROR, "<generated>", 0, 0,
                    "generated test code is not parseable as a Java compilation unit")),
                    0, 0, List.of(), 0);
            new DefaultEvaluator(unparseable, null).evaluate(state);
            return;
        }

        List<String> compileClasspath = new ArrayList<>();
        compileClasspath.add(module.outputDirectory());
        compileClasspath.addAll(module.testClasspath());

        Path testClassesDir = evalDir.resolve("test-classes");
        CompileResult compileResult =
                compiler.compile(testFqn, state.testCode, compileClasspath, testClassesDir);

        if (!compileResult.success()) {
            TestRunResult failed = new TestRunResult(
                    false, compileResult.diagnostics(), 0, 0, List.of(), 0);
            new DefaultEvaluator(failed, null).evaluate(state);
            return;
        }

        Path execFile = evalDir.resolve("jacoco.exec");
        List<String> runClasspath = new ArrayList<>();
        runClasspath.add(testClassesDir.toString());
        runClasspath.addAll(compileClasspath);

        TestRunResult runResult = runner.run(new ForkedTestRunner.RunSpec(
                runClasspath, testFqn, forkTimeout, evalDir, JacocoAgent.javaAgentArg(execFile)));

        CoverageSnapshot snapshot = Files.exists(execFile)
                ? coverageReader.read(execFile, Path.of(module.outputDirectory()), targetFqn)
                : CoverageSnapshot.zero(targetFqn);

        new DefaultEvaluator(runResult, snapshot).evaluate(state);

        // Uncovered lines with source content for D1 target_line actions.
        List<UncoveredLine> uncovered = new ArrayList<>();
        for (Integer line : snapshot.uncoveredLineNumbers()) {
            String content = line >= 1 && line <= sourceLines.size()
                    ? sourceLines.get(line - 1) : "";
            uncovered.add(new UncoveredLine(line, content));
        }
        state.uncoveredLines = uncovered;
    }

    /** FQN of the primary type in the generated test code, or null when unparseable. */
    private String testClassFqn(String testCode) {
        return analyzer.parse(testCode)
                .flatMap(cu -> new ClassModelExtractor().extractPrimary(cu, "<generated>"))
                .map(ClassModel::fqn)
                .orElse(null);
    }
}
