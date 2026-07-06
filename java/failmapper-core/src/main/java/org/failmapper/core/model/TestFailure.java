package org.failmapper.core.model;

/**
 * One failing test method. {@code assertionFailure} is decided by
 * instanceof AssertionError at execution time (typed, contract layer-B fix for
 * the Gradle 'expected'-keyword misclassification) — never by text matching.
 */
public record TestFailure(
        String testClass,
        String testMethod,
        boolean assertionFailure,
        String throwableClass,
        String message,
        String stackTrace) {
}
