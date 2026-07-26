package io.github.mbackschat.a12.dm.adapter;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import io.github.mbackschat.a12.dm.modelkit.DocumentModelDraft;
import io.github.mbackschat.a12.dm.modelkit.FieldKind;

/** Kernel admission basis for the portable workspace's stricter Unicode-scalar source contract. */
class WorkspaceSourceUnicodeKernelTest {

    @Test
    void kernelAcceptsAnEscapedUnpairedSurrogateInAGroupName() {
        var model = DocumentModelDraft.named("permit").locale("en_US")
                .rootGroup("Permit", permit -> permit.field("Description", FieldKind.STRING))
                .build()
                .toJson();
        var escapedHighSurrogate = "\\" + "uD800";
        var source = model.replace(
                "\"name\" : \"Permit\"",
                "\"name\" : \"" + escapedHighSurrogate + "\"");
        assertTrue(source.contains("\"name\" : \"" + escapedHighSurrogate + "\""),
                "the typed fixture replacement must inject the escaped surrogate");

        var expanded = KernelModelOracle.ofSelfContained(source);
        var decodedHighSurrogate = Character.toString((char) 0xd800);

        assertEquals(2, expanded.paths().size(), "the Kernel accepts and expands the malformed scalar");
        assertTrue(expanded.paths().contains("/" + decodedHighSurrogate));
        assertTrue(expanded.paths().contains("/" + decodedHighSurrogate + "/Description"));
    }
}
