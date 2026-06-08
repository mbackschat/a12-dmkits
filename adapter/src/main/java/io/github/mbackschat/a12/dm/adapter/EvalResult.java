package io.github.mbackschat.a12.dm.adapter;

import java.util.LinkedHashSet;
import java.util.List;

/**
 * The outcome of {@link KernelAdapter#evaluate running the kernel's runtime validation} over a document
 * instance: every message the kernel raised, in kernel order. The runtime evaluates the WHOLE model
 * (every rule), so {@code messages} may span rules — narrowing to one rule is the caller's job (match
 * {@link #firedCodes()} against the rule's code).
 */
public record EvalResult(List<EvalMessage> messages) {

    public EvalResult {
        messages = List.copyOf(messages);
    }

    /** The distinct error codes that fired, in first-seen order (the primary fire/no-fire signal). */
    public LinkedHashSet<String> firedCodes() {
        LinkedHashSet<String> codes = new LinkedHashSet<>();
        for (EvalMessage m : messages) {
            codes.add(m.errorCode());
        }
        return codes;
    }
}
