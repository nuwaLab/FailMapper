package org.failmapper.search;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * D11 — port of {@code classify_logical_bugs} ({@code test_state.py:313-384}).
 *
 * <p>The pattern table is an ORDERED list (contract O12): every pattern is tested and the
 * winner is the one with the strictly HIGHEST confidence; among equal confidences the
 * EARLIEST in list order wins because the update condition is a strict
 * {@code confidence > highest_confidence}. Never re-key this table by bug type.
 *
 * <p>Regex dialect (contract 3.3): Python {@code re.IGNORECASE} does full Unicode case
 * folding, so every pattern is compiled with {@code CASE_INSENSITIVE | UNICODE_CASE}
 * (X3); {@code re.search} semantics = {@code Matcher.find()}. UNIX_LINES is required
 * for dot parity: Python {@code .} (no DOTALL) excludes ONLY {@code \n}, while Java's
 * default {@code .} also excludes {@code \r}, {@code }, {@code  },
 * {@code  } — without the flag, a bug message like {@code "expected:\r... but was"}
 * classifies as logical in Python but general in Java (caught by layer-A differential
 * cases D11-036/D11-037). No pattern in this table uses {@code ^}/{@code $}, so
 * UNIX_LINES has no other effect.
 */
public final class LogicalBugClassifier {

    /** One row of the Python {@code logical_bug_patterns} list ({@code test_state.py:319-343}). */
    public record ClassifierRule(Pattern pattern, double confidence, String bugType) {
    }

    /** Result of classifying one bug message. */
    public record Classification(boolean isLogical, String bugType, double confidence) {
        static final Classification NOT_LOGICAL = new Classification(false, "unknown", 0.0);
    }

    private static final int FLAGS =
            Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE | Pattern.UNIX_LINES;

    private static ClassifierRule rule(String regex, double confidence, String bugType) {
        return new ClassifierRule(Pattern.compile(regex, FLAGS), confidence, bugType);
    }

    /**
     * {@code logical_bug_patterns} ({@code test_state.py:319-343}) — source order preserved
     * verbatim (O12).
     */
    public static final List<ClassifierRule> LOGICAL_BUG_PATTERNS = List.of(
            // assertion related patterns
            rule("expected:.*?but was", 0.7, "incorrect_value"),
            rule("expected.*?true.*?but was.*?false|expected.*?false.*?but was.*?true", 0.9, "incorrect_boolean"),
            rule("expected.*?empty|expected.*?null", 0.6, "empty_null_handling"),
            rule("IndexOutOfBoundsException|ArrayIndexOutOfBoundsException", 0.8, "index_error"),
            rule("NullPointerException", 0.6, "null_reference"),
            rule("ClassCastException", 0.7, "incorrect_type"),
            rule("UnsupportedOperationException", 0.8, "unsupported_operation"),
            rule("IllegalArgumentException", 0.7, "invalid_argument"),
            rule("IllegalStateException", 0.8, "invalid_state"),
            rule("ConcurrentModificationException", 0.9, "concurrency_issue"),
            rule("NumberFormatException", 0.7, "number_format"),
            // logical specific patterns
            rule("overflow|underflow", 0.8, "numeric_overflow"),
            rule("boundary|fence.?post|off.by.one", 0.9, "boundary_error"),
            rule("operator.*?precedence|condition.*?logic", 0.8, "operator_logic"),
            rule("race.*?condition|deadlock|concurrent", 0.9, "concurrency_issue"),
            rule("boolean.*?condition|logic.*?error", 0.8, "boolean_bug"),
            rule("infinite.*?loop", 0.9, "infinite_loop"),
            rule("resource.*?leak|not.*?closed", 0.8, "resource_leak"),
            rule("state.*?corruption|invalid.*?state", 0.8, "state_corruption"),
            rule("assertion.*?fail.*?logic", 0.7, "logical_assertion"));

    /**
     * Classify one bug message ({@code test_state.py:355-368}). The message is Python's
     * {@code str(bug.get("error","")) + " " + str(bug.get("description",""))}.
     */
    public Classification classifyMessage(String bugMessage) {
        String message = bugMessage == null ? "" : bugMessage;
        boolean isLogical = false;
        double highestConfidence = 0.0;
        String detectedBugType = "unknown";
        for (ClassifierRule rule : LOGICAL_BUG_PATTERNS) {
            if (rule.pattern().matcher(message).find()) {
                isLogical = true;
                double confidence = rule.confidence();
                if (confidence > highestConfidence) { // strict > — earliest equal-confidence rule wins (O12)
                    highestConfidence = confidence;
                    detectedBugType = rule.bugType();
                }
            }
        }
        return isLogical
                ? new Classification(true, detectedBugType, highestConfidence)
                : Classification.NOT_LOGICAL;
    }

    /**
     * Full port of {@code classify_logical_bugs} ({@code test_state.py:313-384}) over the
     * state's detected bugs. Mutates bug records in place and appends logical bugs to
     * {@code state.logicalBugs} — WITHOUT deduplication against entries already there
     * (see {@link FaTestState#logicalBugs} for why duplicates are load-bearing).
     *
     * <p>{@code added_methods} dedupes only within THIS call and by test-method name:
     * a second bug with the same method is skipped entirely (its {@code bug_category}
     * stays untouched, exactly like Python's {@code continue} at {@code test_state.py:353}).
     */
    public void classify(FaTestState state) {
        if (state.detectedBugs.isEmpty()) {
            return; // test_state.py:315-316
        }
        Set<String> addedMethods = new LinkedHashSet<>();
        for (DetectedBug bug : state.detectedBugs) {
            String testMethod = bug.testMethodOrEmpty();
            if (addedMethods.contains(testMethod)) {
                continue;
            }
            String bugMessage = bug.errorOrEmpty() + " " + bug.descriptionOrEmpty();
            Classification result = classifyMessage(bugMessage);
            if (result.isLogical()) {
                bug.bugCategory = "logical";
                bug.bugType = result.bugType();
                bug.logicConfidence = result.confidence();
                state.logicalBugs.add(bug);
                addedMethods.add(testMethod);
            } else {
                bug.bugCategory = "general";
            }
        }
        state.hasBugs = !state.logicalBugs.isEmpty(); // test_state.py:381
    }
}
