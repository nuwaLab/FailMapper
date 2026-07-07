package org.failmapper.search;

/**
 * One collected potential bug — the Java counterpart of the {@code bug_info} dict built
 * during simulation ({@code fa_mcts.py:3044-3062}, D7):
 * <pre>
 * {"test_method", "bug_type", "error", "severity", "method_code",
 *  "found_in_iteration", "original_test_code", "bug_signature"}
 * </pre>
 *
 * <p>NOTE the D7 field mapping quirk kept verbatim: {@code bug_type} is populated from
 * the detected bug's {@code "type"} key ({@code bug.get("type", "unknown")},
 * {@code fa_mcts.py:3047}) — i.e. the failure KIND such as {@code "assertion_failure"},
 * NOT the D11 classifier's {@code bug_type}.
 *
 * <p>Mutable with public fields, mirroring the Python dict (verification merges results
 * into the same object).
 */
public final class PotentialBug {

    /** {@code bug_info["test_method"]} — {@code bug.get("test_method", "unknown")}. */
    public String testMethod;

    /** {@code bug_info["bug_type"]} — {@code bug.get("type", "unknown")}; see class doc. */
    public String bugType;

    /** {@code bug_info["error"]} — {@code bug.get("error", "")}. */
    public String error;

    /** {@code bug_info["severity"]} — {@code bug.get("severity", "medium")}. */
    public String severity;

    /**
     * {@code bug_info["method_code"]} — extracted from the state's test code via the
     * (brace-counter) method extractor; {@code ""} when extraction failed.
     */
    public String methodCode;

    /** {@code bug_info["found_in_iteration"]}. */
    public int foundInIteration;

    /** {@code bug_info["original_test_code"]} — the full test code of the detecting state. */
    public String originalTestCode;

    /** {@code bug_info["bug_signature"]} — D7 signature ({@link BugSignature}). */
    public String bugSignature;

    /**
     * {@code bug.get("verified", False)} at read sites ({@code fa_mcts.py:1216}). The D7
     * {@code bug_info} dict carries NO {@code "verified"} key ({@code fa_mcts.py:3044-3062})
     * — NOT even for assertion failures the evaluator pre-marked verified
     * ({@code test_state.py:181}) — so D7-collected bugs always stay {@code false} here
     * and are always queued for batch verification.
     */
    public boolean verified;
}
