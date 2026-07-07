package org.failmapper.app;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.failmapper.core.model.BuildModel;
import org.failmapper.core.model.ModuleModel;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Static wiring helpers of {@link FaMctsRunner}: source location and the output guard. */
class FaMctsRunnerHelpersTest {

    @TempDir
    Path projectDir;

    private BuildModel model(Path srcRoot) {
        ModuleModel module = new ModuleModel(
                "g", "a", "1", projectDir.toString(),
                List.of(srcRoot.toString()),
                List.of(projectDir.resolve("src/test/java").toString()),
                List.of(),
                projectDir.resolve("target/classes").toString(),
                projectDir.resolve("target/test-classes").toString());
        return new BuildModel(projectDir.toString(), List.of(module));
    }

    @Test
    void locateSourceFindsFqnUnderSourceRoot() throws Exception {
        Path srcRoot = projectDir.resolve("src/main/java");
        Path file = srcRoot.resolve("com/acme/Calc.java");
        Files.createDirectories(file.getParent());
        Files.writeString(file, "package com.acme; public class Calc {}");

        FaMctsRunner.Located located =
                FaMctsRunner.locateSource(model(srcRoot), "com.acme.Calc");

        assertThat(located.sourceFile()).isEqualTo(file);
        assertThat(located.module().artifactId()).isEqualTo("a");
    }

    @Test
    void locateSourceThrowsForUnknownFqn() {
        assertThatThrownBy(() -> FaMctsRunner.locateSource(
                model(projectDir.resolve("src/main/java")), "com.acme.Missing"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("com/acme/Missing.java");
    }

    @Test
    void outputGuardRejectsSourceTreeTargets() {
        Path srcRoot = projectDir.resolve("src/main/java");
        BuildModel buildModel = model(srcRoot);

        assertThatThrownBy(() -> FaMctsRunner.guardOutputDir(
                srcRoot.resolve("generated"), buildModel))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("refusing to write");
        assertThatThrownBy(() -> FaMctsRunner.guardOutputDir(
                projectDir.resolve("src/test/java"), buildModel))
                .isInstanceOf(IllegalArgumentException.class);

        // Outside any source root is fine (even inside the project, e.g. target/).
        FaMctsRunner.guardOutputDir(projectDir.resolve("target/failmapper"), buildModel);
        FaMctsRunner.guardOutputDir(Path.of("/tmp/somewhere-else"), buildModel);
    }
}
