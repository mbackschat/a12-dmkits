package io.github.mbackschat.a12.dm.cli;

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
 * The {@code manifest} verb (CLI-SPEC §1a/§2) — a machine-readable map of <b>every runnable op and its
 * parameters</b> as JSON, so an agent discovers the whole tool surface in one call (the self-explaining
 * requirement). It walks the full command tree, so a nested op under a target group is emitted under its
 * <b>qualified</b> name (e.g. {@code "doc validate"}); the bare group is not itself an op. The {@code
 * -m/--model} context option is inherited by every verb, so it is listed once under {@code globalOptions}.
 * Derived from the same picocli model that drives parsing and {@code --help}, so it cannot drift.
 */
@Command(name = "manifest", mixinStandardHelpOptions = true,
        description = "Emit a machine-readable map of all verbs and their parameters (JSON).")
final class ManifestCommand extends BaseCommand {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Override
    public Integer call() {
        ObjectNode root = MAPPER.createObjectNode();
        root.put("tool", "dmtool");
        // every verb's output conforms to the result envelope — point the agent at its schema
        root.put("resultSchema", "result");
        // the -m/--model context option is inherited by every verb — list it once, globally
        ArrayNode globals = root.putArray("globalOptions");
        for (OptionSpec o : spec.root().options()) {
            if (o.usageHelp() || o.versionHelp()) {
                continue;
            }
            globals.add(option(o));
        }
        ArrayNode verbs = root.putArray("verbs");
        collect("", spec.root().subcommands(), verbs);
        try {
            out().print(MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(root) + "\n");
            return 0;
        } catch (JsonProcessingException ex) {
            return fail("cannot serialize manifest: " + ex.getMessage());
        }
    }

    /** Walk the tree: emit each runnable leaf op under its qualified name, recursing into target groups. */
    private void collect(String prefix, Map<String, CommandLine> subs, ArrayNode verbs) {
        for (Map.Entry<String, CommandLine> e : subs.entrySet()) {
            CommandSpec cmd = e.getValue().getCommandSpec();
            String name = prefix.isEmpty() ? e.getKey() : prefix + " " + e.getKey();
            Map<String, CommandLine> children = cmd.subcommands();
            if (children.isEmpty()) {
                verbs.add(verb(name, cmd));
            } else {
                collect(name, children, verbs);
            }
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
            if (o.usageHelp() || o.versionHelp() || o.inherited()) {
                continue; // -m is inherited — shown once under globalOptions, not on every verb
            }
            params.add(option(o));
        }
        return v;
    }

    private ObjectNode option(OptionSpec o) {
        ObjectNode n = MAPPER.createObjectNode();
        n.put("name", o.longestName());
        n.put("positional", false);
        n.put("required", o.required());
        n.put("description", String.join(" ", o.description()));
        return n;
    }
}
