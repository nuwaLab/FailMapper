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

    // ------------------------------------------------------------------
    // I18 — spec-grounded verification (documented contract + burden of proof)
    // ------------------------------------------------------------------

    private static LlmBugVerifier specGrounded(LlmClient client) {
        java.util.LinkedHashMap<String, String> docs = new java.util.LinkedHashMap<>();
        docs.put("add", "Returns the sum of a and b.\n@return the exact sum");
        docs.put("unrelated", "Never called by the test.");
        return new LlmBugVerifier(client, SOURCE, "Calc",
                "Simple calculator; all operations are exact.", docs);
    }

    @Test
    void specGroundedPromptAppendsContractAfterUntouchedLegacyBody() {
        FakeClient client = new FakeClient();
        client.reply = "VERDICT: FALSE POSITIVE\nCONFIDENCE: 9\nREASONING: expectation has"
                + " no support in the documented contract.\nSPEC_BASIS: none\n";

        specGrounded(client).verifyBatch(List.of(
                method("testAdd", METHOD_CODE, "s1",
                        bug("testAdd", "assertion_failure", "e", 1))));

        assertThat(client.prompts).hasSize(1);
        String prompt = client.prompts.get(0);
        // Legacy P10 body byte-identical, appendix strictly after it.
        String legacy = org.failmapper.llm.prompt.VerificationPromptBuilder.buildSingle(
                "Calc", SOURCE, METHOD_CODE, "unknown", "medium", "");
        assertThat(prompt).startsWith(legacy);
        assertThat(prompt).contains("DOCUMENTED CONTRACT (authoritative specification)");
        assertThat(prompt).contains("Simple calculator; all operations are exact.");
        // Only the method the test calls (add) is carried, not the unrelated one.
        assertThat(prompt).contains("add:\nReturns the sum of a and b.");
        assertThat(prompt).doesNotContain("Never called by the test.");
        assertThat(prompt).contains("SPEC_BASIS:");
    }

    @Test
    void specGroundedFallsBackToClassDocWhenNoMethodMatches() {
        FakeClient client = new FakeClient();
        client.reply = "VERDICT: FALSE POSITIVE\nCONFIDENCE: 9\nREASONING: fine, documented"
                + " behavior matches implementation.\nSPEC_BASIS: none\n";

        specGrounded(client).verifyBatch(List.of(
                method("testOther", "public void testOther() { assertTrue(true); }", "s1",
                        bug("testOther", "assertion_failure", "e", 1))));

        String prompt = client.prompts.get(0);
        assertThat(prompt).contains("Simple calculator; all operations are exact.");
        assertThat(prompt).contains("(no method-level Javadoc available - judge against"
                + " the class documentation above)");
    }

    @Test
    void unsubstantiatedRealVerdictIsDowngraded() {
        FakeClient client = new FakeClient();
        // REAL verdict with NO SPEC_BASIS line at all.
        client.reply = "VERDICT: REAL BUG\nCONFIDENCE: 9\nREASONING: this seems wrong to me"
                + " even though the documentation says otherwise.";

        List<VerifiedBugMethod> out = specGrounded(client).verifyBatch(List.of(
                method("testAdd", METHOD_CODE, "s1",
                        bug("testAdd", "assertion_failure", "e", 1))));

        assertThat(out).hasSize(1);
        assertThat(out.get(0).isRealBug).isFalse();
        assertThat(out.get(0).verificationConfidence).isEqualTo(0.5);
        assertThat(out.get(0).verificationReasoning).startsWith("[unsubstantiated] ");
        assertThat(out.get(0).verificationReasoning).contains("this seems wrong to me");
    }

    @Test
    void emptyOrNoneSpecBasisAlsoDowngradesRealVerdicts() {
        FakeClient emptyBasis = new FakeClient();
        emptyBasis.reply = "VERDICT: REAL BUG\nCONFIDENCE: 8\nREASONING: some long enough"
                + " reasoning text for the length gate.\nSPEC_BASIS:\n";
        List<VerifiedBugMethod> out1 = specGrounded(emptyBasis).verifyBatch(List.of(
                method("testAdd", METHOD_CODE, "s1",
                        bug("testAdd", "assertion_failure", "e", 1))));
        assertThat(out1.get(0).isRealBug).isFalse();
        assertThat(out1.get(0).verificationConfidence).isEqualTo(0.5);

        FakeClient noneBasis = new FakeClient();
        noneBasis.reply = "VERDICT: REAL BUG\nCONFIDENCE: 8\nREASONING: some long enough"
                + " reasoning text for the length gate.\nSPEC_BASIS: none\n";
        List<VerifiedBugMethod> out2 = specGrounded(noneBasis).verifyBatch(List.of(
                method("testAdd", METHOD_CODE, "s2",
                        bug("testAdd", "assertion_failure", "e", 1))));
        assertThat(out2.get(0).isRealBug).isFalse();
        assertThat(out2.get(0).verificationReasoning).startsWith("[unsubstantiated] ");
    }

    @Test
    void substantiatedRealVerdictIsKept() {
        FakeClient client = new FakeClient();
        client.reply = "VERDICT: REAL BUG\nCONFIDENCE: 9\nREASONING: the Javadoc promises"
                + " an exact sum but add() truncates.\nSPEC_BASIS: \"@return the exact sum\""
                + " is violated for these operands\n";

        List<VerifiedBugMethod> out = specGrounded(client).verifyBatch(List.of(
                method("testAdd", METHOD_CODE, "s1",
                        bug("testAdd", "assertion_failure", "e", 1))));

        assertThat(out.get(0).isRealBug).isTrue();
        assertThat(out.get(0).verificationConfidence).isCloseTo(0.9, within(1e-9));
        assertThat(out.get(0).verificationReasoning).doesNotStartWith("[unsubstantiated]");
    }

    @Test
    void falsePositiveVerdictsAreNeverDowngraded() {
        FakeClient client = new FakeClient();
        client.reply = "VERDICT: FALSE POSITIVE\nCONFIDENCE: 9\nREASONING: expectation"
                + " contradicts the documented contract for this input.";

        List<VerifiedBugMethod> out = specGrounded(client).verifyBatch(List.of(
                method("testAdd", METHOD_CODE, "s1",
                        bug("testAdd", "assertion_failure", "e", 1))));

        assertThat(out.get(0).isRealBug).isFalse();
        assertThat(out.get(0).verificationConfidence).isCloseTo(0.9, within(1e-9));
        assertThat(out.get(0).verificationReasoning).doesNotStartWith("[unsubstantiated]");
    }

    @Test
    void legacyModeNeverDowngradesAndNeverAppends() {
        FakeClient client = new FakeClient();
        client.reply = "VERDICT: REAL BUG\nCONFIDENCE: 9\nREASONING: legacy responses have"
                + " no SPEC_BASIS line and must stay untouched.";

        LlmBugVerifier verifier = new LlmBugVerifier(client, SOURCE, "Calc"); // legacy ctor
        List<VerifiedBugMethod> out = verifier.verifyBatch(List.of(
                method("testAdd", METHOD_CODE, "s1",
                        bug("testAdd", "assertion_failure", "e", 1))));

        assertThat(out.get(0).isRealBug).isTrue();
        assertThat(out.get(0).verificationConfidence).isCloseTo(0.9, within(1e-9));
        assertThat(client.prompts.get(0)).doesNotContain("DOCUMENTED CONTRACT");
    }

    // ------------------------------------------------------------------
    // I19 — per-run signature-level verdict cache
    // ------------------------------------------------------------------

    @Test
    void identicalSignatureAcrossBatchesReusesVerdictWithoutSecondCall() {
        FakeClient client = new FakeClient();
        client.reply = "VERDICT: REAL BUG\nCONFIDENCE: 8\nREASONING: sum is wrong for these"
                + " operands per the documented contract.\nSPEC_BASIS: exact-sum clause\n";
        LlmBugVerifier verifier = specGrounded(client);

        List<VerifiedBugMethod> first = verifier.verifyBatch(List.of(
                method("testAdd", METHOD_CODE, "same:sig",
                        bug("testAdd", "assertion_failure", "e1", 1))));
        List<VerifiedBugMethod> second = verifier.verifyBatch(List.of(
                method("testAdd", METHOD_CODE, "same:sig",
                        bug("testAdd", "assertion_failure", "e1", 1))));

        assertThat(client.prompts).hasSize(1); // one LLM call total: cache hit on run 2
        assertThat(second.get(0).isRealBug).isEqualTo(first.get(0).isRealBug);
        assertThat(second.get(0).verificationConfidence)
                .isEqualTo(first.get(0).verificationConfidence);
        assertThat(second.get(0).verificationReasoning)
                .isEqualTo(first.get(0).verificationReasoning);
    }

    @Test
    void distinctSignaturesStillTriggerSeparateCalls() {
        FakeClient client = new FakeClient();
        client.reply = "VERDICT: FALSE POSITIVE\nCONFIDENCE: 9\nREASONING: the expectation"
                + " has no support in the documented contract.\nSPEC_BASIS: none\n";
        LlmBugVerifier verifier = specGrounded(client);

        verifier.verifyBatch(List.of(
                method("testA", METHOD_CODE, "sig:a",
                        bug("testA", "assertion_failure", "e1", 1))));
        verifier.verifyBatch(List.of(
                method("testB", METHOD_CODE, "sig:b",
                        bug("testB", "assertion_failure", "e2", 2))));

        assertThat(client.prompts).hasSize(2);
    }

    @Test
    void cacheWorksInLegacyModeToo() {
        FakeClient client = new FakeClient();
        client.reply = "VERDICT: REAL BUG\nCONFIDENCE: 8\nREASONING: consistent verdicts"
                + " for identical signatures within one run.";
        LlmBugVerifier verifier = new LlmBugVerifier(client, SOURCE, "Calc");

        verifier.verifyBatch(List.of(
                method("testAdd", METHOD_CODE, "same:sig",
                        bug("testAdd", "assertion_failure", "e1", 1))));
        List<VerifiedBugMethod> second = verifier.verifyBatch(List.of(
                method("testAdd", METHOD_CODE, "same:sig",
                        bug("testAdd", "assertion_failure", "e1", 1))));

        assertThat(client.prompts).hasSize(1);
        assertThat(second.get(0).isRealBug).isTrue();
    }
}
