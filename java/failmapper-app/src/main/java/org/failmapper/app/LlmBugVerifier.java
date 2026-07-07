package org.failmapper.app;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import org.failmapper.llm.LlmClient;
import org.failmapper.llm.prompt.VerdictParser;
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
 * <p><b>REGISTERED IMPROVEMENT I18</b> (contract §4, spec-grounded verification): when
 * constructed with the target's documented contract (class + method Javadocs from
 * {@code JavadocExtractor}), the P10 prompt gains the appended
 * {@code DOCUMENTED CONTRACT} / burden-of-proof section
 * ({@link VerificationPromptBuilder#buildSingleSpecGrounded}) — the legacy P10 bytes
 * are untouched. Method docs are filtered per bug to the methods the test method
 * actually calls; when none match, the class doc alone is carried. A REAL verdict
 * whose response lacks a substantive {@code SPEC_BASIS:} citation (missing, blank, or
 * "none") is DOWNGRADED here — not in the parser — to FALSE POSITIVE at confidence
 * 0.5 with the reasoning prefixed {@code [unsubstantiated]}. Motivation
 * (M5_BENCHMARK §3.1): 3 of 4 clear clean-corpus false positives contradicted the
 * target's own Javadoc, which the legacy prompt never carried.
 *
 * <p><b>REGISTERED IMPROVEMENT I19</b> (contract §4, deterministic verification): a
 * per-run signature-level verdict cache — identical {@code bugSignature}s reuse the
 * first verdict across batches without another LLM call (stderr log line on each hit,
 * no content). Together with the runner giving this verifier its own temperature-0.0
 * client, this removes the M5 §3.5 flip-flop (opposite verdicts for near-identical
 * behaviors within one run).
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

    /** I18 — spec-grounded mode inputs; legacy mode when {@code specGrounded} is false. */
    private final boolean specGrounded;
    private final String classJavadoc;
    private final Map<String, String> methodJavadocs;

    /** I19 — per-run signature-level verdict cache (insertion-ordered, deterministic). */
    private final Map<String, VerificationPolicy.Verdict> verdictCache = new LinkedHashMap<>();

    /**
     * LEGACY mode — mirrors {@code BugVerifier(source_code, class_name, package_name)}
     * ({@code fa_mcts.py:1206}); the package name is carried by the P11 batch prompt
     * only, which the live D9 path does not use. Selected by the runner under
     * {@code FM_LEGACY_VERIFY=1} (I18 A/B knob).
     */
    public LlmBugVerifier(LlmClient client, String sourceCode, String className) {
        this(client, sourceCode, className, false, null, Map.of());
    }

    /**
     * I18 SPEC-GROUNDED mode (the default wiring): the P10 prompt gains the documented
     * contract + burden-of-proof appendix, and unsubstantiated REAL verdicts are
     * downgraded.
     *
     * @param classJavadoc   plain-text class-level Javadoc, or null when absent
     * @param methodJavadocs {@code methodName -> plain-text Javadoc} of the class under
     *                       test (insertion-ordered); filtered per bug to the methods
     *                       the test method references
     */
    public LlmBugVerifier(LlmClient client, String sourceCode, String className,
                          String classJavadoc, Map<String, String> methodJavadocs) {
        this(client, sourceCode, className, true, classJavadoc,
                methodJavadocs == null ? Map.of() : methodJavadocs);
    }

    private LlmBugVerifier(LlmClient client, String sourceCode, String className,
                           boolean specGrounded, String classJavadoc,
                           Map<String, String> methodJavadocs) {
        this.client = client;
        this.sourceCode = sourceCode;
        this.className = className;
        this.specGrounded = specGrounded;
        this.classJavadoc = classJavadoc;
        this.methodJavadocs = methodJavadocs;
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

            // I19 — per-run verdict cache: identical signatures reuse the verdict
            // (and skip the LLM call) instead of re-sampling a possibly contradictory
            // one (M5_BENCHMARK §3.5 flip-flop).
            VerificationPolicy.Verdict verdict = verdictCache.get(signature);
            if (verdict != null) {
                System.err.printf(Locale.ROOT,
                        "[verifier] I19 verdict-cache hit for signature %s — reusing"
                                + " cached verdict, no LLM call%n", signature);
            } else {
                // Live-path bug_info defaults (see class doc): type/error/severity/
                // confidence come from the method-info dict, which lacks those keys.
                verdict = verifySingle("unknown", "", "medium", 0.5, method.methodCode());
                verdictCache.put(signature, verdict);
            }

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
     * for one bug: missing-input guard → D10 pre-filters → P10 prompt (plus the I18
     * spec-grounded appendix in spec-grounded mode) → LLM → insufficient-response /
     * API-failure defaults → F15 parsing → I18 unsubstantiated-REAL downgrade.
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

        String prompt = specGrounded
                ? VerificationPromptBuilder.buildSingleSpecGrounded(
                        className, sourceCode, testMethod, bugType, severity, errorMessage,
                        classJavadoc, relevantMethodDocs(testMethod))
                : VerificationPromptBuilder.buildSingle(
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

        VerificationPolicy.Verdict verdict = policy.parseResponse(response); // F15 (I1)

        // I18 — burden of proof: in spec-grounded mode a REAL verdict must cite the
        // documented statement it violates; otherwise downgrade (here, NOT in the
        // parser) to FALSE POSITIVE at 0.5 with the "[unsubstantiated]" prefix.
        if (specGrounded && verdict.isRealBug() && !hasSpecBasis(response)) {
            return new VerificationPolicy.Verdict(false, 0.5,
                    "[unsubstantiated] " + verdict.reasoning());
        }
        return verdict;
    }

    // ------------------------------------------------------------------
    // I18 helpers
    // ------------------------------------------------------------------

    /**
     * True when the response carries a substantive SPEC_BASIS citation: the line must
     * exist, be non-blank, and not be the FALSE-POSITIVE placeholder "none" (a REAL
     * verdict pointing at no documented statement is by definition unsubstantiated).
     */
    private static boolean hasSpecBasis(String response) {
        String specBasis = VerdictParser.parseSpecBasis(response);
        if (specBasis == null || specBasis.isBlank()) {
            return false;
        }
        String normalized = specBasis.strip();
        if (normalized.endsWith(".")) {
            normalized = normalized.substring(0, normalized.length() - 1);
        }
        return !normalized.equalsIgnoreCase("none");
    }

    /**
     * The Javadocs relevant to this bug: documented methods the test method appears to
     * call ({@code name(} occurrence), insertion order preserved. Empty (class-doc-only
     * fallback) when the test references none of the documented methods.
     */
    private Map<String, String> relevantMethodDocs(String testMethod) {
        LinkedHashMap<String, String> relevant = new LinkedHashMap<>();
        for (Map.Entry<String, String> entry : methodJavadocs.entrySet()) {
            if (testMethod.contains(entry.getKey() + "(")) {
                relevant.put(entry.getKey(), entry.getValue());
            }
        }
        return relevant;
    }
}
