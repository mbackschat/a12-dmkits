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
     * The hosted route: the verb binds a port, announces it on <b>stderr</b>, and leaves stdout empty — which
     * is what its `http` operational profile publishes as {@code EMPTY_STDOUT}.
     */
    @Test
    void theHttpRouteBindsAPortAndSaysSoOnStderr() {
        Runnable block = McpCommand.httpBlock;
        McpCommand.httpBlock = () -> { };
        try {
            CliTestSupport.Result result = CliTestSupport.run("mcp", "--http", "--port", "0");

            assertEquals(0, result.code(), result::err);
            assertEquals("", result.out(), "the frames travel over the port, never over stdout");
            assertTrue(result.err().contains("/mcp"), () -> "the endpoint is announced: " + result.err());
            assertTrue(result.err().contains("2026-07-28"), () -> "with the revision served: " + result.err());
        } finally {
            McpCommand.httpBlock = block;
        }
    }

    /**
     * A hosting platform assigns the address and passes it in the environment, so the bind defaults must say
     * so where the operator reads them. The behaviour itself is a parse-time environment lookup that an
     * in-process test cannot set; it is verified end to end against the packaged launcher and on the host by
     * the deployment smoke test, and what is locked here is that the contract stays *published*.
     */
    @Test
    void theBindDefaultsDocumentThePlatformEnvironment() {
        CliTestSupport.Result help = CliTestSupport.run("mcp", "--help");

        assertEquals(0, help.code(), help::err);
        // Picocli wraps a description across lines, so the match is against whitespace-normalised help —
        // otherwise the assertion would pass or fail on where the column break happens to land.
        String flowed = help.out().replaceAll("\\s+", " ");
        assertTrue(flowed.contains("PORT environment variable"),
                () -> "the port default names the platform's variable: " + help.out());
        assertTrue(flowed.contains("IP or HOST environment variable"),
                () -> "the host default names the platform's variable: " + help.out());
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
