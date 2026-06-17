package io.github.mbackschat.a12.dm.modelkit;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Element metadata shared by every <b>describable</b> element builder — name/value
 * {@code annotation(...)} and the per-locale {@code internalDescription(...)} /
 * {@code externalDescription(...)} (the kernel's {@code IDescribed} + {@code IAnnotated}, carried by
 * {@code IElement} and {@code IFieldTypeDefinition}). Factored out so fields, groups, and type-defs
 * describe + annotate through one surface, with no per-builder duplication. The self-type {@code SELF}
 * keeps the fluent chain in the concrete builder's type.
 */
public abstract class ElementMeta<SELF extends ElementMeta<SELF>> {
    final List<ElementDraft.Annotation> annotations = new ArrayList<>();
    final Map<String, String> internalDescription = new LinkedHashMap<>();
    final Map<String, String> externalDescription = new LinkedHashMap<>();

    @SuppressWarnings("unchecked")
    SELF self() {
        return (SELF) this;
    }

    /** A name/value annotation on the element. */
    public SELF annotation(String name, String value) {
        annotations.add(new ElementDraft.Annotation(name, value));
        return self();
    }

    /** Per-locale internal description (authoring/maintenance text, distinct from any label). */
    public SELF internalDescription(String locale, String text) {
        internalDescription.put(locale, text);
        return self();
    }

    /** Per-locale external description (end-user-facing text, distinct from any label). */
    public SELF externalDescription(String locale, String text) {
        externalDescription.put(locale, text);
        return self();
    }
}
