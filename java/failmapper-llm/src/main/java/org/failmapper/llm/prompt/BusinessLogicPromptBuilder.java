package org.failmapper.llm.prompt;

import java.util.List;
import java.util.Map;
import java.util.Objects;

import org.failmapper.core.util.PyFormat;

/**
 * P1 — byte-exact port of {@code FA_MCTS._create_business_logic_test_prompt}
 * ({@code fa_mcts.py:2793-2862}).
 *
 * <p>Rendering protocol (contract 3.6/P1):
 * <ul>
 *   <li>{@code {state.coverage:.2f}} at fa_mcts.py:2846 renders via {@link PyFormat#f2};</li>
 *   <li>the issue's detail dict is looked up in
 *       {@code state.business_logic_analysis['potential_bugs']} by (method, type)
 *       equality with first-match-wins, exactly like the Python loop;</li>
 *   <li>{@code semantic_signals.get(...)} defaults ('Not specified') and the
 *       {@code test_strategy} default ('all edge cases and logical conditions')
 *       are preserved;</li>
 *   <li>the dependency-context block (fa_mcts.py:2857-2860) is appended only when
 *       non-empty.</li>
 * </ul>
 */
public final class BusinessLogicPromptBuilder {

    private final String className;
    private final String sourceCode;
    private final String testPrompt;

    public BusinessLogicPromptBuilder(String className, String sourceCode, String testPrompt) {
        this.className = className;
        this.sourceCode = sourceCode;
        this.testPrompt = testPrompt;
    }

    /**
     * @param coverage      {@code state.coverage} (0-100 scale)
     * @param testCode      {@code state.test_code}
     * @param potentialBugs {@code state.business_logic_analysis['potential_bugs']};
     *                      pass null when the state has no business_logic_analysis
     * @param action        the business_logic_test action dict
     */
    public String build(double coverage,
                        String testCode,
                        List<Map<String, Object>> potentialBugs,
                        Map<String, Object> action) {
        Object issueType = action != null && action.containsKey("issue_type")
                ? action.get("issue_type") : "unknown";
        Object issueMethod = action != null && action.containsKey("method")
                ? action.get("method") : "";
        Object issueDescription = action != null && action.containsKey("description")
                ? action.get("description") : "";

        // fa_mcts.py:2809-2815 — first potential_bug matching both method and type.
        Map<String, Object> issueDetails = Map.of();
        if (potentialBugs != null) {
            for (Map<String, Object> issue : potentialBugs) {
                if (Objects.equals(issue.get("method"), issueMethod)
                        && Objects.equals(issue.get("type"), issueType)) {
                    issueDetails = issue;
                    break;
                }
            }
        }
        Map<String, Object> semanticSignals = subDict(issueDetails, "semantic_signals");

        StringBuilder prompt = new StringBuilder();
        prompt.append("""

                CRITICAL REQUIREMENTS:
                1. DO NOT use @Nested annotations or nested test classes - they cause coverage tracking issues
                2. Generate a COMPLETE test class with ALL methods intact - do not omit any code
                3. DO NOT use placeholders like "... existing code ..." or similar comments
                4. Your response MUST contain the ENTIRE test class that can compile without modifications

                STRICT ANTI-MOCKING REQUIREMENTS:
                - ABSOLUTELY NO use of any mocking frameworks (Mockito, EasyMock, PowerMock, etc.)
                - ABSOLUTELY NO @Mock, @MockBean, @InjectMocks, or any mock-related annotations
                - ABSOLUTELY NO imports from org.mockito.* or static imports from Mockito
                - ABSOLUTELY NO mock(), when(), verify(), or any mocking methods
                - Use ONLY real objects and direct instantiation for testing
                - Create real instances of dependencies instead of mocks

                You are an expert Java test engineer focusing on detecting BUSINESS LOGIC BUGS.
                You need to extend the following test class for \
                """)
                .append(className)
                .append("""
                 to find a specific business logic bug.

                BUSINESS LOGIC ISSUE DETAILS:
                - Method with potential issue: \
                """)
                .append(PromptPy.str(issueMethod))
                .append("\n- Issue type: ").append(PromptPy.str(issueType))
                .append("\n- Description: ").append(PromptPy.str(issueDescription))
                .append("\n- Expected behavior: ")
                .append(PromptPy.get(semanticSignals, "expected_behavior", "Not specified"))
                .append("\n- Actual behavior: ")
                .append(PromptPy.get(semanticSignals, "actual_behavior", "Not specified"))
                .append("\n- Specifically test: ")
                .append(PromptPy.get(issueDetails, "test_strategy", "all edge cases and logical conditions"))
                .append("\n\nCurrent test coverage: ")
                .append(PyFormat.f2(coverage))
                .append("""
                %

                Here is the existing test code:
                ```java
                """)
                .append(testCode)
                .append("""

                ```
                Here is the source code being tested:
                ```java
                """)
                .append(sourceCode)
                .append("\n```\n");

        // fa_mcts.py:2857-2860.
        String depCtx = PromptPy.extractDependencyContext(testPrompt);
        if (!depCtx.isEmpty()) {
            prompt.append("\nADDITIONAL CONTEXT (dependencies and rules):\n").append(depCtx).append('\n');
        }

        return prompt.toString();
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> subDict(Map<String, Object> dict, String key) {
        Object value = dict.get(key);
        if (value instanceof Map<?, ?> map) {
            return (Map<String, Object>) map;
        }
        return Map.of();
    }
}
