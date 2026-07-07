package org.failmapper.llm.prompt;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import java.util.stream.StreamSupport;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.TestFactory;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * LAYER-P DIFFERENTIAL TESTS (contract section 5 methodology applied to the
 * section 3.6 prompt-template register; milestone M4).
 *
 * <p>Every case in {@code src/test/resources/layerp/prompts.json} was rendered by the
 * REAL Python prompt functions of the FailMapper baseline (see the generator's
 * docstring for the object.__new__/API-capture technique). This class re-renders the
 * same inputs with the ported builders and asserts EXACT STRING equality — prompt
 * bytes drive the whole LLM trajectory, so no tolerance is acceptable.
 *
 * <p>THE FIXTURE FILE IS A PINNED ORACLE SNAPSHOT — do not edit it by hand.
 * Generator: {@code java/failmapper-llm/src/test/python/gen_prompts.py}. Regen:
 * <pre>
 *   cd /Users/ruiqidong/Desktop/FailMapper
 *   python3 java/failmapper-llm/src/test/python/gen_prompts.py \
 *       java/failmapper-llm/src/test/resources/layerp/prompts.json
 * </pre>
 *
 * <p>Templates covered: P2 (action prompt, all branches), P1 (business-logic prompt),
 * P12 (initial-test wrapper), P10 (single verification), P11 (batch verification,
 * incl. the 2500-char truncation edge), P3 (integrated-test fix), P7 (merge prompt,
 * Chinese labels).
 *
 * <p><b>REGISTERED DEVIATION I7</b> (contract section 4): P3 cases whose error list
 * is truthy carry {@code i7Transform: true}. The Python baseline interpolates the
 * error LIST via f-string ({@code fa_mcts.py:4257}), embedding a Python list repr
 * ({@code ['err1', 'err2']}); the registered Java protocol renders a numbered list
 * instead. For those cases the oracle text is transformed FIRST — the recorded
 * {@code pythonErrorRepr} segment (which occurs exactly once) is replaced by
 * {@link FixPromptBuilder#renderErrors} — and every byte OUTSIDE that segment is
 * still compared exactly. Falsy-error cases (null/empty list) share the identical
 * fallback string with Python and are compared without any transform.
 */
class LayerPDifferentialTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {
    };

    private static JsonNode CASES;

    @BeforeAll
    static void load() throws Exception {
        try (InputStream in = LayerPDifferentialTest.class.getResourceAsStream("/layerp/prompts.json")) {
            assertThat(in).as("prompts.json on test classpath").isNotNull();
            CASES = MAPPER.readTree(in).get("cases");
        }
    }

    @TestFactory
    Stream<DynamicTest> promptBytesMatchPythonBaseline() {
        return StreamSupport.stream(CASES.spliterator(), false)
                .map(c -> DynamicTest.dynamicTest(
                        c.get("caseId").asText() + " [" + c.get("templateId").asText() + "]",
                        () -> runCase(c)));
    }

    private void runCase(JsonNode c) {
        String templateId = c.get("templateId").asText();
        JsonNode inputs = c.get("inputs");
        String oracle = c.get("renderedPrompt").asText();

        String java;
        String expected = oracle;
        switch (templateId) {
            case "P2" -> java = new ActionPromptBuilder(
                    text(inputs, "className"),
                    text(inputs, "sourceCode"),
                    text(inputs, "testPrompt"))
                    .build(inputs.get("coverage").asDouble(),
                            text(inputs, "testCode"),
                            map(inputs.get("action")));
            case "P1" -> java = new BusinessLogicPromptBuilder(
                    text(inputs, "className"),
                    text(inputs, "sourceCode"),
                    text(inputs, "testPrompt"))
                    .build(inputs.get("coverage").asDouble(),
                            text(inputs, "testCode"),
                            mapList(inputs.get("potentialBugs")),
                            map(inputs.get("action")));
            case "P12" -> java = InitialTestPromptBuilder.build(text(inputs, "promptContent"));
            case "P10" -> java = VerificationPromptBuilder.buildSingle(
                    text(inputs, "className"),
                    text(inputs, "sourceCode"),
                    text(inputs, "testMethod"),
                    text(inputs, "bugType"),
                    text(inputs, "severity"),
                    text(inputs, "errorMessage"));
            case "P11" -> java = VerificationPromptBuilder.buildBatch(
                    text(inputs, "packageName"),
                    text(inputs, "className"),
                    text(inputs, "sourceCode"),
                    batchMethods(inputs.get("batch")));
            case "P3" -> {
                List<String> errors = stringList(inputs.get("errorMessages"));
                java = new FixPromptBuilder(
                        text(inputs, "className"),
                        text(inputs, "packageName"),
                        text(inputs, "sourceCode"))
                        .build(text(inputs, "testCode"), errors);
                if (inputs.get("i7Transform").asBoolean()) {
                    // REGISTERED I7 TRANSFORM (contract section 4): swap the Python
                    // list-repr segment for the registered numbered-list rendering;
                    // all bytes outside the segment stay under exact comparison.
                    String repr = text(inputs, "pythonErrorRepr");
                    int at = oracle.indexOf(repr);
                    assertThat(at).as("python list-repr segment present in oracle").isNotNegative();
                    assertThat(oracle.indexOf(repr, at + 1))
                            .as("python list-repr segment occurs exactly once").isEqualTo(-1);
                    expected = oracle.substring(0, at)
                            + FixPromptBuilder.renderErrors(errors)
                            + oracle.substring(at + repr.length());
                }
            }
            case "P7" -> java = MergePromptBuilder.build(
                    text(inputs, "baseTest"),
                    stringList(inputs.get("methodCodes")));
            default -> throw new AssertionError("unknown templateId " + templateId);
        }

        assertThat(java).isEqualTo(expected);
    }

    // ------------------------------------------------------------------

    private static String text(JsonNode node, String field) {
        JsonNode value = node.get(field);
        return value == null || value.isNull() ? null : value.asText();
    }

    private static Map<String, Object> map(JsonNode node) {
        return MAPPER.convertValue(node, MAP_TYPE);
    }

    private static List<Map<String, Object>> mapList(JsonNode node) {
        if (node == null || node.isNull()) {
            return null;
        }
        List<Map<String, Object>> list = new ArrayList<>();
        for (JsonNode item : node) {
            list.add(map(item));
        }
        return list;
    }

    private static List<String> stringList(JsonNode node) {
        if (node == null || node.isNull()) {
            return null;
        }
        List<String> list = new ArrayList<>();
        for (JsonNode item : node) {
            list.add(item.asText());
        }
        return list;
    }

    private static List<VerificationPromptBuilder.BatchMethod> batchMethods(JsonNode node) {
        List<VerificationPromptBuilder.BatchMethod> batch = new ArrayList<>();
        for (JsonNode item : node) {
            if (item.get("isRaw").asBoolean()) {
                batch.add(VerificationPromptBuilder.BatchMethod.raw(item.get("code").asText()));
            } else {
                batch.add(VerificationPromptBuilder.BatchMethod.ofMethod(
                        item.get("code").asText(),
                        stringList(item.get("bugTypes"))));
            }
        }
        return batch;
    }
}
