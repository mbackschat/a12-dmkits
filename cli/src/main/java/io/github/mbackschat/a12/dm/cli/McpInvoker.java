package io.github.mbackschat.a12.dm.cli;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Stream;

import com.fasterxml.jackson.core.JacksonException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import picocli.CommandLine.Model.ArgSpec;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Model.OptionSpec;
import picocli.CommandLine.Model.PositionalParamSpec;

/**
 * Runs one {@code tools/call} (MCP-SPEC §7). Two rules govern everything here, and both are hard:
 *
 * <ol>
 *   <li><b>The server never receives, resolves, or returns a client-side path.</b> Every input the caller
 *       controls crosses the wire as content; every path the verb needs is server-generated.</li>
 *   <li><b>The server stores nothing.</b> Nothing a call creates survives it.</li>
 * </ol>
 *
 * <p>That is a simplification rather than a cost: it deletes a hazard class instead of defending against one.
 * With no caller-supplied path there is no traversal surface to sanitise, no ambient workspace to freeze
 * into, and no relative path resolving against a server directory the caller cannot see.
 *
 * <p><b>Why a temporary tree at all.</b> Seven verbs accept their content only as a file, and {@code -m} is a
 * path — so the content is materialised per request under server-generated names, the verb runs against
 * those, outbound artifacts are read back, and the tree is deleted. Content therefore does <i>transiently</i>
 * touch disk: rule 2 is a <b>lifetime</b> guarantee, not an absence of writes, and {@code
 * McpToolCallTest.noRequestTreeSurvivesTheCall} is what holds it.
 *
 * <p><b>Model content is never parsed here.</b> Inbound bytes go to the tree verbatim and the CLI does the
 * parsing it already does correctly; outbound bytes are read back and carried as text. Number fidelity is
 * then unlosable by construction rather than dependent on configuring a mapper — the reason this layer's
 * mapper is allowlisted for frames only.
 */
final class McpInvoker {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** The request tree's name prefix — also what the residency guard looks for. */
    static final String REQUEST_TREE_PREFIX = "dmtool-mcp-";

    /** The subject model's server-generated file name. No caller-supplied component is ever used. */
    private static final String SUBJECT = "subject.dm.json";

    private McpInvoker() {
    }

    static McpResult call(CommandSpec root, List<McpToolRegistry.McpTool> tools, McpRequest request)
            throws McpFailure {
        String name = request.params().path("name").asText(null);
        McpToolRegistry.McpTool tool = tools.stream()
                .filter(candidate -> candidate.name().equals(name)).findFirst()
                .orElseThrow(() -> new McpFailure(McpProtocol.INVALID_PARAMS, "Unknown tool: " + name));
        JsonNode arguments = request.params().path("arguments");
        ObjectNode args = arguments.isObject() ? (ObjectNode) arguments : MAPPER.createObjectNode();
        String op = resolveOp(tool, args);
        Leaf leaf = leafOf(root, tool, op);

        requireAuthorityFor(leaf, args);

        Path tree = createRequestTree();
        try {
            List<String> argv = argv(leaf, args, tree);
            Map<Path, String> before = snapshot(tree);
            StringBuilder out = new StringBuilder();
            StringBuilder err = new StringBuilder();
            int exit = Cli.run(argv.toArray(String[]::new), out, err);
            return result(exit, scrub(out.toString(), tree), scrub(err.toString(), tree), changed(tree, before));
        } catch (IOException failure) {
            throw new McpFailure(McpProtocol.INTERNAL_ERROR, "cannot materialise the request: " + failure);
        } finally {
            delete(tree);
        }
    }

    /** A leaf of the live tree, resolved from the tool's group and the requested op. */
    private record Leaf(String verb, List<String> tokens, CommandSpec command) {
    }

    /**
     * The closed-world gate: a verb whose correctness depends on seeing the whole workspace runs only when
     * the caller has asserted that what it sent <b>is</b> the whole workspace, and is refused otherwise.
     *
     * <p>Refused rather than degraded, because the failure it prevents is silent. {@code model rename}
     * scans inward for a sibling that includes the old id and refuses when it finds one; handed a partial
     * tree it finds nothing and permits the rename, turning "not supplied" into "proved absent". No
     * diagnostic can catch that afterwards — the answer looks like success.
     */
    private static void requireAuthorityFor(Leaf leaf, ObjectNode args) throws McpFailure {
        String why = McpToolRegistry.CLOSED_WORLD_VERBS.get(leaf.verb());
        if (why == null || args.path(McpToolRegistry.AUTHORITY_ASSERTION).asBoolean(false)) {
            return;
        }
        throw new McpFailure(McpProtocol.INVALID_PARAMS,
                "`" + leaf.verb() + "` " + why + ". It therefore runs only when you set `"
                        + McpToolRegistry.AUTHORITY_ASSERTION + ": true`, asserting that the models carried "
                        + "in this request are the COMPLETE workspace rather than a subset — on a partial "
                        + "tree it would answer confidently about a fragment, and nothing downstream could "
                        + "tell. Send every referenced model in `references`, then assert it.");
    }

    private static String resolveOp(McpToolRegistry.McpTool tool, ObjectNode args) throws McpFailure {
        if (tool.ops().size() == 1) {
            return tool.ops().get(0);
        }
        String op = args.path("op").asText(null);
        if (op == null || !tool.ops().contains(op)) {
            throw new McpFailure(McpProtocol.INVALID_PARAMS,
                    "`" + tool.name() + "` needs an `op`, one of " + tool.ops() + "; got " + args.path("op"));
        }
        return op;
    }

    /** Walk the live tree to the leaf the tool's group and op name address. */
    private static Leaf leafOf(CommandSpec root, McpToolRegistry.McpTool tool, String op) throws McpFailure {
        String group = tool.name().substring("dm.".length()).split("\\.")[0];
        List<String> candidate = root.subcommands().containsKey(op) ? List.of(op) : List.of(group, op);
        CommandSpec command = root;
        for (String token : candidate) {
            var child = command.subcommands().get(token);
            if (child == null) {
                throw new McpFailure(McpProtocol.INTERNAL_ERROR, "the tool surface names an absent verb " + op);
            }
            command = child.getCommandSpec();
        }
        return new Leaf(String.join(" ", candidate), candidate, command);
    }

    /**
     * Build the argument vector. Every path in it is one this server generated; a caller-supplied one is
     * refused rather than sanitised, because refusing the shape is strictly safer than cleaning it.
     */
    private static List<String> argv(Leaf leaf, ObjectNode args, Path tree) throws McpFailure, IOException {
        List<String> argv = new ArrayList<>();
        if (args.path("model").isTextual()) {
            Path subject = tree.resolve(SUBJECT);
            Files.writeString(subject, args.path("model").asText(), StandardCharsets.UTF_8);
            argv.add("-m");
            argv.add(subject.toString());
            // Explicit, always: without it the workspace would fall back to $DMTOOL_WORKSPACE, letting the
            // server's own environment decide what a caller's references resolve against.
            argv.add("-w");
            argv.add(tree.toString());
        }
        int reference = 0;
        for (JsonNode content : args.path("references")) {
            Files.writeString(tree.resolve("reference-" + (++reference) + ".dm.json"), content.asText(),
                    StandardCharsets.UTF_8);
        }
        argv.addAll(leaf.tokens());

        Map<String, ArgSpec> parameters = parameters(leaf.command());
        List<String> options = new ArrayList<>();
        // Positionals are keyed by their DECLARED index, never by the order the caller happened to send:
        // `model diff <baseFile> <headFile>` would otherwise compare the two the wrong way round.
        Map<Integer, List<String>> positionals = new java.util.TreeMap<>();
        // A verb whose subject is a DIRECTORY gets the request tree itself. The path is server-generated, so
        // the content rule holds: the caller supplies the models, never the location they live at. Derived
        // from the published role rather than a list of workspace verbs, so a new one is covered by default.
        for (PositionalParamSpec positional : leaf.command().positionalParameters()) {
            if (ContentRole.of(positional).filter(role -> role == ContentRole.SCOPE_DIRECTORY).isPresent()) {
                positionals.put(positional.index().min(), List.of(tree.toString()));
            }
        }
        for (Map.Entry<String, JsonNode> file : files(args, leaf, tree).entrySet()) {
            emit(parameters.get(file.getKey()), file.getValue(), positionals, options);
        }
        for (Map.Entry<String, JsonNode> entry : args.path("args").properties()) {
            ArgSpec parameter = parameters.get(entry.getKey());
            if (parameter == null) {
                // Unknown to the verb: let the verb's own corrective diagnostics answer, which they do well.
                options.add("--" + entry.getKey());
                options.add(entry.getValue().asText());
                continue;
            }
            Optional<ContentRole> role = ContentRole.of(parameter);
            if (role.isPresent()) {
                throw new McpFailure(McpProtocol.INVALID_PARAMS,
                        "`" + entry.getKey() + "` names a filesystem path (" + role.get().meaning() + "), and "
                                + "this server accepts no caller-supplied path. Send content instead — a model "
                                + "as `model`, a referenced model in `references`, any other file under `files` "
                                + "— and read the result back out of the response.");
            }
            emit(parameter, entry.getValue(), positionals, options);
        }
        positionals.values().forEach(argv::addAll);
        argv.addAll(options);
        return argv;
    }

    /**
     * Materialise the non-model content parameters. The file name is the parameter key from <b>our</b> list,
     * reached only by an exact match against a caller-supplied string — so no caller-controlled component
     * ever reaches the filesystem, and a name that is not a content parameter of this verb is refused.
     */
    private static Map<String, JsonNode> files(ObjectNode args, Leaf leaf, Path tree)
            throws McpFailure, IOException {
        Map<String, JsonNode> written = new LinkedHashMap<>();
        JsonNode files = args.path("files");
        if (!files.isObject()) {
            return written;
        }
        List<String> inbound = McpToolRegistry.inboundContentKeys(leaf.command());
        List<String> directories = McpToolRegistry.directoryContentKeys(leaf.command());
        for (Map.Entry<String, JsonNode> file : files.properties()) {
            if (directories.contains(file.getKey())) {
                throw new McpFailure(McpProtocol.INVALID_PARAMS,
                        "`" + file.getKey() + "` takes a DIRECTORY whose FILE NAMES are the identifiers "
                                + "(each `<Name>.js` registers `<Name>`), and this server names every carried "
                                + "file itself, so it cannot accept them. A model is different — it carries "
                                + "its id inside its content, which is why `references` works. Run the verb "
                                + "directly if you need custom JS.");
            }
            String key = inbound.stream().filter(candidate -> candidate.equals(file.getKey())).findFirst()
                    .orElseThrow(() -> new McpFailure(McpProtocol.INVALID_PARAMS,
                            "`" + leaf.verb() + "` takes no content parameter `" + file.getKey() + "`; it takes "
                                    + inbound));
            Path path = tree.resolve(key.contains(".") ? key : key + ".json");
            Files.writeString(path, file.getValue().asText(), StandardCharsets.UTF_8);
            written.put(key, MAPPER.getNodeFactory().textNode(path.toString()));
        }
        return written;
    }

    private static Map<String, ArgSpec> parameters(CommandSpec command) {
        Map<String, ArgSpec> parameters = new LinkedHashMap<>();
        for (PositionalParamSpec positional : command.positionalParameters()) {
            parameters.put(ParamKinds.paramKey(positional.paramLabel()), positional);
        }
        for (OptionSpec option : command.options()) {
            if (!option.usageHelp() && !option.versionHelp()) {
                parameters.put(ParamKinds.paramKey(option.longestName()), option);
            }
        }
        return parameters;
    }

    /**
     * One parameter's tokens. A boolean is a presence flag: {@code false} emits nothing, because picocli's
     * negated form is not universal here and an absent flag is what "off" means on this surface.
     */
    private static void emit(ArgSpec parameter, JsonNode value, Map<Integer, List<String>> positionals,
            List<String> options) {
        if (parameter instanceof PositionalParamSpec positional) {
            // A positional may be VARIADIC — `schema <schema>...` takes `rule add` as two tokens of one
            // parameter — so an array contributes each element in order rather than being stringified.
            positionals.put(positional.index().min(), value.isArray()
                    ? java.util.stream.StreamSupport.stream(value.spliterator(), false).map(JsonNode::asText).toList()
                    : List.of(value.asText()));
            return;
        }
        OptionSpec option = (OptionSpec) parameter;
        String flag = option.longestName();
        if (value.isBoolean()) {
            if (value.asBoolean()) {
                options.add(flag);
            }
            return;
        }
        if (value.isArray()) {
            value.forEach(element -> {
                options.add(flag);
                options.add(element.asText());
            });
            return;
        }
        options.add(flag);
        options.add(value.asText());
    }

    /**
     * Turn one run into a tool result. The envelope becomes {@code structuredContent} — the form the declared
     * {@code outputSchema} describes — while {@code content} carries the same answer as the human-readable
     * rendering the CLI itself produces for {@code --text}.
     *
     * <p>The specification SHOULDs that a tool returning structured content also return the serialized JSON
     * in a text block, for a client that ignores {@code structuredContent}. That client is served here by the
     * rendering, which carries the same information in the form a model reads better; emitting the JSON twice
     * would double every response for a client that already has it.
     *
     * <p>{@code isError} keys on the <b>outcome</b>, not the exit code: a `refused`/`rejected`/`error`
     * envelope is actionable feedback a model can self-correct from, while {@code model check} reporting an
     * invalid model is a successful answer to the question asked.
     */
    private static McpResult result(int exit, String out, String err, List<Artifact> artifacts) {
        ObjectNode payload = MAPPER.createObjectNode();
        ArrayNode content = payload.putArray("content");
        Optional<ObjectNode> envelope = envelope(out);
        boolean isError;
        if (envelope.isPresent()) {
            payload.set("structuredContent", envelope.get());
            text(content, TextRenderer.renderIfEnvelope(out));
            isError = switch (envelope.get().path("outcome").asText("")) {
                case "refused", "rejected", "error" -> true;
                default -> false;
            };
        } else if (!out.isBlank()) {
            text(content, out);
            isError = exit != 0;
        } else {
            // Empty stdout means the invocation never reached the verb: the whole corrective surface — the
            // spec-input hint, the locator hint, the usage — is on stderr, and dropping it would lose the
            // answer on the likeliest outcome of a generated call.
            text(content, err.isBlank() ? "the command produced no output (exit " + exit + ")" : err);
            isError = exit != 0;
        }
        artifacts.forEach(artifact -> {
            ObjectNode block = content.addObject();
            block.put("type", "resource");
            ObjectNode resource = block.putObject("resource");
            resource.put("uri", OUT_NAMESPACE + artifact.name());
            resource.put("mimeType", "application/json");
            resource.put("text", artifact.content());
        });
        payload.put("isError", isError);
        return McpResult.complete(payload);
    }

    /** The namespace an artifact is addressed in — the same one the embedded resources use. */
    private static final String OUT_NAMESPACE = "dm:///out/";

    /**
     * Rewrite the request tree out of anything the caller sees.
     *
     * <p>A verb that creates a file reports where it put it, and where it put it is a path this server
     * generated inside a temporary directory: `group extract` published
     * {@code changed.subModel: "/var/folders/…/dmtool-mcp-…/shipping-address.dm.json"}. That is meaningless
     * to a caller who supplied no path, and it discloses the server's filesystem layout — the output half of
     * the rule whose input half is refusing caller paths. Rewritten, it names the artifact the response
     * already carries, so the receipt and the bytes agree.
     *
     * <p>Textual, and deliberately so: it must catch the path wherever a verb chose to mention it — an
     * envelope field, a diagnostic message, a corrective on stderr — not only where this layer expects it.
     */
    private static String scrub(String text, Path tree) {
        String root = tree.toString();
        if (text.isEmpty() || !text.contains(root)) {
            return text;
        }
        // JSON escapes a Windows separator, so the escaped spelling is rewritten too rather than surviving.
        return text.replace(root.replace("\\", "\\\\") + "\\\\", OUT_NAMESPACE)
                .replace(root + java.io.File.separator, OUT_NAMESPACE)
                .replace(root, OUT_NAMESPACE);
    }

    private static void text(ArrayNode content, String value) {
        ObjectNode block = content.addObject();
        block.put("type", "text");
        block.put("text", value);
    }

    private static Optional<ObjectNode> envelope(String out) {
        try {
            JsonNode parsed = MAPPER.readTree(out);
            return parsed instanceof ObjectNode object && object.path("outcome").isTextual()
                    ? Optional.of(object) : Optional.empty();
        } catch (JacksonException notJson) {
            return Optional.empty();
        }
    }

    /** A file the call produced or changed, named relative to the request tree. */
    private record Artifact(String name, String content) {
    }

    private static Path createRequestTree() throws McpFailure {
        try {
            return Files.createTempDirectory(REQUEST_TREE_PREFIX);
        } catch (IOException failure) {
            throw new McpFailure(McpProtocol.INTERNAL_ERROR, "cannot create a request tree: " + failure);
        }
    }

    private static Map<Path, String> snapshot(Path tree) throws IOException {
        Map<Path, String> contents = new LinkedHashMap<>();
        try (Stream<Path> files = Files.walk(tree)) {
            for (Path file : files.filter(Files::isRegularFile).toList()) {
                contents.put(file, Files.readString(file, StandardCharsets.UTF_8));
            }
        }
        return contents;
    }

    /** Everything the run created or rewrote — which is how an edited subject and a new sub-model both return. */
    private static List<Artifact> changed(Path tree, Map<Path, String> before) throws IOException {
        List<Artifact> artifacts = new ArrayList<>();
        try (Stream<Path> files = Files.walk(tree)) {
            for (Path file : files.filter(Files::isRegularFile).sorted().toList()) {
                String now = Files.readString(file, StandardCharsets.UTF_8);
                if (!now.equals(before.get(file))) {
                    artifacts.add(new Artifact(tree.relativize(file).toString(), now));
                }
            }
        }
        return artifacts;
    }

    private static void delete(Path tree) {
        try (Stream<Path> files = Files.walk(tree)) {
            files.sorted(Comparator.reverseOrder()).forEach(path -> {
                try {
                    Files.deleteIfExists(path);
                } catch (IOException failure) {
                    throw new UncheckedIOException(failure);
                }
            });
        } catch (IOException | UncheckedIOException failure) {
            // Nothing durable is at stake — the tree holds only this call's content — but a leak would break
            // the lifetime guarantee, so it is reported rather than swallowed.
            throw new IllegalStateException("the request tree outlived its call: " + tree, failure);
        }
    }
}
