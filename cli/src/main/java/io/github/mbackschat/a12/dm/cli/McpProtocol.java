package io.github.mbackschat.a12.dm.cli;

/**
 * The constants of Model Context Protocol revision {@code 2026-07-28} — the one revision this server speaks
 * (MCP-SPEC "the revision"). Facts of the protocol, not types of a library: the seam rule bans depending on
 * someone else's classes to express them, not knowing what they are.
 *
 * <p>Every value here was read from the specification itself, not from a summary of it.
 */
final class McpProtocol {

    private McpProtocol() {
    }

    /** The single revision served. A request declaring any other version is refused, never partly served. */
    static final String REVISION = "2026-07-28";

    /** The per-request metadata envelope. There is no handshake in this revision; every request carries this. */
    static final String META = "_meta";

    /** Required on every request: the revision that request is written in. */
    static final String META_PROTOCOL_VERSION = "io.modelcontextprotocol/protocolVersion";

    /** Required on every request: the client capabilities relevant to it (an empty object is a valid answer). */
    static final String META_CLIENT_CAPABILITIES = "io.modelcontextprotocol/clientCapabilities";

    /** Optional on a request; SHOULD be present on every result, so a stateless server still identifies itself. */
    static final String META_SERVER_INFO = "io.modelcontextprotocol/serverInfo";

    // --- error codes -------------------------------------------------------------------------------------
    // The standard JSON-RPC codes, plus the one MCP code this server emits. -32020..-32099 is reserved for the
    // specification: a code from that range may be emitted only with its specified meaning, so this server
    // allocates nothing there and uses -32022 strictly for an unsupported version.

    static final int PARSE_ERROR = -32700;
    static final int INVALID_REQUEST = -32600;
    static final int METHOD_NOT_FOUND = -32601;
    static final int INVALID_PARAMS = -32602;
    static final int INTERNAL_ERROR = -32603;
    static final int UNSUPPORTED_PROTOCOL_VERSION = -32022;

    /** HTTP only: the mirrored headers disagree with the body, or a required one is missing. */
    static final int HEADER_MISMATCH = -32020;

    /**
     * What a result <i>is</i>. Every result MUST carry one, and a client MUST treat an unrecognized value as
     * invalid. This server completes or fails; it never asks for more input, so {@code input_required} — the
     * multi round-trip shape that replaced server-initiated requests — is deliberately not served.
     */
    enum ResultType {
        COMPLETE("complete");

        private final String wireName;

        ResultType(String wireName) {
            this.wireName = wireName;
        }

        String wireName() {
            return wireName;
        }
    }

    /** Who may keep a cacheable result. Content derived from the binary is the same for every caller. */
    enum CacheScope {
        PUBLIC("public"), PRIVATE("private");

        private final String wireName;

        CacheScope(String wireName) {
            this.wireName = wireName;
        }

        String wireName() {
            return wireName;
        }
    }
}
