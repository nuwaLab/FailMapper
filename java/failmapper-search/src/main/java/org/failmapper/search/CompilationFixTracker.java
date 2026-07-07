package org.failmapper.search;

import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * Global compilation-fix bookkeeping of the FA_MCTS instance
 * ({@code fa_mcts.py:659-661}): {@code global_compilation_fix_attempts} counter (gated
 * by C7 MAX_FIX_ATTEMPTS = 10 in D1) and the {@code failed_fix_paths} set of
 * {@code "->"}-joined action-type path signatures ({@link FaMctsNode#pathSignature()}),
 * populated by the orchestrator when a fix attempt leaves compilation errors behind
 * ({@code fa_mcts.py:2762-2768}).
 */
public final class CompilationFixTracker {

    private int globalAttempts = 0;
    private final LinkedHashSet<String> failedFixPaths = new LinkedHashSet<>();

    /** {@code self.global_compilation_fix_attempts}. */
    public int globalAttempts() {
        return globalAttempts;
    }

    /** {@code mcts_instance.global_compilation_fix_attempts += 1} ({@code fa_mcts.py:148}). */
    public void incrementGlobalAttempts() {
        globalAttempts += 1;
    }

    /** {@code path_signature in mcts_instance.failed_fix_paths} ({@code fa_mcts.py:143}). */
    public boolean pathFailed(String pathSignature) {
        return failedFixPaths.contains(pathSignature);
    }

    /** {@code self.failed_fix_paths.add(...)} ({@code fa_mcts.py:2767}). */
    public void markPathFailed(String pathSignature) {
        failedFixPaths.add(pathSignature);
    }

    /** Insertion-ordered view for reporting. */
    public Set<String> failedFixPaths() {
        return Collections.unmodifiableSet(failedFixPaths);
    }
}
