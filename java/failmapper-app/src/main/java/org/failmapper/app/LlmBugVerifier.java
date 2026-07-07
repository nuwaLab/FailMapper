package org.failmapper.app;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;

import org.failmapper.llm.LlmClient;
import org.failmapper.llm.prompt.VerificationPromptBuilder;
import org.failmapper.search.BugSignature;
import org.failmapper.search.FaMcts;
import org.failmapper.search.MethodToVerify;
import org.failmapper.search.PotentialBug;
import org.failmapper.search.VerificationPolicy;
import org.failmapper.search.VerifiedBugMethod;

/**
 * D9 — the LIVE bug-verification path: {@code BugVerifier.verify_bugs}
 * ({@code bug_verifier.py:141-238}) calling {@code verify_bug_with_llm}
 * ({@code verify_bug_with_llm.py:19-224}) per method, with:
 * <ul>
 *   <li>per-signature dedup through an insertion-ordered map (contract O16);</li>
 *   <li>the D10 heuristic pre-filters BEFORE any LLM call (registered I15: they are
 *       algorithm, kept verbatim via {@link VerificationPolicy#preFilter});</li>
 *   <li>the P10 single-bug verification prompt
 *       ({@link VerificationPromptBuilder#buildSingle}) and the FULL F15 response
 *       parsing ({@link VerificationPolicy#parseResponse}, structured VERDICT branch
 *       first — registered I1 — with the keyword fallback preserved).</li>
 * </ul>
 *
 * <p>FAITHFUL QUIRK (not "fixed"; see class javadoc rationale): the live Python path
 * passes the METHOD-INFO dict (not a bug dict) as {@code bug_info} into
 * {@code verify_bug_with_llm} ({@code bug_verifier.py:193-196}), so
 * {@code bug_info.get("type"/"error"/"severity"/"confidence")} all fall to their
 * defaults ({@code "unknown"/""/"medium"/0.5}) — the verification prompt carries NO
 * error text and the D10 pre-filters cannot fire on batch-path inputs. The Java port
 * reproduces exactly that; feeding the real per-bug fields would be an unregistered
 * semantic change (contract iron rule).
 *
 * <p>Consequence kept verbatim: a method with NO extractable code hits the
 * missing-input guard ({@code verify_bug_with_llm.py:32-39}) and resolves to
 * {@code is_real_bug = (0.5 > 0.7) = false} without an LLM call.
 */
public final class LlmBugVerifier implements FaMcts.BugVerifier {

    private final LlmClient client;
    private final VerificationPolicy policy = new VerificationPolicy();
    private final String sourceCode;
    private final String className;

    /**
     * Mirrors {@code BugVerifier(source_code, class_name, package_name)}
     * ({@code fa_mcts.py:1206}); the package name is carried by the P11 batch prompt
     * only, which the live D9 path does not use.
     */
    public LlmBugVerifier(LlmClient client, String sourceCode, String className) {
        this.client = client;
        this.sourceCode = sourceCode;
        this.className = className;
    }

    @Override
    public List<VerifiedBugMethod> verifyBatch(List<MethodToVerify> methods) {
        // verified_methods_dict — insertion-ordered signature dedup (O16).
        LinkedHashMap<String, VerifiedBugMethod> bySignature = new LinkedHashMap<>();
        if (methods == null || methods.isEmpty()) {
            return new ArrayList<>();
        }

        for (MethodToVerify method : methods) {
            // bug_verifier.py:170-176 — fallback signature over the (absent) error "".
            String signature = method.bugSignature() != null
                    ? method.bugSignature()
                    : BugSignature.create(method.methodName(), "");
            if (bySignature.containsKey(signature)) {
                continue;
            }

            // Live-path bug_info defaults (see class doc): type/error/severity/confidence
            // come from the method-info dict, which lacks those keys.
            VerificationPolicy.Verdict verdict = verifySingle(
                    "unknown", "", "medium", 0.5, method.methodCode());

            VerifiedBugMethod result = new VerifiedBugMethod();
            result.methodName = method.methodName();
            result.methodCode = method.methodCode();
            result.bugSignature = signature;
            result.verified = true;
            result.isRealBug = verdict.isRealBug();
            result.verificationConfidence = verdict.confidence();
            result.verificationReasoning = verdict.reasoning();
            if (!method.bugInfo().isEmpty()) {
                PotentialBug first = method.bugInfo().get(0);
                result.bugType = first.bugType == null ? "unknown" : first.bugType;
                result.foundInIteration = first.foundInIteration;
            } else {
                result.bugType = "unknown";
            }
            bySignature.put(signature, result);
        }

        return new ArrayList<>(bySignature.values());
    }

    /**
     * Full port of {@code verify_bug_with_llm} ({@code verify_bug_with_llm.py:19-224})
     * for one bug: missing-input guard → D10 pre-filters → P10 prompt → LLM →
     * insufficient-response / API-failure defaults → F15 parsing.
     */
    public VerificationPolicy.Verdict verifySingle(String bugType,
                                                   String errorMessage,
                                                   String severity,
                                                   double confidence,
                                                   String testMethod) {
        // verify_bug_with_llm.py:32-39 — `if not test_method or not source_code`.
        if (testMethod == null || testMethod.isEmpty()
                || sourceCode == null || sourceCode.isEmpty()) {
            return policy.insufficientInput(confidence);
        }

        // D10 pre-filters (verify_bug_with_llm.py:46-75) — BEFORE any LLM call.
        VerificationPolicy.Verdict preFiltered =
                policy.preFilter(bugType, errorMessage, confidence, testMethod);
        if (preFiltered != null) {
            return preFiltered;
        }

        String prompt = VerificationPromptBuilder.buildSingle(
                className, sourceCode, testMethod, bugType, severity, errorMessage);

        String response;
        try {
            response = client.complete(null, prompt);
        } catch (RuntimeException e) {
            // Both-transports-failed default (verify_bug_with_llm.py:146-153).
            return policy.apiFailureDefault(confidence);
        }
        if (policy.insufficientResponse(response)) {
            // verify_bug_with_llm.py:131-137 — same decision rule as the API failure.
            return policy.apiFailureDefault(confidence);
        }

        return policy.parseResponse(response); // F15 (structured branch first — I1)
    }
}
