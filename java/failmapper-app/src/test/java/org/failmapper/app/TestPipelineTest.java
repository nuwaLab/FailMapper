package org.failmapper.app;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;

import org.failmapper.core.model.ModuleModel;
import org.failmapper.search.FaTestState;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * {@link TestPipeline} compile-side behavior (no fork needed): diagnostics become
 * {@code state.compilationErrors} with coverage 0, matching the DefaultEvaluator
 * keep-parent semantics contract (the parent-coverage restore is the APPLIER's job).
 */
class TestPipelineTest {

    @TempDir
    Path workRoot;

    @TempDir
    Path fakeModuleDir;

    private TestPipeline pipeline() throws Exception {
        Path classes = fakeModuleDir.resolve("classes");
        Files.createDirectories(classes);
        ModuleModel module = new ModuleModel(
                "g", "a", "1", fakeModuleDir.toString(),
                List.of(), List.of(), List.of(),
                classes.toString(), fakeModuleDir.resolve("test-classes").toString());
        return new TestPipeline(module, "com.acme.Calc", workRoot,
                Duration.ofSeconds(30), "class Calc {\n  int x;\n}\n");
    }

    @Test
    void compileErrorsBecomeCompilationErrorsWithZeroCoverage() throws Exception {
        FaTestState state = new FaTestState("""
                public class BrokenTest {
                    public void test() {
                        NoSuchType t = new NoSuchType();
                    }
                }
                """, null, null);
        state.coverage = 33.0; // pre-seeded parent coverage

        pipeline().evaluate(state);

        assertThat(state.executed).isTrue();
        assertThat(state.compilationErrors).isNotEmpty();
        assertThat(String.join("\n", state.compilationErrors)).contains("cannot find symbol");
        // DefaultEvaluator semantics: compile error -> coverage 0 (test_state.py:132-147);
        // the fa_mcts.py:2772-2775 restore happens in LlmActionApplier, not here.
        assertThat(state.coverage).isEqualTo(0.0);
        assertThat(state.uncoveredLines).isNull(); // no coverage data was produced
    }

    @Test
    void unparseableCodeSurfacesAsCompilationError() throws Exception {
        FaTestState state = new FaTestState("this is not java at all {{{", null, null);

        pipeline().evaluate(state);

        assertThat(state.compilationErrors).isNotEmpty();
        assertThat(state.compilationErrors.get(0)).contains("not parseable");
        assertThat(state.coverage).isEqualTo(0.0);
    }
}
