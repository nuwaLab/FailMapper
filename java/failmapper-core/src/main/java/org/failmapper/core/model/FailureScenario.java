package org.failmapper.core.model;

/**
 * A detected failure scenario (Python: FS_Detector pattern record, contract D12).
 * {@code type}/{@code subtype} keep the Python detector vocabulary verbatim
 * (operator_precedence, off_by_one, null_handling, ...) — the pattern->strategy
 * routing table (contract D2) and pattern ids key on them.
 *
 * The pattern id used in covered-pattern sets is "{type}_{line}"
 * (contract: fa_mcts.py / test_state.py "{p['type']}_{p['location']}").
 */
public record FailureScenario(
        String type,
        String subtype,
        int line,
        RiskLevel riskLevel,
        String code,
        String description) {

    public String patternId() {
        return type + "_" + line;
    }
}
