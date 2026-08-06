package io.github.mbackschat.a12.dm.cli;

import java.util.LinkedHashMap;
import java.util.Map;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import picocli.CommandLine.Model.CommandSpec;

/**
 * Method name → handler, over our own records (MCP-SPEC "the seam"). Two properties are deliberate:
 *
 * <ul>
 *   <li><b>the table is data.</b> {@link #handlers()} hands it over, and the binding walks it. A later
 *       binding — an SDK's, if one ever implements this revision — registers the same entries instead of
 *       dispatching itself, so the substitution adds one class and deletes one rather than touching every
 *       method registration;</li>
 *   <li><b>handlers are synchronous pure functions.</b> {@code request → result}, no callbacks, no streams.
 *       That direction adapts: a synchronous function wraps into whatever an SDK wants, while an async one
 *       cannot be unwrapped as cheaply. Dispatch is serialised anyway, so it costs nothing.</li>
 * </ul>
 *
 * <p>The protocol gate below runs before any handler, and it is the whole of this server's version policy.
 */
final class McpDispatcher {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** The removed handshake. A legacy client opens with it, and its error may be the only diagnostic it shows. */
    private static final String LEGACY_HANDSHAKE = "initialize";

    /** Catalogue content is fixed for a given binary, so an hour is a safe freshness hint. */
    private static final long DISCOVER_TTL_MS = 3_600_000L;

    /** The tool surface is derived from the binary's own command tree: it cannot change while it runs. */
    private static final long TOOLS_TTL_MS = 3_600_000L;

    private final Map<String, McpHandler> handlers = new LinkedHashMap<>();

    /**
     * @param root the <b>live</b> command tree this session serves — the running one, so a verb the binary
     *             hides (a native build's JVM-only set) is absent from the tool surface too
     */
    McpDispatcher(CommandSpec root) {
        handlers.put("server/discover", request -> McpResult.cacheable(discover(), DISCOVER_TTL_MS));
        handlers.put("tools/call", request -> McpInvoker.call(root, request));
        handlers.put("tools/list", request -> {
            ObjectNode payload = MAPPER.createObjectNode();
            payload.set("tools", McpToolRegistry.toolsJson(root));
            return McpResult.cacheable(payload, TOOLS_TTL_MS);
        });
    }

    /** The dispatch table, exposed so a different binding can register the same entries. */
    Map<String, McpHandler> handlers() {
        return Map.copyOf(handlers);
    }

    /**
     * Run one request through the protocol gate and its handler. The gate's order is not arbitrary:
     * {@code initialize} is answered first because a legacy request also lacks the required metadata, and
     * refusing it as malformed metadata would tell a legacy client nothing it can act on.
     */
    McpResult dispatch(McpRequest request) throws McpFailure {
        if (LEGACY_HANDSHAKE.equals(request.method())) {
            throw new McpFailure(McpProtocol.METHOD_NOT_FOUND,
                    "`" + LEGACY_HANDSHAKE + "` was removed in MCP " + McpProtocol.REVISION + ", which is the "
                            + "only revision this server implements: there is no handshake, and every request "
                            + "carries `" + McpProtocol.META_PROTOCOL_VERSION + "` in `" + McpProtocol.META
                            + "` instead. Call `server/discover` for the supported set.");
        }
        requireMetadata(request);
        String declared = request.protocolVersion().orElseThrow();
        if (!McpProtocol.REVISION.equals(declared)) {
            ObjectNode data = MAPPER.createObjectNode();
            ArrayNode supported = data.putArray("supported");
            supported.add(McpProtocol.REVISION);
            data.put("requested", declared);
            throw new McpFailure(McpProtocol.UNSUPPORTED_PROTOCOL_VERSION, "Unsupported protocol version", data);
        }
        McpHandler handler = handlers.get(request.method());
        if (handler == null) {
            throw new McpFailure(McpProtocol.METHOD_NOT_FOUND,
                    "unknown method `" + request.method() + "`; this server serves " + handlers.keySet());
        }
        return handler.handle(request);
    }

    /**
     * Both required {@code _meta} fields, refused as invalid params. The specification is explicit that a
     * request missing a required field is malformed and MUST be rejected with {@code -32602} — so the
     * refusal names the revision (an absent version field is, in practice, a pre-revision client) while the
     * code stays the one the specification mandates.
     */
    private static void requireMetadata(McpRequest request) throws McpFailure {
        if (request.protocolVersion().isEmpty()) {
            throw new McpFailure(McpProtocol.INVALID_PARAMS,
                    "every request must carry `" + McpProtocol.META + "." + McpProtocol.META_PROTOCOL_VERSION
                            + "`; this server implements MCP " + McpProtocol.REVISION + " only, and publishes "
                            + "its supported set from `server/discover`.");
        }
        if (!request.declaresClientCapabilities()) {
            throw new McpFailure(McpProtocol.INVALID_PARAMS,
                    "every request must carry `" + McpProtocol.META + "." + McpProtocol.META_CLIENT_CAPABILITIES
                            + "` (an empty object is a valid declaration); this server implements MCP "
                            + McpProtocol.REVISION + " only.");
        }
    }

    /**
     * The mandatory discovery RPC: what this server speaks, what it can do, and where to look next. The
     * {@code instructions} string is a <b>router, never content</b> — the vocabulary and the per-verb detail
     * live behind calls a client can make, and duplicating them here would be a second copy to keep true.
     */
    private static ObjectNode discover() {
        ObjectNode result = MAPPER.createObjectNode();
        result.putArray("supportedVersions").add(McpProtocol.REVISION);
        ObjectNode capabilities = result.putObject("capabilities");
        capabilities.putObject("tools");
        result.put("instructions", "A12 DocumentModel authoring and inspection. Every call carries its own "
                + "content: send the model's DM-JSON, receive the edited DM-JSON back — this server stores "
                + "nothing between calls and accepts no filesystem path. Call `tools/list` for the surface; "
                + "each tool's `op` enum documents that route's effect and what it returns. Where you can run "
                + "the `" + Cli.TOOL_NAME + "` binary directly, prefer it: it edits models in place.");
        return result;
    }
}
