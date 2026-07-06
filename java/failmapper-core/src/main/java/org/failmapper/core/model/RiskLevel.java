package org.failmapper.core.model;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

/**
 * Risk level of a failure scenario. Sort weights per contract C48
 * (failure_scenarios.py:104): high=3, medium=2, low=1, critical=0.
 */
public enum RiskLevel {
    CRITICAL("critical", 0),
    HIGH("high", 3),
    MEDIUM("medium", 2),
    LOW("low", 1);

    private final String wire;
    private final int sortWeight;

    RiskLevel(String wire, int sortWeight) {
        this.wire = wire;
        this.sortWeight = sortWeight;
    }

    @JsonValue
    public String wire() {
        return wire;
    }

    public int sortWeight() {
        return sortWeight;
    }

    @JsonCreator
    public static RiskLevel fromWire(String value) {
        for (RiskLevel level : values()) {
            if (level.wire.equalsIgnoreCase(value)) {
                return level;
            }
        }
        return MEDIUM;
    }
}
