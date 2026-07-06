package org.failmapper.llm.smoke;

import com.github.javaparser.ast.CompilationUnit;
import org.failmapper.analysis.ClassModelExtractor;
import org.failmapper.analysis.FailureModelExtractor;
import org.failmapper.analysis.FailureScenarioDetector;
import org.failmapper.analysis.SourceAnalyzer;
import org.failmapper.build.MavenBuildOracle;
import org.failmapper.core.model.BuildModel;
import org.failmapper.core.model.ClassModel;
import org.failmapper.core.model.CoverageSnapshot;
import org.failmapper.core.model.Diagnostic;
import org.failmapper.core.model.FailureModel;
import org.failmapper.core.model.FailureScenario;
import org.failmapper.core.model.MethodModel;
import org.failmapper.core.model.ModuleModel;
import org.failmapper.core.model.TestFailure;
import org.failmapper.core.model.TestRunResult;
import org.failmapper.coverage.CoverageReader;
import org.failmapper.coverage.JacocoAgent;
import org.failmapper.exec.CompileResult;
import org.failmapper.exec.ForkedTestRunner;
import org.failmapper.exec.InMemoryCompiler;
import org.failmapper.llm.CodeExtractor;
import org.failmapper.llm.DeepSeekClient;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.stream.Collectors;

/**
 * M2 acceptance smoke: the FULL generate → compile → execute → coverage loop
 * against a REAL Maven project, driven entirely by typed facts:
 *
 * <ol>
 *   <li>{@link MavenBuildOracle} — effective model + resolved test classpath
 *       (never scrapes pom.xml text, never mutates the user build)</li>
 *   <li>locate the target source file from the model's sourceRoots + FQN</li>
 *   <li>{@link ClassModelExtractor} + {@link FailureModelExtractor} +
 *       {@link FailureScenarioDetector} (top-5 scenarios feed the prompt)</li>
 *   <li>v0 prompt (DeepSeekSmoke style) → {@link DeepSeekClient} →
 *       {@link CodeExtractor}</li>
 *   <li>{@link InMemoryCompiler} against the module's testClasspath +
 *       outputDirectory (class files only to a temp dir, never the user tree)</li>
 *   <li>{@link ForkedTestRunner} with the JaCoCo agent attached to OUR fork's
 *       command line only ({@link JacocoAgent}), explicit 60 s timeout</li>
 *   <li>{@link CoverageReader} — exact-FQN attribution for the target class</li>
 * </ol>
 *
 * <p>Failing generated tests are DATA (potential bugs in the target), never a
 * smoke failure. Exit codes: 0 = every pipeline stage ran (regardless of test
 * verdicts / compile diagnostics being non-empty); 1 = a pipeline STAGE
 * crashed; 2 = usage error.
 *
 * <p>Usage: {@code FullLoopSmoke <maven-project-root> <target-class-fqn>}.
 * Requires {@code DEEPSEEK_API_KEY} in the environment (never printed, never
 * persisted).
 */
public final class FullLoopSmoke {

    private static final Duration FORK_TIMEOUT = Duration.ofSeconds(60);

    private FullLoopSmoke() {
    }

    public static void main(String[] args) {
        if (args.length < 2) {
            System.err.println("usage: FullLoopSmoke <maven-project-root> <target-class-fqn>");
            System.exit(2);
        }
        Path projectRoot = Path.of(args[0]);
        String targetFqn = args[1];
        String stage = "init";
        try {
            stage = "build-oracle";
            System.out.println("== FullLoopSmoke ==");
            System.out.println("== project: " + projectRoot.toAbsolutePath().normalize());
            System.out.println("== target: " + targetFqn);
            BuildModel buildModel = new MavenBuildOracle().analyze(projectRoot);
            System.out.println("== build oracle: " + buildModel.modules().size() + " module(s)");

            stage = "locate-source";
            Located located = locateSource(buildModel, targetFqn);
            System.out.println("== module: " + located.module().gaKey()
                    + " (testClasspath " + located.module().testClasspath().size() + " entries)");
            System.out.println("== source: " + located.sourceFile());

            stage = "analysis";
            String source = Files.readString(located.sourceFile());
            SourceAnalyzer analyzer = new SourceAnalyzer();
            CompilationUnit cu = analyzer.parse(source).orElseThrow(
                    () -> new IllegalStateException("cannot parse " + located.sourceFile()));
            ClassModel classModel = new ClassModelExtractor()
                    .extractPrimary(cu, located.sourceFile().toString())
                    .orElseThrow(() -> new IllegalStateException("no type in " + located.sourceFile()));
            FailureModel failureModel = new FailureModelExtractor().extract(source, classModel.fqn());
            List<FailureScenario> scenarios =
                    new FailureScenarioDetector(source, classModel.fqn(), failureModel).detect();
            List<FailureScenario> topScenarios = scenarios.stream().limit(5).toList();
            System.out.println("== analysis: " + classModel.methods().size() + " methods, "
                    + failureModel.boundaryConditions().size() + " boundary conditions, "
                    + failureModel.operations().size() + " operations, "
                    + scenarios.size() + " failure scenarios");
            for (FailureScenario s : topScenarios) {
                System.out.println("==   scenario [" + s.riskLevel().wire() + "] " + s.type()
                        + " @ line " + s.line() + ": " + oneLine(s.description()));
            }

            stage = "llm-generate";
            String prompt = buildPrompt(source, classModel, failureModel, topScenarios);
            DeepSeekClient client = new DeepSeekClient();
            System.out.println("== model: " + client.model());
            long start = System.currentTimeMillis();
            String reply = client.complete(
                    "You are an expert Java test engineer. Reply with a single complete JUnit 5 test class "
                            + "in a ```java fence. Do not use any mocking framework. Use only real objects.",
                    prompt);
            long genMillis = System.currentTimeMillis() - start;
            System.out.println("== gen time: " + genMillis + " ms, reply " + reply.length() + " chars");

            stage = "extract-code";
            String testCode = new CodeExtractor().extract(reply).orElseThrow(
                    () -> new IllegalStateException("no parseable Java in LLM reply"));
            CompilationUnit testCu = analyzer.parse(testCode).orElseThrow(
                    () -> new IllegalStateException("extracted code not re-parseable"));
            ClassModel testModel = new ClassModelExtractor().extractPrimary(testCu, "<generated>")
                    .orElseThrow(() -> new IllegalStateException("no type in extracted test code"));
            String testFqn = testModel.fqn();
            System.out.println("== extracted test: " + testFqn + ", " + testCode.length() + " chars");

            stage = "compile";
            Path workDir = Files.createTempDirectory("fm-fullloop");
            Path testClassesDir = workDir.resolve("generated-test-classes");
            List<String> compileClasspath = new ArrayList<>();
            compileClasspath.add(located.module().outputDirectory());
            compileClasspath.addAll(located.module().testClasspath());
            CompileResult compileResult = new InMemoryCompiler()
                    .compile(testFqn, testCode, compileClasspath, testClassesDir);
            List<Diagnostic> errors = compileResult.errors();
            System.out.println("== compile: " + (compileResult.success() ? "OK" : "FAIL")
                    + " (" + errors.size() + " errors)");
            if (!compileResult.success()) {
                errors.stream().limit(5).forEach(d ->
                        System.out.println("==   " + d.line() + ":" + d.column() + " " + oneLine(d.message())));
                System.out.println("FULL LOOP SMOKE: COMPLETE (stopped at compile — errors are data)");
                return;
            }

            stage = "fork-run";
            Path execFile = workDir.resolve("jacoco.exec");
            List<String> runClasspath = new ArrayList<>();
            runClasspath.add(testClassesDir.toString());
            runClasspath.addAll(compileClasspath);
            TestRunResult runResult = new ForkedTestRunner().run(new ForkedTestRunner.RunSpec(
                    runClasspath, testFqn, FORK_TIMEOUT, workDir, JacocoAgent.javaAgentArg(execFile)));
            System.out.println("== fork: testsRun=" + runResult.testsRun()
                    + " testsPassed=" + runResult.testsPassed()
                    + " time=" + runResult.executionTimeMillis() + " ms");
            if (runResult.failures().isEmpty()) {
                System.out.println("== failing tests: none");
            } else {
                System.out.println("== failing tests (DATA — potential bugs in the target):");
                for (TestFailure f : runResult.failures()) {
                    System.out.println("==   " + f.testMethod()
                            + " [" + (f.assertionFailure() ? "assertion" : f.throwableClass()) + "] "
                            + oneLine(f.message()));
                }
            }

            stage = "coverage";
            CoverageSnapshot snapshot = new CoverageReader().read(
                    execFile, Path.of(located.module().outputDirectory()), targetFqn);
            System.out.println(String.format(Locale.ROOT,
                    "== coverage %s: line %.1f%% (%d/%d) branch %.1f%% (%d/%d)",
                    targetFqn,
                    snapshot.lineCoverage(), snapshot.coveredLines(),
                    snapshot.coveredLines() + snapshot.missedLines(),
                    snapshot.branchCoverage(), snapshot.coveredBranches(),
                    snapshot.coveredBranches() + snapshot.missedBranches()));
            System.out.println("== uncovered lines (up to 5): "
                    + snapshot.uncoveredLineNumbers().stream().limit(5)
                            .map(String::valueOf).collect(Collectors.joining(", ")));

            System.out.println("FULL LOOP SMOKE: COMPLETE");
        } catch (Exception e) {
            System.out.println("FULL LOOP SMOKE: STAGE CRASHED [" + stage + "]");
            e.printStackTrace(System.out);
            System.exit(1);
        }
    }

    /** Finds the module + source file for the FQN via the oracle's sourceRoots (never by scanning). */
    private static Located locateSource(BuildModel buildModel, String targetFqn) {
        String relative = targetFqn.replace('.', '/') + ".java";
        for (ModuleModel module : buildModel.modules()) {
            for (String root : module.sourceRoots()) {
                Path candidate = Path.of(root).resolve(relative);
                if (Files.isRegularFile(candidate)) {
                    return new Located(module, candidate);
                }
            }
        }
        throw new IllegalStateException("no module sourceRoot contains " + relative);
    }

    private record Located(ModuleModel module, Path sourceFile) {
    }

    /**
     * v0 prompt in the DeepSeekSmoke style, extended with the top-5 detected
     * failure scenarios (NOT the M4 prompt-register port).
     */
    private static String buildPrompt(String source, ClassModel model, FailureModel failureModel,
            List<FailureScenario> topScenarios) {
        String methods = model.methods().stream()
                .map(MethodModel::name).distinct().collect(Collectors.joining(", "));
        String conditions = failureModel.boundaryConditions().stream().limit(10)
                .map(c -> c.type() + " in " + c.method() + " at line " + c.line() + ": " + c.expression())
                .collect(Collectors.joining("\n"));
        String scenarios = topScenarios.stream()
                .map(s -> "- [" + s.riskLevel().wire() + " risk] " + s.type() + " at line " + s.line()
                        + ": " + s.description() + (s.code() == null || s.code().isBlank()
                                ? "" : " (code: " + oneLine(s.code()) + ")"))
                .collect(Collectors.joining("\n"));
        return "Write a JUnit 5 test class named " + model.simpleName() + "Test in package " + model.packageName()
                + " for the class below. Target the listed boundary conditions with edge-case inputs, and write"
                + " at least one test per listed failure scenario that would EXPOSE the failure if it is real.\n\n"
                + "Methods: " + methods + "\n\nBoundary conditions to target:\n" + conditions
                + "\n\nDetected failure scenarios (highest risk first):\n" + scenarios
                + "\n\nSource:\n```java\n" + source + "\n```\n";
    }

    private static String oneLine(String s) {
        if (s == null) {
            return "";
        }
        String flat = s.replace("\r", " ").replace("\n", " ").strip();
        return flat.length() > 200 ? flat.substring(0, 200) + "..." : flat;
    }
}
