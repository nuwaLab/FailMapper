package org.failmapper.core.model;

/**
 * Contract F11 (extractor.py:740-752):
 *   cyclomatic = decisionCount + 1
 *   cognitive  = decisionCount + logicalOpCount + 2 * nestedCount
 * Target-method ordering sorts by cognitive + cyclomatic (contract O5).
 */
public record MethodComplexity(int cyclomatic, int cognitive) {

    public static MethodComplexity of(int decisionCount, int logicalOpCount, int nestedCount) {
        return new MethodComplexity(
                decisionCount + 1,
                decisionCount + logicalOpCount + 2 * nestedCount);
    }

    public int sortKey() {
        return cyclomatic + cognitive;
    }
}
