package io.github.mbackschat.a12.dm.edit;

import java.util.Map;

import io.github.mbackschat.a12.dm.modelkit.FieldTypeSpec;
import io.github.mbackschat.a12.dm.rulekit.dsl.FieldRef;

/**
 * L2c — a session-bound editor over one existing field: the field-attribute edits (re-type, per-locale
 * label, annotation) fluently on the session — which {@code EditSession} otherwise did not expose — plus
 * an explicit {@link #ref()} downgrade to the pure {@link FieldRef}. Obtain from {@link
 * EditSession#field(String)}.
 *
 * <p>A field is a leaf, so there is no navigation; for a typed operand in a rule, navigate from the
 * enclosing group via {@code GroupEditor} (which yields the typed {@code NumberRef}/{@code StringRef}/…).
 * (Requiredness is fixed at creation today — re-setting it on an existing field needs new {@code
 * ModelDocument} support and is deferred; see proposal §18.7.)
 */
public final class FieldEditor {

    private final EditSession session;
    private final FieldRef location;

    FieldEditor(EditSession session, FieldRef location) {
        this.session = session;
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

    /** Set a name/value annotation on this field. Chains. */
    public FieldEditor setAnnotation(String name, String value) {
        session.setAnnotation(location.path(), name, value);
        return this;
    }
}
