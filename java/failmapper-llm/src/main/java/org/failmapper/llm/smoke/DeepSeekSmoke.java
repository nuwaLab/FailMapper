package org.failmapper.llm.smoke;

import com.github.javaparser.ast.CompilationUnit;
import org.failmapper.analysis.ClassModelExtractor;
import org.failmapper.analysis.FailureModelExtractor;
import org.failmapper.analysis.SourceAnalyzer;
import org.failmapper.core.model.BoundaryCondition;
import org.failmapper.core.model.ClassModel;
import org.failmapper.core.model.FailureModel;
import org.failmapper.core.model.MethodModel;
import org.failmapper.llm.CodeExtractor;
import org.failmapper.llm.DeepSeekClient;

import javax.tools.DiagnosticCollector;
import javax.tools.JavaCompiler;
import javax.tools.JavaFileObject;
import javax.tools.SimpleJavaFileObject;
import javax.tools.StandardJavaFileManager;
import javax.tools.ToolProvider;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Collectors;

/**
 * End-to-end smoke harness (NOT the M4 prompt port): source file -> analysis
 * models -> a simple v0 prompt -> DeepSeek -> code extraction -> in-memory
 * javac compile check against a caller-supplied classpath.
 *
 * Usage: DeepSeekSmoke &lt;java-source-file&gt; &lt;classpath-file&gt;
 * where classpath-file contains a single line produced by
 * mvn dependency:build-classpath (JUnit + target project classes).
 * Requires DEEPSEEK_API_KEY in the environment.
 */
public final class DeepSeekSmoke {

    public static void main(String[] args) throws Exception {
        if (args.length < 2) {
            System.err.println("usage: DeepSeekSmoke <java-source-file> <classpath-file>");
            System.exit(2);
        }
        Path sourceFile = Path.of(args[0]);
        String classpath = Files.readString(Path.of(args[1])).trim();

        String source = Files.readString(sourceFile);
        SourceAnalyzer analyzer = new SourceAnalyzer();
        CompilationUnit cu = analyzer.parse(source).orElseThrow(
                () -> new IllegalStateException("cannot parse " + sourceFile));
        ClassModel classModel = new ClassModelExtractor().extractPrimary(cu, sourceFile.toString())
                .orElseThrow(() -> new IllegalStateException("no type in " + sourceFile));
        FailureModel failureModel = new FailureModelExtractor().extract(source, classModel.fqn());

        String prompt = buildPrompt(source, classModel, failureModel);
        System.out.println("== target: " + classModel.fqn());
        System.out.println("== boundary conditions: " + failureModel.boundaryConditions().size()
                + ", operations: " + failureModel.operations().size());

        DeepSeekClient client = new DeepSeekClient();
        System.out.println("== model: " + client.model());
        long start = System.currentTimeMillis();
        String reply = client.complete(
                "You are an expert Java test engineer. Reply with a single complete JUnit 5 test class in a ```java fence. "
                        + "Do not use any mocking framework. Use only real objects.",
                prompt);
        long elapsed = System.currentTimeMillis() - start;
        System.out.println("== LLM reply: " + reply.length() + " chars in " + elapsed + " ms");

        String testCode = new CodeExtractor().extract(reply).orElse(null);
        if (testCode == null) {
            System.out.println("SMOKE RESULT: FAIL (no parseable Java in reply)");
            System.exit(1);
        }
        System.out.println("== extracted test class: " + testCode.length() + " chars, parseable Java");

        List<javax.tools.Diagnostic<? extends JavaFileObject>> errors = compile(testCode, classpath);
        if (errors.isEmpty()) {
            System.out.println("SMOKE RESULT: PASS (generated test compiles against the target classpath)");
        } else {
            System.out.println("SMOKE RESULT: PARTIAL (parseable but " + errors.size() + " compile errors)");
            errors.stream().limit(5).forEach(d ->
                    System.out.println("   " + d.getLineNumber() + ":" + d.getColumnNumber() + " " + d.getMessage(null)));
        }
    }

    private static String buildPrompt(String source, ClassModel model, FailureModel failureModel) {
        String methods = model.methods().stream()
                .map(MethodModel::name).distinct().collect(Collectors.joining(", "));
        String conditions = failureModel.boundaryConditions().stream().limit(10)
                .map(c -> c.type() + " in " + c.method() + " at line " + c.line() + ": " + c.expression())
                .collect(Collectors.joining("\n"));
        return "Write a JUnit 5 test class named " + model.simpleName() + "Test in package " + model.packageName()
                + " for the class below. Target the listed boundary conditions with edge-case inputs.\n\n"
                + "Methods: " + methods + "\n\nBoundary conditions to target:\n" + conditions
                + "\n\nSource:\n```java\n" + source + "\n```\n";
    }

    private static List<javax.tools.Diagnostic<? extends JavaFileObject>> compile(String testCode, String classpath)
            throws Exception {
        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        DiagnosticCollector<JavaFileObject> diagnostics = new DiagnosticCollector<>();
        Path outDir = Files.createTempDirectory("failmapper-smoke");
        String className = testCode.lines().filter(l -> l.contains("class "))
                .findFirst().map(l -> l.replaceAll(".*class\\s+(\\w+).*", "$1")).orElse("GeneratedTest");
        JavaFileObject file = new SimpleJavaFileObject(
                URI.create("string:///" + className + ".java"), JavaFileObject.Kind.SOURCE) {
            @Override
            public CharSequence getCharContent(boolean ignoreEncodingErrors) {
                return testCode;
            }
        };
        try (StandardJavaFileManager fm = compiler.getStandardFileManager(diagnostics, null, null)) {
            compiler.getTask(null, fm, diagnostics,
                    List.of("-classpath", classpath, "-d", outDir.toString()),
                    null, List.of(file)).call();
        }
        return diagnostics.getDiagnostics().stream()
                .filter(d -> d.getKind() == javax.tools.Diagnostic.Kind.ERROR)
                .collect(Collectors.toList());
    }
}
