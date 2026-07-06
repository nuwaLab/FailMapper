package org.failmapper.analysis;

import com.github.javaparser.JavaParser;
import com.github.javaparser.ParseResult;
import com.github.javaparser.ParserConfiguration;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.symbolsolver.JavaSymbolSolver;
import com.github.javaparser.symbolsolver.resolution.typesolvers.CombinedTypeSolver;
import com.github.javaparser.symbolsolver.resolution.typesolvers.JavaParserTypeSolver;
import com.github.javaparser.symbolsolver.resolution.typesolvers.ReflectionTypeSolver;

import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

/**
 * Shared JavaParser facade for the analysis layer.
 *
 * <p>Configured for {@link ParserConfiguration.LanguageLevel#JAVA_21} with a
 * symbol solver backed by a {@link CombinedTypeSolver}: reflection (JDK/classpath
 * types) plus one {@link JavaParserTypeSolver} per supplied source root, so that
 * best-effort resolution (e.g. override detection) can see project types.
 *
 * <p>Parsing never throws: any syntax or I/O problem yields an empty Optional.
 */
public final class SourceAnalyzer {

    private final JavaParser parser;

    /** Creates an analyzer whose symbol solver only sees JDK/classpath types. */
    public SourceAnalyzer() {
        this(List.of());
    }

    /**
     * Creates an analyzer whose symbol solver sees JDK/classpath types plus
     * the sources under each given source root.
     *
     * @param sourceRoots source directories (package roots) to resolve project types from
     */
    public SourceAnalyzer(List<Path> sourceRoots) {
        CombinedTypeSolver typeSolver = new CombinedTypeSolver();
        typeSolver.add(new ReflectionTypeSolver());
        for (Path root : sourceRoots) {
            typeSolver.add(new JavaParserTypeSolver(root));
        }
        ParserConfiguration configuration = new ParserConfiguration()
                .setLanguageLevel(ParserConfiguration.LanguageLevel.JAVA_21)
                .setSymbolResolver(new JavaSymbolSolver(typeSolver));
        this.parser = new JavaParser(configuration);
    }

    /**
     * Parses Java source text.
     *
     * @return the compilation unit, or empty on any parse problem
     */
    public Optional<CompilationUnit> parse(String source) {
        try {
            return successful(parser.parse(source));
        } catch (Exception e) {
            return Optional.empty();
        }
    }

    /**
     * Parses a Java source file.
     *
     * @return the compilation unit, or empty on any parse or I/O problem
     */
    public Optional<CompilationUnit> parseFile(Path file) {
        try {
            return successful(parser.parse(file));
        } catch (Exception e) {
            return Optional.empty();
        }
    }

    private static Optional<CompilationUnit> successful(ParseResult<CompilationUnit> result) {
        if (!result.isSuccessful()) {
            return Optional.empty();
        }
        return result.getResult();
    }
}
