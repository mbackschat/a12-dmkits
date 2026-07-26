package io.github.mbackschat.a12.dm.adapter;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.Map;

import org.junit.jupiter.api.Test;

import com.mgmtp.a12.kernel.md.model.api.fieldtypes.IStringType;

import io.github.mbackschat.a12.dm.modelkit.DocumentModelDraft;

/**
 * Measured basis for the portable workspace's ambiguity rules. Kernel 30.8.1 rejects duplicate local ids, but
 * accepts distinct imported providers with either equal or conflicting payloads and selects the first provider.
 * The host's cross-provider refusal is therefore an explicit determinism rule rather than a Kernel-parity claim.
 */
class WorkspaceTypeDefinitionCollisionKernelTest {

    @Test
    void kernelAcceptsCollidingImportedTypeDefinitionIdsAndSelectsTheFirstImport() {
        var shortTypes = typeProvider("short-types", 8);
        var longTypes = typeProvider("long-types", 16);
        var sources = Map.of(
                "short-types", shortTypes,
                "long-types", longTypes);

        var shortFirst = assertInstanceOf(
                IStringType.class,
                KernelModelOracle.of(
                        importedConsumer("short-types", "long-types"),
                        sources::get)
                        .fieldType("/Permit/Code"));
        var longFirst = assertInstanceOf(
                IStringType.class,
                KernelModelOracle.of(
                        importedConsumer("long-types", "short-types"),
                        sources::get)
                        .fieldType("/Permit/Code"));

        assertEquals(8, shortFirst.getMaxLength().orElseThrow());
        assertEquals(16, longFirst.getMaxLength().orElseThrow());
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
    void kernelRejectsDuplicateLocalDefinitionIds() {
        var entry = DocumentModelDraft.named("permit").locale("en_US")
                .typeDef("SharedCode", type -> type.string(string -> string.maxLength(8)))
                .typeDef("SharedCode", type -> type.string(string -> string.maxLength(16)))
                .rootGroup("Permit", permit -> permit.typedefField("Code", "SharedCode"))
                .build()
                .toJson();

        assertThrows(IllegalArgumentException.class, () -> KernelModelOracle.ofSelfContained(entry));
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
