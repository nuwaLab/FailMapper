package org.failmapper.core.model;

import java.util.List;

/** A logical/relational expression occurrence (feeds expression_test actions, contract D1). */
public record LogicalOperation(String method, int line, String expression, List<String> operators) {
}
