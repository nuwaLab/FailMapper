package org.failmapper.search;

/**
 * A detected-bug record — the Java counterpart of the mutable Python bug dict that flows
 * through {@code state.detected_bugs} / {@code state.logical_bugs}
 * ({@code test_state.py:175-198, 349-378}).
 *
 * <p>Deliberately a MUTABLE class with public fields, mirroring the Python dict:
 * {@code classify_logical_bugs} (D11) mutates {@code bug_category}/{@code bug_type}/
 * {@code logic_confidence} in place on the same object referenced from both lists.
 *
 * <p>Null fields model absent dict keys; consumers apply the Python {@code .get} default
 * at each read site because the defaults DIFFER per site (e.g. {@code bug_type} defaults
 * to {@code "unknown"} in F9 evidence matching but {@code ""} in F6 reward accrual;
 * {@code confidence} defaults to 0.5 in verification, D10).
 *
 * <p>{@code isRealBug} is tri-state per contract S5 ({@code test_state.py:684}):
 * null = unverified, FALSE = verified false positive, TRUE = verified real bug.
 */
public final class DetectedBug {

    /** Python {@code bug["type"]} — the failure kind, e.g. {@code "assertion_failure"}. */
    public String type;

    /** Python {@code bug["description"]} — raw failure text. */
    public String description;

    /** Python {@code bug["test_method"]}. */
    public String testMethod;

    /** Python {@code bug["error"]} — e.g. {@code "AssertionError"} or a throwable FQN. */
    public String error;

    /** Python {@code bug["severity"]} — defaults to "medium" where read. */
    public String severity;

    /** Python {@code bug["verified"]} ({@code .get(..., False)} at read sites). */
    public boolean verified;

    /** Tri-state per contract S5; see class doc. */
    public Boolean isRealBug;

    /** Python {@code bug["bug_category"]} — "logical" or "general", set by D11. */
    public String bugCategory;

    /** Python {@code bug["bug_type"]} — classifier output (D11), e.g. "incorrect_value". */
    public String bugType;

    /** Python {@code bug["logic_confidence"]} — D11 confidence; null = key absent. */
    public Double logicConfidence;

    /** Python {@code bug["confidence"]} — pre-verification confidence; null = key absent. */
    public Double confidence;

    public DetectedBug() {
    }

    public DetectedBug(String type, String description, String testMethod, String error, String severity) {
        this.type = type;
        this.description = description;
        this.testMethod = testMethod;
        this.error = error;
        this.severity = severity;
    }

    /** Python {@code bug.get("test_method", "")}. */
    public String testMethodOrEmpty() {
        return testMethod == null ? "" : testMethod;
    }

    /** Python {@code bug.get("error", "")}. */
    public String errorOrEmpty() {
        return error == null ? "" : error;
    }

    /** Python {@code bug.get("description", "")}. */
    public String descriptionOrEmpty() {
        return description == null ? "" : description;
    }
}
