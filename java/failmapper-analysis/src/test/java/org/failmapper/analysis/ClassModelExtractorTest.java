package org.failmapper.analysis;

import com.github.javaparser.ast.CompilationUnit;
import org.failmapper.core.model.ClassModel;
import org.failmapper.core.model.ConstructorModel;
import org.failmapper.core.model.FieldModel;
import org.failmapper.core.model.MethodModel;
import org.failmapper.core.model.ParameterModel;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests for {@link ClassModelExtractor}: FQN dotted nesting, kind vocabulary,
 * as-written supertype/interface/type text, multi-declarator fields, constructor
 * visibility semantics (including the implicit record canonical constructor),
 * and source-order preservation.
 */
class ClassModelExtractorTest {

    private static final String SOURCE = """
            package pkg;

            import java.util.List;
            import java.util.Map;
            import static java.util.Objects.requireNonNull;
            import java.io.*;

            public abstract class Outer extends BaseThing<String> implements Comparable<Outer>, Serializable {

                int a, b = 7;
                private static final String NAME = "outer";

                public Outer() {
                }

                private Outer(int seed) {
                    this.a = seed;
                }

                public final <T> List<T> combine(Map<String, T> source, T... extras) throws IOException, IllegalStateException {
                    return null;
                }

                @Override
                public String toString() {
                    return NAME;
                }

                protected abstract int weigh();

                static class Inner {
                    private Inner() {
                    }

                    void ping() {
                    }
                }

                enum Color {
                    RED, GREEN
                }

                record Point(int x, int y) implements Serializable {
                }
            }
            """;

    private final ClassModelExtractor extractor = new ClassModelExtractor();
    private List<ClassModel> models;
    private ClassModel outer;

    @BeforeEach
    void extract() {
        CompilationUnit cu = new SourceAnalyzer().parse(SOURCE).orElseThrow();
        models = extractor.extractAll(cu, "src/pkg/Outer.java");
        outer = models.get(0);
    }

    @Test
    void extractAllYieldsOuterThenNestedTypesInSourceOrderWithDottedFqns() {
        assertThat(models)
                .extracting(ClassModel::fqn)
                .containsExactly("pkg.Outer", "pkg.Outer.Inner", "pkg.Outer.Color", "pkg.Outer.Point");
        assertThat(models)
                .extracting(ClassModel::kind)
                .containsExactly("class", "class", "enum", "record");
        assertThat(models).allSatisfy(m -> {
            assertThat(m.packageName()).isEqualTo("pkg");
            assertThat(m.sourcePath()).isEqualTo("src/pkg/Outer.java");
        });
        assertThat(models)
                .extracting(ClassModel::simpleName)
                .containsExactly("Outer", "Inner", "Color", "Point");
    }

    @Test
    void classHeaderCapturesAbstractnessSuperclassAndInterfacesAsWritten() {
        assertThat(outer.isAbstract()).isTrue();
        assertThat(outer.superclass()).isEqualTo("BaseThing<String>");
        assertThat(outer.interfaces()).containsExactly("Comparable<Outer>", "Serializable");

        ClassModel inner = models.get(1);
        assertThat(inner.isAbstract()).isFalse();
        assertThat(inner.superclass()).isNull();
        assertThat(inner.interfaces()).isEmpty();

        ClassModel point = models.get(3);
        assertThat(point.superclass()).isNull();
        assertThat(point.interfaces()).containsExactly("Serializable");
    }

    @Test
    void multiDeclaratorFieldSplitsIntoOneModelPerVariable() {
        // int a, b = 7;  ->  two FieldModels sharing modifiers, initializer null vs "7".
        assertThat(outer.fields())
                .extracting(FieldModel::name, FieldModel::type, FieldModel::initializer)
                .containsExactly(
                        org.assertj.core.groups.Tuple.tuple("a", "int", null),
                        org.assertj.core.groups.Tuple.tuple("b", "int", "7"),
                        org.assertj.core.groups.Tuple.tuple("NAME", "String", "\"outer\""));
        assertThat(outer.fields().get(0).modifiers()).isEmpty();
        assertThat(outer.fields().get(1).modifiers()).isEmpty();
        assertThat(outer.fields().get(2).modifiers()).containsExactly("private", "static", "final");
    }

    @Test
    void constructorVisibilityIsModeledPerConstructor() {
        assertThat(outer.constructors()).hasSize(2);

        ConstructorModel publicCtor = outer.constructors().get(0);
        assertThat(publicCtor.modifiers()).containsExactly("public");
        assertThat(publicCtor.isPublic()).isTrue();
        assertThat(publicCtor.parameters()).isEmpty();

        ConstructorModel privateCtor = outer.constructors().get(1);
        assertThat(privateCtor.modifiers()).containsExactly("private");
        assertThat(privateCtor.isPublic()).isFalse();
        assertThat(privateCtor.parameters())
                .extracting(ParameterModel::name, ParameterModel::type)
                .containsExactly(org.assertj.core.groups.Tuple.tuple("seed", "int"));

        assertThat(outer.hasPublicConstructor()).isTrue();
    }

    @Test
    void privateOnlyConstructorMeansNoPublicConstructor() {
        ClassModel inner = models.get(1);
        assertThat(inner.constructors()).hasSize(1);
        assertThat(inner.constructors().get(0).isPublic()).isFalse();
        assertThat(inner.hasPublicConstructor()).isFalse();
    }

    @Test
    void recordWithoutExplicitConstructorHasEmptyListAndCountsAsPubliclyConstructible() {
        ClassModel point = models.get(3);
        assertThat(point.kind()).isEqualTo("record");
        assertThat(point.constructors()).isEmpty();
        // Empty list = implicit canonical constructor -> constructible.
        assertThat(point.hasPublicConstructor()).isTrue();
    }

    @Test
    void methodSignaturesCaptureVarargsGenericsThrowsAndLowercaseModifiers() {
        MethodModel combine = outer.methods().get(0);
        assertThat(combine.name()).isEqualTo("combine");
        assertThat(combine.returnType()).isEqualTo("List<T>");
        assertThat(combine.modifiers()).containsExactly("public", "final");
        assertThat(combine.thrownExceptions()).containsExactly("IOException", "IllegalStateException");
        assertThat(combine.parameters())
                .extracting(ParameterModel::name, ParameterModel::type)
                .containsExactly(
                        org.assertj.core.groups.Tuple.tuple("source", "Map<String,T>"),
                        org.assertj.core.groups.Tuple.tuple("extras", "T..."));

        MethodModel weigh = outer.methods().get(2);
        assertThat(weigh.modifiers()).containsExactly("protected", "abstract");
        assertThat(weigh.thrownExceptions()).isEmpty();
    }

    @Test
    void overrideFlagIsTrueForAnnotatedMethodOnly() {
        MethodModel toStringMethod = outer.methods().get(1);
        assertThat(toStringMethod.name()).isEqualTo("toString");
        assertThat(toStringMethod.isOverride()).isTrue();

        assertThat(outer.methods().get(0).isOverride()).as("combine").isFalse();
        assertThat(outer.methods().get(2).isOverride()).as("weigh").isFalse();
    }

    @Test
    void methodsAppearInSourceDeclarationOrderWithOneBasedLines() {
        assertThat(outer.methods())
                .extracting(MethodModel::name)
                .containsExactly("combine", "toString", "weigh");
        // Lines are 1-based and increasing in declaration order.
        assertThat(outer.methods().get(0).startLine()).isPositive();
        assertThat(outer.methods())
                .isSortedAccordingTo((m1, m2) -> Integer.compare(m1.startLine(), m2.startLine()));
        assertThat(outer.methods().get(0).endLine())
                .isGreaterThanOrEqualTo(outer.methods().get(0).startLine());
    }

    @Test
    void importsKeepSourceOrderStaticPrefixAndWildcardSuffix() {
        assertThat(outer.imports()).containsExactly(
                "java.util.List",
                "java.util.Map",
                "static java.util.Objects.requireNonNull",
                "java.io.*");
    }

    @Test
    void extractPrimaryReturnsTheFirstTopLevelType() {
        CompilationUnit cu = new SourceAnalyzer().parse(SOURCE).orElseThrow();
        assertThat(extractor.extractPrimary(cu, "src/pkg/Outer.java"))
                .isPresent()
                .hasValueSatisfying(m -> assertThat(m.fqn()).isEqualTo("pkg.Outer"));
    }
}
