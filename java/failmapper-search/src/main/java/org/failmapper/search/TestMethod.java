package org.failmapper.search;

/**
 * One parsed test method — the Java counterpart of the Python test-method dict
 * {@code {"name": ..., "code": ...}} held in {@code state.test_methods}
 * ({@code test_state.py} reads it with {@code m.get("name", "")} / {@code m.get("code", "")}).
 *
 * <p>Null components model a Python dict lacking that key; consumers apply the
 * Python {@code .get(..., "")} default via {@link #nameOrEmpty()} / {@link #codeOrEmpty()}.
 */
public record TestMethod(String name, String code) {

    /** Python {@code m.get("name", "")}. */
    public String nameOrEmpty() {
        return name == null ? "" : name;
    }

    /** Python {@code m.get("code", "")}. */
    public String codeOrEmpty() {
        return code == null ? "" : code;
    }
}
