package io.github.mbackschat.a12.dm.modelkit;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Field-element attributes — the {@link ElementMeta} describe/annotate surface <em>plus</em> the
 * field-only slots {@code required(...)}, per-locale {@code label(...)}, {@code global}/{@code transient}
 * flags, and {@code helperText(...)}.
 */
public abstract class FieldAttrs<SELF extends FieldAttrs<SELF>> extends ElementMeta<SELF> {
    Requiredness requiredness = Requiredness.OPTIONAL;
    final Map<String, String> labels = new LinkedHashMap<>();
    boolean global;
    boolean notPersisted;
    final Map<String, String> helperText = new LinkedHashMap<>();

    public SELF required() {
        requiredness = Requiredness.REQUIRED;
        return self();
    }

    public SELF requiredIfParentPresent() {
        requiredness = Requiredness.REQUIRED_IF_PARENT_PRESENT;
        return self();
    }

    /** A per-locale display label; once any locale is set, all declared locales are required. */
    public SELF label(String locale, String text) {
        labels.put(locale, text);
        return self();
    }

    /** Mark the field {@code global} — passed to partial validation (a kernel field flag, §3.5). */
    public SELF global() {
        this.global = true;
        return self();
    }

    /** Mark the field {@code transient} — present at runtime but not persisted (a kernel field flag, §3.5). */
    public SELF notPersisted() {
        this.notPersisted = true;
        return self();
    }

    /** Per-locale helper text (inline UI help shown alongside the field). */
    public SELF helperText(String locale, String text) {
        helperText.put(locale, text);
        return self();
    }

    /** The kind-specific type spec this builder configured — implemented per field kind. */
    protected abstract FieldTypeSpec spec();

    /**
     * Assemble the configured field — type spec <em>and</em> all element metadata (requiredness,
     * labels, annotations, descriptions, flags, helper text) — as one {@link ElementDraft.Field}. The
     * single assembly point shared by the from-scratch {@code GroupBuilder} and the edit-surface
     * {@code GroupEditor}, so authoring and editing produce a byte-identical field.
     */
    public ElementDraft.Field toField(String name) {
        return new ElementDraft.Field(name, spec(), requiredness, Map.copyOf(labels),
                List.copyOf(annotations), global, notPersisted, Map.copyOf(internalDescription),
                Map.copyOf(externalDescription), Map.copyOf(helperText));
    }
}
