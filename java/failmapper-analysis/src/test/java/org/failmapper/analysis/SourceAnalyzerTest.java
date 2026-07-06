package org.failmapper.analysis;

import com.github.javaparser.ast.CompilationUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests for {@link SourceAnalyzer}: never-throwing parse facade at Java 21 language level.
 */
class SourceAnalyzerTest {

    private final SourceAnalyzer analyzer = new SourceAnalyzer();

    @Test
    void parseReturnsCompilationUnitForValidSource() {
        Optional<CompilationUnit> cu = analyzer.parse("""
                package fx;
                public class Simple {
                    int twice(int x) {
                        return 2 * x;
                    }
                }
                """);

        assertThat(cu).isPresent();
        assertThat(cu.orElseThrow().getType(0).getNameAsString()).isEqualTo("Simple");
    }

    @Test
    void parseReturnsEmptyForGarbageWithoutThrowing() {
        assertThat(analyzer.parse("not java at all {{{")).isEmpty();
        assertThat(analyzer.parse("class {")).isEmpty();
    }

    @Test
    void parseFileReadsAndParsesATempFile(@TempDir Path tempDir) throws Exception {
        Path file = tempDir.resolve("Simple.java");
        Files.writeString(file, """
                package fx;
                public class Simple {
                }
                """);

        Optional<CompilationUnit> cu = analyzer.parseFile(file);

        assertThat(cu).isPresent();
        assertThat(cu.orElseThrow().getPackageDeclaration().orElseThrow().getNameAsString())
                .isEqualTo("fx");
    }

    @Test
    void parseFileReturnsEmptyForMissingFileWithoutThrowing(@TempDir Path tempDir) {
        assertThat(analyzer.parseFile(tempDir.resolve("Nope.java"))).isEmpty();
    }

    @Test
    void modernJavaSyntaxParsesAtJava21LanguageLevel() {
        Optional<CompilationUnit> cu = analyzer.parse("""
                package fx;
                public record Wrapper(int code) {
                    public String describe() {
                        return switch (code) {
                            case 0 -> "zero";
                            default -> "other";
                        };
                    }
                }
                """);

        assertThat(cu).isPresent();
        assertThat(cu.orElseThrow().getType(0).isRecordDeclaration()).isTrue();
    }
}
