package org.failmapper.llm.prompt;

import java.util.List;

/**
 * P7 — byte-exact port of the merge ("special") prompt in
 * {@code EnhancedMCTSTestGenerator.select_final_best_test}
 * ({@code enhanced_mcts_test_generator.py:1832-1892}).
 *
 * <p>Byte-level details preserved:
 * <ul>
 *   <li>the second template line is FOUR SPACES (not empty) — the f-string source
 *       line at :1837 is {@code "    "};</li>
 *   <li>the Chinese method labels {@code 方法 {i}:} (enumerate starts at 1) must
 *       survive transport identically (non-ASCII, contract P7);</li>
 *   <li>at most FIVE methods are embedded ({@code verified_bug_methods[:5]});
 *       a method dict without a {@code code} key renders as the empty string
 *       ({@code method.get('code', '')});</li>
 *   <li>{@code "2. All import statements "} keeps its trailing space (both
 *       occurrences), the {@code "    Base test class:"} block keeps its 4-space
 *       indentation, and the prompt ends with {@code "\n    "} — newline plus four
 *       spaces, NO trailing newline (the closing {@code """} sits after an indented
 *       blank line).</li>
 * </ul>
 */
public final class MergePromptBuilder {

    private MergePromptBuilder() {
    }

    /**
     * @param baseTest    the base test class code
     * @param methodCodes {@code method.get('code', '')} of each verified bug method,
     *                    in order; only the first five are embedded
     */
    public static String build(String baseTest, List<String> methodCodes) {
        // enhanced_mcts_test_generator.py:1832-1834.
        StringBuilder methodsText = new StringBuilder();
        int limit = Math.min(methodCodes.size(), 5);
        for (int i = 0; i < limit; i++) {
            methodsText.append("\n方法 ").append(i + 1).append(":\n```java\n")
                    .append(methodCodes.get(i)).append("\n```\n");
        }

        return """
                As a Java testing expert, please merge the following verified bug test methods into the base test class.
                   \s
                CRITICAL: I need the ENTIRE test class including ALL original methods, not just the fixed parts.
                Your response must contain:
                1. All package declarations
                2. All import statements\s
                3. The complete class definition
                4. ALL existing test methods, not just the fixed ones
                5. All fields and setup methods

                ABSOLUTELY FORBIDDEN SHORTCUTS:
                - DO NOT use "// All existing test methods remain the same..."
                - DO NOT use "// [Previous test methods continue unchanged...]"
                - DO NOT use "// ... existing code ..."
                - DO NOT use "// [Previous imports remain exactly the same]"
                - DO NOT use ANY placeholders or comments indicating omitted code
                - You MUST include ALL actual code verbatim, even if it's unchanged
                - Shortcuts, abbreviations, or comments indicating omitted code are NOT acceptable
                - I need the complete verbatim code that can be directly saved to a file and compiled

                Format your entire response as a SINGLE complete Java file that I can save and run directly.
                    Base test class:
                    ```java
                    \
                """
                + baseTest
                + """

                    ```

                    Verified bug test methods to merge:
                    \
                """
                + methodsText
                + """


                    Please follow these rules:
                    1. Ensure all import statements are correctly merged
                    2. Add any missing field declarations and initializations
                    3. Rename methods to avoid conflicts
                    4. Add a comment before each bug test method: // VERIFIED BUG TEST
                    5. Ensure the final code can compile and run

                    CRITICAL ANTI-PLACEHOLDER REQUIREMENTS:
                I need the ENTIRE test class including ALL original methods, not just the fixed parts.
                Your response must contain:
                1. All package declarations
                2. All import statements\s
                3. The complete class definition
                4. ALL existing test methods, not just the fixed ones
                5. All fields and setup methods

                ABSOLUTELY FORBIDDEN SHORTCUTS:
                - DO NOT use "// All existing test methods remain the same..."
                - DO NOT use "// [Previous test methods continue unchanged...]"
                - DO NOT use "// ... existing code ..."
                - DO NOT use "// [Previous imports remain exactly the same]"
                - DO NOT use ANY placeholders or comments indicating omitted code
                - You MUST include ALL actual code verbatim, even if it's unchanged
                - Shortcuts, abbreviations, or comments indicating omitted code are NOT acceptable
                - I need the complete verbatim code that can be directly saved to a file and compiled

                Format your entire response as a SINGLE complete Java file that I can save and run directly.
                   \s""";
    }
}
