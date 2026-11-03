package io.github.mbackschat.a12.dm.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import io.github.mbackschat.a12.dm.rulekit.validate.RkCode;
import picocli.CommandLine;
import picocli.CommandLine.Model.ArgSpec;
import picocli.CommandLine.Model.CommandSpec;

/**
 * <b>A corrective must not advise an action its reader cannot take</b> (MCP-SPEC §6). The workspace is the
 * one affordance the two transports do not share: a CLI caller points {@code -w} at a directory, while an MCP
 * caller has no filesystem at all and makes a model available by sending it in {@code references} — the server
 * owns the workspace precisely so its own environment cannot decide what a caller's references resolve
 * against (MCP-SPEC §7). So text that says only "pass -w" is right for one reader and a dead end for the
 * other.
 *
 * <p><b>Scope.</b> Every text an MCP caller can read that mentions the workspace: a diagnostic's {@code fix},
 * a verb's failure message, a caller-settable parameter's description, and a verb's own description. No
 * exemption list — a parameter the <em>server</em> fills carries a {@link ContentRole} and is excluded by that
 * property rather than by name.
 */
class McpCorrectiveAffordanceTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** The CLI-only affordance. {@code -w} needs the word boundary or it matches inside ordinary prose. */
    private static final Pattern WORKSPACE_AFFORDANCE =
            Pattern.compile("(?<![\\w-])-w(?![\\w-])|--workspace|DMTOOL_WORKSPACE");

    /** The MCP-side affordance that must accompany it. */
    private static final String MCP_AFFORDANCE = "references";

    private static CommandSpec liveRoot() {
        return new CommandLine(new RootCommand()).getCommandSpec();
    }

    /** picocli keys aliases into the same map, so walk each subcommand under its own name only. */
    private static List<CommandLine> subcommandsOnce(CommandSpec spec) {
        return spec.subcommands().entrySet().stream()
                .filter(entry -> entry.getKey().equals(entry.getValue().getCommandName()))
                .map(java.util.Map.Entry::getValue)
                .toList();
    }

    /**
     * Every diagnostic corrective, not merely the one that was reported. A {@code fix} rides every envelope on
     * both transports, so it is the corrective with the widest reach and the one worth enumerating whole.
     */
    @Test
    void noDiagnosticCorrectiveOffersTheWorkspaceWithoutTheMcpAlternative() {
        List<String> offenders = Stream.of(RkCode.values())
                .filter(code -> WORKSPACE_AFFORDANCE.matcher(code.fix()).find())
                .filter(code -> !code.fix().contains(MCP_AFFORDANCE))
                .map(code -> code.code() + ": " + code.fix())
                .toList();

        assertEquals(List.of(), offenders,
                () -> "a corrective tells an MCP caller to pass -w, which it cannot: " + offenders);
    }

    /**
     * Every parameter an MCP caller actually sets. A parameter carrying a {@link ContentRole} is filled by the
     * server (the subject model, the request tree, a carried file), so its description is CLI-only by
     * construction; a role-free parameter rides {@code args} verbatim and is read by both callers.
     */
    @Test
    void noCallerSettableParameterDescribesResolutionAsWorkspaceOnly() {
        List<String> offenders = new ArrayList<>();
        collectOffendingParams(liveRoot(), "dmtool", offenders);

        assertEquals(List.of(), offenders,
                () -> "a parameter tells an MCP caller its value resolves via -w, which it cannot set: "
                        + offenders);
    }

    /**
     * A verb's own description reaches an MCP caller twice over: as the {@code op} constant's text in
     * {@code tools/list}, and again through {@code dm.catalog} op {@code manifest}. It tells nobody to do
     * anything, which is why it is a weaker case than a corrective — but "resolves from `-w`" still describes
     * a mechanism the reader does not have.
     */
    @Test
    void noVerbDescribesItsResolutionAsWorkspaceOnly() {
        List<String> offenders = new ArrayList<>();
        collectOffendingVerbs(liveRoot(), "dmtool", offenders);

        assertEquals(List.of(), offenders,
                () -> "a verb describes resolution in terms only a CLI caller has: " + offenders);
    }

    private static void collectOffendingVerbs(CommandSpec spec, String path, List<String> offenders) {
        String text = String.join(" ", spec.usageMessage().description());
        if (WORKSPACE_AFFORDANCE.matcher(text).find() && !text.contains(MCP_AFFORDANCE)) {
            offenders.add(path + ": " + text);
        }
        for (CommandLine sub : subcommandsOnce(spec)) {
            collectOffendingVerbs(sub.getCommandSpec(), path + " " + sub.getCommandName(), offenders);
        }
    }

    private static void collectOffendingParams(CommandSpec spec, String path, List<String> offenders) {
        List<ArgSpec> params = new ArrayList<>(spec.options());
        params.addAll(spec.positionalParameters());
        for (ArgSpec param : params) {
            if (ContentRole.of(param).isPresent()) {
                continue;
            }
            String text = String.join(" ", param.description());
            if (WORKSPACE_AFFORDANCE.matcher(text).find() && !text.contains(MCP_AFFORDANCE)) {
                offenders.add(path + " " + param.paramLabel() + ": " + text);
            }
        }
        for (CommandLine sub : subcommandsOnce(spec)) {
            collectOffendingParams(sub.getCommandSpec(), path + " " + sub.getCommandName(), offenders);
        }
    }

    /**
     * The behavioural half: a verb's own failure message, which no static scan of the surface can see because
     * it exists only at the moment the resolution fails. {@code include inline} is the case that motivated
     * this — its refusal used to read "pass -w &lt;dir&gt;" and offer nothing else at all.
     */
    @Test
    void anUnresolvableIncludeTellsAnMcpCallerWhatItCanActuallyDo() throws Exception {
        ObjectNode arguments = MAPPER.createObjectNode();
        arguments.put("op", "inline");
        arguments.put("model",
                Files.readString(Path.of("src/test/resources/models/multifile/app/storefront.dm.json")));
        arguments.putObject("args").put("reference", "catalog");

        ObjectNode params = MAPPER.createObjectNode();
        params.put("name", "dm.include.edit");
        params.set("arguments", arguments);
        ObjectNode meta = params.putObject("_meta");
        meta.put(McpProtocol.META_PROTOCOL_VERSION, McpProtocol.REVISION);
        meta.putObject(McpProtocol.META_CLIENT_CAPABILITIES);
        JsonNode result = new McpDispatcher(liveRoot())
                .dispatch(new McpRequest("tools/call", MAPPER.getNodeFactory().numberNode(1), params)).payload();

        assertTrue(result.path("isError").asBoolean(),
                () -> "an include with no carried model must fail: " + result);
        String text = MAPPER.writeValueAsString(result);
        assertTrue(text.contains(MCP_AFFORDANCE),
                () -> "the refusal names the affordance an MCP caller has: " + text);
    }
}
