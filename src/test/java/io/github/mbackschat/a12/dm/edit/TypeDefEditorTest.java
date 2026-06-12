package io.github.mbackschat.a12.dm.edit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import org.junit.jupiter.api.Test;

import io.github.mbackschat.a12.dm.Fixtures;
import io.github.mbackschat.a12.dm.modelkit.FieldKind;
import io.github.mbackschat.a12.dm.modelkit.FieldTypeSpec;
import io.github.mbackschat.a12.dm.modelkit.ModelDocument;

/**
 * L2c — the {@link TypeDefEditor}: a held reference to a declared type definition (by id), so
 * re-configuring or removing it doesn't re-type the id. From {@link EditSession#typeDef(String)}.
 */
class TypeDefEditorTest {

    @Test
    void reconfiguresAndRemovesATypeDefByHeldReference() {
        EditSession s = EditSession.read(Fixtures.subscriptionJson());
        s.addTypeDefinition("Money", FieldKind.NUMBER);                 // bare to start
        s.group("/Subscription/Billing").addTypedefField("Fee", "Money");
        s.validate();

        // re-configure the type-def to scale-2, by held reference — the change flows to the typed field
        s.typeDef("Money").setType(new FieldTypeSpec.NumberSpec(2, null, null));
        s.validate();
        assertEquals(2, s.group("/Subscription/Billing").num("Fee").scale());

        // remove it (after dropping the field that uses it — the gate rejects a dangling type ref)
        s.removeField("/Subscription/Billing/Fee");
        s.typeDef("Money").remove();
        s.validate();
        assertFalse(ModelDocument.read(s.toJson()).typeDefinitionIds().contains("Money"));
    }
}
