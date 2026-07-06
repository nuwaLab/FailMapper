package org.failmapper.core.model;

/**
 * A structured compiler diagnostic (replaces console-text scraping; source is
 * javax.tools DiagnosticListener in failmapper-exec).
 */
public record Diagnostic(Kind kind, String source, long line, long column, String message) {

    public enum Kind { ERROR, WARNING, NOTE }
}
