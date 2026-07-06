package org.failmapper.coverage;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;

import org.failmapper.core.model.CoverageSnapshot;
import org.failmapper.core.model.TestRunResult;
import org.failmapper.exec.CompileResult;
import org.failmapper.exec.ForkClasspath;
import org.failmapper.exec.ForkedTestRunner;
import org.failmapper.exec.InMemoryCompiler;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * End-to-end: compile fixtures in-memory, fork a JUnit run WITH the JaCoCo
 * agent attached (no build-file mutation anywhere), then read the .exec file
 * through the JaCoCo core API and check EXACT-FQN attribution.
 *
 * <p>FixtureCalc vs FixtureCalcHelper is the substring trap: 'FixtureCalc' is
 * a strict prefix of 'FixtureCalcHelper', so any substring/startsWith matching
 * would cross-pollute their numbers. The helper is fully covered while the
 * target is deliberately partially covered — attribution mix-ups are visible.</p>
 */
class CoverageReaderTest {

    // Line numbers below are asserted; keep the text-block layout stable.
    private static final String FIXTURE_CALC = """
            package fmfix;
            public class FixtureCalc {
                public int add(int a, int b) {
                    return a + b;
                }
                public int abs(int x) {
                    if (x < 0) {
                        return -x;
                    }
                    return x;
                }
                public int never(int x) {
                    return x * 42;
                }
            }
            """; // uncovered by the fixture test: line 8 (return -x) and line 13 (never's body)

    private static final String FIXTURE_CALC_HELPER = """
            package fmfix;
            public class FixtureCalcHelper {
                public int triple(int x) {
                    return 3 * x;
                }
            }
            """;

    private static final String FIXTURE_TEST = """
            package fmfix;
            import org.junit.jupiter.api.Test;
            import static org.junit.jupiter.api.Assertions.assertEquals;
            public class FixtureCalcTest {
                @Test void addWorks() { assertEquals(5, new FixtureCalc().add(2, 3)); }
                @Test void absOfPositive() { assertEquals(4, new FixtureCalc().abs(4)); }
                @Test void helperTriples() { assertEquals(6, new FixtureCalcHelper().triple(2)); }
            }
            """;

    @TempDir
    static Path tempDir;

    private static Path classesDir;
    private static Path execFile;

    private static final CoverageReader reader = new CoverageReader();

    @BeforeAll
    static void compileAndRunWithAgent() {
        InMemoryCompiler compiler = new InMemoryCompiler();
        classesDir = tempDir.resolve("classes");
        Path testClassesDir = tempDir.resolve("test-classes");
        execFile = tempDir.resolve("jacoco.exec");

        assertThat(compiler.compile("fmfix.FixtureCalc", FIXTURE_CALC, List.of(), classesDir)
                .success()).isTrue();
        assertThat(compiler.compile("fmfix.FixtureCalcHelper", FIXTURE_CALC_HELPER, List.of(), classesDir)
                .success()).isTrue();
        List<String> testCompileClasspath = new java.util.ArrayList<>(List.of(classesDir.toString()));
        testCompileClasspath.addAll(ForkClasspath.currentModuleClasspath());
        CompileResult testCompiled =
                compiler.compile("fmfix.FixtureCalcTest", FIXTURE_TEST, testCompileClasspath, testClassesDir);
        assertThat(testCompiled.success())
                .as("fixture test must compile: %s", testCompiled.diagnostics())
                .isTrue();

        TestRunResult run = new ForkedTestRunner().run(new ForkedTestRunner.RunSpec(
                List.of(testClassesDir.toString(), classesDir.toString()),
                "fmfix.FixtureCalcTest",
                Duration.ofSeconds(60),
                tempDir.resolve("work"),
                JacocoAgent.javaAgentArg(execFile)));
        assertThat(run.testsRun()).isEqualTo(3);
        assertThat(run.testsPassed()).isEqualTo(3);
        assertThat(execFile).exists();
    }

    @Test
    void targetClassGetsRealPartialCoverage() {
        CoverageSnapshot calc = reader.read(execFile, classesDir, "fmfix.FixtureCalc");
        System.out.println("[coverage] FixtureCalc line=" + calc.lineCoverage()
                + "% branch=" + calc.branchCoverage() + "% covered=" + calc.coveredLines()
                + " missed=" + calc.missedLines() + " uncoveredLines=" + calc.uncoveredLineNumbers());

        assertThat(calc.targetClassFqn()).isEqualTo("fmfix.FixtureCalc");
        assertThat(calc.lineCoverage()).isGreaterThan(0.0).isLessThan(100.0);
        assertThat(calc.coveredLines()).isGreaterThan(0);
        // abs's if (x < 0): one of two branches taken
        assertThat(calc.branchCoverage()).isEqualTo(50.0);
        assertThat(calc.coveredBranches()).isEqualTo(1);
        assertThat(calc.missedBranches()).isEqualTo(1);
        // return -x (line 8) and never's body (line 13) were not executed
        assertThat(calc.uncoveredLineNumbers()).contains(8, 13);
    }

    @Test
    void similarlyNamedSiblingDoesNotPolluteAttribution() {
        CoverageSnapshot calc = reader.read(execFile, classesDir, "fmfix.FixtureCalc");
        CoverageSnapshot helper = reader.read(execFile, classesDir, "fmfix.FixtureCalcHelper");
        System.out.println("[coverage] FixtureCalcHelper line=" + helper.lineCoverage()
                + "% uncoveredLines=" + helper.uncoveredLineNumbers());

        // Fully covered helper vs partially covered target: substring matching of
        // 'FixtureCalc' could return the helper's row — exact match must not.
        assertThat(helper.targetClassFqn()).isEqualTo("fmfix.FixtureCalcHelper");
        assertThat(helper.lineCoverage()).isEqualTo(100.0);
        assertThat(helper.uncoveredLineNumbers()).isEmpty();
        assertThat(calc.lineCoverage()).isLessThan(helper.lineCoverage());
        assertThat(calc.uncoveredLineNumbers()).isNotEmpty();
    }

    @Test
    void missingTargetClassYieldsZeroSnapshot() {
        CoverageSnapshot missing = reader.read(execFile, classesDir, "fmfix.DoesNotExist");
        assertThat(missing).isEqualTo(CoverageSnapshot.zero("fmfix.DoesNotExist"));
    }
}
