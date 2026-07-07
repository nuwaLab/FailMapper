package org.failmapper.search;

/**
 * One uncovered source line offered to action generation — the Java counterpart of the
 * Python {@code uncovered_data["uncovered_lines"]} entry dict
 * ({@code fa_mcts.py:214-242}: {@code line_info.get("line", 0)} /
 * {@code line_info.get("content", "")}).
 */
public record UncoveredLine(int line, String content) {

    /** Python {@code line_info.get("content", "")}. */
    public String contentOrEmpty() {
        return content == null ? "" : content;
    }
}
