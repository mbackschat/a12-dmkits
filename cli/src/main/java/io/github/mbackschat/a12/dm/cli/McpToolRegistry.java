package io.github.mbackschat.a12.dm.cli;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import picocli.CommandLine;
import picocli.CommandLine.Model.CommandSpec;

/**
 * The tool surface, <b>derived</b> from the live command tree and {@link CommandContracts} (MCP-SPEC §6).
 * Nothing here is authored per verb, so the projection cannot drift from the tool it projects: a verb added
 * to the picocli tree appears, and one whose effect changes moves to the truthful cell by itself.
 *
 * <p><b>Why the grouping is not a matter of taste.</b> MCP lets a tool carry exactly two facts about what it
 * does — {@code readOnlyHint}, true only if every op is read-only, and {@code outputSchema}, declarable only
 * if every op always returns a conforming structured result. Both are per-<i>tool</i>, so any tool spanning
 * ops that disagree must drop the fact. The correct grouping is therefore the partition of the ops by those
 * two properties inside each target: a coarser one must lie about a fact, a finer one adds definitions
 * without adding truth.
 *
 * <p>The tree is taken from the <b>running</b> command rather than rebuilt, so a verb hidden from the native
 * command tree ({@code Cli.JVM_ONLY_VERBS}) is absent here too — a registry that built its own tree would
 * advertise verbs the same binary refuses.
 */
final class McpToolRegistry {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** The prefix. A host that scopes tool names does it anyway; this serves the direct-connect ones. */
    private static final String PREFIX = "dm.";

    /** The four target-less catalog verbs, grouped: the manifest emits no target for a root-level verb. */
    private static final List<String> CATALOG_VERBS = List.of("operators", "diagnostics", "schema", "manifest");

    private static final String CATALOG_GROUP = "catalog";

    /**
     * Live leaves this server does <b>not</b> route to, each with the reason it cannot be served — data, not
     * a silent omission, because an unexplained hole in a derived surface reads as a bug. Two classes:
     *
     * <ul>
     *   <li><b>a directory is the input.</b> Carrying a tree is a different shape from carrying a document,
     *       and three of these additionally answer a question quantified over a whole workspace — served
     *       from a caller-declared tree they would silently answer about the fragment that was sent;</li>
     *   <li><b>a refusal that must prove ABSENCE.</b> {@code model rename} scans the workspace inward for a
     *       sibling that includes the old id and refuses if it finds one. Given only what a caller chose to
     *       send, it would find nothing and permit the rename — turning "not supplied" into "proved absent",
     *       which silently degrades a safety gate rather than failing loudly. Reachable again behind an
     *       explicit authority assertion (MCP-GAPS MG7), never by default.</li>
     * </ul>
     */
    static final Map<String, String> EXCLUDED_VERBS = Map.of(
            "mcp", "the server itself — routing to it would nest a session inside a session",
            "workspace list", "takes a directory whose contents are the input, and answers over the whole set",
            "workspace graph", "takes a directory whose contents are the input, and answers over the whole set",
            "workspace check", "takes a directory whose contents are the input, and answers over the whole set",
            "workspace roles", "takes a directory whose contents are the input, and answers over the whole set",
            "model rename", "refuses on an INWARD workspace scan, so it must prove a sibling's absence; a "
                    + "caller-declared tree cannot establish that, and the gate would pass silently");

    /** One tool: a cell of (target × effect-class × return-class), with the two facts MCP can carry. */
    record McpTool(String name, String description, List<String> ops, boolean readOnly, JsonNode outputSchema,
                   ObjectNode inputSchema) {

        ObjectNode toJson() {
            ObjectNode node = MAPPER.createObjectNode();
            node.put("name", name);
            node.put("description", description);
            node.set("inputSchema", inputSchema);
            if (outputSchema != null) {
                node.set("outputSchema", outputSchema);
            }
            ObjectNode annotations = node.putObject("annotations");
            annotations.put("readOnlyHint", readOnly);
            // Not "touches nothing external": `model eval` spawns node for the custom-type bridge and
            // `model diff --since` spawns git. Both are local subprocesses under the caller's own account —
            // there is no open world of external entities, which is what the hint actually claims.
            annotations.put("openWorldHint", false);
            return node;
        }
    }

    private McpToolRegistry() {
    }

    /** Every tool, in deterministic order — clients cache the list, and a stable order keeps that cheap. */
    static List<McpTool> tools(CommandSpec root) {
        Map<Cell, List<Leaf>> cells = new LinkedHashMap<>();
        for (Leaf leaf : leaves(root)) {
            cells.computeIfAbsent(cellOf(leaf), key -> new ArrayList<>()).add(leaf);
        }
        Map<String, Long> cellsPerGroup = new TreeMap<>();
        cells.keySet().forEach(cell -> cellsPerGroup.merge(cell.group(), 1L, Long::sum));
        List<McpTool> tools = new ArrayList<>();
        cells.forEach((cell, leaves) -> tools.add(tool(cell, leaves, cellsPerGroup.get(cell.group()) > 1)));
        tools.sort(Comparator.comparing(McpTool::name));
        return List.copyOf(tools);
    }

    /** The whole surface as a {@code tools/list} payload. */
    static ArrayNode toolsJson(CommandSpec root) {
        ArrayNode array = MAPPER.createArrayNode();
        tools(root).forEach(tool -> array.add(tool.toJson()));
        return array;
    }

    /** A leaf of the live tree, with the two facts its profiles agree on (or do not). */
    private record Leaf(String verb, String target, String op, CommandSpec command, boolean readOnly,
                        boolean envelope) {
    }

    /** The grouping key: the target (or the root verb's own name), crossed with the two published facts. */
    private record Cell(String group, boolean readOnly, boolean envelope) {
    }

    private static List<Leaf> leaves(CommandSpec root) {
        List<Leaf> leaves = new ArrayList<>();
        collect("", root.subcommands(), leaves);
        return leaves;
    }

    private static void collect(String prefix, Map<String, CommandLine> subcommands, List<Leaf> leaves) {
        for (Map.Entry<String, CommandLine> entry : subcommands.entrySet()) {
            CommandSpec command = entry.getValue().getCommandSpec();
            if (command.usageMessage().hidden()) {
                continue;
            }
            String verb = prefix.isEmpty() ? entry.getKey() : prefix + " " + entry.getKey();
            if (!command.subcommands().isEmpty()) {
                collect(verb, command.subcommands(), leaves);
                continue;
            }
            if (EXCLUDED_VERBS.containsKey(verb)) {
                continue;
            }
            List<CommandContracts.Profile> profiles = CommandContracts.profiles(verb, command);
            int space = verb.lastIndexOf(' ');
            leaves.add(new Leaf(verb, space < 0 ? null : verb.substring(0, space),
                    space < 0 ? verb : verb.substring(space + 1), command,
                    profiles.stream().allMatch(p -> p.effect() == CommandContracts.Effect.READ_ONLY),
                    profiles.stream().allMatch(
                            p -> p.returns().kind() == CommandContracts.ReturnKind.RESULT_ENVELOPE)));
        }
    }

    private static Cell cellOf(Leaf leaf) {
        String group = leaf.target() != null ? leaf.target()
                : CATALOG_VERBS.contains(leaf.verb()) ? CATALOG_GROUP : leaf.verb();
        return new Cell(group, leaf.readOnly(), leaf.envelope());
    }

    /**
     * The suffix names the cell, and a group carrying only one cell stays bare — so a name says exactly as
     * much as the surface makes true, and no more.
     */
    private static String suffix(Cell cell) {
        if (cell.readOnly()) {
            return cell.envelope() ? "read" : "emit";
        }
        return cell.envelope() ? "edit" : "generate";
    }

    private static McpTool tool(Cell cell, List<Leaf> leaves, boolean groupIsSplit) {
        String name = PREFIX + cell.group() + (groupIsSplit ? "." + suffix(cell) : "");
        List<String> ops = leaves.stream().map(Leaf::op).sorted().toList();
        return new McpTool(name, description(cell, leaves, ops), ops, cell.readOnly(),
                cell.envelope() ? OUTPUT_SCHEMA : null,
                inputSchema(leaves, ops));
    }

    /**
     * The declared {@code outputSchema}: the served result schema with its <b>annotations removed</b>.
     *
     * <p>Measured, and the reason this exists: the served document is 12,777 bytes of which <b>81 % is
     * `description` and `examples`</b>. MCP carries one `outputSchema` per tool, so that prose was repeated
     * for each of the 23 eligible tools — 302 KB of a 337 KB catalogue. A host that injects tool definitions
     * into a prompt pays that on every model turn, whether or not a tool is called.
     *
     * <p><b>Why this is safe rather than a compromise.</b> `description` and `examples` are JSON Schema
     * <i>annotations</i>: they assert nothing, so removing them cannot change what validates. The declared
     * schema therefore still means exactly what the served one means, and MCP's requirement that structured
     * results conform to it is untouched — a claim held by a test that validates a real envelope against this
     * copy and still rejects a malformed diagnostic through the embedded definition.
     *
     * <p>One short description survives, because a caller must be able to find the prose it no longer sees.
     */
    private static final JsonNode OUTPUT_SCHEMA = leanResultSchema();

    private static JsonNode leanResultSchema() {
        ObjectNode lean = (ObjectNode) stripAnnotations(SchemaCommand.servedStructure("result"));
        lean.put("description", "This tool's standard result envelope. The copy carries structure only, so a "
                + "tool definition stays small; the field-by-field contract — what `ok`, `valid`, "
                + "`verification` and the pointer forms each assert — is one call away: the `schema` op of "
                + "`dm.catalog`, with args {\"schema\": [\"result\"]}.");
        return lean;
    }

    /** Drop every annotation keyword, at every depth. Structure, {@code $id} and {@code $ref} are untouched. */
    private static JsonNode stripAnnotations(JsonNode node) {
        if (node instanceof ObjectNode object) {
            ObjectNode copy = MAPPER.createObjectNode();
            object.properties().stream()
                    .filter(entry -> !"description".equals(entry.getKey()) && !"examples".equals(entry.getKey()))
                    .forEach(entry -> copy.set(entry.getKey(), stripAnnotations(entry.getValue())));
            return copy;
        }
        if (node instanceof ArrayNode array) {
            ArrayNode copy = MAPPER.createArrayNode();
            array.forEach(element -> copy.add(stripAnnotations(element)));
            return copy;
        }
        return node;
    }

    /**
     * The op list rides the <b>description</b>, not only the schema: client guidance ranks tool text when a
     * host switches to retrieval instead of injecting every definition, and the discriminating words are the
     * op names.
     */
    private static String description(Cell cell, List<Leaf> leaves, List<String> ops) {
        String what = cell.readOnly() ? "Inspect" : "Edit";
        String returns = cell.envelope() ? "Returns the standard result envelope."
                : "Returns the document or rendering the verb produces, as text.";
        String subject = cell.group().equals(CATALOG_GROUP)
                ? "the tool's own catalogues (DSL operators, diagnostics, schemas, the verb map)"
                : cell.group().replace('-', ' ');
        return what + " " + subject + " — ops: " + String.join(", ", ops) + ". " + returns
                + (leaves.stream().anyMatch(leaf -> takesSubjectModel(leaf.command()))
                        ? " Send the model's DM-JSON as `model`; this server reads no file and keeps nothing."
                        : "");
    }

    /**
     * The input shape. {@code args} stays loosely typed on purpose: per-op argument validation is what this
     * tool's corrective diagnostics already do well, and a large discriminated union degrades a model's
     * tool-calling accuracy for no correctness gain.
     */
    private static ObjectNode inputSchema(List<Leaf> leaves, List<String> ops) {
        ObjectNode schema = MAPPER.createObjectNode();
        schema.put("type", "object");
        ObjectNode properties = schema.putObject("properties");
        ArrayNode required = MAPPER.createArrayNode();
        if (ops.size() > 1) {
            ObjectNode op = properties.putObject("op");
            ArrayNode oneOf = op.putArray("oneOf");
            leaves.stream().sorted(Comparator.comparing(Leaf::op)).forEach(leaf -> {
                ObjectNode branch = oneOf.addObject();
                branch.put("const", leaf.op());
                branch.put("description", opDescription(leaf));
            });
            required.add("op");
        }
        ObjectNode args = properties.putObject("args");
        args.put("type", "object");
        args.put("description", "the verb's parameters by the key the manifest publishes, minus every "
                + "parameter that names a filesystem path — those are not caller-settable here");
        if (leaves.stream().anyMatch(leaf -> takesSubjectModel(leaf.command()))) {
            ObjectNode model = properties.putObject("model");
            model.put("type", "string");
            model.put("description", "the subject model's DM-JSON content, verbatim. An edited model comes "
                    + "back in the response; persisting it is the caller's own job");
            ObjectNode references = properties.putObject("references");
            references.put("type", "array");
            references.putObject("items").put("type", "string");
            references.put("description", "the DM-JSON of every model the subject includes or imports type "
                    + "definitions from. Resolution sees exactly what is sent, so an omitted one is an "
                    + "unresolved reference, never a silent success");
        }
        if (leaves.stream().anyMatch(leaf -> !inboundContentKeys(leaf.command()).isEmpty())) {
            ObjectNode files = properties.putObject("files");
            files.put("type", "object");
            files.putObject("additionalProperties").put("type", "string");
            files.put("description", "content for the op's non-model file parameters, keyed by the "
                    + "parameter key the manifest publishes (a rule spec, a document instance, an op list)");
        }
        if (!required.isEmpty()) {
            schema.set("required", required);
        }
        return schema;
    }

    /**
     * The route-level fact {@code annotations} cannot express: MCP carries one effect hint per tool, while a
     * verb carries one profile per argument-selected route. Derived from the registry, so it cannot drift.
     */
    private static String opDescription(Leaf leaf) {
        List<CommandContracts.Profile> profiles = CommandContracts.profiles(leaf.verb(), leaf.command());
        StringBuilder text = new StringBuilder(String.join(" ", leaf.command().usageMessage().description()));
        text.append(" [");
        for (int index = 0; index < profiles.size(); index++) {
            CommandContracts.Profile profile = profiles.get(index);
            if (index > 0) {
                text.append("; ");
            }
            text.append(profile.id()).append(": ").append(profile.effect())
                    .append(", failure=").append(profile.failurePersistence())
                    .append(", dryRun=").append(profile.dryRun());
        }
        return text.append(']').toString();
    }

    /**
     * Whether the op actually <b>consumes</b> a subject model — derived from the {@code WorkspaceOption}
     * mixin, which is how a verb gains model and workspace resolution in the first place.
     *
     * <p>The obvious test is wrong and was: {@code -m} is <i>inherited by every leaf</i>, so asking whether
     * the option exists answers "yes" even for the catalogue verbs, and every tool then advertised a `model`
     * property it ignores — inviting a caller to attach a model to a catalogue lookup. The mixin is the
     * honest signal because it is the mechanism rather than a restatement: a verb that reads a model
     * declares it, `model new` (which makes one) does not, and neither do the four self-describing verbs.
     * A verb that somehow read a model without the mixin would simply not be offered the property and would
     * fail with the CLI's own "pass -m" — legible, not silent.
     */
    private static boolean takesSubjectModel(CommandSpec command) {
        return command.mixins().values().stream()
                .anyMatch(mixin -> mixin.userObject() instanceof WorkspaceOption);
    }

    /** The label of a parameter whose value is a directory of files rather than one file. */
    private static final String DIRECTORY_LABEL = "<dir>";

    /** The op's non-model content parameters that take ONE file, by the key an {@code apply} op record uses. */
    static List<String> inboundContentKeys(CommandSpec command) {
        return contentKeys(command, false);
    }

    /**
     * The op's content parameters that take a <b>directory</b> — excluded from this surface, because inline
     * content for a tree is a different shape from inline content for a document. Published as its own list
     * so the refusal can say which it is instead of "no such parameter".
     *
     * <p>It also disposes of a residency question: the only path to {@code CustomJsWorker} — which registers
     * an unremoved JVM shutdown hook per call and leaves its {@code node} child running until the process
     * exits, harmless in a one-shot CLI and a leak in a resident server — is through the two custom-JS
     * directories, and both are in this list. Unreachable, measured rather than hoped.
     */
    static List<String> directoryContentKeys(CommandSpec command) {
        return contentKeys(command, true);
    }

    private static List<String> contentKeys(CommandSpec command, boolean directories) {
        List<String> keys = new ArrayList<>();
        command.positionalParameters().forEach(positional -> {
            if (isInboundContent(positional, directories)) {
                keys.add(ParamKinds.paramKey(positional.paramLabel()));
            }
        });
        command.options().stream().filter(option -> !option.inherited()).forEach(option -> {
            if (isInboundContent(option, directories)) {
                keys.add(ParamKinds.paramKey(option.longestName()));
            }
        });
        return List.copyOf(keys);
    }

    private static boolean isInboundContent(CommandLine.Model.ArgSpec param, boolean directories) {
        return ContentRole.of(param).filter(role -> role == ContentRole.INBOUND_CONTENT).isPresent()
                && DIRECTORY_LABEL.equals(param.paramLabel()) == directories;
    }
}
