package io.github.mbackschat.a12.dm.core;

/**
 * A model reference (an {@code include} or a {@code typeDefinitions} import) could not be resolved against
 * the workspace — the referenced model id was not found among the indexed models, or a found model failed
 * to deserialize. This is an <b>input</b> problem (the model + workspace cannot compose), not a tool defect:
 * the CLI boundary maps it to a structured {@code rejected} envelope ({@code RK_UNRESOLVED_REFERENCE}) with a
 * corrective fix, never a raw stack trace. Thrown by {@link ReferenceResolvers} during kernel expansion (the
 * kernel asks the resolver for the referenced model id and the resolver can't provide a valid one).
 */
public final class UnresolvedReferenceException extends RuntimeException {

    private final String reference;

    public UnresolvedReferenceException(String reference, String message) {
        super(message);
        this.reference = reference;
    }

    public UnresolvedReferenceException(String reference, String message, Throwable cause) {
        super(message, cause);
        this.reference = reference;
    }

    /** The unresolved reference — the included or type-definition-importing model id that could not be resolved. */
    public String reference() {
        return reference;
    }
}
