package org.failmapper.llm.prompt;

/**
 * P12 — byte-exact port of the prompt wrapper in {@code feedback.generate_initial_test}
 * ({@code feedback.py:520-554}).
 *
 * <p>The Python function reads the on-disk prompt file verbatim and wraps it in fixed
 * instruction blocks: a leading newline, the file content, THREE blank lines
 * (four consecutive newlines), the instruction text, and a trailing newline. This
 * builder takes the already-read file content ({@code prompt_content}) so file I/O
 * stays with the caller; the whitespace/newline layout is byte-identical.
 */
public final class InitialTestPromptBuilder {

    private InitialTestPromptBuilder() {
    }

    /** @param promptContent the verbatim content of the test prompt file */
    public static String build(String promptContent) {
        // f"""\n{prompt_content}\n\n\n\nPlease provide...""" — the file content line's
        // own newline plus three blank lines = four consecutive '\n'.
        return "\n"
                + promptContent
                + """




                Please provide the complete test class code, including all necessary imports and annotations. Ensure that your tests are thorough, covering all aspects of the class behavior while considering the provided structure, data flow, and dependencies.

                Important notes:
                1. Remember to import all necessary classes as listed in the Imports section.
                2. In your test class, explicitly verify that the class implements all listed interfaces and extends the superclass (if any).
                3. When testing overridden methods, add comments indicating which interface or superclass they are inherited from.
                4. DO NOT use @Nested annotations or nested test classes, as they cause coverage tracking issues.
                5. Always provide a complete, well-structured test class that will compile without any modifications.
                6. Use straightforward test methods without nesting to ensure proper coverage tracking.

                STRICT ANTI-MOCKING REQUIREMENTS:
                - ABSOLUTELY NO use of any mocking frameworks (Mockito, EasyMock, PowerMock, etc.)
                - ABSOLUTELY NO @Mock, @MockBean, @InjectMocks, or any mock-related annotations
                - ABSOLUTELY NO imports from org.mockito.* or static imports from Mockito
                - ABSOLUTELY NO mock(), when(), verify(), or any mocking methods
                - Use ONLY real objects and direct instantiation for testing
                - Create real instances of dependencies instead of mocks
                - Focus on testing actual behavior with real object interactions

                Please generate a complete JUnit test class, ensuring coverage of all main functionality.
                Use JUnit 5 (Jupiter) annotations and assertions. Please follow all testing requirements in the prompt.

                CRITICAL ANTI-PLACEHOLDER REQUIREMENTS:
                - YOUR RESPONSE MUST CONTAIN THE COMPLETE TEST CLASS CODE
                - DO NOT OMIT ANY PARTS OF THE CODE OR USE PLACEHOLDERS
                - FORBIDDEN: "// ... existing code ...", "// [Previous imports remain exactly the same]", "// All previous fields and methods remain exactly the same"
                - REQUIRED: Every single import, field, and method must be written out in full
                - NO shortcuts, abbreviations, or comments indicating omitted code are allowed
                - Your response must be compilable Java code that can be directly saved to a file
                """;
    }
}
