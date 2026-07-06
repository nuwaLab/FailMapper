package org.failmapper.core.model;

import java.util.List;

/**
 * A declared constructor. Visibility matters downstream: the anti-mock policy
 * needs to know whether a real instance can be constructed at all.
 */
public record ConstructorModel(List<ParameterModel> parameters, List<String> modifiers) {

    public boolean isPublic() {
        return modifiers.contains("public");
    }
}
