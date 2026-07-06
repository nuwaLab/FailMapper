package org.failmapper.build;

import java.io.File;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.logging.Logger;
import org.failmapper.core.model.BuildModel;
import org.failmapper.core.model.ModuleModel;
import org.gradle.tooling.BuildCancelledException;
import org.gradle.tooling.CancellationTokenSource;
import org.gradle.tooling.GradleConnectionException;
import org.gradle.tooling.GradleConnector;
import org.gradle.tooling.ProjectConnection;
import org.gradle.tooling.model.eclipse.EclipseExternalDependency;
import org.gradle.tooling.model.eclipse.EclipseProject;
import org.gradle.tooling.model.eclipse.EclipseProjectDependency;
import org.gradle.tooling.model.eclipse.EclipseSourceDirectory;

/**
 * Gradle build oracle, backed by the official Gradle Tooling API — never by parsing build.gradle
 * text (the Python port's regex approach) and never by mutating the user's build files.
 *
 * <p>Fetches the {@link EclipseProject} model (source directories + resolved classpath per
 * project) and walks the project hierarchy recursively. Every tooling-API call carries a
 * {@link CancellationTokenSource} that is cancelled after {@link #DEFAULT_TIMEOUT} (contract:
 * every subprocess gets an explicit timeout — the tooling API forks a Gradle daemon).
 *
 * <p>Known limitations (documented, not silent):
 * <ul>
 *   <li>Gradle does not expose group/version through the Eclipse model, so {@code groupId} and
 *       {@code version} are empty strings; {@code artifactId} is the Gradle project name and
 *       reactor matching for Gradle uses project names via the Eclipse project dependencies.</li>
 *   <li>{@code outputDirectory}/{@code testOutputDirectory} use the Gradle Java convention
 *       ({@code build/classes/java/main|test}); custom output layouts and Kotlin class dirs
 *       ({@code build/classes/kotlin/...}) are not reflected.</li>
 *   <li>The Eclipse classpath is scope-annotated but reported here as one TEST-superset list,
 *       which matches {@link ModuleModel#testClasspath()} semantics.</li>
 * </ul>
 */
public final class GradleBuildOracle implements BuildOracle {

    private static final Logger LOG = Logger.getLogger(GradleBuildOracle.class.getName());

    /** Hard wall-clock cap on any single Gradle Tooling API model request. */
    public static final Duration DEFAULT_TIMEOUT = Duration.ofSeconds(120);

    private final Duration timeout;

    public GradleBuildOracle() {
        this(DEFAULT_TIMEOUT);
    }

    public GradleBuildOracle(Duration timeout) {
        this.timeout = timeout;
    }

    @Override
    public BuildModel analyze(Path projectRoot) {
        return analyze(projectRoot, w -> LOG.warning(w));
    }

    /** Same as {@link #analyze(Path)} but routes non-fatal warnings to {@code warnings}. */
    public BuildModel analyze(Path projectRoot, Consumer<String> warnings) {
        Path root = projectRoot.toAbsolutePath().normalize();
        GradleConnector connector = GradleConnector.newConnector().forProjectDirectory(root.toFile());
        ScheduledExecutorService watchdog = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "failmapper-gradle-timeout");
            t.setDaemon(true);
            return t;
        });
        CancellationTokenSource cancellation = GradleConnector.newCancellationTokenSource();
        try (ProjectConnection connection = connector.connect()) {
            ScheduledFuture<?> cancelTask = watchdog.schedule(
                    cancellation::cancel, timeout.toMillis(), TimeUnit.MILLISECONDS);
            EclipseProject rootProject;
            try {
                rootProject = connection.model(EclipseProject.class)
                        .withCancellationToken(cancellation.token())
                        .get();
            } finally {
                cancelTask.cancel(false);
            }
            return toBuildModel(root, rootProject, warnings);
        } catch (BuildCancelledException e) {
            throw new BuildOracleException(
                    "Gradle model request for " + root + " timed out after " + timeout, e);
        } catch (GradleConnectionException | IllegalStateException e) {
            throw new BuildOracleException("Cannot obtain Gradle build model for " + root, e);
        } finally {
            watchdog.shutdownNow();
        }
    }

    private BuildModel toBuildModel(Path root, EclipseProject rootProject, Consumer<String> warnings) {
        // Pass 1: collect every project in the hierarchy so project dependencies can be
        // rewritten to sibling output directories (mirrors the Maven reactor GA matching).
        Map<String, EclipseProject> byName = new LinkedHashMap<>();
        indexProjects(rootProject, byName);

        List<ModuleModel> modules = new ArrayList<>();
        for (EclipseProject project : byName.values()) {
            modules.add(toModuleModel(project, byName, warnings));
        }
        return new BuildModel(root.toString(), List.copyOf(modules));
    }

    private void indexProjects(EclipseProject project, Map<String, EclipseProject> byName) {
        byName.putIfAbsent(project.getName(), project);
        for (EclipseProject child : project.getChildren()) {
            indexProjects(child, byName);
        }
    }

    private ModuleModel toModuleModel(EclipseProject project, Map<String, EclipseProject> byName,
            Consumer<String> warnings) {
        Path projectDir = project.getProjectDirectory().toPath().toAbsolutePath().normalize();

        List<String> sourceRoots = new ArrayList<>();
        List<String> testSourceRoots = new ArrayList<>();
        for (EclipseSourceDirectory sourceDir : project.getSourceDirectories()) {
            String absolute = projectDir.resolve(sourceDir.getPath()).normalize().toString();
            if (isTestSourceDirectory(sourceDir)) {
                testSourceRoots.add(absolute);
            } else {
                sourceRoots.add(absolute);
            }
        }

        LinkedHashSet<String> classpath = new LinkedHashSet<>();
        for (EclipseProjectDependency projectDep : project.getProjectDependencies()) {
            String targetName = projectDep.getPath().startsWith("/")
                    ? projectDep.getPath().substring(1)
                    : projectDep.getPath();
            EclipseProject target = byName.get(targetName);
            if (target != null) {
                classpath.add(conventionalOutputDir(target, "main"));
            } else {
                warnings.accept("Unknown Gradle project dependency '" + projectDep.getPath()
                        + "' of project " + project.getName());
            }
        }
        for (EclipseExternalDependency dep : project.getClasspath()) {
            File file = dep.getFile();
            if (file != null) {
                classpath.add(file.getAbsoluteFile().toPath().normalize().toString());
            }
        }

        return new ModuleModel(
                "", // Gradle's Eclipse model does not expose group — see class javadoc
                project.getName(),
                "", // nor version
                projectDir.toString(),
                List.copyOf(sourceRoots),
                List.copyOf(testSourceRoots),
                List.copyOf(classpath),
                conventionalOutputDir(project, "main"),
                conventionalOutputDir(project, "test"));
    }

    /**
     * Decides main vs test source root: primarily from the Gradle-provided classpath attributes
     * ({@code gradle_scope=test}); falls back to the conventional path heuristic for Gradle
     * versions that predate classpath attributes.
     */
    private static boolean isTestSourceDirectory(EclipseSourceDirectory sourceDir) {
        try {
            for (var attribute : sourceDir.getClasspathAttributes()) {
                if ("gradle_scope".equals(attribute.getName())
                        && "test".equalsIgnoreCase(attribute.getValue())) {
                    return true;
                }
            }
            for (var attribute : sourceDir.getClasspathAttributes()) {
                if ("gradle_used_by_scope".equals(attribute.getName())) {
                    // e.g. "test" for test sources vs "main,test" for main sources
                    return "test".equalsIgnoreCase(attribute.getValue().trim());
                }
            }
        } catch (RuntimeException e) {
            // older Gradle: model method unsupported — fall through to path heuristic
        }
        String path = sourceDir.getPath().toLowerCase(Locale.ROOT);
        return path.startsWith("src/test/") || path.contains("/test/") || path.endsWith("/test");
    }

    private static String conventionalOutputDir(EclipseProject project, String sourceSet) {
        return project.getProjectDirectory().toPath().toAbsolutePath().normalize()
                .resolve("build").resolve("classes").resolve("java").resolve(sourceSet).toString();
    }
}
