package io.github.mbackschat.a12.dm.core;

/** Malformed DM-JSON that could not reach a model consistency verdict. */
public final class ModelInputException extends IllegalArgumentException {

    public ModelInputException(String message) {
        super(message);
    }

    public ModelInputException(String message, Throwable cause) {
        super(message, cause);
    }
}
