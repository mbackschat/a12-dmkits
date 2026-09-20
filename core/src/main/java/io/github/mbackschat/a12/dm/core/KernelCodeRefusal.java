package io.github.mbackschat.a12.dm.core;

/**
 * A client-side refusal that carries the kernel code it refuses on, as a TYPE rather than as message text.
 *
 * <p><b>Why a type.</b> Every refusal already names its code — {@link Refusals} appends it as a {@code
 * (CODE)} suffix — but a caller that needed the code back had to parse the message. That is fine for a human
 * reading a stack trace and wrong for a provider deciding what KIND of outcome it just had: a
 * {@code ConditionReadBack} catches a {@code RuntimeException} per rule, and until this existed it could not
 * tell "the converter does not model this construct yet" from "it mapped, and a rulekit gate says the kernel
 * would reject it". Those are opposite messages to a consumer — retry later versus fix the rule — and they
 * became one {@code ReadResult.Opaque} with a prose reason (DEV-API-GAPS DG1001). The distinction is only
 * reachable on the KERNEL-FREE provider, which API-FINDINGS AF154 measures and explains.
 *
 * <p>It extends {@link IllegalArgumentException} so every existing {@code catch} keeps working and no caller
 * has to learn a new type to stay correct; only a caller that WANTS the distinction names this one.
 */
public final class KernelCodeRefusal extends IllegalArgumentException {

    private static final long serialVersionUID = 1L;

    /** Not {@code transient}: {@link KernelCode} is an enum and therefore serializable, so marking it so
     *  would silently drop the one thing this type exists to carry across a round trip. */
    private final KernelCode code;

    KernelCodeRefusal(KernelCode code, String message) {
        super(message);
        this.code = code;
    }

    /** The kernel code this refusal claims — the same one rendered into {@link #getMessage()}. */
    public KernelCode code() {
        return code;
    }
}
