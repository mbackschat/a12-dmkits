package io.github.mbackschat.a12.rulekit.cli;

import java.util.Map;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Model.OptionSpec;
import picocli.CommandLine.Model.PositionalParamSpec;

/**
 * The {@code manifest} verb (CLI-SPEC §1a / §4) — emits a machine-readable map of <b>every verb and
 * its parameters</b> as JSON, so an agent can discover the whole tool surface in one call (the
 * self-explaining requirement). It is derived from the same picocli command model that drives parsing
 * and {@code --help}, so it cannot drift from behavior. For a verb's I/O <em>shape</em>, see
 * {@code schema}; for the DSL vocabulary, {@code operators}.
 */
@Command(name = "manifest", mixinStandardHelpOptions = true,
        description = "Emit a machine-readable map of all verbs and their parameters (JSON).")
final class ManifestCommand extends BaseCommand {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Override
    public Integer call() {
        ObjectNode root = MAPPER.createObjectNode();
        root.put("tool", "rulekit");
        ArrayNode verbs = root.putArray("verbs");
        for (Map.Entry<String, CommandLine> e : spec.root().subcommands().entrySet()) {
            verbs.add(verb(e.getKey(), e.getValue().getCommandSpec()));
        }
        try {
            out().print(MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(root) + "\n");
            return 0;
        } catch (JsonProcessingException ex) {
            return fail("cannot serialize manifest: " + ex.getMessage());
        }
    }

    private ObjectNode verb(String name, CommandSpec cmd) {
        ObjectNode v = MAPPER.createObjectNode();
        v.put("verb", name);
        v.put("description", String.join(" ", cmd.usageMessage().description()));
        ArrayNode params = v.putArray("params");
        for (PositionalParamSpec p : cmd.positionalParameters()) {
            ObjectNode n = params.addObject();
            n.put("name", p.paramLabel());
            n.put("positional", true);
            n.put("required", p.required());
            n.put("description", String.join(" ", p.description()));
        }
        for (OptionSpec o : cmd.options()) {
            if (o.usageHelp() || o.versionHelp()) {
                continue; // skip the standard --help/--version mixin
            }
            ObjectNode n = params.addObject();
            n.put("name", o.longestName());
            n.put("positional", false);
            n.put("required", o.required());
            n.put("description", String.join(" ", o.description()));
        }
        return v;
    }
}
