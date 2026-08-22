package io.github.mbackschat.a12.dm.cli;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import picocli.CommandLine;
import picocli.CommandLine.Model.CommandSpec;

/**
 * An MCP caller cannot reach {@code -}, on either transport (G59).
 *
 * <p>The server's standing policy is that <b>no caller-supplied path is ever used</b> — {@code McpInvoker}
 * refuses one and tells the caller to send content instead. {@code -} is the same thing one step further out:
 * an input SOURCE the caller does not own. It slipped through because the policy is enforced on content
 * parameters and a {@code batch} child's argv is not scanned.
 *
 * <p>What it would otherwise do is transport-specific and bad both ways. On <b>stdio</b>, stdin IS the protocol
 * channel, so the verb would consume the caller's own frames and end the session. On <b>--http</b> there is no
 * caller stdin at all, so the worker would block on the process's until EOF; with the pool capped at
 * {@code --workers}, a handful of such calls answer every later request with 503.
 */
class McpStdinRefusalTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @BeforeEach
    @AfterEach
    void clearStdinPosture() {
        JsonInput.resetForInvocation();
    }

    private static JsonNode call(String tool, ObjectNode arguments) throws Exception {
        ObjectNode params = MAPPER.createObjectNode();
        params.put("name", tool);
        params.set("arguments", arguments);
        ObjectNode meta = params.putObject("_meta");
        meta.put(McpProtocol.META_PROTOCOL_VERSION, McpProtocol.REVISION);
        meta.putObject(McpProtocol.META_CLIENT_CAPABILITIES);
        CommandSpec root = new CommandLine(new RootCommand()).getCommandSpec();
        return new McpDispatcher(root)
                .dispatch(new McpRequest("tools/call", MAPPER.getNodeFactory().numberNode(1), params))
                .payload();
    }

    private static String textOf(JsonNode result) {
        StringBuilder text = new StringBuilder();
        for (JsonNode block : result.path("content")) {
            text.append(block.path("text").asText());
        }
        return text.toString();
    }

    /**
     * <b>The two links that make a stdin read UNREACHABLE over MCP today</b> — asserted, so that if either one
     * changes, this test says so instead of the posture silently becoming the only thing standing there.
     *
     * <p>A cold review reported that an MCP caller could make the server read its own stdin via a {@code batch}
     * child's argv (the caller-supplied-path check reads content parameters, not child argv). Attacking each
     * link showed it does not reach: of the nine inputs wired for {@code -}, eight require {@code -m}, and an
     * MCP caller cannot supply a path — so those children fail on the missing model before any spec read. The
     * ninth is {@code batch} itself, and nesting is refused in the parent's op loop before the child runs.
     *
     * <p>The posture the invoker installs is therefore <b>defense in depth, not a fix for a live hole</b> —
     * worth keeping because it is nearly free and holds if a future wiring gives batch children a model or
     * relaxes nesting, but it must not be described as closing a reachable exploit. The real defect the review
     * found on this path was the {@code System.in} close, and that one was reachable; it is fixed separately.
     */
    @Test
    void theMcpSurfaceCannotReachAStdinReadToday() throws Exception {
        ObjectNode needsModel = MAPPER.createObjectNode();
        needsModel.putObject("files").put("ops.json", """
                [{"verb":"field","args":["add","-"]}]""");
        assertTrue(textOf(call("dm.batch", needsModel)).contains("pass -m"),
                "link 1: a model-requiring child fails before its spec read, and an MCP caller has no path to give");

        ObjectNode nested = MAPPER.createObjectNode();
        nested.putObject("files").put("ops.json", """
                [{"verb":"batch","args":["-"]}]""");
        assertTrue(textOf(call("dm.batch", nested)).contains("batch cannot nest"),
                "link 2: the only model-free wired input is batch, and nesting is refused before dispatch");
    }

    /**
     * The direct read, once the process is serving: the refusal is about the TRANSPORT having no caller stdin,
     * so it must fire regardless of what the seam would have returned.
     */
    @Test
    void onceServingNoVerbMayClaimStdin() {
        JsonInput.markStdinUnavailable("an MCP request");
        IOException refused = org.junit.jupiter.api.Assertions.assertThrows(IOException.class,
                () -> JsonInput.read("-", "<spec.json>"));
        assertTrue(refused.getMessage().contains("content"), refused::getMessage);
        assertTrue(refused.getMessage().contains("MCP"), refused::getMessage);
        assertTrue(refused.getMessage().contains("no caller stdin"), refused::getMessage);
    }

    /** …and the ordinary CLI process is untouched by that posture. */
    @Test
    void anOrdinaryProcessStillReadsStdin() throws Exception {
        JsonInput.stdinSource = () -> new java.io.StringReader("{\"ok\":true}");
        JsonInput.resetForInvocation();
        assertTrue(JsonInput.read("-", "<spec.json>").contains("ok"));
    }
}
