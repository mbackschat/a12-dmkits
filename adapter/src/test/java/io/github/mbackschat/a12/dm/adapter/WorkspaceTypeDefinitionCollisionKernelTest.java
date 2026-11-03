package io.github.mbackschat.a12.dm.adapter;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Map;

import org.junit.jupiter.api.Test;

import com.mgmtp.a12.kernel.md.model.api.fieldtypes.IStringType;
import com.mgmtp.a12.kernel.md.model.api.services.DocumentModelExpansionException;

import io.github.mbackschat.a12.dm.core.ConsistencyCheckFailedException;
import io.github.mbackschat.a12.dm.core.ReferenceResolvers;

import io.github.mbackschat.a12.dm.modelkit.DocumentModelDraft;

/**
 * Measured basis for the portable workspace's ambiguity rules (KERNEL-FINDINGS KF647). Kernel 31.1.1 accepts distinct
 * imported providers only when their definitions are equal, and refuses conflicting ones while it expands the
 * importer. Two local definitions sharing an id are read, the field binding the later one, but A12 cannot build the
 * model its consistency check runs on. The host's refusal of every cross-provider collision, equal ones included,
 * stays an explicit determinism rule: for equal payloads it is stricter than A12.
 */
class WorkspaceTypeDefinitionCollisionKernelTest {

    @Test
    void kernelRefusesConflictingImportedTypeDefinitionIdsWhileExpanding() {
        var sources = Map.of(
                "short-types", typeProvider("short-types", 8),
                "long-types", typeProvider("long-types", 16));

        for (var order : java.util.List.of(new String[] {"short-types", "long-types"}, new String[] {"long-types", "short-types"})) {
            var refused = assertThrows(DocumentModelExpansionException.class,
                    () -> KernelModelOracle.of(importedConsumer(order[0], order[1]), sources::get));
            assertTrue(refused.getMessage().contains("The type definition id 'SharedCode' already exists and is different."),
                    refused.getMessage());
        }
    }

    @Test
    void kernelAlsoAcceptsByteEquivalentDefinitionsFromDistinctImportedProviders() {
        var shortTypes = typeProvider("short-types", 8);
        var equivalentTypes = typeProvider("long-types", 8);
        var effective = assertInstanceOf(
                IStringType.class,
                KernelModelOracle.of(
                        importedConsumer(),
                        Map.of(
                                "short-types", shortTypes,
                                "long-types", equivalentTypes)::get)
                        .fieldType("/Permit/Code"));

        assertEquals(8, effective.getMaxLength().orElseThrow());
    }

    @Test
    void kernelReadsDuplicateLocalDefinitionIdsButCannotCheckThem() {
        var entry = DocumentModelDraft.named("permit").locale("en_US")
                .typeDef("SharedCode", type -> type.string(string -> string.maxLength(8)))
                .typeDef("SharedCode", type -> type.string(string -> string.maxLength(16)))
                .rootGroup("Permit", permit -> permit.typedefField("Code", "SharedCode"))
                .build()
                .toJson();

        var bound = assertInstanceOf(IStringType.class, KernelModelOracle.ofSelfContained(entry).fieldType("/Permit/Code"));
        assertEquals(16, bound.getMaxLength().orElseThrow(), "the field binds the later definition");
        var unchecked = assertThrows(ConsistencyCheckFailedException.class,
                () -> new KernelConsistencyOracle().checkConsistency(entry, ReferenceResolvers.selfContained()));
        assertTrue(unchecked.getMessage().contains("Duplicate key SharedCode"), unchecked.getMessage());
    }

    private static String importedConsumer() {
        return importedConsumer("short-types", "long-types");
    }

    private static String importedConsumer(String firstProvider, String secondProvider) {
        return DocumentModelDraft.named("permit").locale("en_US")
                .importTypeDefs(firstProvider)
                .importTypeDefs(secondProvider)
                .rootGroup("Permit", permit -> permit.typedefField("Code", "SharedCode"))
                .build()
                .toJson();
    }

    private static String typeProvider(String modelId, int maxLength) {
        return DocumentModelDraft.typeDefModel(modelId).locale("en_US")
                .typeDef("SharedCode", type -> type.string(string -> string.maxLength(maxLength)))
                .build()
                .toJson();
    }
}
