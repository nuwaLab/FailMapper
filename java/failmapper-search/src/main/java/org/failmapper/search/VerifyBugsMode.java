package org.failmapper.search;

/**
 * When detected bugs are verified with the LLM.
 *
 * <p>Contract C5 — Python default {@code 'batch'} ({@code fa_mcts.py:588},
 * docstring {@code fa_mcts.py:607}: "when to verify bugs (immediate/batch/none)").
 * {@code IMMEDIATE} verifies bugs during state evaluation; {@code BATCH} defers all
 * verification to the end of the search via {@code verify_all_potential_bugs};
 * {@code NONE} skips verification.
 */
public enum VerifyBugsMode {
    IMMEDIATE,
    BATCH,
    NONE
}
