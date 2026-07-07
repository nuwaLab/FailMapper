package org.failmapper.app;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import java.util.ArrayList;
import java.util.List;

import org.failmapper.llm.LlmClient;
import org.failmapper.search.MethodToVerify;
import org.failmapper.search.PotentialBug;
import org.failmapper.search.VerificationPolicy;
import org.failmapper.search.VerifiedBugMethod;
import org.junit.jupiter.api.Test;

/**
 * {@link LlmBugVerifier} (D9 live path + D10 pre-filters + F15 parsing) with a fake
 * client — no network.
 */
class LlmBugVerifierTest {

    private static final String SOURCE = "public class Calc { int add(int a, int b) { return a + b; } }";
    private static final String METHOD_CODE = """
            public void testAdd() {
                assertEquals(5, new Calc().add(2, 2));
            }
            """;

    private static final class FakeClient implements LlmClient {
        final List<String> prompts = new ArrayList<>();
        String reply;
        boolean fail = false;

        @Override
        public String complete(String systemPrompt, String userPrompt) {
            prompts.add(userPrompt);
            if (fail) {
                throw new LlmException("scripted failure");
            }
            return reply;
        }
    }

    private static PotentialBug bug(String method, String type, String error, int iteration) {
        PotentialBug b = new PotentialBug();
        b.testMethod = method;
        b.bugType = type;
        b.error = error;
        b.severity = "medium";
        b.foundInIteration = iteration;
        return b;
    }

    private static MethodToVerify method(String name, String code, String signature,
                                         PotentialBug... bugs) {
        return new MethodToVerify(name, code, List.of(bugs),
                List.of(), signature);
    }

    @Test
    void structuredRealBugVerdictParses() {
        FakeClient client = new FakeClient();
        client.reply = "VERDICT: REAL BUG\nCONFIDENCE: 8\nREASONING: The addition result "
                + "is wrong for these operands, which violates the class contract.";

        LlmBugVerifier verifier = new LlmBugVerifier(client, SOURCE, "Calc");
        List<VerifiedBugMethod> out = verifier.verifyBatch(List.of(
                method("testAdd", METHOD_CODE, "testAdd:aaaa",
                        bug("testAdd", "assertion_failure", "expected: <5> but was: <4>", 2))));

        assertThat(out).hasSize(1);
        VerifiedBugMethod v = out.get(0);
        assertThat(v.verified).isTrue();
        assertThat(v.isRealBug).isTrue();
        assertThat(v.verificationConfidence).isCloseTo(0.8, within(1e-9)); // min(8/10, 0.95)
        assertThat(v.bugType).isEqualTo("assertion_failure");
        assertThat(v.foundInIteration).isEqualTo(2);
        assertThat(v.bugSignature).isEqualTo("testAdd:aaaa");
        // The P10 prompt carried the source and the method (live-path bug_info defaults:
        // type "unknown", empty error — bug_verifier.py:193-196 quirk kept verbatim).
        assertThat(client.prompts).hasSize(1);
        assertThat(client.prompts.get(0)).contains(SOURCE);
        assertThat(client.prompts.get(0)).contains("Bug type: unknown");
        assertThat(client.prompts.get(0)).contains("Error message: \n");
    }

    @Test
    void dedupesBySignatureWithOneLlmCall() {
        FakeClient client = new FakeClient();
        client.reply = "VERDICT: FALSE POSITIVE\nCONFIDENCE: 9\nREASONING: The expectation in the"
                + " test method itself is wrong; the class behaves as documented.";

        LlmBugVerifier verifier = new LlmBugVerifier(client, SOURCE, "Calc");
        List<VerifiedBugMethod> out = verifier.verifyBatch(List.of(
                method("testA", METHOD_CODE, "same:sig",
                        bug("testA", "assertion_failure", "e1", 1)),
                method("testB", METHOD_CODE, "same:sig",
                        bug("testB", "assertion_failure", "e2", 2))));

        // Second method with the same signature skipped (bug_verifier.py:178-181, O16).
        assertThat(out).hasSize(1);
        assertThat(out.get(0).methodName).isEqualTo("testA");
        assertThat(out.get(0).isRealBug).isFalse();
        assertThat(client.prompts).hasSize(1);
    }

    @Test
    void missingMethodCodeShortCircuitsWithoutLlm() {
        FakeClient client = new FakeClient();
        LlmBugVerifier verifier = new LlmBugVerifier(client, SOURCE, "Calc");

        List<VerifiedBugMethod> out = verifier.verifyBatch(List.of(
                method("testGhost", null, "testGhost:sig",
                        bug("testGhost", "runtime_error", "boom", 1))));

        assertThat(out).hasSize(1);
        // verify_bug_with_llm.py:32-39: is_real_bug = 0.5 > 0.7 = false, conf 0.5.
        assertThat(out.get(0).isRealBug).isFalse();
        assertThat(out.get(0).verificationConfidence).isEqualTo(0.5);
        assertThat(out.get(0).verificationReasoning)
                .isEqualTo("Insufficient data for verification");
        assertThat(client.prompts).isEmpty();
    }

    @Test
    void apiFailureFallsBackToConfidenceDefault() {
        FakeClient client = new FakeClient();
        client.fail = true;
        LlmBugVerifier verifier = new LlmBugVerifier(client, SOURCE, "Calc");

        List<VerifiedBugMethod> out = verifier.verifyBatch(List.of(
                method("testAdd", METHOD_CODE, "s1",
                        bug("testAdd", "assertion_failure", "e", 1))));

        assertThat(out).hasSize(1);
        assertThat(out.get(0).isRealBug).isFalse(); // 0.5 > 0.7 = false
        assertThat(out.get(0).verificationConfidence).isEqualTo(0.5);
    }

    @Test
    void shortResponseTreatedAsInsufficient() {
        FakeClient client = new FakeClient();
        client.reply = "REAL BUG"; // < 50 chars (verify_bug_with_llm.py:131)
        LlmBugVerifier verifier = new LlmBugVerifier(client, SOURCE, "Calc");

        List<VerifiedBugMethod> out = verifier.verifyBatch(List.of(
                method("testAdd", METHOD_CODE, "s1",
                        bug("testAdd", "assertion_failure", "e", 1))));

        assertThat(out.get(0).isRealBug).isFalse();
        assertThat(out.get(0).verificationConfidence).isEqualTo(0.5);
    }

    @Test
    void d10PreFiltersFireForGenuineBugInfo() {
        // The pre-filters cannot fire on batch-path inputs (defaults) but remain live
        // for genuine bug dicts (I15) — exercised through verifySingle directly.
        FakeClient client = new FakeClient();
        LlmBugVerifier verifier = new LlmBugVerifier(client, SOURCE, "Calc");

        VerificationPolicy.Verdict memory = verifier.verifySingle(
                "memory_error", "java.lang.StackOverflowError", "high", 0.5, METHOD_CODE);
        assertThat(memory.isRealBug()).isTrue();
        assertThat(memory.confidence()).isEqualTo(0.95);

        VerificationPolicy.Verdict nullFp = verifier.verifySingle(
                "assertion_failure", "expected: <null> but was: <x>", "medium", 0.5,
                "public void testNullInput() { }");
        assertThat(nullFp.isRealBug()).isFalse();
        assertThat(nullFp.confidence()).isEqualTo(0.9);

        assertThat(client.prompts).isEmpty(); // both decided BEFORE any LLM call (D10)
    }

    @Test
    void emptyInputYieldsEmptyOutput() {
        LlmBugVerifier verifier = new LlmBugVerifier(new FakeClient(), SOURCE, "Calc");
        assertThat(verifier.verifyBatch(List.of())).isEmpty();
        assertThat(verifier.verifyBatch(null)).isEmpty();
    }
}
