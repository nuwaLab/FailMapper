package org.failmapper.analysis;

import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.body.TypeDeclaration;
import com.github.javaparser.javadoc.Javadoc;
import com.github.javaparser.javadoc.JavadocBlockTag;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * I18 (contract §4, registered improvement) — extracts the DOCUMENTED CONTRACT of a
 * class under test: its class-level Javadoc and a {@code methodName -> Javadoc text}
 * map, both as plain text (comment markers stripped, prose plus
 * {@code @param}/{@code @return}/{@code @throws} lines kept).
 *
 * <p>Motivation (M5_BENCHMARK §3.1): 3 of the 4 clear clean-corpus false positives had
 * their refutation sitting in the target's OWN Javadoc, which the verification prompt
 * never carried. This extractor feeds the spec-grounded verification appendix
 * ({@code VerificationPromptBuilder.specGroundedSection}).
 *
 * <p>Semantics:
 * <ul>
 *   <li>the target type is located by fully-qualified name (nested types accepted with
 *       either {@code .} or {@code $} separators), falling back to a simple-name match
 *       (covers default packages and pre-resolved primary types);</li>
 *   <li>overloads are MERGED: their Javadoc texts are joined with a blank line, in
 *       source order;</li>
 *   <li>absent documentation yields an empty {@link Optional} / no map entry — never
 *       empty strings;</li>
 *   <li>the method map preserves source (insertion) order — it is rendered into a
 *       prompt, so ordering must be deterministic (contract O-series).</li>
 * </ul>
 */
public final class JavadocExtractor {

    /**
     * The documented contract of one type.
     *
     * @param classDoc   class-level Javadoc as plain text, empty when absent
     * @param methodDocs {@code methodName -> plain-text Javadoc}, overloads merged with
     *                   a blank line; insertion-ordered; undocumented methods absent
     */
    public record ClassJavadocs(Optional<String> classDoc, Map<String, String> methodDocs) {

        public static ClassJavadocs empty() {
            return new ClassJavadocs(Optional.empty(), Map.of());
        }
    }

    /**
     * Parses {@code source} and extracts the Javadocs of {@code classFqn}; empty result
     * when the source does not parse or the type is not found.
     */
    public ClassJavadocs extract(String source, String classFqn) {
        return new SourceAnalyzer().parse(source)
                .map(cu -> extract(cu, classFqn))
                .orElse(ClassJavadocs.empty());
    }

    /** Extracts the Javadocs of {@code classFqn} from an already-parsed unit. */
    public ClassJavadocs extract(CompilationUnit cu, String classFqn) {
        TypeDeclaration<?> type = findType(cu, classFqn);
        if (type == null) {
            return ClassJavadocs.empty();
        }

        Optional<String> classDoc = type.getJavadocComment()
                .map(c -> toPlainText(c.parse()))
                .filter(text -> !text.isEmpty());

        LinkedHashMap<String, String> methodDocs = new LinkedHashMap<>();
        for (MethodDeclaration method : type.getMethods()) {
            method.getJavadocComment()
                    .map(c -> toPlainText(c.parse()))
                    .filter(text -> !text.isEmpty())
                    .ifPresent(text -> methodDocs.merge(
                            method.getNameAsString(), text,
                            (existing, added) -> existing + "\n\n" + added));
        }

        return new ClassJavadocs(classDoc, Collections.unmodifiableMap(methodDocs));
    }

    // ------------------------------------------------------------------

    private static TypeDeclaration<?> findType(CompilationUnit cu, String classFqn) {
        if (classFqn == null || classFqn.isBlank()) {
            return null;
        }
        String normalizedFqn = classFqn.replace('$', '.');
        List<TypeDeclaration<?>> types = new ArrayList<>();
        cu.findAll(TypeDeclaration.class).forEach(t -> types.add((TypeDeclaration<?>) t));

        for (TypeDeclaration<?> type : types) {
            if (type.getFullyQualifiedName().map(normalizedFqn::equals).orElse(false)) {
                return type;
            }
        }
        String simpleName = normalizedFqn.substring(normalizedFqn.lastIndexOf('.') + 1);
        for (TypeDeclaration<?> type : types) {
            if (type.getNameAsString().equals(simpleName)) {
                return type;
            }
        }
        return null;
    }

    /**
     * Renders a parsed Javadoc as plain text: the description prose followed by one
     * line per block tag ({@code @param name ...}, {@code @return ...},
     * {@code @throws Type ...}); markers ({@code /** * *&#47;}) are already stripped
     * by JavaParser's Javadoc parser. Line endings normalized to {@code \n}.
     */
    static String toPlainText(Javadoc javadoc) {
        StringBuilder text = new StringBuilder(javadoc.getDescription().toText().strip());
        for (JavadocBlockTag tag : javadoc.getBlockTags()) {
            StringBuilder line = new StringBuilder("@").append(tag.getTagName());
            tag.getName().ifPresent(name -> line.append(' ').append(name));
            String content = tag.getContent().toText().strip();
            if (!content.isEmpty()) {
                line.append(' ').append(content);
            }
            if (text.length() > 0) {
                text.append('\n');
            }
            text.append(line);
        }
        return text.toString().replace("\r\n", "\n").strip();
    }
}
