package org.failmapper.core.model;

import java.util.List;

/** A declared method of the class under test. Line numbers are 1-based source positions. */
public record MethodModel(
        String name,
        List<ParameterModel> parameters,
        String returnType,
        List<String> modifiers,
        List<String> thrownExceptions,
        boolean isOverride,
        int startLine,
        int endLine) {
}
