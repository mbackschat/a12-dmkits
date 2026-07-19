package io.github.mbackschat.a12.dm.cli.interp;

import java.util.List;

/**
 * The kernel-free interpreter preparation path found a surviving <b>included</b> rule/computation whose
 * reference-bearing fragment retains a genuine unmapped reference (INTERPRETER-FINDINGS IF146). A mount is
 * remapped as a MOVE through a reference-aware pass, so legal absolute, short-name, MODEL_ROOT, and
 * {@code $}-outer references evaluate; only a genuine unmapped reference refuses loudly with
 * {@code RK_INTERPRETER_INCLUDE_RULES_UNSUPPORTED} rather than risk a silent-wrong-result.
 */
public final class IncludeRelativizationException extends RuntimeException {

    private final transient List<String> elements;

    IncludeRelativizationException(List<String> elements) {
        super("cannot evaluate " + elements.size() + " rule(s)/computation(s) from an included model on the "
                + "default path — the assembly layer retained a genuine unmapped reference in: " + elements);
        this.elements = List.copyOf(elements);
    }

    /** The full name-paths of the mounted included rules/computations with an unmapped reference. */
    public List<String> elements() {
        return elements;
    }

    /** Historical compatibility alias for {@link #elements()}. */
    @Deprecated(forRemoval = false)
    public List<String> rules() {
        return elements();
    }
}
