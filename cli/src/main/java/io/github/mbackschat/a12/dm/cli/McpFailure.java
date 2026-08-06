package io.github.mbackschat.a12.dm.cli;

import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * A refusal that becomes a JSON-RPC error frame. Thrown by a handler or by the dispatcher's protocol gate and
 * turned into bytes by the binding — the only layer that knows what a frame looks like.
 *
 * <p>Not a bug channel: an unexpected exception inside a handler is an internal error the dispatcher wraps.
 * This is for the refusals the protocol defines, each of which must be <i>corrective</i> — it names what the
 * server saw and what it serves instead, because a bare "method not found" reads as a broken server.
 */
final class McpFailure extends Exception {

    private static final long serialVersionUID = 1L;

    private final int code;
    private final transient ObjectNode data;

    McpFailure(int code, String message) {
        this(code, message, null);
    }

    McpFailure(int code, String message, ObjectNode data) {
        super(message);
        this.code = code;
        this.data = data;
    }

    int code() {
        return code;
    }

    /** Structured detail for the client to act on, or {@code null} when the message says everything. */
    ObjectNode data() {
        return data;
    }
}
