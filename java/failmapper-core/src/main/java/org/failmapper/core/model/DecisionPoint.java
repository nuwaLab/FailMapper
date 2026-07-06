package org.failmapper.core.model;

/** A decision point (branching construct) in a method; {@code kind} keeps Python vocabulary. */
public record DecisionPoint(String method, int line, String kind, String expression) {
}
