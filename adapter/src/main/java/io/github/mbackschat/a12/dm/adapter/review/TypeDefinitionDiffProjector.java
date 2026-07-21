package io.github.mbackschat.a12.dm.adapter.review;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import io.github.mbackschat.a12.dm.core.ValueKind;
import io.github.mbackschat.a12.dm.modelkit.ModelDocument;

/** Projects reusable type definitions without resolving or expanding their consumers. */
final class TypeDefinitionDiffProjector {

    private TypeDefinitionDiffProjector() {}

    static Map<String, Map<String, String>> project(ModelDiffState state) {
        Map<String, Map<String, String>> definitions = new LinkedHashMap<>();
        state.document().typeDefinitions().forEach((id, definition) -> definitions.put(id, aspects(definition)));
        return definitions;
    }

    static List<String> reasons(ModelDiff.ChangeKind change, List<ModelDiff.Delta> deltas) {
        return switch (change) {
            case ADDED -> List.of("TYPEDEF_ADDED");
            case REMOVED -> List.of("TYPEDEF_REMOVED");
            case MODIFIED -> {
                List<String> reasons = new ArrayList<>();
                deltas.forEach(delta -> reasons.add(FieldDiffProjector.modifiedReason(delta)));
                yield reasons;
            }
            case REORDERED, RENAMED, MOVED -> List.of();
        };
    }

    static String modifiedReason(ModelDiff.Delta delta) {
        return FieldDiffProjector.modifiedReason(delta);
    }

    private static Map<String, String> aspects(ModelDocument.TypeDefinitionState definition) {
        Map<String, String> aspects = new LinkedHashMap<>();
        aspects.put("declaredType", definition.type().jsonType());
        aspects.put("kind", ValueKind.fromKernelType(definition.type().jsonType()).name());
        FieldDiffProjector.putType(aspects, definition.type());
        FieldDiffProjector.putDescriptive(aspects, definition.meta());
        return aspects;
    }
}
