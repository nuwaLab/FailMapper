package org.failmapper.core.model;

/** A method/constructor parameter; {@code type} is the resolved textual type (generics preserved). */
public record ParameterModel(String name, String type) {
}
