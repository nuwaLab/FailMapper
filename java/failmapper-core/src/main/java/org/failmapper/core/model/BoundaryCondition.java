package org.failmapper.core.model;

/**
 * A branch/loop condition extracted from the class under test.
 *
 * {@code type} keeps the Python extractor vocabulary verbatim
 * (if_condition, while_loop, for_loop, do_while_loop, for_each_loop,
 * switch_statement, ...) because strategy routing and coverage counting
 * key on these exact strings (contract D2/D3, F10).
 *
 * The condition id used in covered-branch sets is "{method}_{line}"
 * (contract: test_state.py:565).
 */
public record BoundaryCondition(String method, int line, String type, String expression) {

    public String conditionId() {
        return method + "_" + line;
    }
}
