package org.failmapper.llm.prompt;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * I18 (contract §4) — the spec-grounded verification prompt mode.
 *
 * <p>Layer-P byte fidelity is the design constraint: {@code buildSingle} (the legacy
 * P10 rendering) must stay byte-identical to the pinned Python oracle in
 * {@code /layerp/prompts.json} — asserted here DIRECTLY against that resource, on top
 * of the unmodified {@link LayerPDifferentialTest} — and the spec-grounded mode may
 * only APPEND after the legacy body.
 */
class SpecGroundedPromptTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static List<JsonNode> P10_CASES;

    @BeforeAll
    static void loadOracle() throws Exception {
        try (InputStream in = SpecGroundedPromptTest.class.getResourceAsStream("/layerp/prompts.json")) {
            assertThat(in).as("prompts.json on test classpath").isNotNull();
            JsonNode cases = MAPPER.readTree(in).get("cases");
            List<JsonNode> p10 = new ArrayList<>();
            cases.forEach(c -> {
                if ("P10".equals(c.get("templateId").asText())) {
                    p10.add(c);
                }
            });
            P10_CASES = p10;
            assertThat(P10_CASES).as("P10 cases in the Layer-P oracle").isNotEmpty();
        }
    }

    private static String legacy(JsonNode c) {
        JsonNode in = c.get("inputs");
        return VerificationPromptBuilder.buildSingle(
                in.get("className").asText(),
                in.get("sourceCode").asText(),
                in.get("testMethod").asText(),
                in.get("bugType").asText(),
                in.get("severity").asText(),
                in.get("errorMessage").asText());
    }

    private static String specGrounded(JsonNode c, String classDoc, Map<String, String> methodDocs) {
        JsonNode in = c.get("inputs");
        return VerificationPromptBuilder.buildSingleSpecGrounded(
                in.get("className").asText(),
                in.get("sourceCode").asText(),
                in.get("testMethod").asText(),
                in.get("bugType").asText(),
                in.get("severity").asText(),
                in.get("errorMessage").asText(),
                classDoc, methodDocs);
    }

    // ------------------------------------------------------------------
    // Legacy byte fidelity, pinned against the Layer-P Python oracle
    // ------------------------------------------------------------------

    @Test
    void legacyModeIsByteIdenticalToThePinnedPythonOracle() {
        for (JsonNode c : P10_CASES) {
            assertThat(legacy(c))
                    .as("P10 legacy bytes for %s", c.get("caseId").asText())
                    .isEqualTo(c.get("renderedPrompt").asText());
        }
    }

    @Test
    void specGroundedModeOnlyAppendsAfterTheLegacyBody() {
        Map<String, String> docs = new LinkedHashMap<>();
        docs.put("isValidOpt", "Checks an option char.\n@param c the char");
        for (JsonNode c : P10_CASES) {
            String oracle = c.get("renderedPrompt").asText();
            String spec = specGrounded(c, "Validates options.", docs);
            assertThat(spec)
                    .as("spec-grounded prompt for %s starts with the untouched oracle bytes",
                            c.get("caseId").asText())
                    .startsWith(oracle);
            assertThat(spec.substring(oracle.length()))
                    .isEqualTo(VerificationPromptBuilder.specGroundedSection(
                            "Validates options.", docs));
        }
    }

    // ------------------------------------------------------------------
    // Appendix content
    // ------------------------------------------------------------------

    @Test
    void appendixCarriesHeaderContractAndBurdenOfProof() {
        Map<String, String> docs = new LinkedHashMap<>();
        docs.put("stripLeadingHyphens", "Removes the hyphens from the beginning of str.");
        String section = VerificationPromptBuilder.specGroundedSection(
                "Utility class for command-line strings.", docs);

        assertThat(section).contains(VerificationPromptBuilder.SPEC_SECTION_HEADER);
        assertThat(section).contains(
                "DOCUMENTED CONTRACT (authoritative specification)");
        assertThat(section).contains(
                "Class documentation:\nUtility class for command-line strings.");
        assertThat(section).contains(
                "stripLeadingHyphens:\nRemoves the hyphens from the beginning of str.");
        // The four mandated burden-of-proof clauses.
        assertThat(section).contains("ONLY authoritative specification");
        assertThat(section).contains(
                "A \"REAL BUG\" verdict REQUIRES citing which documented statement");
        assertThat(section).contains("universally-expected invariant");
        assertThat(section).contains("MUST be judged \"FALSE POSITIVE\"");
        assertThat(section).contains(
                "same VERDICT/CONFIDENCE/REASONING format");
        assertThat(section).contains("SPEC_BASIS:");
    }

    @Test
    void methodDocsRenderInInsertionOrder() {
        Map<String, String> docs = new LinkedHashMap<>();
        docs.put("beta", "Doc B.");
        docs.put("alpha", "Doc A.");
        String section = VerificationPromptBuilder.specGroundedSection("C.", docs);

        assertThat(section.indexOf("beta:\nDoc B."))
                .isLessThan(section.indexOf("alpha:\nDoc A."));
    }

    @Test
    void missingClassDocFallsBackToPlaceholder() {
        String section = VerificationPromptBuilder.specGroundedSection(
                null, Map.of("m", "doc"));
        assertThat(section).contains("Class documentation:\n(no class-level Javadoc)");

        String blank = VerificationPromptBuilder.specGroundedSection(
                "   ", Map.of("m", "doc"));
        assertThat(blank).contains("Class documentation:\n(no class-level Javadoc)");
    }

    @Test
    void missingMethodDocsFallBackToClassDocOnlyNotice() {
        String section = VerificationPromptBuilder.specGroundedSection("Class doc.", Map.of());
        assertThat(section).contains(
                "(no method-level Javadoc available - judge against the class documentation above)");

        String nullMap = VerificationPromptBuilder.specGroundedSection("Class doc.", null);
        assertThat(nullMap).isEqualTo(section);
    }
}
