package org.failmapper.core.model;

import java.util.List;

/** A declared field; {@code initializer} is the source text of the initializer or null. */
public record FieldModel(String name, String type, List<String> modifiers, String initializer) {
}
