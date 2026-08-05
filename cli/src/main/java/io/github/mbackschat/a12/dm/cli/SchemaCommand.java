package io.github.mbackschat.a12.dm.cli;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Parameters;

/**
 * The {@code schema} verb (CLI-SPEC §5a) — learn a contract <b>on demand</b>, addressed with the same tokens
 * as the verbs:
 * <ul>
 *   <li>{@code schema result} / {@code schema diagnostic} — the cross-cutting <b>structures</b> (raw JSON
 *       Schema): the standard result envelope and a single finding.</li>
 *   <li>{@code schema <target> <op>} — that op's <b>directional contract</b> {@code {input?, returns, data?}}:
 *       what it <em>consumes</em> (a rich JSON input like the rule-spec) and whether it <em>returns</em> the
 *       result envelope or an artifact. One call, unambiguous direction. Flag-only ops carry no {@code input}
 *       (their params are in the manifest); reads note that their payload rides the envelope's {@code data}.</li>
 *   <li>Every runnable leaf, including a directionally exempt artifact/catalog verb, serves the operational
 *       profiles projected from {@link CommandContracts}; exempt leaves return an operational-only contract.</li>
 * </ul>
 * Schemas are never attached to a verb's own response (that would bloat every call); they live here, fetched
 * when wanted. Structure schemas are classpath resources under {@code /schema/<name>.schema.json}; the
 * directional contracts are assembled from the live command tree. Drift-locked by {@code SchemaVerbTest}.
 */
@Command(name = "schema", mixinStandardHelpOptions = true,
        description = "Emit a structure schema or a verb's directional/operational contract "
                + "(`schema <target> <op>`), on demand.")
final class SchemaCommand extends BaseCommand {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** The cross-cutting structures with a published raw JSON Schema (CI-locked against drift). */
    static final List<String> STRUCTURES = List.of("result", "diagnostic");

    /** The batch op-record frames, served as raw JSON Schema (the list frame + a worked, CI-executed example). */
    static final List<String> FRAMES = List.of("apply", "batch");

    /** Schema resources embedded under a named property of another public schema response. */
    record EmbeddedSchema(String property, String schema) { }

    static final Map<String, EmbeddedSchema> EMBEDDED_SCHEMAS = Map.of(
            "batch", new EmbeddedSchema("observationArtifact", "batch-observations"));

    /** Ops that consume a rich JSON input file → the input schema resource that documents it. */
    static final Map<String, String> INPUT_OPS = Map.of(
            "rule add", "rule-add",
            "computation add", "computation-add",
            "computation modify", "computation-add",
            "field add", "field-add",
            "field modify", "field-modify",
            "typedef add", "typedef-add",
            "typedef modify", "typedef-add",
            "profile synthesize", "profile");

    /** One artifact route: its kind, optional output schema, and optional file-writing receipt branch. */
    record ArtifactContract(String kind, String schema, String outputOption) {
        ArtifactContract {
            if (kind.isBlank()) {
                throw new IllegalArgumentException("artifact kind must not be blank");
            }
        }
    }

    /** Ops whose primary stdout value is an artifact rather than the result envelope. */
    static final Map<String, ArtifactContract> ARTIFACT_OPS = Map.of(
            "profile synthesize", new ArtifactContract("dm-json-model", null, "-o"),
            "model seed", new ArtifactContract("a12-document-json", "document-instance", null));

    /** Ops that consume a {@code --instance} document INSTANCE → the schema describing that instance tree. */
    static final Map<String, String> DOC_INPUT = Map.of(
            "model eval", "document-instance",
            "model compute", "document-instance",
            "rule eval", "document-instance");

    /** Directional contract notes for flag-only verbs whose cross-value relation is not a JSON input shape. */
    static final Map<String, String> CONTRACT_NOTES = Map.of(
            "config modify",
            "time-zone has four domains: absence defaults UTC; exact UTC, GMT, Europe/Berlin are "
                    + "interpreter-supported; another kernel-valid Java id is accepted with an evaluator-scope "
                    + "warning; explicit null/blank/unknown ids are rejected",
            "patterns",
            "four routes, one envelope. `patterns` lists the idioms and `patterns <id>` details one — both reads "
                    + "with the catalog in `data`. `patterns <id> --arg k=v` (needs -m) scaffolds a rule-spec and "
                    + "kernel-checks it: a VERDICT carrying `valid` + `verification` + `diagnostics`, so a rejected "
                    + "scaffold exits 1 while the spec still rides `data.spec` for inspection. Adding --apply makes "
                    + "it a write: a kernel-rejected spec becomes `rejected`, --dry-run a preview, and a committed "
                    + "one an applied result naming where it wrote (-o redirects). See `schema result`");

    /** Read ops whose {@code data} payload has a published schema → surfaced so the agent learns the output shape. */
    static final Map<String, String> DATA_SCHEMA = Map.ofEntries(
            Map.entry("model info", "model-info"),
            Map.entry("model describe", "describe"),
            Map.entry("model diff", "model-diff"),
            Map.entry("model usage", "model-usage"),
            Map.entry("model report", "model-report"),
            Map.entry("profile extract", "profile"),
            Map.entry("profile compare", "profile-comparison"),
            Map.entry("where-used", "where-used"),
            Map.entry("meta", "meta-read"),
            Map.entry("field read", "field-read"),
            Map.entry("group read", "group-read"),
            Map.entry("typedef read", "typedef-read"),
            Map.entry("rule read", "read"),
            Map.entry("rule explain", "explain"),
            Map.entry("model eval", "eval-doc"),
            Map.entry("model compute", "compute-doc"),
            Map.entry("rule eval", "rule-eval"),
            // Three payload shapes on one verb, selected by the arguments rather than a flag — so the `oneOf`
            // is the contract, not a convenience (CG6). The scaffold branch `$ref`s the `add` verbs' own input
            // schemas instead of restating the spec, since a restatement drifts invisibly.
            Map.entry("patterns", "patterns"),
            Map.entry("workspace list", "workspace-list"),
            Map.entry("workspace graph", "workspace-graph"),
            Map.entry("workspace roles", "workspace-roles"));

    /**
     * Verbs whose result is the kernel <b>verdict</b> — the envelope's {@code valid} + {@code diagnostics},
     * <b>not</b> a {@code data} payload (`model check` and `rule check` emit no {@code data}). So their
     * contract documents the verdict and points at {@code schema result}/{@code schema diagnostic}; it does not
     * claim a (nonexistent) data shape. ({@code workspace check} is different — it <i>does</i> return a
     * {@code data} payload of per-model results, so it is a normal read, not a verdict verb.)
     */
    static final Set<String> VERDICT_OPS = Set.of("model check", "rule check");

    /**
     * Verbs for which {@code schema <verb>} serves <b>no</b> directional contract (their operational contract is
     * still always served). Two reasons, mapped here as
     * the single source of truth (read by {@link
     * ManifestCommand} to decide the schema pointer, and by {@code CliConformanceMatrixTest} as the reasoned
     * exemption ledger): the self-describing verbs ({@code manifest}/{@code schema}/{@code operators}/
     * {@code patterns}/{@code diagnostics}) emit their own JSON (run them to see it), while the listed legacy
     * artifact verbs have no directional contract. An artifact verb registered in {@link #ARTIFACT_OPS} is not
     * exempt: its input, stdout artifact, and {@code -o} receipt branch are contract-discoverable.
     */
    static final Map<String, String> CONTRACT_EXEMPT = Map.ofEntries(
            Map.entry("manifest", "emits the verb/param map itself — run it"),
            Map.entry("schema", "emits schemas itself — run it"),
            Map.entry("operators", "emits the operator catalog itself — run it"),
            // `patterns` was here with that same reason until it joined the result envelope; the reason then
            // became false and `schema patterns` was a dead end for a verb with four documented routes. Its
            // contract is now derived like any envelope verb, with the routes in CONTRACT_NOTES.
            Map.entry("diagnostics", "emits the diagnostic registry itself — run it"),
            Map.entry("export", "renders an artifact (model card / dot / …), not the result envelope"),
            Map.entry("model new", "emits a brand-new model document (an artifact) to stdout; with -o stdout carries "
                    + "an additive write-confirmation envelope, but the primary form has no directional contract"),
            Map.entry("model expand", "emits one self-contained expanded DM-JSON model artifact to stdout; with -o "
                    + "the model goes to the selected file and stdout carries an additive write-confirmation envelope"),
            Map.entry("model import-jsonschema", "reads a JSON Schema file and emits the imported model as a DM-JSON "
                    + "artifact to stdout (report on stderr); with -o the model goes to file + a write envelope"),
            Map.entry("model export-jsonschema", "emits the model as a JSON Schema artifact to stdout (report on "
                    + "stderr); with -o stdout carries a write-confirmation envelope"));

    /** The prefix every published {@code $id} shares — a {@code $ref} carrying it addresses a sibling document. */
    private static final String SCHEMA_ID_PREFIX = "https://github.com/mbackschat/a12-dmkits/schema/";

    private static final String SCHEMA_SUFFIX = ".schema.json";

    /** Leaf op-names whose verb returns a read payload in the envelope's {@code data}. */
    private static final Set<String> READ_OPS =
            Set.of("read", "explain", "deps", "format", "describe", "usage", "eval", "compute", "meta");

    @Parameters(arity = "0..*", paramLabel = "<schema>",
            description = "result | diagnostic (structures), or a verb's directional contract "
                    + "(e.g. `rule add`, `computation modify`, `field read`)")
    List<String> tokens = new ArrayList<>();

    @Override
    public Integer call() {
        if (tokens.isEmpty()) {
            out().print("Structure schemas: " + String.join(", ", STRUCTURES)
                    + "\nBatch frames: " + String.join(", ", FRAMES)
                    + "\nVerb contracts: `schema <target> <op>` (e.g. `schema rule add`, `schema field read`).\n"
                    + crossReferenceRegistry());
            return 0;
        }
        if (tokens.size() == 1 && (STRUCTURES.contains(tokens.get(0)) || FRAMES.contains(tokens.get(0)))) {
            return serveStructure(tokens.get(0));
        }
        String verb = String.join(" ", tokens);
        if (!resolvesToLeaf(tokens)) {
            return fail("unknown schema: " + verb + "   (structures: " + String.join(", ", STRUCTURES)
                    + "; or a verb contract like `rule add`, `field read`, `meta`)");
        }
        if (CONTRACT_EXEMPT.containsKey(verb)) {
            return operationalContract(verb);
        }
        return contract(verb);
    }

    /**
     * The orientation's registry section: which invocation hands over each document that another schema
     * {@code $ref}s. Without it a consumer of {@code schema result} holds a reference it cannot resolve and
     * has nothing telling it where the referent lives.
     */
    private String crossReferenceRegistry() {
        Map<String, String> referenced = crossReferenceEntries();
        if (referenced.isEmpty()) {
            return "";
        }
        int width = referenced.keySet().stream()
                .mapToInt(name -> name.length() + SCHEMA_SUFFIX.length()).max().orElseThrow();
        StringBuilder registry = new StringBuilder(
                "\nThese schemas are `$ref`d by another document rather than restated, so a validator needs both"
                        + " registered.\nFetch each with the invocation named here:\n");
        String row = "  %-" + width + "s  `%s`%n";
        referenced.forEach((target, invocation) -> registry.append(row.formatted(target + SCHEMA_SUFFIX, invocation)));
        return registry.toString();
    }

    /**
     * Every published schema resource, mapped to the {@code schema} invocation that hands it over — derived
     * from the tables above rather than authored, so it cannot drift from what the verb actually serves. Where
     * several invocations serve one document ({@code computation add} and {@code computation modify} both carry
     * {@code computation-add}), the lexicographically first wins, so the map is deterministic.
     */
    static Map<String, String> servedSchemas() {
        Map<String, String> served = new TreeMap<>();
        STRUCTURES.forEach(name -> serves(served, name, name));
        FRAMES.forEach(name -> serves(served, name, name));
        EMBEDDED_SCHEMAS.forEach((frame, embedded) -> serves(served, embedded.schema(), frame));
        INPUT_OPS.forEach((verb, name) -> serves(served, name, verb));
        DOC_INPUT.forEach((verb, name) -> serves(served, name, verb));
        DATA_SCHEMA.forEach((verb, name) -> serves(served, name, verb));
        ARTIFACT_OPS.forEach((verb, artifact) -> {
            if (artifact.schema() != null) {
                serves(served, artifact.schema(), verb);
            }
        });
        return served;
    }

    private static void serves(Map<String, String> served, String resource, String verb) {
        served.merge(resource, "schema " + verb, (a, b) -> a.compareTo(b) <= 0 ? a : b);
    }

    /**
     * The sibling documents that some published schema addresses by {@code $id} instead of restating its shape.
     * That single-sourcing is deliberate — a restatement conforms to its meta-schema, executes nothing, and
     * drifts invisibly — but it makes the published documents a <b>registry</b>: a consumer validating against
     * one of them must register the documents named here too. Scanned from the resources, never listed by hand.
     */
    static Set<String> crossDocumentReferences() {
        Set<String> targets = new TreeSet<>();
        for (String name : servedSchemas().keySet()) {
            JsonNode document = readSchema(name);
            if (document != null) {
                collectCrossReferences(document, targets);
            }
        }
        return targets;
    }

    /**
     * The cross-referenced documents the tool can actually hand over, mapped to the invocation that serves
     * each. Filtered rather than assumed total, so a reference the tool cannot serve degrades to an omission
     * instead of rendering a {@code null} invocation; {@code SchemaResourceSurfaceTest} owns the separate
     * claim that the filter never drops anything.
     */
    static Map<String, String> crossReferenceEntries() {
        Map<String, String> served = servedSchemas();
        Map<String, String> entries = new TreeMap<>();
        for (String target : crossDocumentReferences()) {
            String invocation = served.get(target);
            if (invocation != null) {
                entries.put(target, invocation);
            }
        }
        return entries;
    }

    private static void collectCrossReferences(JsonNode node, Set<String> targets) {
        if (node.isObject()) {
            node.fields().forEachRemaining(field -> {
                JsonNode value = field.getValue();
                if (field.getKey().equals("$ref") && value.isTextual()
                        && value.asText().startsWith(SCHEMA_ID_PREFIX)) {
                    String target = value.asText().substring(SCHEMA_ID_PREFIX.length());
                    targets.add(target.endsWith(SCHEMA_SUFFIX)
                            ? target.substring(0, target.length() - SCHEMA_SUFFIX.length())
                            : target);
                } else {
                    collectCrossReferences(value, targets);
                }
            });
        } else if (node.isArray()) {
            node.forEach(child -> collectCrossReferences(child, targets));
        }
    }

    /** The table-owned contract for a leaf that deliberately has no directional I/O contract. */
    private int operationalContract(String verb) {
        CommandSpec command = resolveLeaf(tokens);
        if (command == null) {
            return fail("cannot resolve live command contract: " + verb);
        }
        ObjectNode contract = MAPPER.createObjectNode();
        contract.put("op", verb);
        contract.put("contract", "operational-only");
        contract.put("directionalExemption", CONTRACT_EXEMPT.get(verb));
        CommandContracts.attachTo(contract, verb, command);
        out().print(contract.toPrettyString() + "\n");
        return 0;
    }

    private int serveStructure(String name) {
        String resource = resourcePath(name);
        try (InputStream in = SchemaCommand.class.getResourceAsStream(resource)) {
            if (in == null) {
                return fail("schema resource missing: " + resource);
            }
            String source = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            if (FRAMES.contains(name)) {
                JsonNode parsed = MAPPER.readTree(source);
                if (!(parsed instanceof ObjectNode frame)) {
                    return fail("schema frame is not a JSON object: " + resource);
                }
                CommandSpec command = resolveLeaf(List.of(name));
                if (command == null) {
                    return fail("cannot resolve live command contract: " + name);
                }
                // A JSON-Schema annotation: the frame shape and its operational contract remain one response,
                // rendered from the same owner as manifest/help rather than duplicated in schema prose.
                EmbeddedSchema embedded = EMBEDDED_SCHEMAS.get(name);
                if (embedded != null) {
                    JsonNode embeddedSchema = readSchema(embedded.schema());
                    if (embeddedSchema == null) {
                        return fail("schema resource missing: " + embedded.schema());
                    }
                    frame.set(embedded.property(), embeddedSchema);
                }
                CommandContracts.attachTo(frame, name, command);
                out().print(frame.toPrettyString() + "\n");
            } else {
                out().print(source);
            }
            return 0;
        } catch (IOException e) {
            return fail("cannot read schema: " + e.getMessage());
        }
    }

    private int contract(String verb) {
        ObjectNode c = MAPPER.createObjectNode();
        c.put("op", verb);
        String input = INPUT_OPS.get(verb);
        if (input != null) {
            JsonNode schema = readSchema(input);
            if (schema == null) {
                return fail("schema resource missing: " + input);
            }
            c.set("input", schema);
        }
        // a `--instance` document instance the verb evaluates (eval/compute/test) — the shape it expects
        String docInput = DOC_INPUT.get(verb);
        if (docInput != null) {
            JsonNode schema = readSchema(docInput);
            if (schema != null) {
                c.set("docInput", schema);
            }
        }
        ArtifactContract artifactContract = ARTIFACT_OPS.get(verb);
        if (artifactContract != null) {
            c.put("returns", "artifact");
            ObjectNode artifact = c.putObject("artifact");
            artifact.put("kind", artifactContract.kind());
            artifact.put("defaultDestination", "stdout");
            if (artifactContract.schema() != null) {
                JsonNode schema = readSchema(artifactContract.schema());
                if (schema == null) {
                    return fail("schema resource missing: " + artifactContract.schema());
                }
                artifact.set("schema", schema);
            }
            if (artifactContract.outputOption() != null) {
                artifact.put("outputOption", artifactContract.outputOption());
                artifact.put(
                        "withOutput",
                        "writes the artifact to the selected file and returns an applied result envelope");
                artifact.put("receiptSchema", "result");
            }
        } else {
            c.put("returns", "result");
            c.put("resultSchema", "result");
            String note = CONTRACT_NOTES.get(verb);
            if (note != null) {
                c.put("notes", note);
            }
            if (VERDICT_OPS.contains(verb)) {
                // a verdict verb carries its result in the envelope's valid + diagnostics, not a data payload
                c.put("verdict", "the kernel verdict rides the envelope's `valid` (true iff no ERROR diagnostic)"
                        + " and `diagnostics` — there is no `data` payload; see `schema result` and `schema diagnostic`");
            } else {
                if (READ_OPS.contains(tokens.get(tokens.size() - 1)) || DATA_SCHEMA.containsKey(verb)) {
                    c.put("data", "this read returns its payload in the result envelope's `data` field");
                }
                // … and where that payload has its own published schema, surface it so the shape is discoverable
                String dataSchema = DATA_SCHEMA.get(verb);
                if (dataSchema != null) {
                    JsonNode schema = readSchema(dataSchema);
                    if (schema != null) {
                        c.set("dataSchema", schema);
                    }
                }
            }
        }
        CommandSpec command = resolveLeaf(tokens);
        if (command == null) {
            return fail("cannot resolve live command contract: " + verb);
        }
        CommandContracts.attachTo(c, verb, command);
        out().print(c.toPrettyString() + "\n");
        return 0;
    }

    /** Resolve the tokens against the live command tree; true iff they name a runnable leaf op. */
    private boolean resolvesToLeaf(List<String> toks) {
        return resolveLeaf(toks) != null;
    }

    private CommandSpec resolveLeaf(List<String> toks) {
        Map<String, CommandLine> subs = spec.root().subcommands();
        CommandSpec cur = null;
        for (String t : toks) {
            CommandLine cl = subs.get(t);
            if (cl == null) {
                return null;
            }
            cur = cl.getCommandSpec();
            subs = cur.subcommands();
        }
        // a runnable, non-hidden leaf — hidden commands aren't part of the canonical surface (the manifest
        // skips them too). Stays as the invariant; the legacy flat aliases this once guarded against are gone.
        return cur != null && cur.subcommands().isEmpty() && !cur.usageMessage().hidden() ? cur : null;
    }

    private static JsonNode readSchema(String name) {
        try (InputStream in = SchemaCommand.class.getResourceAsStream(resourcePath(name))) {
            return in == null ? null : MAPPER.readTree(in);
        } catch (IOException e) {
            return null;
        }
    }

    /** Where a published document lives on the classpath — the one place the resource layout is spelled out. */
    private static String resourcePath(String name) {
        return "/schema/" + name + SCHEMA_SUFFIX;
    }
}
