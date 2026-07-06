package org.failmapper.build;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;
import java.util.logging.Logger;
import org.apache.maven.model.Build;
import org.apache.maven.model.Dependency;
import org.apache.maven.model.Model;
import org.apache.maven.model.Repository;
import org.apache.maven.model.building.DefaultModelBuilderFactory;
import org.apache.maven.model.building.DefaultModelBuildingRequest;
import org.apache.maven.model.building.ModelBuilder;
import org.apache.maven.model.building.ModelBuildingException;
import org.apache.maven.model.building.ModelBuildingRequest;
import org.apache.maven.model.building.ModelBuildingResult;
import org.apache.maven.model.building.ModelProblem;
import org.apache.maven.repository.internal.MavenRepositorySystemUtils;
import org.eclipse.aether.ConfigurationProperties;
import org.eclipse.aether.DefaultRepositorySystemSession;
import org.eclipse.aether.RepositorySystem;
import org.eclipse.aether.RepositorySystemSession;
import org.eclipse.aether.artifact.Artifact;
import org.eclipse.aether.artifact.ArtifactType;
import org.eclipse.aether.artifact.ArtifactTypeRegistry;
import org.eclipse.aether.artifact.DefaultArtifact;
import org.eclipse.aether.artifact.DefaultArtifactType;
import org.eclipse.aether.collection.CollectRequest;
import org.eclipse.aether.connector.basic.BasicRepositoryConnectorFactory;
import org.eclipse.aether.graph.Exclusion;
import org.eclipse.aether.impl.DefaultServiceLocator;
import org.eclipse.aether.repository.LocalRepository;
import org.eclipse.aether.repository.RemoteRepository;
import org.eclipse.aether.repository.RepositoryPolicy;
import org.eclipse.aether.resolution.ArtifactResult;
import org.eclipse.aether.resolution.DependencyRequest;
import org.eclipse.aether.resolution.DependencyResolutionException;
import org.eclipse.aether.resolution.DependencyResult;
import org.eclipse.aether.spi.connector.RepositoryConnectorFactory;
import org.eclipse.aether.spi.connector.transport.TransporterFactory;
import org.eclipse.aether.transport.file.FileTransporterFactory;
import org.eclipse.aether.transport.http.HttpTransporterFactory;
import org.eclipse.aether.util.artifact.JavaScopes;
import org.eclipse.aether.util.filter.DependencyFilterUtils;
import org.failmapper.core.model.BuildModel;
import org.failmapper.core.model.ModuleModel;

/**
 * Maven build oracle. Replaces the Python port's regex parsing of pom.xml (dependency_analyzer.py)
 * with the build system's own machinery:
 *
 * <ul>
 *   <li><b>Effective model</b> via {@code maven-model-builder} ({@link DefaultModelBuilderFactory}):
 *       parent POM chains, {@code <properties>} interpolation, dependencyManagement/BOM imports and
 *       default profile activation are all applied by Maven's own code. Parents/imports not on disk
 *       are fetched by {@link RepositoryModelResolver} from ~/.m2/repository and Maven Central.</li>
 *   <li><b>Multi-module reactors</b>: {@code <modules>} are walked recursively (depth-first,
 *       declaration order); aggregator (packaging=pom) modules are included in the returned model
 *       so the reactor structure is complete.</li>
 *   <li><b>Transitive TEST-scope classpath</b> via Maven Resolver: one {@link CollectRequest} per
 *       module over its effective dependencies, filtered with
 *       {@code DependencyFilterUtils.classpathFilter(JavaScopes.TEST)}. Individual artifacts that
 *       fail to resolve are reported as warnings — the rest of the classpath is still returned.
 *       Only a completely unbuildable model throws {@link BuildOracleException}.</li>
 *   <li><b>Reactor-internal dependencies</b>: a dependency whose groupId:artifactId matches a
 *       sibling module contributes the sibling's {@code outputDirectory} (plus, transitively, the
 *       sibling's compile/runtime dependencies) instead of a repository lookup.</li>
 * </ul>
 *
 * <p>Read-only by contract: never mutates the user project's build files, never writes into the
 * project tree. All remote transfers carry explicit connect/request timeouts.
 */
public final class MavenBuildOracle implements BuildOracle {

    private static final Logger LOG = Logger.getLogger(MavenBuildOracle.class.getName());

    static final RemoteRepository MAVEN_CENTRAL = new RemoteRepository.Builder(
            "central", "default", "https://repo.maven.apache.org/maven2/").build();

    private static final int CONNECT_TIMEOUT_MS = 10_000;
    private static final int REQUEST_TIMEOUT_MS = 60_000;

    private final Path localRepository;
    private final RepositorySystem repositorySystem;
    private final ModelBuilder modelBuilder;

    /** Uses the standard local repository, {@code ~/.m2/repository}. */
    public MavenBuildOracle() {
        this(Path.of(System.getProperty("user.home"), ".m2", "repository"));
    }

    public MavenBuildOracle(Path localRepository) {
        this.localRepository = localRepository.toAbsolutePath().normalize();
        this.repositorySystem = newRepositorySystem();
        this.modelBuilder = new DefaultModelBuilderFactory().newInstance();
    }

    @Override
    public BuildModel analyze(Path projectRoot) {
        return analyze(projectRoot, w -> LOG.warning(w));
    }

    /**
     * Same as {@link #analyze(Path)} but routes non-fatal warnings (unresolvable individual
     * artifacts, model problems, missing declared modules) to {@code warnings}.
     */
    public BuildModel analyze(Path projectRoot, Consumer<String> warnings) {
        Path root = projectRoot.toAbsolutePath().normalize();
        Path rootPom = root.resolve("pom.xml");
        if (!Files.isRegularFile(rootPom)) {
            throw new BuildOracleException("Not a Maven project (no pom.xml): " + root);
        }
        RepositorySystemSession session = newSession();

        List<Model> reactor = new ArrayList<>();
        collectReactorModels(rootPom, session, reactor, new HashSet<>(), warnings);

        Map<String, Model> reactorByGa = new LinkedHashMap<>();
        for (Model m : reactor) {
            reactorByGa.put(gaOf(m), m);
        }

        List<ModuleModel> modules = new ArrayList<>();
        for (Model m : reactor) {
            modules.add(toModuleModel(m, reactorByGa, session, warnings));
        }
        return new BuildModel(root.toString(), List.copyOf(modules));
    }

    // ------------------------------------------------------------------ reactor walking

    private void collectReactorModels(Path pomFile, RepositorySystemSession session,
            List<Model> out, Set<Path> visited, Consumer<String> warnings) {
        Path pom = pomFile.toAbsolutePath().normalize();
        if (!visited.add(pom)) {
            return;
        }
        Model model = buildEffectiveModel(pom, session, warnings);
        out.add(model);
        if ("pom".equals(model.getPackaging())) {
            for (String moduleName : model.getModules()) {
                Path moduleRef = pom.getParent().resolve(moduleName).normalize();
                Path modulePom = Files.isDirectory(moduleRef) ? moduleRef.resolve("pom.xml") : moduleRef;
                if (Files.isRegularFile(modulePom)) {
                    collectReactorModels(modulePom, session, out, visited, warnings);
                } else {
                    warnings.accept("Declared <module> " + moduleName + " of " + pom + " has no pom.xml; skipped");
                }
            }
        }
    }

    private Model buildEffectiveModel(Path pom, RepositorySystemSession session, Consumer<String> warnings) {
        DefaultModelBuildingRequest request = new DefaultModelBuildingRequest();
        request.setPomFile(pom.toFile());
        request.setValidationLevel(ModelBuildingRequest.VALIDATION_LEVEL_MINIMAL);
        request.setSystemProperties(System.getProperties());
        request.setProcessPlugins(false);
        request.setTwoPhaseBuilding(false);
        request.setLocationTracking(false);
        request.setModelResolver(new RepositoryModelResolver(repositorySystem, session, List.of(MAVEN_CENTRAL)));
        try {
            ModelBuildingResult result = modelBuilder.build(request);
            for (ModelProblem problem : result.getProblems()) {
                if (problem.getSeverity() != ModelProblem.Severity.WARNING) {
                    warnings.accept("Model problem in " + pom + ": " + problem);
                }
            }
            return result.getEffectiveModel();
        } catch (ModelBuildingException e) {
            throw new BuildOracleException("Cannot build effective Maven model for " + pom + ": " + e.getMessage(), e);
        }
    }

    // ------------------------------------------------------------------ module mapping

    private ModuleModel toModuleModel(Model model, Map<String, Model> reactorByGa,
            RepositorySystemSession session, Consumer<String> warnings) {
        Path moduleDir = model.getProjectDirectory().toPath().toAbsolutePath().normalize();
        Build build = model.getBuild();
        String sourceRoot = absolute(moduleDir,
                build == null ? null : build.getSourceDirectory(), "src/main/java");
        String testSourceRoot = absolute(moduleDir,
                build == null ? null : build.getTestSourceDirectory(), "src/test/java");
        String outputDirectory = absolute(moduleDir,
                build == null ? null : build.getOutputDirectory(), "target/classes");
        String testOutputDirectory = absolute(moduleDir,
                build == null ? null : build.getTestOutputDirectory(), "target/test-classes");
        List<String> testClasspath = resolveTestClasspath(model, reactorByGa, session, warnings);
        return new ModuleModel(
                model.getGroupId(),
                model.getArtifactId(),
                model.getVersion(),
                moduleDir.toString(),
                List.of(sourceRoot),
                List.of(testSourceRoot),
                testClasspath,
                outputDirectory,
                testOutputDirectory);
    }

    /**
     * Transitive TEST-scope classpath for one module. Reactor-internal dependencies contribute the
     * sibling's output directory (+ the sibling's compile/runtime dependencies, walked
     * transitively); everything else goes through one Maven Resolver collect/resolve round-trip.
     */
    private List<String> resolveTestClasspath(Model model, Map<String, Model> reactorByGa,
            RepositorySystemSession session, Consumer<String> warnings) {
        LinkedHashSet<String> classpath = new LinkedHashSet<>();
        List<org.eclipse.aether.graph.Dependency> collectDeps = new ArrayList<>();
        ArtifactTypeRegistry types = session.getArtifactTypeRegistry();

        Deque<Model> siblingQueue = new ArrayDeque<>();
        Set<String> seenGa = new HashSet<>();
        seenGa.add(gaOf(model));

        for (Dependency d : model.getDependencies()) {
            addDependency(d, scopeOf(d), reactorByGa, seenGa, siblingQueue, classpath, collectDeps, types, model, warnings);
        }
        while (!siblingQueue.isEmpty()) {
            Model sibling = siblingQueue.poll();
            classpath.add(outputDirectoryOf(sibling));
            for (Dependency d : sibling.getDependencies()) {
                String scope = scopeOf(d);
                if (JavaScopes.COMPILE.equals(scope) || JavaScopes.RUNTIME.equals(scope)) {
                    // A dependent module sees only the sibling's compile/runtime dependencies.
                    addDependency(d, scope, reactorByGa, seenGa, siblingQueue, classpath, collectDeps, types, model, warnings);
                }
            }
        }

        if (!collectDeps.isEmpty()) {
            CollectRequest collect = new CollectRequest();
            collect.setDependencies(collectDeps);
            collect.setManagedDependencies(managedDependencies(model, types));
            collect.setRepositories(remoteRepositoriesFor(model));
            DependencyRequest request = new DependencyRequest(
                    collect, DependencyFilterUtils.classpathFilter(JavaScopes.TEST));

            DependencyResult result;
            try {
                result = repositorySystem.resolveDependencies(session, request);
            } catch (DependencyResolutionException e) {
                // Partial failure: keep whatever resolved, surface the rest as warnings.
                warnings.accept("Partial dependency resolution for " + gaOf(model) + ": " + e.getMessage());
                result = e.getResult();
            }
            if (result != null) {
                for (ArtifactResult ar : result.getArtifactResults()) {
                    if (ar.isResolved()) {
                        classpath.add(ar.getArtifact().getFile().getAbsolutePath());
                    } else {
                        warnings.accept("Unresolved artifact for " + gaOf(model) + ": "
                                + ar.getRequest().getArtifact());
                    }
                }
            }
        }
        return List.copyOf(classpath);
    }

    private void addDependency(Dependency d, String effectiveScope, Map<String, Model> reactorByGa,
            Set<String> seenGa, Deque<Model> siblingQueue, Set<String> classpath,
            List<org.eclipse.aether.graph.Dependency> collectDeps, ArtifactTypeRegistry types,
            Model owner, Consumer<String> warnings) {
        String ga = d.getGroupId() + ":" + d.getArtifactId();
        Model sibling = reactorByGa.get(ga);
        if (sibling != null) {
            if (seenGa.add(ga)) {
                siblingQueue.add(sibling);
            }
            return;
        }
        if (JavaScopes.SYSTEM.equals(effectiveScope)) {
            if (d.getSystemPath() != null && Files.exists(Path.of(d.getSystemPath()))) {
                classpath.add(Path.of(d.getSystemPath()).toAbsolutePath().normalize().toString());
            } else {
                warnings.accept("System-scope dependency of " + gaOf(owner) + " has missing systemPath: " + ga);
            }
            return;
        }
        if (d.getVersion() == null || d.getVersion().isEmpty()) {
            warnings.accept("Dependency without resolvable version in " + gaOf(owner) + ": " + ga + "; skipped");
            return;
        }
        if (seenGa.add(ga + ":" + d.getClassifier() + ":" + d.getType())) {
            collectDeps.add(toAetherDependency(d, effectiveScope, types));
        }
    }

    private List<org.eclipse.aether.graph.Dependency> managedDependencies(Model model, ArtifactTypeRegistry types) {
        List<org.eclipse.aether.graph.Dependency> managed = new ArrayList<>();
        if (model.getDependencyManagement() != null) {
            for (Dependency d : model.getDependencyManagement().getDependencies()) {
                if ("import".equals(d.getScope())
                        || d.getVersion() == null || d.getVersion().isEmpty()) {
                    continue; // imports are already flattened into the effective model
                }
                managed.add(toAetherDependency(d, scopeOf(d), types));
            }
        }
        return managed;
    }

    private List<RemoteRepository> remoteRepositoriesFor(Model model) {
        LinkedHashMap<String, RemoteRepository> repos = new LinkedHashMap<>();
        for (Repository r : model.getRepositories()) {
            repos.putIfAbsent(r.getId(), toRemoteRepository(r));
        }
        repos.putIfAbsent(MAVEN_CENTRAL.getId(), MAVEN_CENTRAL);
        return List.copyOf(repos.values());
    }

    // ------------------------------------------------------------------ conversions

    private static org.eclipse.aether.graph.Dependency toAetherDependency(
            Dependency d, String scope, ArtifactTypeRegistry types) {
        String typeId = d.getType() == null || d.getType().isEmpty() ? "jar" : d.getType();
        ArtifactType type = types.get(typeId);
        if (type == null) {
            type = new DefaultArtifactType(typeId);
        }
        String classifier = d.getClassifier() != null && !d.getClassifier().isEmpty()
                ? d.getClassifier()
                : type.getClassifier();
        Artifact artifact = new DefaultArtifact(
                d.getGroupId(), d.getArtifactId(), classifier, type.getExtension(), d.getVersion(), null, type);
        List<Exclusion> exclusions = new ArrayList<>();
        for (org.apache.maven.model.Exclusion e : d.getExclusions()) {
            exclusions.add(new Exclusion(e.getGroupId(), e.getArtifactId(), "*", "*"));
        }
        return new org.eclipse.aether.graph.Dependency(artifact, scope, d.isOptional(), exclusions);
    }

    static RemoteRepository toRemoteRepository(Repository r) {
        RemoteRepository.Builder builder = new RemoteRepository.Builder(r.getId(), "default", r.getUrl());
        if (r.getReleases() != null) {
            builder.setReleasePolicy(toPolicy(r.getReleases()));
        }
        if (r.getSnapshots() != null) {
            builder.setSnapshotPolicy(toPolicy(r.getSnapshots()));
        }
        return builder.build();
    }

    private static RepositoryPolicy toPolicy(org.apache.maven.model.RepositoryPolicy p) {
        boolean enabled = p.getEnabled() == null || p.getEnabled().isEmpty()
                || Boolean.parseBoolean(p.getEnabled());
        String updates = p.getUpdatePolicy() == null || p.getUpdatePolicy().isEmpty()
                ? RepositoryPolicy.UPDATE_POLICY_DAILY : p.getUpdatePolicy();
        String checksums = p.getChecksumPolicy() == null || p.getChecksumPolicy().isEmpty()
                ? RepositoryPolicy.CHECKSUM_POLICY_WARN : p.getChecksumPolicy();
        return new RepositoryPolicy(enabled, updates, checksums);
    }

    private static String scopeOf(Dependency d) {
        return d.getScope() == null || d.getScope().isEmpty() ? JavaScopes.COMPILE : d.getScope();
    }

    private static String gaOf(Model model) {
        return model.getGroupId() + ":" + model.getArtifactId();
    }

    private static String outputDirectoryOf(Model model) {
        Path moduleDir = model.getProjectDirectory().toPath().toAbsolutePath().normalize();
        Build build = model.getBuild();
        return absolute(moduleDir, build == null ? null : build.getOutputDirectory(), "target/classes");
    }

    private static String absolute(Path moduleDir, String candidate, String defaultRelative) {
        if (candidate == null || candidate.isEmpty()) {
            return moduleDir.resolve(defaultRelative).toString();
        }
        Path p = Path.of(candidate);
        return (p.isAbsolute() ? p : moduleDir.resolve(p)).normalize().toString();
    }

    // ------------------------------------------------------------------ resolver bootstrap

    /**
     * Builds the resolver stack the same way standalone Maven Resolver consumers do. The service
     * locator API is deprecated in resolver 1.9 (replaced by the supplier in 2.x) but is the
     * documented mechanism for the 1.9.x line used here.
     */
    @SuppressWarnings("deprecation")
    private static RepositorySystem newRepositorySystem() {
        DefaultServiceLocator locator = MavenRepositorySystemUtils.newServiceLocator();
        locator.addService(RepositoryConnectorFactory.class, BasicRepositoryConnectorFactory.class);
        locator.addService(TransporterFactory.class, FileTransporterFactory.class);
        locator.addService(TransporterFactory.class, HttpTransporterFactory.class);
        locator.setErrorHandler(new DefaultServiceLocator.ErrorHandler() {
            @Override
            public void serviceCreationFailed(Class<?> type, Class<?> impl, Throwable exception) {
                throw new BuildOracleException(
                        "Cannot create Maven Resolver service " + type.getName(), exception);
            }
        });
        RepositorySystem system = locator.getService(RepositorySystem.class);
        if (system == null) {
            throw new BuildOracleException("Maven Resolver bootstrap failed (no RepositorySystem)");
        }
        return system;
    }

    private RepositorySystemSession newSession() {
        DefaultRepositorySystemSession session = MavenRepositorySystemUtils.newSession();
        session.setLocalRepositoryManager(repositorySystem.newLocalRepositoryManager(
                session, new LocalRepository(localRepository.toFile())));
        session.setSystemProperties(System.getProperties());
        // Contract: every remote interaction has an explicit timeout.
        session.setConfigProperty(ConfigurationProperties.CONNECT_TIMEOUT, CONNECT_TIMEOUT_MS);
        session.setConfigProperty(ConfigurationProperties.REQUEST_TIMEOUT, REQUEST_TIMEOUT_MS);
        return session;
    }
}
