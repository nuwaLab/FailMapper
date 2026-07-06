package org.failmapper.analysis;

import org.failmapper.core.model.FailureScenario;
import org.failmapper.core.model.RiskLevel;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Objects;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * Tests for {@link FailureScenarioDetector}, pinned against the Python FS_Detector
 * contract (failure_scenarios.py, doc/JAVA_PORT_CONTRACT.md D12/O11/X4/X5):
 * type/subtype vocabulary, 1-based line numbers, risk levels, the stable
 * high&gt;medium&gt;low&gt;critical sort, the per-detector exception boundary, and
 * the split(-1) trailing-empty-string fidelity.
 */
class FailureScenarioDetectorTest {

    private static List<FailureScenario> detect(String source) {
        return new FailureScenarioDetector(source, "fx.Sample", null).detect();
    }

    private static boolean has(List<FailureScenario> scenarios, String type, String subtype,
                               int line, RiskLevel risk) {
        return scenarios.stream().anyMatch(s -> s.type().equals(type)
                && Objects.equals(s.subtype(), subtype)
                && s.line() == line
                && s.riskLevel() == risk);
    }

    @Test
    void registersAllTwentyOneDetectors() {
        assertThat(new FailureScenarioDetector("", "fx.Sample", null).detectorCount())
                .isEqualTo(21);
    }

    @Test
    void detectsOperatorPrecedence() {
        List<FailureScenario> result = detect("""
                int f(boolean a, boolean b, boolean c) {
                    if (a && b || c) { return 1; }
                }
                """);
        assertThat(has(result, "operator_precedence", null, 2, RiskLevel.HIGH)).isTrue();
    }

    @Test
    void detectsOffByOneInLoopCondition() {
        List<FailureScenario> result = detect("""
                void g(int[] arr) {
                    for (int i = 0; i <= arr.length; i++) {
                        arr[i] = 0;
                    }
                }
                """);
        assertThat(has(result, "off_by_one", null, 2, RiskLevel.HIGH)).isTrue();
        // detector 21 fires on the same idiom via the loop scan
        assertThat(has(result, "array_index_bounds", "off_by_one_loop", 2, RiskLevel.HIGH)).isTrue();
    }

    @Test
    void detectsBoundaryCondition() {
        List<FailureScenario> result = detect("""
                int h(int[] a, int size) {
                    if (size == 0) { return a[0]; }
                    return 1;
                }
                """);
        assertThat(has(result, "boundary_condition", null, 2, RiskLevel.HIGH)).isTrue();
    }

    @Test
    void detectsNullHandlingOnParameter() {
        List<FailureScenario> result = detect("""
                String w(String name) {
                    return name.trim();
                }
                """);
        assertThat(has(result, "null_handling", null, 2, RiskLevel.HIGH)).isTrue();
    }

    @Test
    void detectsStringComparisonWithEqualsOperator() {
        List<FailureScenario> result = detect("""
                boolean cmp(String s, String t) {
                    return s == t;
                }
                """);
        assertThat(has(result, "string_comparison", null, 2, RiskLevel.HIGH)).isTrue();
    }

    @Test
    void detectsRedundantBooleanCondition() {
        List<FailureScenario> result = detect("""
                void b(boolean x, boolean y) {
                    if (x && y && x) { }
                }
                """);
        assertThat(result).anyMatch(s -> s.type().equals("boolean_bug")
                && s.line() == 2
                && s.riskLevel() == RiskLevel.MEDIUM
                && s.description().equals("Redundant conditions in boolean expression"));
    }

    /**
     * Contract X4 (failure_scenarios.py:385): Python re.split keeps trailing empty
     * strings, so "x &&&&" splits into 3 parts {"x","",""} with 2 unique -> redundant.
     * A port using Java's default split (drops trailing empties) would see 1 part and
     * never fire.
     */
    @Test
    void booleanSplitKeepsTrailingEmptyStrings() {
        List<FailureScenario> result = detect("if (x &&&&) { }\n");
        assertThat(result).anyMatch(s -> s.type().equals("boolean_bug")
                && s.line() == 1
                && s.description().equals("Redundant conditions in boolean expression"));
    }

    @Test
    void detectsResourceLeakFromBothDetectors() {
        List<FailureScenario> result = detect("""
                void r() throws Exception {
                    FileInputStream in = new FileInputStream("f");
                    in.read();
                }
                """);
        assertThat(has(result, "resource_leak", null, 2, RiskLevel.HIGH)).isTrue();
        assertThat(has(result, "resource_management", "resource_leak", 2, RiskLevel.HIGH)).isTrue();
    }

    @Test
    void detectsStateCorruption() {
        List<FailureScenario> result = detect("""
                void s(List<String> items) {
                    for (String item : items) {
                        items.remove(item);
                    }
                }
                """);
        assertThat(has(result, "state_corruption", null, 2, RiskLevel.HIGH)).isTrue();
    }

    @Test
    void detectsIntegerOverflow() {
        List<FailureScenario> result = detect("""
                int o(int x) {
                    return x + Integer.MAX_VALUE;
                }
                """);
        assertThat(has(result, "integer_overflow", null, 2, RiskLevel.HIGH)).isTrue();
    }

    @Test
    void detectsCopyPaste() {
        List<FailureScenario> result = detect(
                "int totalA = compute(alpha, beta);\n"
                        + "int totalB = compute(alpha, beta);\n");
        assertThat(has(result, "copy_paste", null, 1, RiskLevel.MEDIUM)).isTrue();
    }

    @Test
    void detectsFloatingPointComparison() {
        List<FailureScenario> result = detect("""
                boolean fp(double d) {
                    return d == 0.1;
                }
                """);
        assertThat(has(result, "floating_point_comparison", null, 2, RiskLevel.HIGH)).isTrue();
    }

    @Test
    void detectsComplexLoopCondition() {
        List<FailureScenario> result = detect("""
                void c(int a, int b, int d) {
                    while (a > 0 && b > 0 && d > 0) {
                        a--;
                    }
                }
                """);
        assertThat(has(result, "complex_loop_condition", null, 2, RiskLevel.MEDIUM)).isTrue();
    }

    @Test
    void detectsUseAfterClose() {
        List<FailureScenario> result = detect("""
                void m(java.io.InputStream in) throws Exception {
                    in.close();
                    in.read();
                }
                """);
        assertThat(has(result, "resource_management", "use_after_close", 2, RiskLevel.HIGH)).isTrue();
    }

    @Test
    void detectsIntegerDivisionInFloatingContext() {
        List<FailureScenario> result = detect("double half = 1 / 2;\n");
        assertThat(has(result, "data_operation", "integer_division", 1, RiskLevel.MEDIUM)).isTrue();
    }

    @Test
    void detectsUnsynchronizedSharedState() {
        List<FailureScenario> result = detect("""
                public class W {
                    private List workers;
                    void spawn() { new Thread(); }
                }
                """);
        assertThat(has(result, "concurrency", "unsynchronized_shared_state", 2, RiskLevel.HIGH))
                .isTrue();
    }

    @Test
    void detectsMissingValidation() {
        List<FailureScenario> result = detect("""
                public int size(String name) {
                    return name.length();
                }
                """);
        assertThat(has(result, "validation", "missing_null_check", 1, RiskLevel.MEDIUM)).isTrue();
        assertThat(has(result, "validation", "missing_empty_check", 1, RiskLevel.MEDIUM)).isTrue();
    }

    @Test
    void detectsHardcodedCredentialAsCritical() {
        List<FailureScenario> result = detect("String password = \"hunter42\";\n");
        assertThat(has(result, "security", "hardcoded_password", 1, RiskLevel.CRITICAL)).isTrue();
        // Python redacts the credential from the pattern record
        assertThat(result).filteredOn(s -> s.type().equals("security"))
                .allMatch(s -> s.code().equals("Redacted for security reasons"));
    }

    @Test
    void detectsStringIndexBounds() {
        List<FailureScenario> result = detect("""
                char first(String s) {
                    return s.charAt(0);
                }
                """);
        assertThat(has(result, "string_index_bounds", null, 2, RiskLevel.HIGH)).isTrue();
    }

    @Test
    void detectsArrayIndexBoundsComplexIndex() {
        List<FailureScenario> result = detect("""
                void ax(int[] data, int i) {
                    data[i+1] = 7;
                }
                """);
        assertThat(has(result, "array_index_bounds", null, 2, RiskLevel.HIGH)).isTrue();
    }

    /**
     * Sort contract (failure_scenarios.py:104): stable sort by risk weight descending,
     * high=3 &gt; medium=2 &gt; low=1 &gt; critical=0 (critical sorts LAST); within one
     * weight, detector registration order then per-detector match order.
     *
     * Fixture yields exactly 6 scenarios:
     *  line 1 charAt         -&gt; detector 20 string_index_bounds HIGH
     *  line 2 1 / 2          -&gt; detector 15 data_operation MEDIUM
     *  line 3 catch          -&gt; detector 12 LOW (comment-only) + MEDIUM (generic catch),
     *                           detector 17 HIGH (swallowed_exception)
     *  line 4 password       -&gt; detector 19 security CRITICAL
     */
    @Test
    void sortsByRiskWeightDescendingAndIsStable() {
        List<FailureScenario> result = detect("""
                char c0 = text.charAt(0);
                double half = 1 / 2;
                try { half = 2; } catch (Exception e) { /* ignored */ }
                String password = "hunter42";
                """);

        assertThat(result).hasSize(6);

        // Weights are non-increasing (high=3 ... critical=0).
        for (int i = 1; i < result.size(); i++) {
            assertThat(result.get(i - 1).riskLevel().sortWeight())
                    .isGreaterThanOrEqualTo(result.get(i).riskLevel().sortWeight());
        }

        // HIGH ties keep registration order: detector 17 before detector 20.
        assertThat(result.get(0).type()).isEqualTo("exception_handling");
        assertThat(result.get(0).subtype()).isEqualTo("swallowed_exception");
        assertThat(result.get(0).line()).isEqualTo(3);
        assertThat(result.get(1).type()).isEqualTo("string_index_bounds");
        assertThat(result.get(1).line()).isEqualTo(1);

        // MEDIUM ties keep registration order: detector 12 (line 3) before detector 15
        // (line 2) — registration order wins over source-line order.
        assertThat(result.get(2).type()).isEqualTo("exception_handling");
        assertThat(result.get(2).subtype()).isNull();
        assertThat(result.get(2).line()).isEqualTo(3);
        assertThat(result.get(3).type()).isEqualTo("data_operation");
        assertThat(result.get(3).subtype()).isEqualTo("integer_division");
        assertThat(result.get(3).line()).isEqualTo(2);

        // LOW next, CRITICAL last (weight 0, Python's unmapped default).
        assertThat(result.get(4).type()).isEqualTo("exception_handling");
        assertThat(result.get(4).riskLevel()).isEqualTo(RiskLevel.LOW);
        assertThat(result.get(5).type()).isEqualTo("security");
        assertThat(result.get(5).riskLevel()).isEqualTo(RiskLevel.CRITICAL);
    }

    /**
     * Contract X5 / detect() exception boundary: "grid[a{b][c]" makes detector 21
     * interpolate the raw fragment "a{b" into a regex; java.util.regex throws on the
     * stray '{' (Python re tolerates it). The detector must abort WITHOUT throwing,
     * keeping its own earlier matches (data[i+1], scanned before the multidim pass)
     * and every other detector's scenarios.
     */
    @Test
    void pathologicalInterpolatedFragmentAbortsOnlyItsDetector() {
        List<FailureScenario> result = detect("""
                char c1 = str.charAt(2);
                data[i+1] = 7;
                grid[a{b][c] = 1;
                """);

        // Other detectors survive.
        assertThat(has(result, "string_index_bounds", null, 1, RiskLevel.HIGH)).isTrue();
        // Detector 21's own scenario appended before the aborting multidim pass survives.
        assertThat(has(result, "array_index_bounds", null, 2, RiskLevel.HIGH)).isTrue();
        // The multidim scenario (and everything after it in detector 21) is lost.
        assertThat(result).noneMatch(s -> "multidimensional".equals(s.subtype()));
    }

    @Test
    void nullSourceDoesNotThrowAndYieldsEmptyList() {
        assertThatCode(() -> {
            List<FailureScenario> result =
                    new FailureScenarioDetector(null, "fx.Sample", null).detect();
            assertThat(result).isEmpty();
        }).doesNotThrowAnyException();
    }

    @Test
    void garbageSourceDoesNotThrow() {
        assertThatCode(() -> {
            List<FailureScenario> result = detect(" ￿ ]]][[[ %$#@! \\ \"\n\t{{{ )))");
            assertThat(result).isNotNull();
        }).doesNotThrowAnyException();
    }
}
