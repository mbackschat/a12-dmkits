package io.github.mbackschat.a12.dm.core;

import io.github.mbackschat.a12.dm.core.spi.ConsistencyOracle;

/**
 * A {@link ConsistencyOracle} could not reach a verdict because the backend <b>failed to build the model</b>
 * while checking it — a bare throw from inside the consistency check itself, before any structured problem
 * exists. The real kernel does this for a model it cannot construct at all: a blank temporal {@code format}
 * dies on {@code DateTypeImpl}'s {@code notBlank} precondition, and a duplicate child name in one group dies
 * in the same tier (KERNEL-FINDINGS KF259).
 *
 * <p><b>It exists because the two callers of the port dispose of this event differently, and both are
 * right.</b> The whole-model gate ({@link ModelLoader#loadAndCheck}) has nothing injected, so the throw is the
 * kernel's verdict on the bytes it was handed and becomes an {@code ERROR} notification with its receipt
 * intact — otherwise it reaches a user as "this is a tool defect, not an invalid input", which asserts the
 * opposite of the truth. The draft gate ({@code RuleValidator}) has just injected a candidate, so the throw
 * may be the injection's fault rather than the kernel judging the draft, and it keeps publishing <b>no</b>
 * receipt; its existing catch-all sees this as the {@link RuntimeException} it is.
 *
 * <p>Deliberately narrow: it wraps the consistency call alone. A failure to deserialize the DM-JSON, and a
 * reference the caller's own resolver could not resolve, are earlier tiers that report themselves.
 */
public class ConsistencyCheckFailedException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public ConsistencyCheckFailedException(String message, Throwable cause) {
        super(message, cause);
    }
}
