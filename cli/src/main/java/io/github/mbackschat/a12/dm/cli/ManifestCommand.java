package io.github.mbackschat.a12.dm.cli;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Model.ArgSpec;
import picocli.CommandLine.Model.OptionSpec;
import picocli.CommandLine.Model.PositionalParamSpec;

/**
 * The {@code manifest} verb (CLI-SPEC §2/§5) — a machine-readable map of <b>every runnable op and its
 * parameters</b> as JSON (the self-explaining requirement), in three tiers. Bare, it is an index of one entry per
 * verb, because the full document is large and an agent reads its first discovery call whole; a {@code <target>
 * [<op>]} selector returns the full entries of the verbs it names; {@code --full} returns every full entry. It
 * walks the full command tree, so a nested op under a target group is emitted under its <b>qualified</b> name
 * (e.g. {@code "model check"}); the bare group is not itself an op. The {@code -m/--model} context option is
 * inherited by every verb, so it is listed once under {@code globalOptions}. Derived from the same picocli model
 * that drives parsing and {@code --help}, so it cannot drift.
 */
@Command(name = "manifest", mixinStandardHelpOptions = true,
        description = "Emit a machine-readable map of the verbs (JSON): bare, one line per verb; with a target or "
                + "verb, those verbs' parameters, worked example and operational profile; with --full, all of it.")
final class ManifestCommand extends BaseCommand {

    /** Where the index sends an agent for what it leaves out; the help footer routes the same way. */
    static final String DETAIL = "For a verb's parameters, worked example and operational profile: dmtool manifest "
            + "<target> [<op>] (a target selects all its verbs). For every verb in one document: dmtool manifest --full.";

    @Parameters(paramLabel = "<verb>", arity = "0..2", description = "a target (`field`) or a verb (`field add`, "
            + "`export`): return the full entries of the verbs it names")
    List<String> selector = new ArrayList<>();

    @Option(names = "--full", description = "return every verb's full entry, the whole surface in one document")
    boolean full;

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /**
     * Per-verb worked demonstrations (the {@code verb-examples.json} resource): each verb's {@code example}
     * {@code {intent, command}} so a cold agent sees the verb <b>in action</b> straight from the manifest.
     * CI-executed by {@code VerbDemonstrationTest} and coverage-gated by {@code CliConformanceMatrixTest}.
     */
    private static final JsonNode EXAMPLES = loadExamples();

    private static JsonNode loadExamples() {
        try (InputStream in = ManifestCommand.class.getResourceAsStream("/verb-examples.json")) {
            return in == null ? MAPPER.createObjectNode() : MAPPER.readTree(in);
        } catch (IOException e) {
            return MAPPER.createObjectNode();
        }
    }

    @Override
    public Integer call() {
        if (full && !selector.isEmpty()) {
            return fail("--full returns every verb; drop it to select `" + String.join(" ", selector) + "` alone");
        }
        ArrayNode all = MAPPER.createArrayNode();
        collect("", spec.root().subcommands(), all);
        boolean index = !full && selector.isEmpty();
        String wanted = String.join(" ", selector);
        ArrayNode verbs = selector.isEmpty() ? all : named(wanted, all);
        if (verbs.isEmpty()) {
            return fail(unknownSelector(wanted, all));
        }
        ObjectNode root = MAPPER.createObjectNode();
        // the live root command name (= Cli.TOOL_NAME) — derived, never re-hardcoded, so it can't drift
        root.put("tool", spec.root().name());
        // the machine-readable version surface (CLI-SPEC §2a) — what this build is + what it was built/
        // verified against; the human peer is `--version`. A cold agent reads it from `manifest.version`.
        root.set("version", VersionInfo.current().toJson(MAPPER));
        // The standard result-envelope schema remains available even though explicitly described artifact
        // operations can return an artifact on stdout and a result receipt only when -o is selected.
        root.put("resultSchema", "result");
        if (index) {
            root.put("detail", DETAIL);
        }
        // What the operational axes actually claim, where the bare enum name under-describes it: once per document
        // that carries profiles (`schema <verb>` repeats it per verb that can write), so never in the index.
        if (!index) {
            root.set("contractVocabulary", CommandContracts.vocabulary());
            // What each content role claims, so a consumer learns the vocabulary from the manifest itself.
            ObjectNode contentRoles = root.putObject("contentRoles");
            for (ContentRole role : ContentRole.values()) {
                contentRoles.put(role.name(), role.meaning());
            }
        }
        // the -m/--model context option is inherited by every verb — list it once, globally
        ArrayNode globals = root.putArray("globalOptions");
        for (OptionSpec o : spec.root().options()) {
            if (o.usageHelp() || o.versionHelp()) {
                continue;
            }
            globals.add(option(o));
        }
        ArrayNode emitted = root.putArray("verbs");
        verbs.forEach(verb -> emitted.add(index ? indexEntry(verb) : verb));
        try {
            out().print(MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(root) + "\n");
            return 0;
        } catch (JsonProcessingException ex) {
            return fail("cannot serialize manifest: " + ex.getMessage());
        }
    }

    /** The verbs {@code wanted} names: the one verb it spells, or every verb of the target it spells. */
    private static ArrayNode named(String wanted, ArrayNode verbs) {
        ArrayNode named = MAPPER.createArrayNode();
        verbs.forEach(verb -> {
            String name = verb.path("verb").asText();
            if (name.equals(wanted) || name.startsWith(wanted + " ")) {
                named.add(verb);
            }
        });
        return named;
    }

    /** The verb's routing fields only: its name, its schema pointer when it has one, and its description. */
    private static ObjectNode indexEntry(JsonNode verb) {
        ObjectNode entry = MAPPER.createObjectNode();
        entry.set("verb", verb.get("verb"));
        if (verb.has("schema")) {
            entry.set("schema", verb.get("schema"));
        }
        entry.set("description", verb.get("description"));
        return entry;
    }

    /** Names what does exist: the target's own verbs when the first word is a target, else every target and root verb. */
    private static String unknownSelector(String wanted, ArrayNode verbs) {
        String first = wanted.split(" ")[0];
        TreeSet<String> sameTarget = new TreeSet<>();
        TreeSet<String> starts = new TreeSet<>();
        verbs.forEach(verb -> {
            String name = verb.path("verb").asText();
            if (name.startsWith(first + " ")) {
                sameTarget.add(name);
            }
            starts.add(name.split(" ")[0]);
        });
        return "no verb or target `" + wanted + "` in the manifest; "
                + (sameTarget.isEmpty() ? "targets and root verbs: " + String.join(", ", starts)
                        : "`" + first + "` has: " + String.join(", ", sameTarget));
    }

    /**
     * Walk the tree: emit each runnable leaf op under its qualified name, recursing into target groups.
     * Hidden commands are skipped — the manifest is the canonical target-first surface. (None today; this
     * stays as the invariant so a hidden command can never leak into the contract.)
     */
    private void collect(String prefix, Map<String, CommandLine> subs, ArrayNode verbs) {
        for (Map.Entry<String, CommandLine> e : subs.entrySet()) {
            CommandSpec cmd = e.getValue().getCommandSpec();
            if (cmd.usageMessage().hidden()) {
                continue;
            }
            String name = prefix.isEmpty() ? e.getKey() : prefix + " " + e.getKey();
            Map<String, CommandLine> children = cmd.subcommands();
            if (children.isEmpty()) {
                verbs.add(verb(name, cmd));
            } else {
                collect(name, children, verbs);
            }
        }
    }

    /**
     * One verb entry. The qualified {@code name} carries the two op-record axes: {@code target} (the group,
     * for a nested op) and {@code op} (the leaf). Root-level verbs (meta + cross-cutting) carry {@code op}
     * only. Each param exposes both its CLI {@code flag} and the op-record {@code key} (the {@code --}-stripped
     * name a batched {@code apply} op uses), so an agent assembles any call straight from the manifest.
     */
    private ObjectNode verb(String name, CommandSpec cmd) {
        ObjectNode v = MAPPER.createObjectNode();
        v.put("verb", name);
        int sp = name.lastIndexOf(' ');
        if (sp >= 0) {
            v.put("target", name.substring(0, sp));
            v.put("op", name.substring(sp + 1));
        } else {
            v.put("op", name);
        }
        // The directional schema pointer. CONTRACT_EXEMPT verbs omit this pointer but still carry the separate
        // operationalSchema pointer below, so no command is absent from the table-owned contract surface.
        if (!SchemaCommand.CONTRACT_EXEMPT.containsKey(name)) {
            v.put("schema", name);
        }
        // Every leaf has an operational contract even when its directional I/O shape is deliberately exempt.
        v.put("operationalSchema", name);
        v.put("description", String.join(" ", cmd.usageMessage().description()));
        // a worked demonstration — the verb IN ACTION ({intent, command}), so an agent learns it from one read
        JsonNode example = EXAMPLES.get(name);
        if (example != null && example.hasNonNull("command")) {
            ObjectNode ex = v.putObject("example");
            ex.put("intent", example.path("intent").asText());
            ex.set("command", example.get("command"));
        }
        // Operational semantics are not inferred from prose. This is the same typed owner rendered by
        // `schema <verb>` and the generated leaf-help footer.
        v.set("operationalProfiles", CommandContracts.toJson(name, cmd));
        // the semantic kind of each param (so an agent knows what to pass; derived from @ParamKind tags)
        Map<String, ParamKind.Kind> kinds = ParamKinds.of(cmd);
        ArrayNode params = v.putArray("params");
        for (PositionalParamSpec p : cmd.positionalParameters()) {
            ObjectNode n = params.addObject();
            String key = ParamKinds.paramKey(p.paramLabel());
            n.put("flag", p.paramLabel());
            n.put("key", key);
            n.put("positional", true);
            n.put("required", p.required());
            n.put("description", String.join(" ", p.description()));
            describeContent(n, p);
            if (kinds.containsKey(key)) {
                n.put("kind", kinds.get(key).name());
            }
        }
        for (OptionSpec o : cmd.options()) {
            if (o.usageHelp() || o.versionHelp() || o.inherited()) {
                continue; // -m is inherited — shown once under globalOptions, not on every verb
            }
            ObjectNode n = option(o);
            String key = ParamKinds.paramKey(o.longestName());
            if (kinds.containsKey(key)) {
                n.put("kind", kinds.get(key).name());
            }
            params.add(n);
        }
        return v;
    }

    private ObjectNode option(OptionSpec o) {
        ObjectNode n = MAPPER.createObjectNode();
        n.put("flag", o.longestName());
        n.put("key", ParamKinds.paramKey(o.longestName()));
        n.put("positional", false);
        n.put("required", o.required());
        n.put("description", String.join(" ", o.description()));
        describeContent(n, o);
        return n;
    }

    /**
     * Publish the value shape a parameter takes, and — where it names something on the filesystem — which way
     * content flows through it (CG8). The label was previously emitted only for positionals, so an option's
     * value shape was invisible; {@code contentRole} is derived in {@link ContentRole} from that label plus the
     * flag, never hand-listed per parameter.
     */
    private static void describeContent(ObjectNode n, ArgSpec param) {
        // Only a param that actually ACCEPTS a value has a value shape to publish. picocli synthesizes a label
        // from the field name even for an arity-0 boolean (`--recursive` gets `<recursive>`) and simply never
        // renders it in help; publishing that would tell an agent the flag takes an argument when it does not.
        if (param.arity().max() > 0 && param.paramLabel() != null && !param.paramLabel().isBlank()) {
            n.put("paramLabel", param.paramLabel());
        }
        ContentRole.of(param).ifPresent(role -> n.put("contentRole", role.name()));
    }
}
