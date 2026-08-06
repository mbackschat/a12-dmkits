package io.github.mbackschat.a12.dm.cli;

import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * What a handler returns: the result body plus the two facts about it that are ours to decide — whether it is
 * complete, and how long it stays fresh.
 *
 * <p>{@code resultType}, {@code ttlMs}, and {@code cacheScope} are protocol <i>facts</i> carried on our own
 * record on purpose. Which operation is cacheable and how fresh its answer is are semantics this server owns;
 * pushing them out to the transport would make the wire decide caching policy.
 *
 * @param payload      the operation's own fields, merged into the result object
 * @param resultType   complete, or a shape needing another round trip — this server only ever completes
 * @param ttlMs        freshness in milliseconds from receipt, or {@code null} when the result is not cacheable
 * @param cacheScope   who may keep it, or {@code null} when {@code ttlMs} is
 */
record McpResult(ObjectNode payload, McpProtocol.ResultType resultType, Long ttlMs,
                 McpProtocol.CacheScope cacheScope) {

    /** A complete, non-cacheable answer — the shape of every call whose answer depends on what was sent. */
    static McpResult complete(ObjectNode payload) {
        return new McpResult(payload, McpProtocol.ResultType.COMPLETE, null, null);
    }

    /**
     * A complete answer the client may keep for {@code ttlMs}. Everything cacheable this server serves is
     * derived from the binary, so it is the same for every caller and the scope is public.
     */
    static McpResult cacheable(ObjectNode payload, long ttlMs) {
        return new McpResult(payload, McpProtocol.ResultType.COMPLETE, ttlMs, McpProtocol.CacheScope.PUBLIC);
    }
}
