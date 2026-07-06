package org.failmapper.analysis;

import org.failmapper.core.model.BoundaryCondition;
import org.failmapper.core.model.DecisionPoint;
import org.failmapper.core.model.FailureModel;
import org.failmapper.core.model.LogicalOperation;
import org.failmapper.core.model.MethodComplexity;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * Tests for {@link FailureModelExtractor}, pinned against the extractor.py contract
 * (doc/JAVA_PORT_CONTRACT.md F11, D2/D3): boundary-type vocabulary, decision-point
 * kinds, top-level-operator-only operations, and the deliberate line-proximity
 * nested-if heuristic (global if-line stack, 10-line window, never reset per method).
 */
class FailureModelExtractorTest {

    private final FailureModelExtractor extractor = new FailureModelExtractor();

    /**
     * Main fixture. 1-based line numbers of interest:
     *  6 if (a &gt; b)                     — classify
     *  7 if (a &gt; 10 &amp;&amp; b &lt; 5)  — classify
     * 11 if (a == 0 || b == 0)             — classify
     * 20 while (i &lt; n &amp;&amp; total &lt; 100) — loops
     * 24 for (int j = 0; j &lt; n; j++)    — loops
     * 28 for (int v : values)              — loops
     * 31 do { ... } while (total &gt; n)   — loops
     * 38 switch (code), cases at 39/41/43  — describe
     */
    private static final String SAMPLE = """
            package fx;

            public class Sample {

                public int classify(int a, int b) {
                    if (a > b) {
                        if (a > 10 && b < 5) {
                            return 1;
                        }
                    }
                    if (a == 0 || b == 0) {
                        return 2;
                    }
                    return 0;
                }

                public int loops(int n) {
                    int total = 0;
                    int i = 0;
                    while (i < n && total < 100) {
                        total += i;
                        i++;
                    }
                    for (int j = 0; j < n; j++) {
                        total += j;
                    }
                    int[] values = {1, 2, 3};
                    for (int v : values) {
                        total += v;
                    }
                    do {
                        total--;
                    } while (total > n);
                    return total;
                }

                public String describe(int code) {
                    switch (code) {
                        case 1:
                            return "one";
                        case 2:
                            return "two";
                        default:
                            return "many";
                    }
                }
            }
            """;

    @Test
    void boundaryConditionTypeVocabularyIsExactlyThePythonVocabulary() {
        FailureModel model = extractor.extract(SAMPLE, "fx.Sample");

        assertThat(model.classFqn()).isEqualTo("fx.Sample");
        assertThat(model.boundaryConditions()).hasSize(8);
        assertThat(model.boundaryConditions())
                .extracting(BoundaryCondition::type)
                .containsExactlyInAnyOrder(
                        "if_condition", "if_condition", "if_condition",
                        "while_loop",
                        "for_loop",
                        "for_each_loop",
                        "do_while_loop",
                        "switch_statement");
    }

    @Test
    void boundaryConditionsAreAttributedToTheTrueEnclosingMethod() {
        FailureModel model = extractor.extract(SAMPLE, "fx.Sample");

        assertThat(model.boundaryConditions())
                .filteredOn(bc -> bc.type().equals("if_condition"))
                .allSatisfy(bc -> assertThat(bc.method()).isEqualTo("classify"));
        assertThat(model.boundaryConditions())
                .filteredOn(bc -> bc.type().equals("while_loop")
                        || bc.type().equals("for_loop")
                        || bc.type().equals("for_each_loop")
                        || bc.type().equals("do_while_loop"))
                .allSatisfy(bc -> assertThat(bc.method()).isEqualTo("loops"));
        assertThat(model.boundaryConditions())
                .filteredOn(bc -> bc.type().equals("switch_statement"))
                .singleElement()
                .satisfies(bc -> assertThat(bc.method()).isEqualTo("describe"));
    }

    @Test
    void boundaryConditionLineNumbersAreOneBasedSourceLines() {
        FailureModel model = extractor.extract(SAMPLE, "fx.Sample");

        BoundaryCondition firstIf = model.boundaryConditions().stream()
                .filter(bc -> bc.type().equals("if_condition"))
                .findFirst().orElseThrow();
        assertThat(firstIf.line()).isEqualTo(6);
        assertThat(firstIf.expression()).isEqualTo("a > b");

        BoundaryCondition switchBoundary = model.boundaryConditions().stream()
                .filter(bc -> bc.type().equals("switch_statement"))
                .findFirst().orElseThrow();
        assertThat(switchBoundary.line()).isEqualTo(38);
        assertThat(switchBoundary.expression()).isEqualTo("code");

        BoundaryCondition whileBoundary = model.boundaryConditions().stream()
                .filter(bc -> bc.type().equals("while_loop"))
                .findFirst().orElseThrow();
        assertThat(whileBoundary.line()).isEqualTo(20);

        BoundaryCondition doWhileBoundary = model.boundaryConditions().stream()
                .filter(bc -> bc.type().equals("do_while_loop"))
                .findFirst().orElseThrow();
        assertThat(doWhileBoundary.line()).isEqualTo(31);
    }

    @Test
    void conditionIdIsMethodUnderscoreLine() {
        FailureModel model = extractor.extract(SAMPLE, "fx.Sample");

        BoundaryCondition firstIf = model.boundaryConditions().stream()
                .filter(bc -> bc.type().equals("if_condition"))
                .findFirst().orElseThrow();
        assertThat(firstIf.conditionId()).isEqualTo("classify_6");

        BoundaryCondition switchBoundary = model.boundaryConditions().stream()
                .filter(bc -> bc.type().equals("switch_statement"))
                .findFirst().orElseThrow();
        assertThat(switchBoundary.conditionId()).isEqualTo("describe_38");
    }

    @Test
    void decisionPointsCarryOnlySwitchCaseIfAndWhileKindsInPythonAppendOrder() {
        FailureModel model = extractor.extract(SAMPLE, "fx.Sample");

        // for / for-each / do-while / the switch itself are deliberately NOT decision points.
        assertThat(model.decisionPoints())
                .extracting(DecisionPoint::kind)
                .containsOnly("switch_case", "if", "while");

        // extractor.py append order: switch cases (during boundary extraction), then ifs, then whiles.
        assertThat(model.decisionPoints())
                .extracting(DecisionPoint::kind, DecisionPoint::line, DecisionPoint::expression)
                .containsExactly(
                        org.assertj.core.groups.Tuple.tuple("switch_case", 39, "code == 1"),
                        org.assertj.core.groups.Tuple.tuple("switch_case", 41, "code == 2"),
                        org.assertj.core.groups.Tuple.tuple("switch_case", 43, "code == default"),
                        org.assertj.core.groups.Tuple.tuple("if", 6, "a > b"),
                        org.assertj.core.groups.Tuple.tuple("if", 7, "a > 10 && b < 5"),
                        org.assertj.core.groups.Tuple.tuple("if", 11, "a == 0 || b == 0"),
                        org.assertj.core.groups.Tuple.tuple("while", 20, "i < n && total < 100"));
    }

    @Test
    void operationsRecordOnlyTopLevelOperatorsWithComparisonsForIfsOnly() {
        FailureModel model = extractor.extract(SAMPLE, "fx.Sample");

        // if-conditions contribute their top-level && / || / relational operator;
        // the while contributes only its top-level &&; the do-while condition (total > n),
        // the for compare (j < n) and nested operands contribute nothing.
        assertThat(model.operations())
                .extracting(LogicalOperation::method, o -> o.operators().get(0), LogicalOperation::line)
                .containsExactly(
                        org.assertj.core.groups.Tuple.tuple("classify", ">", 6),
                        org.assertj.core.groups.Tuple.tuple("classify", "&&", 7),
                        org.assertj.core.groups.Tuple.tuple("classify", "||", 11),
                        org.assertj.core.groups.Tuple.tuple("loops", "&&", 20));
        assertThat(model.operations()).allSatisfy(op -> assertThat(op.operators()).hasSize(1));
    }

    @Test
    void methodComplexityMatchesContractF11() {
        FailureModel model = extractor.extract(SAMPLE, "fx.Sample");

        // classify: 3 if decision points, 3 operations (>, &&, ||), nested = 2 under the
        // line-proximity heuristic (ifs at lines 6, 7, 11 all land on the same global stack:
        // the if at 7 sees 6 alive, the if at 11 sees both 6 and 7 alive because neither is
        // more than 10 lines above — even though the if at 11 is NOT AST-nested).
        // cyclomatic = 3 + 1 = 4; cognitive = 3 + 3 + 2*2 = 10.
        assertThat(model.methodComplexity().get("classify"))
                .isEqualTo(new MethodComplexity(4, 10));

        // loops: 1 decision point (the while only), 1 operation (&&), 0 nested.
        // cyclomatic = 1 + 1 = 2; cognitive = 1 + 1 + 0 = 2.
        assertThat(model.methodComplexity().get("loops"))
                .isEqualTo(new MethodComplexity(2, 2));

        // describe: 3 switch_case decision points, 0 operations, 0 nested.
        // cyclomatic = 3 + 1 = 4; cognitive = 3 + 0 + 0 = 3.
        assertThat(model.methodComplexity().get("describe"))
                .isEqualTo(new MethodComplexity(4, 3));
    }

    @Test
    void methodComplexityPreservesSourceDeclarationOrder() {
        FailureModel model = extractor.extract(SAMPLE, "fx.Sample");

        assertThat(new ArrayList<>(model.methodComplexity().keySet()))
                .containsExactly("classify", "loops", "describe");
    }

    @Test
    void nestedIfStackIsGlobalAcrossMethodsWithinTheTenLineWindow() {
        // extractor.py's decision stack is never reset between methods: the if in second()
        // at line 9 sees the if from first() at line 4 still on the stack (4 >= 9 - 10),
        // so it counts as nested even though the methods are unrelated.
        String source = """
                package fx;
                public class CrossMethod {
                    void first(int x) {
                        if (x > 0) {
                            int t = 1;
                        }
                    }
                    void second(int y) {
                        if (y > 0) {
                            int u = 2;
                        }
                    }
                }
                """;
        FailureModel model = extractor.extract(source, "fx.CrossMethod");

        // first: 1 decision, 1 op (>), 0 nested -> (2, 2)
        assertThat(model.methodComplexity().get("first")).isEqualTo(new MethodComplexity(2, 2));
        // second: 1 decision, 1 op (>), 1 nested (global stack) -> (2, 1 + 1 + 2*1 = 4)
        assertThat(model.methodComplexity().get("second")).isEqualTo(new MethodComplexity(2, 4));
    }

    @Test
    void nestedIfStackEntriesExpireBeyondTheTenLineWindow() {
        // Here the second if sits at line 15; the stacked if-line 4 satisfies 4 < 15 - 10,
        // is popped before the push, and the second if is NOT nested.
        String source = """
                package fx;
                public class FarApart {
                    void first(int x) {
                        if (x > 0) {
                            int t = 1;
                        }
                    }
                    // filler
                    // filler
                    // filler
                    // filler
                    // filler
                    // filler
                    void second(int y) {
                        if (y > 0) {
                            int u = 2;
                        }
                    }
                }
                """;
        FailureModel model = extractor.extract(source, "fx.FarApart");

        assertThat(model.methodComplexity().get("first")).isEqualTo(new MethodComplexity(2, 2));
        // second: 1 decision, 1 op, 0 nested -> (2, 2)
        assertThat(model.methodComplexity().get("second")).isEqualTo(new MethodComplexity(2, 2));
    }

    @Test
    void unparseableSourceYieldsTheEmptyNullObjectWithoutThrowing() {
        assertThatCode(() -> extractor.extract("not java at all {{{", "x.Y"))
                .doesNotThrowAnyException();

        FailureModel model = extractor.extract("not java at all {{{", "x.Y");
        assertThat(model.classFqn()).isEqualTo("x.Y");
        assertThat(model.isEmpty()).isTrue();
        assertThat(model.boundaryConditions()).isEmpty();
        assertThat(model.operations()).isEmpty();
        assertThat(model.decisionPoints()).isEmpty();
        assertThat(model.methodComplexity()).isEmpty();
    }

    @Test
    void nullSourceYieldsTheEmptyNullObjectWithoutThrowing() {
        assertThatCode(() -> extractor.extract(null, "x.Y")).doesNotThrowAnyException();

        FailureModel model = extractor.extract(null, "x.Y");
        assertThat(model.classFqn()).isEqualTo("x.Y");
        assertThat(model.isEmpty()).isTrue();
        assertThat(model.boundaryConditions()).isEmpty();
        assertThat(model.operations()).isEmpty();
        assertThat(model.decisionPoints()).isEmpty();
        assertThat(model.methodComplexity()).isEmpty();
    }

    @Test
    void modernSyntaxParsesAndSwitchExpressionSurfacesAsSwitchStatementBoundary() {
        // Record + switch EXPRESSION (arrow form with yield) + text block: all beyond the
        // javalang (Java 8) ceiling of the Python extractor; the Java port must handle them.
        String source = """
                package fx;

                public record Modern(String name, int value) {
                    public String label() {
                        String banner = \"""
                                greetings
                                \""";
                        int score = switch (value) {
                            case 1 -> 10;
                            case 2 -> {
                                int bonus = 5;
                                yield 20 + bonus;
                            }
                            default -> 0;
                        };
                        return banner + score;
                    }
                }
                """;
        FailureModel model = extractor.extract(source, "fx.Modern");

        assertThat(model.isEmpty()).isFalse();
        assertThat(model.boundaryConditions())
                .singleElement()
                .satisfies(bc -> {
                    assertThat(bc.type()).isEqualTo("switch_statement");
                    assertThat(bc.method()).isEqualTo("label");
                    assertThat(bc.line()).isEqualTo(8);
                    assertThat(bc.expression()).isEqualTo("value");
                });
        assertThat(model.decisionPoints())
                .extracting(DecisionPoint::kind, DecisionPoint::expression)
                .containsExactly(
                        org.assertj.core.groups.Tuple.tuple("switch_case", "value == 1"),
                        org.assertj.core.groups.Tuple.tuple("switch_case", "value == 2"),
                        org.assertj.core.groups.Tuple.tuple("switch_case", "value == default"));
        // label: 3 switch_case decisions, 0 ops, 0 nested -> cyclomatic 4, cognitive 3.
        assertThat(model.methodComplexity().get("label")).isEqualTo(new MethodComplexity(4, 3));
    }

    @Test
    void forLoopBoundaryRendersControlAsInitCompareUpdate() {
        FailureModel model = extractor.extract(SAMPLE, "fx.Sample");

        BoundaryCondition forBoundary = model.boundaryConditions().stream()
                .filter(bc -> bc.type().equals("for_loop"))
                .findFirst().orElseThrow();
        assertThat(forBoundary.expression()).isEqualTo("int j = 0; j < n; j++");

        BoundaryCondition forEachBoundary = model.boundaryConditions().stream()
                .filter(bc -> bc.type().equals("for_each_loop"))
                .findFirst().orElseThrow();
        assertThat(forEachBoundary.expression()).isEqualTo("for-each: values");
    }

    @Test
    void emptyModelHelperMatchesNullObjectShape() {
        FailureModel empty = FailureModel.empty("x.Y");
        assertThat(empty.isEmpty()).isTrue();
        assertThat(List.of(empty.boundaryConditions(), empty.operations(), empty.decisionPoints()))
                .allSatisfy(list -> assertThat(list).isEmpty());
    }
}
