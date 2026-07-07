package org.failmapper.llm.prompt;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Parsers for the batch-verification response of
 * {@code filter_verified_bug_methods} ({@code verify_bug_with_llm.py:389-457}),
 * consuming the {@code Method {i+1}} numbering emitted by
 * {@link VerificationPromptBuilder#buildBatch}.
 *
 * <p>Preferred format is the explicit {@code REAL_BUGS: 2, 5, 8} trailer
 * (pattern {@code REAL_BUGS:\s*([\d,\s]+)} at :393, then {@code \d+} findall);
 * fallback 1 is the {@code (?:- Method|Method)\s+(\d+).*?(?:real bug|REAL BUG)}
 * list format (:412), fallback 2 the per-method Yes/No judgments (:426-430).
 * All indices convert 1-based -&gt; 0-based with bounds checks against the batch
 * size, then dedupe + sort ({@code sorted(list(set(...)))}).
 *
 * <p>Dialect fixes per contract X9: {@link Pattern#UNIX_LINES} on every pattern
 * whose {@code .} runs without DOTALL (Java's default {@code .} also excludes
 * {@code \r} and U+2028/U+2029, unlike CPython's which excludes only {@code \n}).
 */
public final class BatchVerdictParser {

    /** verify_bug_with_llm.py:393. */
    static final Pattern REAL_BUGS = Pattern.compile("REAL_BUGS:\\s*([\\d,\\s]+)", Pattern.UNIX_LINES);

    private static final Pattern DIGITS = Pattern.compile("\\d+");

    /** verify_bug_with_llm.py:412 — non-DOTALL {@code .*?}, hence UNIX_LINES (X9). */
    static final Pattern LIST_FORMAT = Pattern.compile(
            "(?:- Method|Method)\\s+(\\d+).*?(?:real bug|REAL BUG)",
            Pattern.CASE_INSENSITIVE | Pattern.UNIX_LINES);

    /** verify_bug_with_llm.py:426-430. */
    static final Pattern YES_NO_JUDGMENT = Pattern.compile(
            "Method\\s+(\\d+).*?(?::|is)\\s*(Yes|No|yes|no|TRUE|FALSE|True|False)",
            Pattern.CASE_INSENSITIVE | Pattern.DOTALL | Pattern.UNIX_LINES);

    /** verify_bug_with_llm.py:448-449. */
    static final Pattern CONFIDENCE_SCORE = Pattern.compile(
            "Method\\s+(\\d+).*?[Cc]onfidence:?\\s*(\\d+)(?:\\s*/\\s*10)?",
            Pattern.CASE_INSENSITIVE | Pattern.DOTALL | Pattern.UNIX_LINES);

    private BatchVerdictParser() {
    }

    /**
     * 0-based indices of the methods the LLM judged to be real bugs, sorted and
     * deduplicated; empty when nothing parseable was found.
     */
    public static List<Integer> realBugIndices(String result, int batchSize) {
        TreeSet<Integer> indices = new TreeSet<>();

        Matcher realBugs = REAL_BUGS.matcher(result);
        if (realBugs.find()) {
            Matcher numbers = DIGITS.matcher(realBugs.group(1).strip());
            while (numbers.find()) {
                addIfInRange(indices, numbers.group(), batchSize);
            }
            return new ArrayList<>(indices);
        }

        // Fallback strategy 1: "Method N ... real bug" list format.
        Matcher listFormat = LIST_FORMAT.matcher(result);
        while (listFormat.find()) {
            addIfInRange(indices, listFormat.group(1), batchSize);
        }
        if (!indices.isEmpty()) {
            return new ArrayList<>(indices);
        }

        // Fallback strategy 2: per-method Yes/No judgments; only Yes/True count.
        Matcher judgments = YES_NO_JUDGMENT.matcher(result);
        while (judgments.find()) {
            String judgment = judgments.group(2).toLowerCase(java.util.Locale.ROOT);
            if (judgment.equals("yes") || judgment.equals("true")) {
                addIfInRange(indices, judgments.group(1), batchSize);
            }
        }
        return new ArrayList<>(indices);
    }

    /**
     * Per-method confidence, normalized to 0-1 ({@code float(score) / 10.0},
     * verify_bug_with_llm.py:446-457); later matches for the same method overwrite
     * earlier ones, like the Python dict assignment.
     */
    public static Map<Integer, Double> confidenceScores(String result, int batchSize) {
        Map<Integer, Double> scores = new LinkedHashMap<>();
        Matcher matcher = CONFIDENCE_SCORE.matcher(result);
        while (matcher.find()) {
            int idx = Integer.parseInt(matcher.group(1)) - 1;
            if (idx >= 0 && idx < batchSize) {
                scores.put(idx, Double.parseDouble(matcher.group(2)) / 10.0);
            }
        }
        return scores;
    }

    private static void addIfInRange(TreeSet<Integer> indices, String oneBased, int batchSize) {
        int idx;
        try {
            idx = Integer.parseInt(oneBased) - 1;
        } catch (NumberFormatException e) {
            return; // mirrors the Python except ValueError: continue
        }
        if (idx >= 0 && idx < batchSize) {
            indices.add(idx);
        }
    }
}
