package io.github.mbackschat.a12.dm.adapter;

import java.util.List;
import java.util.Optional;

/**
 * One validation message the kernel's runtime raised when {@link KernelAdapter#evaluate evaluating} a
 * document instance — the rulekit-level projection of a kernel {@code IMessage} (boundary conversion,
 * N8: kernel types never escape), carrying the same channels as {@code IMessage}. An <b>author rule</b>'s
 * message carries that rule's full slash-path in {@code rulePath} (e.g. {@code /Order/DeliveryNotBeforeOrder});
 * a kernel <b>formal check</b> (e.g. a malformed value) reports the sentinel {@code rulePath}
 * {@code "formalePruefung"} with a kernel formal-error {@code errorCode} — so a {@code rulePath} starting
 * with {@code /} is the author-rule discriminator the consumer needs.
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
 */
public record EvalMessage(String errorCode, Optional<String> rulePath, FieldPointer field,
        String severity, String messageType, String message,
        List<FieldPointer> referenced, List<FieldPointer> fillToFix) {

    public EvalMessage {
        referenced = List.copyOf(referenced);
        fillToFix = List.copyOf(fillToFix);
    }
}
