package io.github.mbackschat.a12.dm.edit;

/**
 * The common mutable metadata surface of an A12 model-tree element. Fields, groups, rules, and
 * computations all carry annotations plus per-locale internal and external descriptions; their held
 * editors inherit those operations here so sibling surfaces cannot drift.
 *
 * <p>The other common element properties are not metadata edits: identity is exposed by the concrete
 * editor's held path, rename is a safety-gated structural refactor, and the parent is structural/read-only.
 *
 * @param <E> the concrete editor type returned by fluent operations
 */
public abstract sealed class ElementMetadataEditor<E extends ElementMetadataEditor<E>>
        permits FieldEditor, GroupEditor, RuleEditor, ComputationEditor {

    final EditSession session;
    final String elementPath;

    ElementMetadataEditor(EditSession session, String elementPath) {
        this.session = session;
        this.elementPath = elementPath;
    }

    /** Set a name/value annotation on this element. Chains. */
    public final E setAnnotation(String name, String value) {
        session.setAnnotation(elementPath, name, value);
        return self();
    }

    /** Remove the annotation {@code name} from this element. Chains. */
    public final E removeAnnotation(String name) {
        session.removeAnnotation(elementPath, name);
        return self();
    }

    /** Upsert one per-locale internal description (authoring/maintenance text) on this element. Chains. */
    public final E setInternalDescription(String locale, String text) {
        session.setInternalDescription(elementPath, locale, text);
        return self();
    }

    /** Remove this element's internal description for {@code locale}. Chains. */
    public final E removeInternalDescription(String locale) {
        session.removeInternalDescription(elementPath, locale);
        return self();
    }

    /** Upsert one per-locale external description (end-user-facing text) on this element. Chains. */
    public final E setExternalDescription(String locale, String text) {
        session.setExternalDescription(elementPath, locale, text);
        return self();
    }

    /** Remove this element's external description for {@code locale}. Chains. */
    public final E removeExternalDescription(String locale) {
        session.removeExternalDescription(elementPath, locale);
        return self();
    }

    @SuppressWarnings("unchecked")
    private E self() {
        // The sealed permits list fixes every subtype's E to itself.
        return (E) this;
    }
}
