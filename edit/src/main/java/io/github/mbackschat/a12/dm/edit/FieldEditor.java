package io.github.mbackschat.a12.dm.edit;

import java.util.Map;

import io.github.mbackschat.a12.dm.modelkit.FieldModification;
import io.github.mbackschat.a12.dm.modelkit.FieldTypeSpec;
import io.github.mbackschat.a12.dm.modelkit.Requiredness;
import io.github.mbackschat.a12.dm.rulekit.dsl.FieldRef;

/**
 * L2c — a session-bound editor over one existing field: the field-attribute edits (re-type, per-locale
 * label, annotation, requiredness) fluently on the session — which {@code EditSession} otherwise did not
 * expose — plus an explicit {@link #ref()} downgrade to the pure {@link FieldRef}. Obtain from {@link
 * EditSession#field(String)}.
 *
 * <p>A field is a leaf, so there is no navigation; for a typed operand in a rule, navigate from the
 * enclosing group via {@code GroupEditor} (which yields the typed {@code NumberRef}/{@code StringRef}/…).
 */
public final class FieldEditor extends ElementMetadataEditor<FieldEditor> {

    private final FieldRef location;

    FieldEditor(EditSession session, FieldRef location) {
        super(session, location.path());
        this.location = location;
    }

    /** Downgrade to the pure field ref (a rule's error field, an operand). */
    public FieldRef ref() {
        return location;
    }

    /** Re-type this field — scale, bounds, pattern, enum domain, … via a {@link FieldTypeSpec}. Chains. */
    public FieldEditor setType(FieldTypeSpec type) {
        session.setFieldType(location.path(), type);
        return this;
    }

    /** Set this field's per-locale display labels (once any locale is set, all declared ones are required). Chains. */
    public FieldEditor setLabel(Map<String, String> localeToText) {
        session.setLabel(location.path(), localeToText);
        return this;
    }

    /** Set this field's requiredness ({@link Requiredness#OPTIONAL} clears it). Chains. */
    public FieldEditor setRequiredness(Requiredness requiredness) {
        session.setRequiredness(location.path(), requiredness);
        return this;
    }

    /** Set this field's per-locale custom REQUIRED-empty error (G23); the field must already be required and
     *  every declared locale must be covered. Chains. */
    public FieldEditor setRequiredErrorMessages(Map<String, String> localeToText) {
        session.setRequiredErrorMessages(location.path(), localeToText);
        return this;
    }

    /** Set this field's per-locale helper text (need not cover every locale, unlike a label). Chains. */
    public FieldEditor setHelperText(Map<String, String> localeToText) {
        session.setHelperText(location.path(), localeToText);
        return this;
    }

    /** Set this field's {@code global} flag (shared across repetitions); {@code false} clears it. Chains. */
    public FieldEditor setGlobal(boolean global) {
        session.setGlobal(location.path(), global);
        return this;
    }

    /** Set this field's {@code transient} flag (not persisted); {@code false} clears it. Chains. */
    public FieldEditor setTransient(boolean isTransient) {
        session.setTransient(location.path(), isTransient);
        return this;
    }

    /** Apply a partial {@link FieldModification} (type and/or metadata) in place — nothing it carries is dropped,
     *  absent aspects are left untouched. Chains. */
    public FieldEditor modify(FieldModification modification) {
        session.modifyField(location.path(), modification);
        return this;
    }
}
