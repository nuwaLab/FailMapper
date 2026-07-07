package org.failmapper.llm.prompt;

import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Python string semantics shared by the prompt builders (contract section 3.6).
 *
 * <p>These helpers reproduce the exact text CPython f-strings produce for the
 * value shapes that actually occur in the baseline's action/issue dicts:
 * strings, ints, booleans and {@code None}. They are NOT a general
 * {@code repr()}/{@code str()} port — anything outside those shapes is a
 * register-worthy finding for the port contract, not something to guess at.
 */
final class PromptPy {

    private PromptPy() {
    }

    /**
     * {@code str(value)} as an f-string renders it, for the value shapes used by
     * the search loop's action dicts (strings, ints, booleans, None).
     */
    static String str(Object value) {
        if (value == null) {
            return "None"; // f"{None}" -> "None"
        }
        if (value instanceof Boolean b) {
            return b ? "True" : "False";
        }
        if (value instanceof Double || value instanceof Float) {
            double d = ((Number) value).doubleValue();
            if (d == Math.floor(d) && !Double.isInfinite(d) && Math.abs(d) < 1e16) {
                return (long) d + ".0"; // Python str(3.0) == "3.0"
            }
            return Double.toString(d);
        }
        return value.toString(); // String, Integer, Long
    }

    /** {@code action.get(key, dflt)} rendered through {@link #str}. */
    static String get(Map<String, Object> dict, String key, String dflt) {
        if (dict == null || !dict.containsKey(key)) {
            return dflt;
        }
        return str(dict.get(key));
    }

    /** Python truthiness for the values these templates branch on. */
    static boolean truthy(Object value) {
        if (value == null) {
            return false;
        }
        if (value instanceof Boolean b) {
            return b;
        }
        if (value instanceof String s) {
            return !s.isEmpty();
        }
        if (value instanceof List<?> l) {
            return !l.isEmpty();
        }
        if (value instanceof Map<?, ?> m) {
            return !m.isEmpty();
        }
        if (value instanceof Number n) {
            return n.doubleValue() != 0.0;
        }
        return true;
    }

    /** Python {@code str.lower()} — locale-pinned for the ASCII compiler messages it sees. */
    static String lower(String s) {
        return s.toLowerCase(Locale.ROOT);
    }

    /**
     * {@code fa_mcts.py:769-788 _extract_dependency_context_from_prompt} — extracts the
     * "4. DEPENDENCY API REFERENCES" section (and, when present, everything from the
     * "5. GUIDELINES" separator on) out of the on-disk test prompt. Returns "" when the
     * prompt is null/empty or has no dependency section, exactly like the Python.
     */
    static String extractDependencyContext(String testPrompt) {
        String content = testPrompt == null ? "" : testPrompt;
        if (content.isEmpty()) {
            return "";
        }
        int depStart = content.indexOf("4. DEPENDENCY API REFERENCES");
        if (depStart == -1) {
            return "";
        }
        String guideMarker = "\n-----------\n5. GUIDELINES";
        int guidePos = content.indexOf(guideMarker, depStart);
        if (guidePos != -1) {
            String depSection = content.substring(depStart, guidePos).strip();
            String guideSection = content.substring(guidePos).strip();
            return depSection + "\n\n" + guideSection;
        }
        return content.substring(depStart).strip();
    }
}
