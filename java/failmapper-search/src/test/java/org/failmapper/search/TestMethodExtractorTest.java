package org.failmapper.search;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/**
 * {@link TestMethodExtractor} — port of {@code _extract_method_from_test_code}
 * ({@code fa_mcts.py:1465-1488}) with the X7-sanctioned brace-counter body.
 */
class TestMethodExtractorTest {

    private static final String TEST_CLASS = """
            import org.junit.jupiter.api.Test;

            public class CalcTest {
                private int shared = 0;

                @Test
                public void testAdd() throws Exception {
                    if (shared == 0) {
                        for (int i = 0; i < 3; i++) {
                            assertEquals(i + 1, Calc.add(i, 1));
                        }
                    }
                }

                @Test
                void testSubtract() {
                    assertEquals(1, Calc.subtract(2, 1));
                }
            }
            """;

    @Test
    void extractsFullMethodWithNestedBraces() {
        String method = TestMethodExtractor.extract(TEST_CLASS, "testAdd");
        assertThat(method).startsWith("public void testAdd() throws Exception {");
        assertThat(method).contains("for (int i = 0; i < 3; i++)");
        assertThat(method).endsWith("}");
        // The extraction stops at the METHOD's closing brace, not the class's.
        assertThat(method).doesNotContain("testSubtract");
    }

    @Test
    void extractsPackagePrivateMethod() {
        String method = TestMethodExtractor.extract(TEST_CLASS, "testSubtract");
        assertThat(method).contains("assertEquals(1, Calc.subtract(2, 1));");
    }

    @Test
    void missingMethodYieldsEmptyString() {
        assertThat(TestMethodExtractor.extract(TEST_CLASS, "testMissing")).isEmpty();
    }

    @Test
    void nullInputsYieldEmptyString() {
        assertThat(TestMethodExtractor.extract(null, "x")).isEmpty();
        assertThat(TestMethodExtractor.extract(TEST_CLASS, null)).isEmpty();
    }

    @Test
    void deeplyNestedMethodStillExtracts() {
        // X7-registered divergence: the Python 4-level regex would return "" here;
        // the brace counter (the contract's sanctioned rewrite) extracts it.
        String deep = """
                class T {
                    void deep() {
                        { { { { { int x = 1; } } } } }
                    }
                }
                """;
        String method = TestMethodExtractor.extract(deep, "deep");
        assertThat(method).contains("int x = 1;");
        assertThat(method).endsWith("}");
    }
}
