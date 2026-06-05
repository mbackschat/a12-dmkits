package io.github.mbackschat.a12.dm.adapter;

/**
 * Fail-fast signal of the F10 converter (proposal N8 guardrail): a kernel construct the converter
 * does not yet handle was encountered — never a silent loss. The message names the construct so the
 * coverage gap is visible and actionable (coverage grows by corpus priority). Distinct from a real
 * parse/validation error, so callers can tell "rulekit can't model this yet" from "this is invalid".
 */
public final class UnsupportedConstructException extends RuntimeException {

    UnsupportedConstructException(String message) {
        super(message);
    }
}
