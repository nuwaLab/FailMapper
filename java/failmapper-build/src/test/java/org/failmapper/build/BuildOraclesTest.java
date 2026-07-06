package org.failmapper.build;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Build-system detection: {@link BuildOracles#detect(Path)}. */
class BuildOraclesTest {

    @TempDir
    Path dir;

    @Test
    void pomXmlMeansMaven() throws IOException {
        Files.writeString(dir.resolve("pom.xml"), "<project/>");
        assertThat(BuildOracles.detect(dir)).isInstanceOf(MavenBuildOracle.class);
    }

    @Test
    void groovyBuildScriptMeansGradle() throws IOException {
        Files.writeString(dir.resolve("build.gradle"), "");
        assertThat(BuildOracles.detect(dir)).isInstanceOf(GradleBuildOracle.class);
    }

    @Test
    void kotlinBuildScriptMeansGradle() throws IOException {
        Files.writeString(dir.resolve("build.gradle.kts"), "");
        assertThat(BuildOracles.detect(dir)).isInstanceOf(GradleBuildOracle.class);
    }

    @Test
    void settingsOnlyGradleRootIsStillGradle() throws IOException {
        Files.writeString(dir.resolve("settings.gradle.kts"), "rootProject.name = \"x\"");
        assertThat(BuildOracles.detect(dir)).isInstanceOf(GradleBuildOracle.class);
    }

    @Test
    void mavenWinsWhenBothArePresent() throws IOException {
        Files.writeString(dir.resolve("pom.xml"), "<project/>");
        Files.writeString(dir.resolve("build.gradle"), "");
        assertThat(BuildOracles.detect(dir)).isInstanceOf(MavenBuildOracle.class);
    }

    @Test
    void unknownBuildSystemThrows() {
        assertThatThrownBy(() -> BuildOracles.detect(dir))
                .isInstanceOf(BuildOracleException.class)
                .hasMessageContaining("No supported build system");
    }
}
