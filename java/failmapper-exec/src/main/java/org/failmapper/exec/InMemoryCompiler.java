package org.failmapper.exec;

import java.io.IOException;
import java.io.PrintWriter;
import java.io.UncheckedIOException;
import java.io.Writer;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

import javax.tools.DiagnosticCollector;
import javax.tools.JavaCompiler;
import javax.tools.JavaFileObject;
import javax.tools.SimpleJavaFileObject;
import javax.tools.StandardJavaFileManager;
import javax.tools.StandardLocation;
import javax.tools.ToolProvider;

import org.failmapper.core.model.Diagnostic;

/**
 * Compiles a single generated test class fully in memory via
 * {@link javax.tools.JavaCompiler} — milliseconds-fast, no subprocess, no
 * temp .java files, and (contract root-cause fixes) no console-text parsing
 * and nothing ever written into a user project's {@code src/} tree: class
 * files go ONLY to the caller-supplied {@code outputDir}.
 *
 * <p>Diagnostics are rendered with {@link Locale#ROOT} so messages are
 * locale-independent (stable across machines/JDK language settings).</p>
 */
public final class InMemoryCompiler {

    /**
     * @param className fully-qualified name of the (single) top-level class in {@code source}
     * @param source    the complete compilation-unit source text
     * @param classpath compile classpath entries (dirs or jars), in order
     * @param outputDir where .class files are written (created if absent); must NOT
     *                  point inside a user project's source tree
     */
    public CompileResult compile(String className, String source, List<String> classpath, Path outputDir) {
        Objects.requireNonNull(className, "className");
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(classpath, "classpath");
        Objects.requireNonNull(outputDir, "outputDir");

        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        if (compiler == null) {
            throw new IllegalStateException(
                    "No system Java compiler available — FailMapper must run on a JDK, not a JRE");
        }
        DiagnosticCollector<JavaFileObject> collector = new DiagnosticCollector<>();
        try (StandardJavaFileManager fileManager =
                     compiler.getStandardFileManager(collector, Locale.ROOT, StandardCharsets.UTF_8)) {
            Files.createDirectories(outputDir);
            fileManager.setLocationFromPaths(StandardLocation.CLASS_OUTPUT, List.of(outputDir));
            fileManager.setLocationFromPaths(StandardLocation.CLASS_PATH,
                    classpath.stream().map(Path::of).toList());

            JavaFileObject unit = new StringSource(className, source);
            JavaCompiler.CompilationTask task = compiler.getTask(
                    new PrintWriter(Writer.nullWriter()), // non-diagnostic output discarded
                    fileManager,
                    collector,
                    List.of("-proc:none"), // no annotation processing for generated tests
                    null,
                    List.of(unit));
            boolean success = Boolean.TRUE.equals(task.call());
            List<Diagnostic> mapped = collector.getDiagnostics().stream().map(InMemoryCompiler::map).toList();
            return new CompileResult(success, mapped);
        } catch (IOException e) {
            throw new UncheckedIOException("in-memory compilation of " + className + " failed", e);
        }
    }

    private static Diagnostic map(javax.tools.Diagnostic<? extends JavaFileObject> d) {
        return new Diagnostic(
                mapKind(d.getKind()),
                d.getSource() == null ? "<unknown>" : d.getSource().getName(),
                d.getLineNumber(),
                d.getColumnNumber(),
                d.getMessage(Locale.ROOT));
    }

    private static Diagnostic.Kind mapKind(javax.tools.Diagnostic.Kind kind) {
        return switch (kind) {
            case ERROR -> Diagnostic.Kind.ERROR;
            case WARNING, MANDATORY_WARNING -> Diagnostic.Kind.WARNING;
            case NOTE, OTHER -> Diagnostic.Kind.NOTE;
        };
    }

    /** A compilation unit held as a String (never touches disk). */
    private static final class StringSource extends SimpleJavaFileObject {
        private final String code;

        StringSource(String className, String code) {
            super(URI.create("string:///" + className.replace('.', '/') + Kind.SOURCE.extension), Kind.SOURCE);
            this.code = code;
        }

        @Override
        public CharSequence getCharContent(boolean ignoreEncodingErrors) {
            return code;
        }
    }
}
