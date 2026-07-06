package org.failmapper.exec;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.failmapper.core.model.Diagnostic;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.assertj.core.api.Assertions.assertThat;

class InMemoryCompilerTest {

    private final InMemoryCompiler compiler = new InMemoryCompiler();

    @Test
    void compilesValidSourceAndWritesClassFileToOutputDir(@TempDir Path tempDir) {
        Path outputDir = tempDir.resolve("classes");
        CompileResult result = compiler.compile(
                "fm.sample.Greeter",
                """
                package fm.sample;
                public class Greeter {
                    public String greet(String name) { return "hello " + name; }
                }
                """,
                List.of(),
                outputDir);

        assertThat(result.success()).isTrue();
        assertThat(result.errors()).isEmpty();
        assertThat(Files.exists(outputDir.resolve("fm/sample/Greeter.class"))).isTrue();
    }

    @Test
    void reportsStructuredDiagnosticsForBrokenSource(@TempDir Path tempDir) {
        CompileResult result = compiler.compile(
                "fm.sample.Broken",
                """
                package fm.sample;
                public class Broken {
                    public int oops() { return undefinedSymbol; }
                }
                """,
                List.of(),
                tempDir.resolve("classes"));

        assertThat(result.success()).isFalse();
        assertThat(result.errors()).isNotEmpty();
        Diagnostic error = result.errors().get(0);
        assertThat(error.kind()).isEqualTo(Diagnostic.Kind.ERROR);
        assertThat(error.line()).isEqualTo(3); // the undefinedSymbol line
        assertThat(error.message()).contains("undefinedSymbol");
    }

    @Test
    void resolvesTypesFromSuppliedClasspath(@TempDir Path tempDir) {
        Path libDir = tempDir.resolve("lib");
        CompileResult dependency = compiler.compile(
                "fm.dep.Adder",
                """
                package fm.dep;
                public class Adder {
                    public int add(int a, int b) { return a + b; }
                }
                """,
                List.of(),
                libDir);
        assertThat(dependency.success()).isTrue();

        CompileResult dependent = compiler.compile(
                "fm.sample.UsesAdder",
                """
                package fm.sample;
                import fm.dep.Adder;
                public class UsesAdder {
                    public int five() { return new Adder().add(2, 3); }
                }
                """,
                List.of(libDir.toString()),
                tempDir.resolve("classes"));
        assertThat(dependent.success()).isTrue();
    }
}
