package io.github.mbackschat.a12.dm.cli.interp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

import io.github.mbackschat.a12.dm.core.ModelResolver;
import io.github.mbackschat.a12.dm.modelkit.DocumentModelDraft;
import io.github.mbackschat.a12.dm.modelkit.FieldKind;

class PortableModelPreparationTest {

    @Test
    void providerSuppliesOnlyTheDiscoveredExactIdClosure() {
        String included = DocumentModelDraft.named("included").locale("en_US")
                .rootGroup("Included", group -> group.field("Value", FieldKind.STRING))
                .build().toJson();
        String entry = DocumentModelDraft.named("entry").locale("en_US")
                .rootGroup("Entry", group -> group.mount("Mount", "included"))
                .build().toJson();
        String unused = DocumentModelDraft.named("unused").locale("en_US")
                .rootGroup("Unused", group -> group.field("Value", FieldKind.STRING))
                .build().toJson();
        List<String> requests = new ArrayList<>();
        ModelResolver resolver = modelId -> {
            requests.add(modelId);
            return switch (modelId) {
                case "included" -> included;
                case "unused" -> unused;
                default -> null;
            };
        };

        String expanded = PortableModelPreparation.expand(entry, "entry.dm.json", resolver);

        assertEquals(List.of("included"), requests);
        assertTrue(expanded.contains("\"Mount\""));
        assertFalse(expanded.contains("\"Unused\""));
    }
}
