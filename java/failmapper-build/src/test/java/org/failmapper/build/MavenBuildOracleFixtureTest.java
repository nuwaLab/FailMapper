package org.failmapper.build;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.failmapper.core.model.BuildModel;
import org.failmapper.core.model.ModuleModel;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * MavenBuildOracle against the checked-in multi-module fixture
 * (src/test/resources/fixtures/multimod): parent POM (packaging=pom) with a property-versioned
 * junit-bom dependencyManagement import, module-a (junit-jupiter-api, test scope, version from the
 * BOM) and module-b (reactor dependency on module-a).
 *
 * <p>junit-jupiter-api 5.10.2 is guaranteed to be in ~/.m2/repository because this build itself
 * manages JUnit 5.10.2 via the parent POM.
 */
class MavenBuildOracleFixtureTest {

    private static final Path FIXTURE =
            Path.of("src/test/resources/fixtures/multimod").toAbsolutePath().normalize();

    private static BuildModel model;
    private static List<String> warnings;

    @BeforeAll
    static void analyzeFixture() {
        warnings = new ArrayList<>();
        model = new MavenBuildOracle().analyze(FIXTURE, warnings::add);
    }

    @Test
    void reactorContainsTheTwoCodeModules() {
        assertThat(model.rootDir()).isEqualTo(FIXTURE.toString());
        // Aggregator (packaging=pom) parent is part of the reactor; exactly 2 code modules exist.
        assertThat(model.modules()).extracting(ModuleModel::artifactId)
                .containsExactly("multimod-parent", "module-a", "module-b");
        List<ModuleModel> codeModules = model.modules().stream()
                .filter(m -> m.sourceRoots().stream().anyMatch(r -> Files.isDirectory(Path.of(r))))
                .toList();
        assertThat(codeModules).hasSize(2);
        assertThat(codeModules).extracting(ModuleModel::artifactId)
                .containsExactly("module-a", "module-b");
        assertThat(model.modules()).allSatisfy(m -> {
            assertThat(m.groupId()).isEqualTo("org.failmapper.fixture");
            assertThat(m.version()).isEqualTo("1.0.0");
        });
    }

    @Test
    void propertyInterpolationAndBomImportResolvedJunitVersion() {
        ModuleModel moduleA = moduleByArtifactId("module-a");
        // The junit-jupiter-api version came from ${junit.bom.version} through the BOM import:
        // interpolation + import must yield a concrete 5.10.2 jar on the test classpath.
        assertThat(moduleA.testClasspath())
                .anySatisfy(entry -> assertThat(entry).endsWith("junit-jupiter-api-5.10.2.jar"));
        // No unresolved ${...} placeholder may survive anywhere in the module model.
        assertThat(moduleA.testClasspath()).noneSatisfy(entry -> assertThat(entry).contains("${"));
        assertThat(moduleA.moduleDir()).doesNotContain("${");
        assertThat(moduleA.outputDirectory()).doesNotContain("${");
    }

    @Test
    void moduleBTestClasspathContainsModuleAOutputDirectory() {
        ModuleModel moduleA = moduleByArtifactId("module-a");
        ModuleModel moduleB = moduleByArtifactId("module-b");
        assertThat(moduleB.testClasspath()).contains(moduleA.outputDirectory());
        assertThat(moduleA.outputDirectory())
                .isEqualTo(FIXTURE.resolve("module-a/target/classes").toString());
        // module-a's junit dep is TEST scope, so it must NOT propagate to module-b.
        assertThat(moduleB.testClasspath())
                .noneSatisfy(entry -> assertThat(entry).contains("junit-jupiter-api"));
    }

    @Test
    void resolvedTestClasspathJarsExistOnDisk() {
        ModuleModel moduleA = moduleByArtifactId("module-a");
        List<String> jars = moduleA.testClasspath().stream()
                .filter(entry -> entry.endsWith(".jar"))
                .toList();
        // junit-jupiter-api + its transitives (opentest4j, apiguardian-api) at minimum.
        assertThat(jars).hasSizeGreaterThanOrEqualTo(3);
        assertThat(jars).allSatisfy(jar -> assertThat(Path.of(jar))
                .as("resolved classpath jar must exist on disk (from ~/.m2): %s", jar)
                .exists());
        assertThat(jars).allSatisfy(jar -> assertThat(Path.of(jar).isAbsolute()).isTrue());
    }

    @Test
    void sourceRootsAndOutputDirectoriesAreAbsoluteAndConventional() {
        ModuleModel moduleA = moduleByArtifactId("module-a");
        assertThat(moduleA.sourceRoots())
                .containsExactly(FIXTURE.resolve("module-a/src/main/java").toString());
        assertThat(moduleA.testSourceRoots())
                .containsExactly(FIXTURE.resolve("module-a/src/test/java").toString());
        assertThat(moduleA.testOutputDirectory())
                .isEqualTo(FIXTURE.resolve("module-a/target/test-classes").toString());
        assertThat(warnings).isEmpty();
    }

    private static ModuleModel moduleByArtifactId(String artifactId) {
        return model.modules().stream()
                .filter(m -> m.artifactId().equals(artifactId))
                .findFirst()
                .orElseThrow(() -> new AssertionError("module not found: " + artifactId));
    }
}
