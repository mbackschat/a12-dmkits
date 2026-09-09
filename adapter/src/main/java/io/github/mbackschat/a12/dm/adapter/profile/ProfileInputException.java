package io.github.mbackschat.a12.dm.adapter.profile;

/** A diagnosed profile input or synthesis constraint, distinct from an unexpected processing failure. */
public final class ProfileInputException extends IllegalArgumentException {

    private static final long serialVersionUID = 1L;

    ProfileInputException(String message) {
        super(message);
    }

    ProfileInputException(String message, Throwable cause) {
        super(message, cause);
    }
}
