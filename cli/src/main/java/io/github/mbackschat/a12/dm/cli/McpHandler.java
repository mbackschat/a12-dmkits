package io.github.mbackschat.a12.dm.cli;

/**
 * One method of the protocol, as a synchronous pure function. Deliberately not a callback or a stream: this
 * is the shape that adapts to any later transport, and the shape a serialised dispatcher can run directly.
 */
@FunctionalInterface
interface McpHandler {

    /** Answer one request, or refuse it with a corrective failure. */
    McpResult handle(McpRequest request) throws McpFailure;
}
