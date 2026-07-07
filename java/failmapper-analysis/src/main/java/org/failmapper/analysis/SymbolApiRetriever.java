package org.failmapper.analysis;

import com.github.javaparser.resolution.declarations.ResolvedConstructorDeclaration;
import com.github.javaparser.resolution.declarations.ResolvedMethodDeclaration;
import com.github.javaparser.resolution.declarations.ResolvedReferenceTypeDeclaration;
import com.github.javaparser.symbolsolver.resolution.typesolvers.CombinedTypeSolver;
import com.github.javaparser.symbolsolver.resolution.typesolvers.JarTypeSolver;
import com.github.javaparser.symbolsolver.resolution.typesolvers.ReflectionTypeSolver;
import com.github.javaparser.resolution.TypeSolver;
import com.github.javaparser.resolution.model.SymbolReference;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.stream.Stream;

/**
 * I16 — error-driven API retrieval (contract section 4, registered 2026-07-07).
 *
 * <p>When a generated test fails to compile with {@code cannot find symbol} /
 * {@code package ... does not exist}, the Python baseline could only re-prompt with the
 * raw compiler text. This helper resolves the missing symbol against the build model's
 * REAL classpath via JavaParser's symbol solver ({@link JarTypeSolver} entries combined
 * with a JRE {@link ReflectionTypeSolver}) and renders the type's declared constructors
 * and methods as summary strings, which the app layer appends to the NEXT fix prompt as
 * a {@code REAL API OF MISSING SYMBOL} section — the fix is grounded in the actual API
 * instead of the LLM's guess.
 *
 * <p>Determinism: candidates are searched in classpath order (first match wins);
 * member summaries are sorted lexicographically. Resolution failures of individual
 * members (e.g. a parameter type not on the classpath) skip that member only.
 */
public final class SymbolApiRetriever {

    /** Default cap on rendered member lines (constructors + methods). */
    public static final int DEFAULT_MAX_MEMBERS = 40;

    /**
     * Look up a type by simple or fully-qualified name on the given classpath.
     *
     * @param simpleOrFqn e.g. {@code "Assertions"} or {@code "org.junit.jupiter.api.Assertions"}
     * @param classpath   jar/directory entries (absolute paths); non-jar or missing
     *                    entries are skipped for solving but directories are still
     *                    scanned for candidate class files
     * @return summary lines — first line {@code "class <fqn>"}, then
     *         {@code "constructor <sig>"} and {@code "method <sig>"} lines (sorted,
     *         capped at {@link #DEFAULT_MAX_MEMBERS} with an overflow marker); empty
     *         when the symbol cannot be resolved
     */
    public List<String> lookup(String simpleOrFqn, List<String> classpath) {
        return lookup(simpleOrFqn, classpath, DEFAULT_MAX_MEMBERS);
    }

    public List<String> lookup(String simpleOrFqn, List<String> classpath, int maxMembers) {
        if (simpleOrFqn == null || simpleOrFqn.isBlank() || classpath == null) {
            return List.of();
        }
        String name = simpleOrFqn.strip();

        TypeSolver solver = buildSolver(classpath);

        List<String> candidateFqns = name.indexOf('.') >= 0
                ? List.of(name)
                : findCandidateFqns(name, classpath);

        for (String fqn : candidateFqns) {
            try {
                SymbolReference<ResolvedReferenceTypeDeclaration> ref = solver.tryToSolveType(fqn);
                if (ref.isSolved()) {
                    return describe(ref.getCorrespondingDeclaration(), fqn, maxMembers);
                }
            } catch (RuntimeException e) {
                // Unresolvable candidate — try the next one.
            }
        }
        return List.of();
    }

    private static TypeSolver buildSolver(List<String> classpath) {
        CombinedTypeSolver combined = new CombinedTypeSolver();
        combined.add(new ReflectionTypeSolver(true)); // JRE types only
        for (String entry : classpath) {
            Path path = Path.of(entry);
            if (entry.endsWith(".jar") && Files.isRegularFile(path)) {
                try {
                    combined.add(new JarTypeSolver(path));
                } catch (IOException | RuntimeException e) {
                    // Unreadable jar — skip; the remaining classpath still resolves.
                }
            }
        }
        return combined;
    }

    /** Scan jars/dirs in classpath order for {@code **}{@code /Simple.class}; skip inner classes. */
    private static List<String> findCandidateFqns(String simpleName, List<String> classpath) {
        LinkedHashSet<String> fqns = new LinkedHashSet<>();
        String suffix = "/" + simpleName + ".class";
        String bare = simpleName + ".class";

        for (String entry : classpath) {
            Path path = Path.of(entry);
            if (entry.endsWith(".jar") && Files.isRegularFile(path)) {
                try (JarFile jar = new JarFile(path.toFile())) {
                    Enumeration<JarEntry> entries = jar.entries();
                    while (entries.hasMoreElements()) {
                        String entryName = entries.nextElement().getName();
                        if ((entryName.endsWith(suffix) || entryName.equals(bare))
                                && entryName.indexOf('$') < 0) {
                            fqns.add(entryName
                                    .substring(0, entryName.length() - ".class".length())
                                    .replace('/', '.'));
                        }
                    }
                } catch (IOException e) {
                    // Unreadable jar — skip.
                }
            } else if (Files.isDirectory(path)) {
                try (Stream<Path> walk = Files.walk(path)) {
                    walk.filter(p -> p.getFileName().toString().equals(bare)
                                    && !p.getFileName().toString().contains("$"))
                            .forEach(p -> {
                                String relative = path.relativize(p).toString()
                                        .replace(java.io.File.separatorChar, '/');
                                fqns.add(relative
                                        .substring(0, relative.length() - ".class".length())
                                        .replace('/', '.'));
                            });
                } catch (IOException e) {
                    // Unreadable dir — skip.
                }
            }
        }
        return new ArrayList<>(fqns);
    }

    private static List<String> describe(ResolvedReferenceTypeDeclaration decl,
                                         String fqn, int maxMembers) {
        List<String> constructorLines = new ArrayList<>();
        for (ResolvedConstructorDeclaration ctor : decl.getConstructors()) {
            try {
                constructorLines.add("constructor " + ctor.getSignature());
            } catch (RuntimeException e) {
                // A parameter type off the classpath — skip this member only.
            }
        }
        constructorLines.sort(String::compareTo);

        List<String> methodLines = new ArrayList<>();
        for (ResolvedMethodDeclaration method : decl.getDeclaredMethods()) {
            try {
                String returnType;
                try {
                    returnType = method.getReturnType().describe();
                } catch (RuntimeException e) {
                    returnType = "?";
                }
                methodLines.add("method "
                        + (method.isStatic() ? "static " : "")
                        + returnType + " " + method.getSignature());
            } catch (RuntimeException e) {
                // Signature unresolvable — skip this member only.
            }
        }
        methodLines.sort(String::compareTo);

        List<String> lines = new ArrayList<>();
        lines.add("class " + fqn);
        int budget = Math.max(0, maxMembers);
        int total = constructorLines.size() + methodLines.size();
        int emitted = 0;
        for (String line : constructorLines) {
            if (emitted >= budget) {
                break;
            }
            lines.add(line);
            emitted++;
        }
        for (String line : methodLines) {
            if (emitted >= budget) {
                break;
            }
            lines.add(line);
            emitted++;
        }
        if (total > emitted) {
            lines.add("... and " + (total - emitted) + " more members");
        }
        return lines;
    }
}
