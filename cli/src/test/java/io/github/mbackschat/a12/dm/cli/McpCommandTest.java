package io.github.mbackschat.a12.dm.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.Reader;
import java.io.StringReader;
import java.util.function.Supplier;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * The verb wiring: that {@code mcp} is a real leaf of the live tree and that a session runs through the one
 * entry point every other verb uses. The protocol itself is asserted on the wire in {@code McpWireTest}; this
 * only proves the leaf reaches it.
 */
class McpCommandTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final Supplier<Reader> original = McpCommand.stdinSource;

    @AfterEach
    void restoreStdin() {
        McpCommand.stdinSource = original;
    }

    @Test
    void theVerbServesASessionThroughTheCliEntryPoint() throws Exception {
        McpCommand.stdinSource = () -> new StringReader("""
                {"jsonrpc":"2.0","id":1,"method":"server/discover","params":{"_meta":{\
                "io.modelcontextprotocol/protocolVersion":"2026-07-28",\
                "io.modelcontextprotocol/clientCapabilities":{}}}}
                """);

        CliTestSupport.Result result = CliTestSupport.run("mcp");

        assertEquals(0, result.code(), result::err);
        assertEquals("2026-07-28",
                MAPPER.readTree(result.out()).path("result").path("supportedVersions").get(0).asText());
    }

    /**
     * {@code --text} buffers stdout until the verb returns, and this verb returns when the session ends — so
     * passing it would withhold every frame and hang the client on its first request. Refused, not ignored.
     */
    @Test
    void textIsRefusedBecauseAProtocolStreamHasNoTextRendering() {
        McpCommand.stdinSource = () -> new StringReader("");

        CliTestSupport.Result result = CliTestSupport.run("--text", "mcp");

        assertEquals(2, result.code(), result::out);
        assertTrue(result.err().contains("--text"), () -> "the refusal names the flag: " + result.err());
    }
}
