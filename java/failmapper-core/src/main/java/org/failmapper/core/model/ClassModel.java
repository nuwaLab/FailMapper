package org.failmapper.core.model;

import java.util.List;

/**
 * Structural model of a class under test (Python: class_analyzer/file_analyzer output).
 * Keyed by FQN end-to-end — never by simple name (contract root-cause fix for
 * same-simple-name collisions).
 *
 * {@code kind} is one of: class, interface, enum, record, annotation.
 */
public record ClassModel(
        String fqn,
        String packageName,
        String simpleName,
        String kind,
        boolean isAbstract,
        String superclass,
        List<String> interfaces,
        List<FieldModel> fields,
        List<ConstructorModel> constructors,
        List<MethodModel> methods,
        List<String> imports,
        String sourcePath) {

    public boolean hasPublicConstructor() {
        return constructors.isEmpty() || constructors.stream().anyMatch(ConstructorModel::isPublic);
    }
}
