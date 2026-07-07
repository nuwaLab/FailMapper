package org.failmapper.analysis;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URISyntaxException;
import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.Test;

/**
 * {@link SymbolApiRetriever} (I16) against a known REAL jar: the junit-jupiter-api jar
 * this test itself runs with, located from the class's code source (i.e. the local
 * repository copy) — no network, no hardcoded repo layout.
 */
class SymbolApiRetrieverTest {

    private static String junitApiJar() {
        try {
            Path jar = Path.of(Test.class.getProtectionDomain()
                    .getCodeSource().getLocation().toURI());
            assertThat(jar).exists();
            return jar.toString();
        } catch (URISyntaxException e) {
            throw new IllegalStateException(e);
        }
    }

    @Test
    void resolvesSimpleNameAgainstJar() {
        List<String> lines = new SymbolApiRetriever()
                .lookup("Assertions", List.of(junitApiJar()), 2000);

        assertThat(lines).isNotEmpty();
        assertThat(lines.get(0)).isEqualTo("class org.junit.jupiter.api.Assertions");
        // A well-known method with JRE-only parameter types must be present.
        assertThat(lines).anySatisfy(line ->
                assertThat(line).contains("assertTrue(boolean)"));
        // Member lines carry the documented prefixes.
        assertThat(lines.subList(1, lines.size())).allSatisfy(line ->
                assertThat(line).matches("(constructor |method |\\.\\.\\. and ).*"));
    }

    @Test
    void resolvesFullyQualifiedName() {
        List<String> lines = new SymbolApiRetriever()
                .lookup("org.junit.jupiter.api.Assertions", List.of(junitApiJar()));

        assertThat(lines).isNotEmpty();
        assertThat(lines.get(0)).isEqualTo("class org.junit.jupiter.api.Assertions");
    }

    @Test
    void capsMemberLinesWithOverflowMarker() {
        List<String> lines = new SymbolApiRetriever()
                .lookup("Assertions", List.of(junitApiJar()), 5);

        // 1 class line + 5 members + overflow marker (Assertions has far more members).
        assertThat(lines).hasSize(7);
        assertThat(lines.get(6)).startsWith("... and ").endsWith(" more members");
    }

    @Test
    void unknownSymbolYieldsEmptyList() {
        assertThat(new SymbolApiRetriever()
                .lookup("NoSuchClazz12345", List.of(junitApiJar()))).isEmpty();
        assertThat(new SymbolApiRetriever()
                .lookup("com.nowhere.NoSuchClazz", List.of(junitApiJar()))).isEmpty();
    }

    @Test
    void missingClasspathEntriesAreSkippedNotFatal() {
        List<String> lines = new SymbolApiRetriever().lookup("Assertions",
                List.of("/nonexistent/path/foo.jar", junitApiJar()));
        assertThat(lines).isNotEmpty();
        assertThat(lines.get(0)).isEqualTo("class org.junit.jupiter.api.Assertions");
    }

    @Test
    void blankOrNullInputsYieldEmptyList() {
        SymbolApiRetriever retriever = new SymbolApiRetriever();
        assertThat(retriever.lookup(null, List.of(junitApiJar()))).isEmpty();
        assertThat(retriever.lookup("  ", List.of(junitApiJar()))).isEmpty();
        assertThat(retriever.lookup("Assertions", null)).isEmpty();
    }
}
