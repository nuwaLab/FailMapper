package org.failmapper.search;

import java.util.List;

/**
 * One test method queued for batch bug verification — the Java counterpart of the
 * {@code method_info} dict built by {@code verify_all_potential_bugs}
 * ({@code fa_mcts.py:1264-1273}):
 * <pre>
 * {"method_name", "code", "bug_info", "bug_descriptions", "bug_signature"}
 * </pre>
 *
 * @param methodName      {@code bug.get("test_method", "unknown")} grouping key
 * @param methodCode      the first non-empty {@code method_code} among the method's bugs,
 *                        or null when none was extractable ({@code fa_mcts.py:1239-1250};
 *                        Python leaves {@code method_code = None})
 * @param bugInfo         the (unverified) bugs grouped under this method, insertion order
 * @param bugDescriptions {@code "{bug_type}: {error[:100](+...)}"} per bug
 * @param bugSignature    {@code bugs[0].get("bug_signature")} — the FIRST bug's signature
 *                        ({@code fa_mcts.py:1270}, contract O6)
 */
public record MethodToVerify(
        String methodName,
        String methodCode,
        List<PotentialBug> bugInfo,
        List<String> bugDescriptions,
        String bugSignature) {
}
