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
 *   <li>A manifest {@code schema} / {@code operationalSchema} pointer may be passed as one scalar argument
 *       ({@code schema "field add"}) or as ordinary command tokens ({@code schema field add}); both forms serve
 *       byte-identical output.</li>
 *   <li>Every runnable leaf, including a directionally exempt artifact/catalog verb, serves the operational
 *       profiles projected from {@link CommandContracts}; exempt leaves return an operational-only contract.</li>
 * </ul>
 * Schemas are never attached to a verb's own response (that would bloat every call); they live here, fetched
 * when wanted. Structure schemas are classpath resources under {@code /schema/<name>.schema.json}; the
 * directional contracts are assembled from the live command tree. Drift-locked by {@code SchemaVerbTest}.
 */
@Command(name = "schema", mixinStandardHelpOptions = true,
        description = "Emit a structure schema or a verb's directional/operational contract "
                + "(`schema <target> <op>` or one manifest pointer argument), on demand.")
final class SchemaCommand extends BaseCommand {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** The cross-cutting structures with a published raw JSON Schema (CI-locked against drift). */
    static final List<String> STRUCTURES = List.of("result", "diagnostic");

    /**
     * The two batch op-record frames. They are ordinary VERBS, so their schema is served as a verb contract
     * like every other one — the frame document rides {@code input}, beside a worked, CI-executed example.
     * It used to be served raw at the top level because the name resolved as a "frame" before it resolved as
     * a verb, which left {@code .input} null on exactly the two responses whose shape is least guessable
     * (reported by a12-kernel-lean, 2026-09-22). The list survives only to name them in the no-arg listing.
     */
    static final List<String> FRAMES = List.of("apply", "batch");

    /** Schema resources embedded under a named property of another public schema response. */
    record EmbeddedSchema(String property, String schema) { }

    static final Map<String, EmbeddedSchema> EMBEDDED_SCHEMAS = Map.of(
            "batch", new EmbeddedSchema("observationArtifact", "batch-observations"));

    /** Ops that consume a rich JSON input file → the input schema resource that documents it. */
    static final Map<String, String> INPUT_OPS = Map.ofEntries(
            Map.entry("rule add", "rule-add"),
            Map.entry("computation add", "computation-add"),
            Map.entry("computation modify", "computation-add"),
            Map.entry("field add", "field-add"),
            Map.entry("field modify", "field-modify"),
            Map.entry("typedef add", "typedef-add"),
            Map.entry("typedef modify", "typedef-add"),
            Map.entry("criterion add", "criterion-add"),
            Map.entry("profile synthesize", "profile"),
            Map.entry("apply", "apply"),
            Map.entry("batch", "batch"));

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
            Map.entry("criterion read", "criterion-read"),
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
            Map.entry("mcp", "serves a protocol session on stdin/stdout rather than consuming and returning one "
                    + "document; its own contract is published over that protocol, by `server/discover` and "
                    + "`tools/list`, and each tool there carries the schemas of the verb it routes to"),
            Map.entry("export", "renders an artifact (model card / dot / …), not the result envelope — but a "
                    + "REFUSAL is still an envelope, as everywhere else: the exemption is about the success "
                    + "shape, and stdout carrying nothing at all was the one thing an agent could not read"),
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
                    + "as one manifest pointer or tokenized address "
                    + "(e.g. `rule add`, `computation modify`, `field read`)")
    List<String> tokens = new ArrayList<>();

    @Override
    public Integer call() {
        List<String> argv = List.copyOf(tokens);
        if (argv.isEmpty()) {
            out().print("Structure schemas: " + String.join(", ", STRUCTURES)
                    + "\nBatch frames: " + String.join(", ", FRAMES)
                    + " (ordinary verb contracts — the op-record frame rides `input`, like every other verb)"
                    + "\nVerb contracts: pass a manifest pointer as one argument or tokenize it "
                    + "(e.g. `schema \"rule add\"` or `schema rule add`).\n"
                    + SELF_CONTAINED_NOTE);
            return 0;
        }
        if (argv.size() == 1 && STRUCTURES.contains(argv.get(0))) {
            return serveStructure(argv.get(0));
        }
        List<String> address = addressTokens(argv);
        String verb = String.join(" ", address);
        if (!resolvesToLeaf(address)) {
            return fail("unknown schema argv " + MAPPER.valueToTree(argv) + ": " + verb
                    + "   (structures: " + String.join(", ", STRUCTURES)
                    + "; or a verb contract like `rule add`, `field read`, `meta`)");
        }
        if (CONTRACT_EXEMPT.containsKey(verb)) {
            return operationalContract(verb, address);
        }
        return contract(verb, address);
    }

    /** Expand exactly one manifest-style qualified name; multi-token argv and structures remain untouched. */
    private static List<String> addressTokens(List<String> argv) {
        if (argv.size() != 1 || !argv.get(0).contains(" ")) {
            return argv;
        }
        return List.of(argv.get(0).split(" ", -1));
    }

    /**
     * The guarantee a consumer needs stated, rather than a list of extra fetches to make. The sources
     * single-source their shapes by {@code $ref}-ing a sibling, but emission bundles the referents in, so one
     * served document validates on its own. (Measured: without the bundle, a 2020-12 validator given only
     * {@code schema result} raises {@code Unresolvable} rather than warning.)
     */
    private static final String SELF_CONTAINED_NOTE =
            "\nEvery served document is self-contained: a schema it references is embedded under `$defs`,\n"
                    + "so one call is enough — no second fetch, no network.\n";

    /**
     * Every published schema resource, mapped to the {@code schema} invocation that hands it over — derived
     * from the tables above rather than authored, so it cannot drift from what the verb actually serves. Where
     * several invocations serve one document ({@code computation add} and {@code computation modify} both carry
     * {@code computation-add}), the lexicographically first wins, so the map is deterministic.
     */
    static Map<String, String> servedSchemas() {
        Map<String, String> served = new TreeMap<>();
        STRUCTURES.forEach(name -> serves(served, name, name));
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
     * drifts invisibly. Emission bundles these in ({@link #bundle}), so a consumer never registers them itself;
     * this scan is what feeds that, and the guards. Scanned from the resources, never listed by hand.
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
    private int operationalContract(String verb, List<String> address) {
        CommandSpec command = resolveLeaf(address);
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
                // NOT a caller error: this resource ships inside the binary. Escaping to Cli's error boundary
                // publishes RK_INTERNAL_ERROR, which is what "the tool is missing its own file" means.
                throw new IllegalStateException("bundled schema resource missing: " + resource);
            }
            String source = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            // A document with no sibling reference is emitted verbatim, so its served bytes stay exactly the
            // authored file; only one that needs bundling is re-rendered.
            JsonNode parsed = MAPPER.readTree(source);
            Set<String> siblings = new TreeSet<>();
            collectCrossReferences(parsed, siblings);
            out().print(siblings.isEmpty() ? source : bundle(parsed).toPrettyString() + "\n");
            return 0;
        } catch (IOException e) {
            // Same reasoning as the two throws above: the stream is over a classpath resource, so a read
            // failure here is a defect in the packaged binary and never something the caller can correct.
            throw new IllegalStateException("cannot read the bundled schema resource: " + e.getMessage(), e);
        }
    }

    private int contract(String verb, List<String> address) {
        ObjectNode c = MAPPER.createObjectNode();
        c.put("op", verb);
        String input = INPUT_OPS.get(verb);
        if (input != null) {
            JsonNode schema = readSchema(input);
            if (schema == null) {
                return fail("schema resource missing: " + input);
            }
            // A JSON-Schema annotation on the input document itself — `batch`'s `observationArtifact`. It is
            // attached BEFORE bundling so the referents the embedded schema pulls in are embedded too.
            EmbeddedSchema embedded = EMBEDDED_SCHEMAS.get(verb);
            if (embedded != null && schema instanceof ObjectNode frame) {
                JsonNode embeddedSchema = readSchema(embedded.schema());
                if (embeddedSchema == null) {
                    return fail("schema resource missing: " + embedded.schema());
                }
                frame.set(embedded.property(), embeddedSchema);
            }
            c.set("input", bundle(schema));
        }
        // a `--instance` document instance the verb evaluates (eval/compute/test) — the shape it expects
        String docInput = DOC_INPUT.get(verb);
        if (docInput != null) {
            JsonNode schema = readSchema(docInput);
            if (schema != null) {
                c.set("docInput", bundle(schema));
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
                artifact.set("schema", bundle(schema));
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
                if (READ_OPS.contains(address.get(address.size() - 1)) || DATA_SCHEMA.containsKey(verb)) {
                    c.put("data", "this read returns its payload in the result envelope's `data` field");
                }
                // … and where that payload has its own published schema, surface it so the shape is discoverable
                String dataSchema = DATA_SCHEMA.get(verb);
                if (dataSchema != null) {
                    JsonNode schema = readSchema(dataSchema);
                    if (schema != null) {
                        c.set("dataSchema", bundle(schema));
                    }
                }
            }
        }
        CommandSpec command = resolveLeaf(address);
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

    /**
     * A published structure exactly as {@code schema <name>} serves it — referents embedded under
     * {@code $defs}, so one document resolves on its own. Another surface that hands this schema to a
     * consumer (the MCP tool projection's {@code outputSchema}) must take it from here rather than reading
     * the resource, or it would publish the unbundled form whose {@code $ref} the consumer cannot resolve.
     */
    static JsonNode servedStructure(String name) {
        return bundle(readSchema(name));
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

    /**
     * Make an emitted document <b>self-contained</b>: embed every sibling it {@code $ref}s under {@code $defs},
     * as a JSON Schema 2020-12 <i>compound schema document</i> — the embedded resource keeps its {@code $id}, so
     * the existing absolute {@code $ref} resolves against it with no rewriting and no second fetch.
     *
     * <p>This is the one duplication that is safe. Restating a shape in the <em>source</em> files drifts
     * invisibly (well-formed JSON, conforms to its meta-schema, executed by no example — the {@code eval-doc}
     * incident), which is why the sources single-source by {@code $ref}. Recomputing the copy at serve time from
     * that same source cannot drift: it is derived, not authored. A consumer therefore needs no knowledge of the
     * registry to validate against what it was handed.
     *
     * <p>The inner {@code $schema} is dropped: all published documents share one dialect, and a differing
     * {@code $schema} on an embedded resource is the corner of the spec implementations support least.
     */
    private static JsonNode bundle(JsonNode document) {
        if (!(document instanceof ObjectNode root)) {
            return document;
        }
        Set<String> pending = new TreeSet<>();
        collectCrossReferences(root, pending);
        if (pending.isEmpty()) {
            return root;
        }
        // A non-object `$defs` would be a malformed document; treat it as absent rather than letting a
        // ClassCastException escape the verb (the standing no-raw-exception rule).
        ObjectNode defs = root.get("$defs") instanceof ObjectNode authored ? authored : MAPPER.createObjectNode();
        Set<String> seen = new TreeSet<>();
        while (!pending.isEmpty()) {
            String target = pending.iterator().next();
            pending.remove(target);
            if (!seen.add(target)) {
                continue; // already handled — also what makes a reference cycle terminate
            }
            if (defs.has(target)) {
                continue; // an authored def of that name wins; never clobbered
            }
            if (readSchema(target) instanceof ObjectNode referent) {
                ObjectNode copy = referent.deepCopy();
                copy.remove("$schema");
                defs.set(target, copy);
                // Close transitively: an embedded document may itself reference a third. Depth-1 embedding
                // would emit a `$ref` the consumer still cannot resolve, which is the whole defect.
                collectCrossReferences(copy, pending);
            }
        }
        root.set("$defs", defs);
        return root;
    }
}
