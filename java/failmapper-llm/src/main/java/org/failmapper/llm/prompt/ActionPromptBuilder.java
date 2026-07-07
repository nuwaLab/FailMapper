package org.failmapper.llm.prompt;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.failmapper.core.util.PyFormat;

/**
 * P2 — byte-exact port of {@code FA_MCTS.create_logic_aware_action_prompt}
 * ({@code fa_mcts.py:2864-3027}) plus its error-analysis helper
 * {@code _analyze_compilation_errors} ({@code fa_mcts.py:711-767}).
 *
 * <p>Rendering protocol (contract 3.6/P2, N7, I12):
 * <ul>
 *   <li>{@code {state.coverage:.2f}} at fa_mcts.py:2912 renders via {@link PyFormat#f2}
 *       (HALF_EVEN on the exact binary double, '.' separator);</li>
 *   <li>the base template, source block, optional dependency-context block
 *       (fa_mcts.py:2928-2930), the fix_compilation_errors branch (numbered
 *       {@code "{i}. ERROR: {error}"} lines over the first 10 analyzed errors,
 *       {@code "   SUGGESTED FIX: ..."} when a suggestion exists, and the
 *       {@code "... and N more errors"} overflow line), every action-specific branch
 *       (boundary_test / expression_test / exception_test / target_line /
 *       business_logic_test — any other type gets no specific block), and the final
 *       reminder block are reproduced byte-for-byte, including the trailing space
 *       after {@code "FINAL REMINDER: "};</li>
 *   <li>action values render with Python {@code str()} semantics
 *       ({@link PromptPy#str}); missing keys fall back to {@code 'N/A'}.</li>
 * </ul>
 *
 * <p>Byte fidelity is pinned by LayerPDifferentialTest against prompts rendered by
 * the genuine Python method.
 */
public final class ActionPromptBuilder {

    private final String className;
    private final String sourceCode;
    private final String testPrompt;

    /**
     * @param className  {@code self.class_name}
     * @param sourceCode {@code self.source_code}
     * @param testPrompt {@code self.test_prompt} (the on-disk prompt content; nullable) —
     *                   only its dependency-API section is injected, per
     *                   {@code _extract_dependency_context_from_prompt}
     */
    public ActionPromptBuilder(String className, String sourceCode, String testPrompt) {
        this.className = className;
        this.sourceCode = sourceCode;
        this.testPrompt = testPrompt;
    }

    /** One analyzed compilation error: the raw message plus an optional fix suggestion. */
    public record AnalyzedError(String error, String suggestion) {
    }

    /**
     * @param coverage {@code state.coverage} (0-100 scale)
     * @param testCode {@code state.test_code}
     * @param action   the action dict; {@code type} missing means {@code "fallback"}
     */
    public String build(double coverage, String testCode, Map<String, Object> action) {
        String actionType = PromptPy.get(action, "type", "fallback");

        StringBuilder prompt = new StringBuilder();

        // fa_mcts.py:2879-2918 — base template (leading newline from the f""" form).
        prompt.append("""

                CRITICAL REQUIREMENTS - READ CAREFULLY:
                1. DO NOT use @Nested annotations or nested test classes - they cause coverage tracking issues
                2. Generate a COMPLETE test class with ALL methods intact - do not omit any code
                3. ABSOLUTELY FORBIDDEN: placeholders like "... existing code ...", "// [Previous imports remain exactly the same]", "// ... existing code ...", "// All previous fields and methods remain exactly the same", or ANY similar comments that indicate omitted code
                4. Your response MUST contain the ENTIRE test class that can compile without modifications
                5. WRITE OUT EVERY SINGLE LINE OF CODE - no shortcuts, abbreviations, or omissions allowed
                6. If the existing test class has 100 lines, your response should contain at least 100 lines plus your additions
                7. Copy every import statement, every field declaration, every existing method in full
                8. NEVER use comments to indicate that code continues - write the actual code

                STRICT ANTI-MOCKING REQUIREMENTS:
                - ABSOLUTELY NO use of any mocking frameworks (Mockito, EasyMock, PowerMock, etc.)
                - ABSOLUTELY NO @Mock, @MockBean, @InjectMocks, or any mock-related annotations
                - ABSOLUTELY NO imports from org.mockito.* or static imports from Mockito
                - ABSOLUTELY NO mock(), when(), verify(), or any mocking methods
                - Use ONLY real objects and direct instantiation for testing
                - Create real instances of dependencies instead of mocks

                You are an expert Java test engineer focusing on detecting logical bugs.
                You need to extend the following test class for \
                """)
                .append(className)
                .append("""
                 to find bugs.

                Focus specifically on finding logical bugs related to:
                1. Boundary conditions
                2. Boolean logic errors
                3. Operator precedence issues
                4. Off-by-one errors
                5. Null handling problems
                6. Resource management issues
                7. Exception handling defects
                8. Data operation bugs

                Current test coverage: \
                """)
                .append(PyFormat.f2(coverage))
                .append("""
                %

                Here is the existing test code:
                ```java
                """)
                .append(testCode)
                .append("\n```\n");

        // fa_mcts.py:2921-2926 — source code context block.
        prompt.append("\nHere is the source code being tested:\n```java\n")
                .append(sourceCode)
                .append("\n```\n");

        // fa_mcts.py:2928-2930 — dependency API context, only when non-empty.
        String depCtx = PromptPy.extractDependencyContext(testPrompt);
        if (!depCtx.isEmpty()) {
            prompt.append("\nADDITIONAL CONTEXT (dependencies and rules):\n").append(depCtx).append('\n');
        }

        if ("fix_compilation_errors".equals(actionType)) {
            // fa_mcts.py:2934-2970.
            prompt.append("""


                    IMPORTANT: The current test code has COMPILATION ERRORS that MUST be fixed!

                    Compilation errors found:
                    """);
            Object errorsValue = action == null ? null : action.get("errors");
            if (errorsValue instanceof List<?> errors && !errors.isEmpty()) {
                List<AnalyzedError> analyzed = analyzeCompilationErrors(errors);
                int limit = Math.min(analyzed.size(), 10);
                for (int i = 0; i < limit; i++) {
                    AnalyzedError entry = analyzed.get(i);
                    prompt.append(i + 1).append(". ERROR: ").append(entry.error()).append('\n');
                    if (entry.suggestion() != null) {
                        prompt.append("   SUGGESTED FIX: ").append(entry.suggestion()).append('\n');
                    }
                }
                if (errors.size() > 10) {
                    prompt.append("... and ").append(errors.size() - 10).append(" more errors\n");
                }
            }
            prompt.append("""


                    Your task is to:
                    1. Fix ALL compilation errors in the test code above
                    2. Make sure the fixed code is syntactically correct and can compile
                    3. Preserve all existing test logic while fixing the errors
                    4. Add any missing imports if needed
                    5. Fix any incorrect method calls or type mismatches
                    6. Ensure proper Java syntax throughout
                    7. IMPORTANT: Write out the COMPLETE test class with all fixes applied

                    Common compilation errors to fix:
                    - Missing semicolons
                    - Unclosed brackets or parentheses
                    - Invalid comment syntax (e.g., incomplete comment blocks)
                    - Missing imports
                    - Type mismatches
                    - Undefined methods or variables

                    Remember: You MUST provide the COMPLETE test class, not just the fixes!
                    """);
        } else {
            // fa_mcts.py:2973-3014 — action-specific instruction blocks.
            switch (actionType) {
                case "boundary_test" -> prompt.append("""


                        Add new test methods to specifically test the boundary condition:
                        Condition: \
                        """)
                        .append(PromptPy.get(action, "condition", "N/A"))
                        .append("\nLine: ")
                        .append(PromptPy.get(action, "line", "N/A"))
                        .append("""


                        Focus on edge cases around this boundary (e.g., value-1, value, value+1).
                        """);
                case "expression_test" -> prompt.append("""


                        Add new test methods to test the logical expression:
                        Expression: \
                        """)
                        .append(PromptPy.get(action, "operation", "N/A"))
                        .append("\nLine: ")
                        .append(PromptPy.get(action, "line", "N/A"))
                        .append("""


                        Test all combinations of boolean values and edge cases.
                        """);
                case "exception_test" -> prompt.append("""


                        Add new test methods to test exception handling paths.
                        Focus on triggering exceptions and verifying proper handling.
                        """);
                case "target_line" -> prompt.append("""


                        Add new test methods to cover the uncovered line:
                        Line \
                        """)
                        .append(PromptPy.get(action, "line", "N/A"))
                        .append(": ")
                        .append(PromptPy.get(action, "content", "N/A"))
                        .append("""


                        Create test cases that will execute this specific line.
                        """);
                case "business_logic_test" -> prompt.append("""


                        Add new test methods to test the business logic issue:
                        Issue Type: \
                        """)
                        .append(PromptPy.get(action, "issue_type", "N/A"))
                        .append("\nMethod: ")
                        .append(PromptPy.get(action, "method", "N/A"))
                        .append("\nDescription: ")
                        .append(PromptPy.get(action, "description", "N/A"))
                        .append("""


                        Focus on testing the specific business logic concern identified.
                        """);
                default -> {
                    // Other action types (bug_pattern_test, general_exploration, fallback, ...)
                    // get no action-specific block in the Python baseline.
                }
            }
        }

        // fa_mcts.py:3017-3025 — final reminder ("FINAL REMINDER: " keeps its trailing space).
        prompt.append("""


                FINAL REMINDER:\s
                - Your response MUST be a COMPLETE, COMPILABLE Java test class
                - Include ALL imports, ALL fields, ALL existing methods, and your new additions
                - DO NOT use any comments like "// ... existing code ..." or "// [Previous test methods remain exactly as shown in the original code]"
                - The test class should be ready to compile and run immediately
                - Every single line of the original test code must be included in your response
                """);

        return prompt.toString();
    }

    /**
     * {@code _analyze_compilation_errors} ({@code fa_mcts.py:711-767}) — the exact
     * if/elif suggestion table, matched on the lowercased error message.
     */
    public static List<AnalyzedError> analyzeCompilationErrors(List<?> errors) {
        List<AnalyzedError> analyzed = new ArrayList<>();
        for (Object errorValue : errors) {
            String error = PromptPy.str(errorValue);
            String errorLower = PromptPy.lower(error);
            String suggestion = null;

            if (errorLower.contains("cannot find symbol")) {
                if (errorLower.contains("variable")) {
                    suggestion = "Declare the missing variable or check its spelling";
                } else if (errorLower.contains("method")) {
                    suggestion = "Check method name spelling and ensure it exists in the class being tested";
                } else if (errorLower.contains("class")) {
                    suggestion = "Add the missing import statement for this class";
                } else {
                    suggestion = "Check spelling and ensure the symbol is properly imported or declared";
                }
            } else if (errorLower.contains("incompatible types")) {
                suggestion = "Fix type mismatch - ensure the types match in assignment or method call";
            } else if (errorLower.contains("package") && errorLower.contains("does not exist")) {
                suggestion = "Add the correct import statement for this package";
            } else if (errorLower.contains("illegal start of expression")) {
                suggestion = "Check for missing parentheses, brackets, or semicolons before this line";
            } else if (errorLower.contains("reached end of file while parsing")) {
                suggestion = "Check for missing closing brackets } or parentheses )";
            } else if (errorLower.contains("unclosed comment")) {
                suggestion = "Close the comment block with */ or check for incomplete // comments";
            } else if (errorLower.contains("; expected")) {
                suggestion = "Add missing semicolon at the end of the statement";
            } else if (errorLower.contains("already defined")) {
                suggestion = "Remove duplicate variable or method declaration";
            } else if (errorLower.contains("unreachable statement")) {
                suggestion = "Remove or fix code after return/break/continue statements";
            } else if (errorLower.contains("missing return statement")) {
                suggestion = "Add a return statement with appropriate value";
            }

            analyzed.add(new AnalyzedError(error, suggestion));
        }
        return analyzed;
    }
}
