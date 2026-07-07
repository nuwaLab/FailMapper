package org.failmapper.app;

import com.github.javaparser.ast.CompilationUnit;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;

import org.failmapper.analysis.ClassModelExtractor;
import org.failmapper.analysis.FailureModelExtractor;
import org.failmapper.analysis.FailureScenarioDetector;
import org.failmapper.analysis.SourceAnalyzer;
import org.failmapper.analysis.SymbolApiRetriever;
import org.failmapper.build.MavenBuildOracle;
import org.failmapper.core.model.BuildModel;
import org.failmapper.core.model.ClassModel;
import org.failmapper.core.model.FailureModel;
import org.failmapper.core.model.FailureScenario;
import org.failmapper.core.model.MethodModel;
import org.failmapper.core.model.ModuleModel;
import org.failmapper.llm.CodeExtractor;
import org.failmapper.llm.DeepSeekClient;
import org.failmapper.llm.LlmClient;
import org.failmapper.llm.prompt.InitialTestPromptBuilder;
import org.failmapper.search.ActionGenerator;
import org.failmapper.search.FaMcts;
import org.failmapper.search.FaTestState;
import org.failmapper.search.RandomSource;
import org.failmapper.search.RewardCalculator;
import org.failmapper.search.SearchConfig;
import org.failmapper.search.SeededRandomSource;
import org.failmapper.search.SelectionPolicy;
import org.failmapper.search.StrategySelector;
import org.failmapper.search.TerminationPolicy;
import org.failmapper.search.UpdateBestPolicy;

/**
 * End-to-end FA-MCTS pipeline (M4): the real composition of every module —
 *
 * <pre>
 * MavenBuildOracle → locate source (sourceRoots + FQN) → ClassModel/FailureModel/
 * FailureScenarioDetector → initial test (P12 InitialTestPromptBuilder + DeepSeekClient
 * + CodeExtractor) → FaTestState (evaluated by TestPipeline) → FaMcts loop with
 * LlmActionApplier (D6 + I16) and LlmBugVerifier (D9/D10) → artifacts under the
 * caller's OUTPUT dir (never the target project tree).
 * </pre>
 *
 * <p>Reproducibility knobs (registered improvements): seed → {@link SeededRandomSource}
 * (I9); temperature → {@link DeepSeekClient} constructor (I10); model → env
 * {@code DEEPSEEK_MODEL}, default {@code deepseek-v4-pro}.
 *
 * <p>Usage: {@code FaMctsRunner <maven-project-root> <target-class-fqn> <output-dir>
 * [maxIterations=20] [seed=42]}. Requires {@code DEEPSEEK_API_KEY} in the environment.
 */
public final class FaMctsRunner {

    /** Everything a run needs; {@code temperature} implements I10 (no more hardcoded 0.7). */
    public record RunnerConfig(
            Path projectRoot,
            String targetFqn,
            Path outputDir,
            int maxIterations,
            long seed,
            double temperature,
            Duration forkTimeout) {

        public RunnerConfig {
            Objects.requireNonNull(projectRoot, "projectRoot");
            Objects.requireNonNull(targetFqn, "targetFqn");
            Objects.requireNonNull(outputDir, "outputDir");
            Objects.requireNonNull(forkTimeout, "forkTimeout");
        }

        public static RunnerConfig of(Path projectRoot, String targetFqn, Path outputDir,
                                      int maxIterations, long seed) {
            return new RunnerConfig(projectRoot, targetFqn, outputDir, maxIterations, seed,
                    0.7, Duration.ofSeconds(60));
        }
    }

    /** Run outcome: the artifact location plus the raw search result. */
    public record RunOutcome(Path bestTestFile, FaMcts.SearchResult searchResult) {
    }

    public static void main(String[] args) throws Exception {
        if (args.length < 3) {
            System.err.println(
                    "usage: FaMctsRunner <maven-project-root> <target-class-fqn> <output-dir>"
                            + " [maxIterations=20] [seed=42]");
            System.exit(2);
        }
        int maxIterations = args.length > 3 ? Integer.parseInt(args[3]) : 20;
        long seed = args.length > 4 ? Long.parseLong(args[4]) : 42L;
        RunnerConfig config = RunnerConfig.of(
                Path.of(args[0]), args[1], Path.of(args[2]), maxIterations, seed);

        RunOutcome outcome = new FaMctsRunner().run(config);
        System.out.println("best test: " + outcome.bestTestFile());
        System.out.println(String.format(java.util.Locale.ROOT,
                "coverage %.2f%%, %d real bug(s), %d iteration(s)",
                outcome.searchResult().bestCoverage(),
                outcome.searchResult().realBugsCount(),
                outcome.searchResult().iterationsRun()));
    }

    public RunOutcome run(RunnerConfig config) throws Exception {
        // --- 1. Build oracle + source location (typed facts, never scraping) ---
        BuildModel buildModel = new MavenBuildOracle().analyze(config.projectRoot());
        Located located = locateSource(buildModel, config.targetFqn());
        guardOutputDir(config.outputDir(), buildModel);

        // --- 2. Analysis ---
        String sourceCode = Files.readString(located.sourceFile());
        SourceAnalyzer analyzer = new SourceAnalyzer();
        CompilationUnit cu = analyzer.parse(sourceCode).orElseThrow(
                () -> new IllegalStateException("cannot parse " + located.sourceFile()));
        ClassModel classModel = new ClassModelExtractor()
                .extractPrimary(cu, located.sourceFile().toString())
                .orElseThrow(() -> new IllegalStateException(
                        "no type declaration in " + located.sourceFile()));
        FailureModel fModel = new FailureModelExtractor().extract(sourceCode, classModel.fqn());
        List<FailureScenario> failures =
                new FailureScenarioDetector(sourceCode, classModel.fqn(), fModel).detect();

        // --- 3. LLM client (I10: temperature from config; model from env) ---
        String model = envModel();
        LlmClient client = new DeepSeekClient(
                DeepSeekClient.DEFAULT_BASE_URL, model, requireApiKey(),
                config.temperature(), 8192);

        // --- 4. Initial test via P12 ---
        String promptContent = initialPromptContent(sourceCode, classModel, fModel, failures);
        String initialPrompt = InitialTestPromptBuilder.build(promptContent);
        String initialReply = client.complete(null, initialPrompt);
        String initialTestCode = new CodeExtractor().extract(initialReply).orElseThrow(
                () -> new IllegalStateException("no parseable Java in the initial LLM reply"));

        // --- 5. Evaluate the initial state ---
        Path workRoot = Files.createTempDirectory("fm-mcts-work");
        TestPipeline pipeline = new TestPipeline(
                located.module(), config.targetFqn(), workRoot,
                config.forkTimeout(), sourceCode);
        FaTestState rootState = new FaTestState(initialTestCode, fModel, failures);
        pipeline.evaluate(rootState);

        // --- 6. Kernel wiring ---
        SearchConfig searchConfig = SearchConfig.builder()
                .maxIterations(config.maxIterations())
                .build();
        RandomSource random = new SeededRandomSource(config.seed()); // I9

        List<String> classpath = new ArrayList<>();
        classpath.add(located.module().outputDirectory());
        classpath.addAll(located.module().testClasspath());

        LlmActionApplier applier = new LlmActionApplier(
                client, pipeline, classModel.simpleName(), sourceCode, promptContent,
                new SymbolApiRetriever(), classpath, fModel, failures);
        LlmBugVerifier verifier = new LlmBugVerifier(
                client, sourceCode, classModel.simpleName());

        FaMcts search = new FaMcts(
                searchConfig,
                rootState,
                new ActionGenerator(searchConfig, random),
                new SelectionPolicy(searchConfig, random),
                applier,
                new RewardCalculator(searchConfig),
                new UpdateBestPolicy(searchConfig, rootState.testCode, rootState.coverage),
                new TerminationPolicy(searchConfig),
                verifier,
                random,
                new FaMcts.SearchContext(fModel, failures, new StrategySelector(failures, fModel)));

        // --- 7. Search + artifacts ---
        FaMcts.SearchResult result = search.runSearch();
        Path bestTestFile = new ArtifactWriter().write(
                config.outputDir(), config.targetFqn(), config.seed(), model, result);

        return new RunOutcome(bestTestFile, result);
    }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    record Located(ModuleModel module, Path sourceFile) {
    }

    /** Finds the module + source file for the FQN via the oracle's sourceRoots. */
    static Located locateSource(BuildModel buildModel, String targetFqn) {
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

    /**
     * Artifacts must never land in the target project's source trees: reject an output
     * dir under any module's source/test-source root.
     */
    static void guardOutputDir(Path outputDir, BuildModel buildModel) {
        Path normalized = outputDir.toAbsolutePath().normalize();
        for (ModuleModel module : buildModel.modules()) {
            List<String> roots = new ArrayList<>(module.sourceRoots());
            roots.addAll(module.testSourceRoots());
            for (String root : roots) {
                Path rootPath = Path.of(root).toAbsolutePath().normalize();
                if (normalized.startsWith(rootPath)) {
                    throw new IllegalArgumentException(
                            "output dir " + outputDir + " is inside the target project's"
                                    + " source tree (" + root + ") — refusing to write");
                }
            }
        }
    }

    /**
     * The prompt-file content wrapped by P12: class structure, boundary conditions and
     * detected failure scenarios plus the full source (the Java analog of the on-disk
     * prompt the Python {@code prompt_generator} produced).
     */
    static String initialPromptContent(String sourceCode, ClassModel classModel,
                                       FailureModel fModel, List<FailureScenario> failures) {
        String methods = classModel.methods().stream()
                .map(MethodModel::name).distinct().collect(Collectors.joining(", "));
        String conditions = fModel.boundaryConditions().stream().limit(15)
                .map(c -> "- " + c.type() + " in " + c.method() + " at line " + c.line()
                        + ": " + c.expression())
                .collect(Collectors.joining("\n"));
        String scenarios = failures.stream().limit(10)
                .map(s -> "- [" + s.riskLevel().wire() + " risk] " + s.type()
                        + " at line " + s.line() + ": " + s.description())
                .collect(Collectors.joining("\n"));
        return "Write a JUnit 5 test class named " + classModel.simpleName() + "Test in package "
                + classModel.packageName() + " for the class below.\n\n"
                + "Class: " + classModel.fqn() + "\n"
                + "Methods: " + methods + "\n\n"
                + "Boundary conditions to target with edge-case inputs:\n" + conditions + "\n\n"
                + "Detected failure scenarios (highest risk first) — write at least one test per"
                + " scenario that would EXPOSE the failure if it is real:\n" + scenarios + "\n\n"
                + "Source:\n```java\n" + sourceCode + "\n```";
    }

    static String envModel() {
        String model = System.getenv("DEEPSEEK_MODEL");
        return model == null || model.isBlank() ? DeepSeekClient.DEFAULT_MODEL : model;
    }

    private static String requireApiKey() {
        String key = System.getenv("DEEPSEEK_API_KEY");
        if (key == null || key.isBlank()) {
            throw new IllegalStateException("DEEPSEEK_API_KEY environment variable is not set");
        }
        return key;
    }
}
