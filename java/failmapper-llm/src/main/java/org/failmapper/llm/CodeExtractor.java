package org.failmapper.llm;

import org.failmapper.analysis.SourceAnalyzer;

import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Extracts a Java compilation unit from an LLM reply: prefers the first
 * ```java fenced block, falls back to any fenced block, then to the whole
 * text. The result is accepted only if JavaParser parses it.
 */
public final class CodeExtractor {

    private static final Pattern JAVA_FENCE = Pattern.compile("```java\\s*\\n(.*?)```", Pattern.DOTALL);
    private static final Pattern ANY_FENCE = Pattern.compile("```\\s*\\n(.*?)```", Pattern.DOTALL);

    private final SourceAnalyzer analyzer = new SourceAnalyzer();

    public Optional<String> extract(String llmReply) {
        if (llmReply == null || llmReply.isBlank()) {
            return Optional.empty();
        }
        for (Pattern fence : new Pattern[] {JAVA_FENCE, ANY_FENCE}) {
            Matcher matcher = fence.matcher(llmReply);
            while (matcher.find()) {
                String candidate = matcher.group(1);
                if (analyzer.parse(candidate).isPresent()) {
                    return Optional.of(candidate);
                }
            }
        }
        return analyzer.parse(llmReply).isPresent() ? Optional.of(llmReply) : Optional.empty();
    }
}
