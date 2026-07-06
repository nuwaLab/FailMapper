package org.failmapper.core.model;

import java.util.List;
import java.util.Map;

/**
 * The failure model of a class under test (Python: extractor.Extractor / "f_model").
 * An empty model (all lists empty) is the null-object used when the source is
 * missing or unparseable — the pipeline continues with it (failmapper.py behavior).
 *
 * methodComplexity keeps insertion order = source declaration order; complexity
 * ties in target-method selection are broken by this order (contract O5) —
 * implementations must supply a LinkedHashMap.
 */
public record FailureModel(
        String classFqn,
        List<BoundaryCondition> boundaryConditions,
        List<LogicalOperation> operations,
        List<DecisionPoint> decisionPoints,
        Map<String, MethodComplexity> methodComplexity) {

    public static FailureModel empty(String classFqn) {
        return new FailureModel(classFqn, List.of(), List.of(), List.of(), Map.of());
    }

    public boolean isEmpty() {
        return boundaryConditions.isEmpty() && operations.isEmpty();
    }
}
