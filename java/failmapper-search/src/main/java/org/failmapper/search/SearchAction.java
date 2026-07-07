package org.failmapper.search;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * An MCTS action — the Java counterpart of the Python action dict
 * (e.g. {@code {"type": "boundary_test", "line": 42, ...}}).
 *
 * <p>Value equality is by CONTENTS ({@code type} + {@code attributes}), matching Python
 * dict equality: contract S2 — {@code fa_mcts.py:385} dedupes possible actions against
 * {@code used_action} by deep dict-value equality, so reference identity here would
 * silently re-offer used actions every expansion. {@link java.util.Map#equals} is
 * order-independent contents equality, exactly like Python {@code dict.__eq__}.
 *
 * <p>Attributes are kept insertion-ordered (LinkedHashMap) because action dicts flow
 * into ordered structures downstream (contract section 3.2).
 *
 * @param type       the action type string ({@code action['type']}); may be null to model
 *                   a Python action dict lacking the 'type' key
 * @param attributes remaining key/value pairs of the action dict (never null; defensively
 *                   copied and unmodifiable)
 */
public record SearchAction(String type, Map<String, Object> attributes) {

    public SearchAction {
        attributes = attributes == null
                ? Collections.emptyMap()
                : Collections.unmodifiableMap(new LinkedHashMap<>(attributes));
    }

    public static SearchAction of(String type) {
        return new SearchAction(type, Collections.emptyMap());
    }
}
