package org.failmapper.build;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.failmapper.core.model.BuildModel;
import org.failmapper.core.model.ModuleModel;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.io.TempDir;

/**
 * INTEGRATION test for {@link GradleBuildOracle} — guarded by the {@code FM_GRADLE_IT=1}
 * environment variable because connecting the Gradle Tooling API to a project without a wrapper
 * makes it <b>download a full Gradle distribution</b> (hundreds of MB, minutes on a cold cache)
 * and fork a Gradle daemon. That is far too heavy and network-dependent for the default
 * {@code mvn test} run, so it is opt-in:
 *
 * <pre>{@code FM_GRADLE_IT=1 mvn -pl failmapper-build test}</pre>
 *
 * <p>Builds a throwaway two-project Kotlin-DSL Gradle build in a temp directory (never inside any
 * user project) and asserts source-root/test-classpath/inter-project-dependency extraction.
 */
@EnabledIfEnvironmentVariable(named = "FM_GRADLE_IT", matches = "1")
class GradleBuildOracleIntegrationTest {

    @TempDir
    Path projectDir;

    @Test
    void multiProjectKotlinDslBuildYieldsModulesSourceRootsAndProjectDependency() throws IOException {
        writeFixtureProject();

        List<String> warnings = new ArrayList<>();
        BuildModel model = new GradleBuildOracle().analyze(projectDir, warnings::add);

        assertThat(model.rootDir()).isEqualTo(projectDir.toAbsolutePath().normalize().toString());
        assertThat(model.modules()).extracting(ModuleModel::artifactId)
                .contains("lib", "app");

        ModuleModel lib = byName(model, "lib");
        ModuleModel app = byName(model, "app");

        assertThat(lib.sourceRoots())
                .anySatisfy(root -> assertThat(root).endsWith("lib/src/main/java"));
        assertThat(lib.testSourceRoots())
                .anySatisfy(root -> assertThat(root).endsWith("lib/src/test/java"));

        // app depends on project(":lib") -> lib's conventional output directory.
        assertThat(app.testClasspath()).contains(lib.outputDirectory());
        assertThat(lib.outputDirectory()).endsWith("lib/build/classes/java/main");
        assertThat(lib.testOutputDirectory()).endsWith("lib/build/classes/java/test");
    }

    private void writeFixtureProject() throws IOException {
        Files.writeString(projectDir.resolve("settings.gradle.kts"), """
                rootProject.name = "gradle-fixture"
                include("lib", "app")
                """);
        Path lib = Files.createDirectories(projectDir.resolve("lib"));
        Files.writeString(lib.resolve("build.gradle.kts"), """
                plugins { java }
                """);
        Path app = Files.createDirectories(projectDir.resolve("app"));
        Files.writeString(app.resolve("build.gradle.kts"), """
                plugins { java }
                dependencies { implementation(project(":lib")) }
                """);
        Path libSrc = Files.createDirectories(lib.resolve("src/main/java/fixture"));
        Files.writeString(libSrc.resolve("Lib.java"),
                "package fixture;\npublic class Lib { public int one() { return 1; } }\n");
        Files.createDirectories(lib.resolve("src/test/java"));
        Path appSrc = Files.createDirectories(app.resolve("src/main/java/fixture"));
        Files.writeString(appSrc.resolve("App.java"),
                "package fixture;\npublic class App { }\n");
    }

    private static ModuleModel byName(BuildModel model, String name) {
        return model.modules().stream()
                .filter(m -> m.artifactId().equals(name))
                .findFirst()
                .orElseThrow(() -> new AssertionError("module not found: " + name));
    }
}
