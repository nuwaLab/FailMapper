package org.failmapper.llm;

import org.failmapper.analysis.SourceAnalyzer;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Extracts a Java compilation unit from an LLM reply.
 *
 * <p><b>Strict pass first</b> (the original M4 behavior, unchanged): the first
 * ```java fenced block that JavaParser parses wins, then any fenced block, then the
 * whole reply — parse-validated candidates are returned as-is.
 *
 * <p><b>Lenient fallback chain</b> (M5 §3.4 fix): when nothing parses, degrade
 * through the Python baseline's {@code extract_java_code} chain
 * ({@code feedback.py:417-500}) instead of returning empty:
 * <ol>
 *   <li>L1 ({@code feedback.py:420-429}) — first ```java block that <i>starts</i>
 *       with a class/interface/enum declaration and looks complete
 *       (contains {@code "class "} and {@code "{"}, ends with {@code "}"});</li>
 *   <li>L2 ({@code feedback.py:432-452}) — all ```java blocks: first complete one,
 *       else the single block, else the {@code "\n\n"}-joined blocks when the join is
 *       complete, else the longest block;</li>
 *   <li>L3 ({@code feedback.py:455-465}) — any fenced block: first complete one,
 *       else the longest;</li>
 *   <li>L4 ({@code feedback.py:468-494}) — no usable fence: salvage an <i>unclosed</i>
 *       (truncated) fence body, else brace-balance-scan from the first class
 *       declaration; either way a truncated reply gets its missing closing braces
 *       appended, within reason ({@value #MAX_APPENDED_BRACES} max);</li>
 *   <li>L5 ({@code feedback.py:496-497}) — last resort: the whole reply.</li>
 * </ol>
 *
 * <p>L4/L5 are gated on the reply looking Java-like at all
 * ({@code "public class"} or {@code "import "} present — the {@code feedback.py:468}
 * gate). A pure-prose reply therefore yields {@link Optional#empty()} so callers can
 * re-sample (I17); this is the one deliberate deviation from Python, whose final
 * {@code feedback.py:500} returns <i>any</i> text unconditionally.
 *
 * <p>Philosophy (contract §4 I17, M5 §3.4): for any reply containing something
 * class-like, return SOMETHING compilable-ish rather than nothing — the downstream
 * compile-diagnostics fix loop (M4/I16) repairs the rest.
 */
public final class CodeExtractor {

    // --- strict pass (original M4 patterns, parse-validated) ---
    private static final Pattern JAVA_FENCE = Pattern.compile("```java\\s*\\n(.*?)```", Pattern.DOTALL);
    private static final Pattern ANY_FENCE = Pattern.compile("```\\s*\\n(.*?)```", Pattern.DOTALL);

    // --- lenient fallback chain (ports of the feedback.py:417-500 regexes) ---
    /** feedback.py:420 {@code class_pattern}: fence content starting at a type declaration. */
    private static final Pattern CLASS_FENCE = Pattern.compile(
            "```java\\s*((?:public\\s+)?(?:class|interface|enum)\\s+\\w+[\\s\\S]*?)\\s*```");
    /** feedback.py:432 {@code java_pattern}: any ```java block, no newline required. */
    private static final Pattern JAVA_FENCE_LENIENT =
            Pattern.compile("```java\\s*(.*?)\\s*```", Pattern.DOTALL);
    /** feedback.py:455 {@code code_pattern}: any fenced block (Python quirks included). */
    private static final Pattern CODE_FENCE_LENIENT =
            Pattern.compile("```\\s*(.*?)\\s*```", Pattern.DOTALL);

    /** Salvage cap: a truncation deeper than this is returned unbalanced (Python behavior). */
    private static final int MAX_APPENDED_BRACES = 12;

    private final SourceAnalyzer analyzer = new SourceAnalyzer();

    public Optional<String> extract(String llmReply) {
        if (llmReply == null || llmReply.isBlank()) {
            return Optional.empty();
        }
        // Strict pass: parse-validated fences win, exactly as before the M5 fix.
        for (Pattern fence : new Pattern[] {JAVA_FENCE, ANY_FENCE}) {
            Matcher matcher = fence.matcher(llmReply);
            while (matcher.find()) {
                String candidate = matcher.group(1);
                if (analyzer.parse(candidate).isPresent()) {
                    return Optional.of(candidate);
                }
            }
        }
        if (analyzer.parse(llmReply).isPresent()) {
            return Optional.of(llmReply);
        }
        return fallbackChain(llmReply);
    }

    // ------------------------------------------------------------------
    // Lenient fallback chain — extract_java_code (feedback.py:417-500)
    // ------------------------------------------------------------------

    private static Optional<String> fallbackChain(String text) {
        // L1 (feedback.py:420-429): first ```java block starting at a type declaration.
        Matcher classMatch = CLASS_FENCE.matcher(text);
        if (classMatch.find()) {
            String extracted = classMatch.group(1);
            if (looksComplete(extracted)) {
                return nonBlank(extracted);
            }
        }

        // L2 (feedback.py:432-452): all ```java blocks.
        List<String> javaBlocks = allMatches(JAVA_FENCE_LENIENT, text);
        if (!javaBlocks.isEmpty()) {
            for (String block : javaBlocks) {
                if (looksComplete(block)) {
                    return nonBlank(block);
                }
            }
            if (javaBlocks.size() == 1) {
                return nonBlank(javaBlocks.get(0));
            }
            String combined = String.join("\n\n", javaBlocks);
            if (looksComplete(combined)) {
                return nonBlank(combined);
            }
            return nonBlank(longest(javaBlocks));
        }

        // L3 (feedback.py:455-465): any fenced block.
        List<String> anyBlocks = allMatches(CODE_FENCE_LENIENT, text);
        if (!anyBlocks.isEmpty()) {
            for (String block : anyBlocks) {
                if (looksComplete(block)) {
                    return nonBlank(block);
                }
            }
            return nonBlank(longest(anyBlocks));
        }

        // L4/L5 gate (feedback.py:468): must look Java-like at all. Pure prose ends
        // here with empty — the deliberate deviation from feedback.py:500 (see class
        // doc); the I17 retry re-samples instead.
        if (!text.contains("public class") && !text.contains("import ")) {
            return Optional.empty();
        }

        // L4a: an unclosed (truncated) fence — keep everything after the fence tag so
        // package/imports survive, then append the missing closing braces.
        Optional<String> unclosedFence = salvageUnclosedFence(text);
        if (unclosedFence.isPresent()) {
            return unclosedFence;
        }

        // L4b (feedback.py:470-494): brace-balance scan from the first class declaration.
        Optional<String> braceScan = salvageFromClassDeclaration(text);
        if (braceScan.isPresent()) {
            return braceScan;
        }

        // L5 (feedback.py:496-497): the whole reply; downstream compile diagnostics
        // deal with it.
        return nonBlank(text);
    }

    /** feedback.py:426/438 completeness heuristic. */
    private static boolean looksComplete(String code) {
        return code.contains("class ") && code.contains("{") && code.strip().endsWith("}");
    }

    /**
     * Truncated-fence salvage: a final ``` fence that never closes. Returns the fence
     * body (imports included) with missing closing braces appended, if it holds a type
     * declaration.
     */
    private static Optional<String> salvageUnclosedFence(String text) {
        int fenceIdx = text.lastIndexOf("```");
        if (fenceIdx < 0) {
            return Optional.empty();
        }
        int contentStart = text.indexOf('\n', fenceIdx);
        if (contentStart < 0 || text.indexOf("```", contentStart) >= 0) {
            return Optional.empty(); // no body, or the fence is actually closed
        }
        String candidate = text.substring(contentStart + 1).strip();
        if (candidate.isEmpty()
                || !(candidate.contains("class ") || candidate.contains("interface ")
                        || candidate.contains("enum ") || candidate.contains("record "))) {
            return Optional.empty();
        }
        return Optional.of(appendMissingBraces(candidate));
    }

    /**
     * feedback.py:470-494: from {@code "public class"} (else {@code "class "}), collect
     * lines until the braces balance. A reply truncated before balance gets the missing
     * closing braces appended (the salvage extension; Python returned it unbalanced).
     */
    private static Optional<String> salvageFromClassDeclaration(String text) {
        int classStart = text.indexOf("public class");
        if (classStart < 0) {
            classStart = text.indexOf("class ");
        }
        if (classStart < 0) {
            return Optional.empty();
        }
        List<String> collected = new ArrayList<>();
        int openBraces = 0;
        boolean inClass = false;
        for (String line : text.substring(classStart).split("\n", -1)) {
            collected.add(line);
            if (line.indexOf('{') >= 0) {
                inClass = true;
                openBraces += count(line, '{');
            }
            if (line.indexOf('}') >= 0) {
                openBraces -= count(line, '}');
            }
            if (inClass && openBraces == 0) {
                break;
            }
        }
        String result = String.join("\n", collected);
        if (inClass && openBraces > 0) {
            result = appendMissingBraces(result); // truncated reply
        }
        return nonBlank(result);
    }

    /** Appends the missing {@code '}'} count, when positive and within reason. */
    private static String appendMissingBraces(String code) {
        int missing = count(code, '{') - count(code, '}');
        if (missing <= 0 || missing > MAX_APPENDED_BRACES) {
            return code;
        }
        return code + "\n}".repeat(missing);
    }

    private static int count(String s, char c) {
        return (int) s.chars().filter(ch -> ch == c).count();
    }

    private static List<String> allMatches(Pattern pattern, String text) {
        List<String> result = new ArrayList<>();
        Matcher matcher = pattern.matcher(text);
        while (matcher.find()) {
            result.add(matcher.group(1));
        }
        return result;
    }

    /** Python {@code max(matches, key=len)}: first maximal element wins ties. */
    private static String longest(List<String> blocks) {
        return blocks.stream().max(Comparator.comparingInt(String::length)).orElse("");
    }

    private static Optional<String> nonBlank(String s) {
        return s == null || s.isBlank() ? Optional.empty() : Optional.of(s);
    }
}
