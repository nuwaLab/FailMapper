package org.failmapper.search;

/**
 * One verified bug method — the Java counterpart of the {@code method_result} dict
 * produced by the live verification path ({@code bug_verifier.py:198-206}): the
 * {@code method_info} contents merged with
 * {@code verified/is_real_bug/verification_confidence/verification_reasoning}.
 *
 * <p>Mutable with public fields, mirroring the Python dict merge.
 */
public final class VerifiedBugMethod {

    /** {@code method_info["method_name"]}. */
    public String methodName;

    /** {@code method_info["code"]}; null when no method code was extractable. */
    public String methodCode;

    /** {@code method_info["bug_signature"]} (or the verifier's fallback signature). */
    public String bugSignature;

    /** First bug's type for reporting ({@code method['bug_info'][0].get('bug_type','unknown')}). */
    public String bugType;

    /** {@code method_result["verified"]} — always true after the verifier ran. */
    public boolean verified;

    /** {@code method_result["is_real_bug"]}. */
    public boolean isRealBug;

    /** {@code method_result["verification_confidence"]} (0-1 scale, capped 0.95 per F15). */
    public double verificationConfidence;

    /** {@code method_result["verification_reasoning"]}. */
    public String verificationReasoning;

    /** {@code bug_method["found_in_iteration"]} carried from the first grouped bug. */
    public int foundInIteration;
}
