package org.failmapper.llm.prompt;

import java.util.List;

/**
 * P3 — port of the {@code fix_integrated_test_with_llm} prompt
 * ({@code fa_mcts.py:4218-4268}) with ONE registered deviation.
 *
 * <p><b>REGISTERED DEVIATION I7</b> (contract section 4, improvements register;
 * also P14/S-series "list repr" hazard): at {@code fa_mcts.py:4257} the Python
 * f-string interpolates the {@code error_message} LIST directly, so the prompt
 * contains a Python list repr like {@code ['err1', 'err2']}. Per register entry I7
 * that rendering is normalized here to a NUMBERED ERROR LIST:
 *
 * <pre>
 *   1. err1
 *   2. err2
 * </pre>
 *
 * <p>Everything outside that one segment is byte-identical to the Python baseline,
 * including the trailing space after {@code "2. all import statements "} and after
 * {@code "{self.source_code} "} (fa_mcts.py:4247), and the falsy-fallback text
 * {@code "compilation failed, please check possible issues"} used when the error
 * list is None/empty. LayerPDifferentialTest byte-compares the whole prompt after
 * applying the registered I7 transform to the Python-rendered oracle segment.
 */
public final class FixPromptBuilder {

    private final String className;
    private final String packageName;
    private final String sourceCode;

    public FixPromptBuilder(String className, String packageName, String sourceCode) {
        this.className = className;
        this.packageName = packageName;
        this.sourceCode = sourceCode;
    }

    /**
     * @param testCode      the integrated test code to repair
     * @param errorMessages the compilation error list; null or empty renders the
     *                      Python falsy-fallback string
     */
    public String build(String testCode, List<String> errorMessages) {
        return """
                please help me fix the compilation issues in the following JUnit test code. your task is to identify issues such as undeclared variables, missing imports, method conflicts, and provide complete repaired code. i need the complete code, not just the repaired parts.

                CRITICAL ANTI-PLACEHOLDER REQUIREMENTS:
                i need the complete test class, including all original methods, not just the repaired parts.
                your answer must include:
                1. all package declarations
                2. all import statements\s
                3. complete class definition
                4. all existing test methods, not just the repaired ones
                5. all fields and setup methods

                ABSOLUTELY FORBIDDEN SHORTCUTS:
                - do not use placeholders like "// all existing test methods remain unchanged..."
                - do not use "// [previous test methods remain unchanged...]"
                - do not use "// ... existing code ..."
                - do not use "// [Previous imports remain exactly the same]"
                - do not use ANY comments that indicate omitted code
                - you must include the original code of all actual code, even if it is not changed
                - do not accept shortcuts, abbreviations, or comments indicating that code is omitted
                - i need the complete code that can be saved to a file and compiled directly

                format your entire answer as a complete, compilable Java file that can be saved and run directly.

                class information:
                - class name: \
                """
                + className
                + "\n- package name: " + packageName
                + """


                source code:
                ```java
                """
                + sourceCode
                + """
                \s
                ```

                test code:
                ```java
                """
                + testCode
                + """

                ```

                compilation errors:
                ```
                """
                + renderErrors(errorMessages)
                + """

                ```

                please pay special attention to the following points:
                1. check variable declarations and initializations - variables may need to be redeclared in different test methods
                2. ensure that integrated test methods do not have variable name conflicts
                3. ensure that all necessary imports exist
                4. fix method signatures or parameter issues
                5. ensure that variable scope is correct within methods

                only fix necessary compilation issues, while preserving the original functionality of the test methods.
                """;
    }

    /**
     * REGISTERED I7 RENDERING: numbered error list instead of Python's
     * {@code ['err1', 'err2']} list repr. Falsy input (null/empty) keeps the exact
     * Python fallback string from {@code fa_mcts.py:4257}.
     */
    static String renderErrors(List<String> errorMessages) {
        if (errorMessages == null || errorMessages.isEmpty()) {
            return "compilation failed, please check possible issues";
        }
        StringBuilder rendered = new StringBuilder();
        for (int i = 0; i < errorMessages.size(); i++) {
            if (i > 0) {
                rendered.append('\n');
            }
            rendered.append(i + 1).append(". ").append(errorMessages.get(i));
        }
        return rendered.toString();
    }
}
