package io.github.mbackschat.a12.dm.edit;

import io.github.mbackschat.a12.dm.modelkit.FieldTypeSpec;

/**
 * L2c — a held reference to a declared type definition (by id), so re-configuring its underlying type or
 * removing it doesn't re-type the id. Obtain from {@link EditSession#typeDef(String)}. Existence is
 * checked when the edit applies, not at construction.
 */
public final class TypeDefEditor {

    private final EditSession session;
    private final String id;

    TypeDefEditor(EditSession session, String id) {
        this.session = session;
        this.id = id;
    }

    /** This type definition's id. */
    public String id() {
        return id;
    }

    /** Re-configure the underlying type (scale, pattern, enum, …). Chains. */
    public TypeDefEditor setType(FieldTypeSpec type) {
        session.setTypeDefinitionType(id, type);
        return this;
    }

    /** Remove this type definition; returns the session ({@code validate()} catches a field still using it). */
    public EditSession remove() {
        return session.removeTypeDefinition(id);
    }
}
