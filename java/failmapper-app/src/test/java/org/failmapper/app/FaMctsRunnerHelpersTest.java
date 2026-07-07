package org.failmapper.app;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import org.failmapper.core.model.BuildModel;
import org.failmapper.core.model.ModuleModel;
import org.failmapper.llm.LlmClient;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Static wiring helpers of {@link FaMctsRunner}: source location, the output guard,
 * and the I17 bounded initial-generation retry.
 */
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

    // ------------------------------------------------------------------
    // Layer-D regression for I17 / M5_BENCHMARK §3.4: the initial generation
    // used to be a single LLM call + strict extraction + orElseThrow, which
    // crashed 2 of 8 pilot cells. It now re-samples (fresh call) up to
    // INITIAL_GENERATION_ATTEMPTS times before failing with a clear error.
    // ------------------------------------------------------------------

    @Test
    void initialGenerationRetriesFreshSamplesUntilExtractionSucceeds() {
        List<String> replies = List.of(
                "I could not produce anything useful, sorry.",   // pure prose -> empty
                "Nothing here either, my apologies.",            // pure prose -> empty
                "```java\npublic class CalcTest {\n    void t() {}\n}\n```");
        AtomicInteger calls = new AtomicInteger();
        LlmClient stub = (system, user) -> replies.get(calls.getAndIncrement());

        String code = FaMctsRunner.generateInitialTest(stub, "prompt");

        assertThat(calls.get()).isEqualTo(3);
        assertThat(code).contains("public class CalcTest");
    }

    @Test
    void initialGenerationFailsClearlyAfterBoundedAttempts() {
        AtomicInteger calls = new AtomicInteger();
        LlmClient stub = (system, user) -> {
            calls.incrementAndGet();
            return "still nothing resembling a unit for you";
        };

        assertThatThrownBy(() -> FaMctsRunner.generateInitialTest(stub, "prompt"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("after " + FaMctsRunner.INITIAL_GENERATION_ATTEMPTS
                        + " attempts");
        assertThat(calls.get()).isEqualTo(FaMctsRunner.INITIAL_GENERATION_ATTEMPTS);
    }

    @Test
    void initialGenerationDoesNotRetryOnFirstSuccess() {
        AtomicInteger calls = new AtomicInteger();
        LlmClient stub = (system, user) -> {
            calls.incrementAndGet();
            return "```java\npublic class CalcTest {\n}\n```";
        };

        FaMctsRunner.generateInitialTest(stub, "prompt");

        assertThat(calls.get()).isEqualTo(1);
    }
}
