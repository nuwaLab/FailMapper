package org.failmapper.llm.prompt;

import java.util.List;

/**
 * P10 + P11 — byte-exact ports of the bug-verification prompts in
 * {@code verify_bug_with_llm.py}.
 *
 * <ul>
 *   <li>{@link #buildSingle}: the single-bug prompt at {@code verify_bug_with_llm.py:83-123}
 *       (embeds full source_code / test_method / bug_type / severity / error_message).
 *       The response is parsed by {@link VerdictParser}.</li>
 *   <li>{@link #buildBatch}: the {@code filter_verified_bug_methods} batch prompt at
 *       {@code verify_bug_with_llm.py:328-371}. The source is truncated to the first
 *       2500 CHARACTERS ({@code source_code[:2500]} — Python slices code points, so the
 *       truncation here counts code points, not UTF-16 units), and methods are numbered
 *       {@code Method {i+1}} — the numbering the REAL_BUGS response parser
 *       ({@link BatchVerdictParser}) depends on.</li>
 * </ul>
 */
public final class VerificationPromptBuilder {

    private VerificationPromptBuilder() {
    }

    /** {@code verify_bug_with_llm.py:83-123}. */
    public static String buildSingle(String className,
                                     String sourceCode,
                                     String testMethod,
                                     String bugType,
                                     String severity,
                                     String errorMessage) {
        return """

                You are a professional Java analysis expert specializing in identifying real bugs and false positives in unit tests.
                I will provide you with the source code of a Java class, a test method, and information about a potential bug found during testing.
                Please analyze whether this is a real bug in the source code or just a false positive caused by testing environment or code issues.

                Class name: \
                """
                + className
                + """


                Source code:
                ```java
                """
                + sourceCode
                + """

                ```

                Test method:
                ```java
                """
                + testMethod
                + """

                ```

                Issue found:
                - Bug type: \
                """
                + bugType
                + "\n- Severity: " + severity
                + "\n- Error message: " + errorMessage
                + """


                Please analyze whether the issue found by this test method is a real bug in the source code or a false positive due to test code issues or environment problems.

                Please provide your response in this specific format:
                1. VERDICT: "REAL BUG" or "FALSE POSITIVE"
                2. CONFIDENCE: A number between 1-10
                3. REASONING: Your detailed analysis and reasoning

                The analysis should particularly consider:
                1. Whether the error is caused by the test code itself (e.g., test environment configuration, test dependencies, etc.)
                2. Whether the issue actually exposes a defect in the class being tested
                3. Whether the test method is reasonable or if it's testing unreasonable/extreme edge cases
                4. Whether the test expectations match the intended behavior of the class

                For CONFIDENCE score, use these guidelines:
                - 9-10: Very confident in the assessment
                - 7-8: Confident but with some uncertainty
                - 5-6: Moderately confident
                - 1-4: Significant uncertainty
                """;
    }

    /**
     * One batch entry. {@code ofMethod} mirrors the dict-with-"code" branch
     * (bug types joined with {@code ", "}, {@code ", and N more"} overflow past 3,
     * {@code "Unknown issue"} when empty); {@code raw} mirrors the non-dict branch
     * ({@code verify_bug_with_llm.py:351-352}).
     */
    public record BatchMethod(String code, List<String> bugTypes, boolean isRaw) {

        public static BatchMethod ofMethod(String code, List<String> bugTypes) {
            return new BatchMethod(code, bugTypes == null ? List.of() : bugTypes, false);
        }

        public static BatchMethod raw(String text) {
            return new BatchMethod(text, List.of(), true);
        }
    }

    /** {@code verify_bug_with_llm.py:328-371}. */
    public static String buildBatch(String packageName,
                                    String className,
                                    String sourceCode,
                                    List<BatchMethod> batch) {
        StringBuilder prompt = new StringBuilder();
        prompt.append("""
                You are a Java testing expert. You need to analyze the following test methods to determine if they likely identify real bugs in the code under test.

                Source class: \
                """)
                .append(packageName).append('.').append(className)
                .append("""


                Source code snippet:
                ```java
                """)
                .append(truncateCodePoints(sourceCode, 2500))
                .append("""

                ```

                Potential bug-finding test methods:
                """);

        for (int i = 0; i < batch.size(); i++) {
            BatchMethod method = batch.get(i);
            if (method.isRaw()) {
                prompt.append("\nMethod ").append(i + 1).append(":\n```java\n")
                        .append(method.code()).append("\n```\n\n");
            } else {
                String bugInfo;
                if (!method.bugTypes().isEmpty()) {
                    bugInfo = String.join(", ", method.bugTypes());
                    if (method.bugTypes().size() > 3) {
                        bugInfo += ", and " + (method.bugTypes().size() - 3) + " more";
                    }
                } else {
                    bugInfo = "Unknown issue";
                }
                prompt.append("\nMethod ").append(i + 1).append(":\n```java\n")
                        .append(method.code()).append("\n```\n\nDetected issues: ")
                        .append(bugInfo).append('\n');
            }
        }

        prompt.append("""

                For each method, determine if it's testing a real bug or potential issue in the code, rather than just a feature or expected behavior.
                Criteria for a real bug:
                - The test identifies an actual flaw, exception, or unexpected behavior
                - The behavior being tested violates the expected contract or reasonable assumptions for the class
                - It's not just testing a documented limitation or expected boundary condition

                For each method, provide:
                1. Is it likely detecting a real bug/issue? Please answer with a Yes/No
                2. A brief explanation of your reasoning
                3. A "confidence" score from 1-10 on whether this is a genuine bug

                Then provide a final list of real bugs in this exact format:
                REAL_BUGS: [comma-separated method numbers]

                For example, if methods 2, 5, and 8 are real bugs, end your response with:
                REAL_BUGS: 2, 5, 8
                """);

        return prompt.toString();
    }

    /** Python {@code s[:n]} — first {@code n} code points (not UTF-16 units). */
    static String truncateCodePoints(String s, int n) {
        if (s.codePointCount(0, s.length()) <= n) {
            return s;
        }
        return s.substring(0, s.offsetByCodePoints(0, n));
    }
}
