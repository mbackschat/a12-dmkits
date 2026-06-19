package io.github.mbackschat.a12.dm.modelkit;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * A <b>partial, in-place</b> edit of an existing field: each aspect is {@link Optional#isPresent() present}
 * iff it should change, and absent iff it should be left exactly as-is. This is the MODIFY peer of
 * {@link ElementDraft.Field} (which describes a <em>whole</em> new field) — it lets {@code field modify}
 * change a field's type <em>and/or</em> its metadata (requiredness, labels, helper text, descriptions,
 * annotations, the global/transient flags) without delete-and-recreate, and crucially <b>without silently
 * dropping</b> the aspects the old type-only modify ignored. Applied by {@link ModelDocument#applyModification}.
 *
 * <p>Built via {@link #builder()} (a positional 9-arg constructor is too error-prone for this many optional
 * aspects). {@link #isEmpty()} lets a caller reject a modification that would change nothing rather than
 * report a false {@code applied}.
 */
public record FieldModification(
        Optional<FieldTypeSpec> type,
        Optional<Requiredness> requiredness,
        Optional<Map<String, String>> label,
        Optional<Map<String, String>> helperText,
        Optional<Map<String, String>> internalDescription,
        Optional<Map<String, String>> externalDescription,
        Optional<List<ElementDraft.Annotation>> annotations,
        Optional<Boolean> global,
        Optional<Boolean> isTransient) {

    /** True iff this would change nothing — the caller should reject it (no silent no-op success). */
    public boolean isEmpty() {
        return changedAspects().isEmpty();
    }

    /** The names of the aspects this edit changes — for the result envelope's {@code changed.aspects}. */
    public List<String> changedAspects() {
        List<String> aspects = new ArrayList<>();
        type.ifPresent(t -> aspects.add("type"));
        requiredness.ifPresent(r -> aspects.add("required"));
        label.ifPresent(l -> aspects.add("label"));
        helperText.ifPresent(h -> aspects.add("helperText"));
        internalDescription.ifPresent(d -> aspects.add("internalDescription"));
        externalDescription.ifPresent(d -> aspects.add("externalDescription"));
        annotations.ifPresent(a -> aspects.add("annotations"));
        global.ifPresent(g -> aspects.add("global"));
        isTransient.ifPresent(t -> aspects.add("transient"));
        return aspects;
    }

    public static Builder builder() {
        return new Builder();
    }

    /** Collects the aspects to change; a {@code null} argument means "leave this aspect untouched". */
    public static final class Builder {
        private FieldTypeSpec type;
        private Requiredness requiredness;
        private Map<String, String> label;
        private Map<String, String> helperText;
        private Map<String, String> internalDescription;
        private Map<String, String> externalDescription;
        private List<ElementDraft.Annotation> annotations;
        private Boolean global;
        private Boolean isTransient;

        public Builder type(FieldTypeSpec v) { this.type = v; return this; }
        public Builder requiredness(Requiredness v) { this.requiredness = v; return this; }
        public Builder label(Map<String, String> v) { this.label = v; return this; }
        public Builder helperText(Map<String, String> v) { this.helperText = v; return this; }
        public Builder internalDescription(Map<String, String> v) { this.internalDescription = v; return this; }
        public Builder externalDescription(Map<String, String> v) { this.externalDescription = v; return this; }
        public Builder annotations(List<ElementDraft.Annotation> v) { this.annotations = v; return this; }
        public Builder global(Boolean v) { this.global = v; return this; }
        public Builder isTransient(Boolean v) { this.isTransient = v; return this; }

        public FieldModification build() {
            return new FieldModification(Optional.ofNullable(type), Optional.ofNullable(requiredness),
                    Optional.ofNullable(label), Optional.ofNullable(helperText),
                    Optional.ofNullable(internalDescription), Optional.ofNullable(externalDescription),
                    Optional.ofNullable(annotations), Optional.ofNullable(global), Optional.ofNullable(isTransient));
        }
    }
}
