package org.failmapper.exec;

import java.util.List;

import org.failmapper.core.model.Diagnostic;

/**
 * Outcome of one in-memory compilation. Diagnostics are the structured
 * javax.tools facts (kind/line/column/message) — the contract fix for the
 * Python port's console-text scraping: the compiler's DiagnosticListener is
 * the ONLY source of compilation errors, never process output.
 */
public record CompileResult(boolean success, List<Diagnostic> diagnostics) {

    /** Errors only (warnings and notes filtered out). */
    public List<Diagnostic> errors() {
        return diagnostics.stream()
                .filter(d -> d.kind() == Diagnostic.Kind.ERROR)
                .toList();
    }
}
