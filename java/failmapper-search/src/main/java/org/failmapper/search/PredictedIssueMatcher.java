package org.failmapper.search;

import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * D13 — port of {@code _bug_matches_predicted_issue} ({@code fa_mcts.py:3363-3404}).
 *
 * <p>Match rule: the SIMPLIFIED test-method name (leading {@code "test"} stripped) must
 * overlap the issue's method name (substring containment in EITHER direction,
 * case-insensitive), AND at least {@code min(2, len(keywords) // 2)} of the issue
 * description's keywords must appear in the bug's error+description text.
 *
 * <p>Keywords are {@code \b\w{4,}\b} matches over the LOWERCASED issue description
 * ({@code fa_mcts.py:3395}), deduplicated as a set — compiled with
 * UNICODE_CHARACTER_CLASS because Python's {@code \w}/{@code \b} are Unicode-aware
 * (contract X2). The keyword count uses set SIZE only, so Python's arbitrary set
 * iteration order is irrelevant; a LinkedHashSet keeps this deterministic anyway.
 *
 * <p>On a match, the reward layer accrues {@code 1.0 * issue.get('confidence', 0.5)}
 * ({@code fa_mcts.py:3225}, wired via
 * {@link RewardInputs#matchedBusinessLogicIssueConfidences()}).
 */
public final class PredictedIssueMatcher {

    /** {@code fa_mcts.py:3395} — {@code r'\b\w{4,}\b'}. */
    private static final Pattern KEYWORD_PATTERN =
            Pattern.compile("\\b\\w{4,}\\b", Pattern.UNICODE_CHARACTER_CLASS);

    /**
     * Port of the predicate. Nulls model absent dict keys (Python
     * {@code bug.get("test_method", "")} etc.).
     */
    public boolean matches(DetectedBug bug, BusinessLogicIssue issue) {
        // Method-name overlap (fa_mcts.py:3374-3388).
        String bugMethod = bug.testMethodOrEmpty();
        String issueMethod = issue.method() == null ? "" : issue.method();
        if (bugMethod.isEmpty() || issueMethod.isEmpty()) {
            return false;
        }

        String simplifiedBugMethod = bugMethod.startsWith("test") ? bugMethod.substring(4) : bugMethod;

        String issueMethodLower = issueMethod.toLowerCase(Locale.ROOT);
        String simplifiedLower = simplifiedBugMethod.toLowerCase(Locale.ROOT);
        // Python `a in b or b in a`; note "" is contained in everything, exactly like
        // Python's `"" in s` == True (a bug method named exactly "test" always overlaps).
        if (!simplifiedLower.contains(issueMethodLower) && !issueMethodLower.contains(simplifiedLower)) {
            return false;
        }

        // Keyword evidence (fa_mcts.py:3390-3404).
        String bugError = bug.errorOrEmpty() + " " + bug.descriptionOrEmpty();
        String issueDesc = issue.description() == null ? "" : issue.description();

        Set<String> issueKeywords = new LinkedHashSet<>();
        Matcher m = KEYWORD_PATTERN.matcher(issueDesc.toLowerCase(Locale.ROOT));
        while (m.find()) {
            issueKeywords.add(m.group());
        }
        if (issueKeywords.isEmpty()) {
            return false;
        }

        String errorText = bugError.toLowerCase(Locale.ROOT);
        int matches = 0;
        for (String kw : issueKeywords) {
            if (errorText.contains(kw)) {
                matches += 1;
            }
        }

        // N12: Python floor division `len // 2` == Java int division for non-negatives.
        return matches >= Math.min(2, issueKeywords.size() / 2);
    }
}
