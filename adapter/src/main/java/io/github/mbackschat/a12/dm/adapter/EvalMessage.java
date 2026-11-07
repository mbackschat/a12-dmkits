package io.github.mbackschat.a12.dm.adapter;

import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * One validation message the kernel's runtime raised when {@link KernelAdapter#evaluate evaluating} a
 * document instance — the rulekit-level projection of a kernel {@code IMessage} (boundary conversion,
 * N8: kernel types never escape), carrying the same channels as {@code IMessage}. An <b>author rule</b>'s
 * message carries that rule's full slash-path in {@code rulePath} (e.g. {@code /Order/DeliveryNotBeforeOrder});
 * a kernel <b>formal check</b> (e.g. a malformed value) reports the sentinel {@code rulePath}
 * {@code "formalePruefung"} with a kernel formal-error {@code errorCode} — so a {@code rulePath} starting
 * with {@code /} is the author-rule discriminator the consumer needs.
 *
 * <p>Built via {@link #builder()}: four of its channels are strings, which a positional constructor lets a caller
 * swap silently.
 *
 * @param errorCode   the rule's error code (or the kernel's formal-error code)
 * @param rulePath    the firing rule's full slash-path, or {@code "formalePruefung"} for a formal check
 * @param field       the offending field's instance pointer ({@code getErrorFieldPointer}) — the row it hit
 * @param severity    {@code ERROR} | {@code WARNING} | {@code INFO}
 * @param messageType {@code VALUE_ERROR} | {@code OMISSION_ERROR}
 * @param message     the <b>interpolated</b> end-user text (the kernel's {@code getErrorText}): the authored
 *                    error message with every {@code $Field$} / {@code $Field.value$} token already resolved
 *                    against this document instance — what the end-user actually reads (FEATURE-GAPS G1)
 * @param referenced  the fields the rule read ({@code getReferencedFieldsPointers}) — its operands
 * @param fillToFix   for an OMISSION error, the fields that — if filled — would clear it
 *                    ({@code getRefOmissionErrorResponsiblePointers}); empty for a VALUE error
 * @param possiblyCausing the groups of possibly-causing entities ({@code getGroupsOfPossiblyCausingEntities},
 *                    KERNEL-FINDINGS KF683); a {@link FieldPointer} carries no missed key, so two members that
 *                    differ only by theirs read as one, as the interpreter's do (INTERPRETER-GAPS IG188)
 */
public record EvalMessage(String errorCode, Optional<String> rulePath, FieldPointer field,
        String severity, String messageType, String message,
        List<FieldPointer> referenced, List<FieldPointer> fillToFix, List<Set<FieldPointer>> possiblyCausing) {

    public EvalMessage {
        referenced = List.copyOf(referenced);
        fillToFix = List.copyOf(fillToFix);
        possiblyCausing = possiblyCausing.stream().<Set<FieldPointer>>map(Set::copyOf).toList();
    }

    public static Builder builder() {
        return new Builder();
    }

    /** Collects the channels; an address list left unset is empty, and so is a {@code null} rule path. */
    public static final class Builder {
        private String errorCode;
        private String rulePath;
        private FieldPointer field;
        private String severity;
        private String messageType;
        private String message;
        private List<FieldPointer> referenced = List.of();
        private List<FieldPointer> fillToFix = List.of();
        private List<Set<FieldPointer>> possiblyCausing = List.of();

        public Builder errorCode(String v) { this.errorCode = v; return this; }
        public Builder rulePath(String v) { this.rulePath = v; return this; }
        public Builder field(FieldPointer v) { this.field = v; return this; }
        public Builder severity(String v) { this.severity = v; return this; }
        public Builder messageType(String v) { this.messageType = v; return this; }
        public Builder message(String v) { this.message = v; return this; }
        public Builder referenced(List<FieldPointer> v) { this.referenced = v; return this; }
        public Builder fillToFix(List<FieldPointer> v) { this.fillToFix = v; return this; }
        public Builder possiblyCausing(List<Set<FieldPointer>> v) { this.possiblyCausing = v; return this; }

        public EvalMessage build() {
            return new EvalMessage(errorCode, Optional.ofNullable(rulePath), field, severity, messageType, message,
                    referenced, fillToFix, possiblyCausing);
        }
    }
}
