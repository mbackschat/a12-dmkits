package io.github.mbackschat.a12.dm.cli;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

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
 *       Schema): the universal output envelope and a single finding.</li>
 *   <li>{@code schema <target> <op>} — that op's <b>directional contract</b> {@code {input?, returns, data?}}:
 *       what it <em>consumes</em> (a rich JSON input like the rule-spec) and that it <em>returns</em> the
 *       result envelope. One call, unambiguous direction. Flag-only ops carry no {@code input} (their params
 *       are in the manifest); reads note that their payload rides the envelope's {@code data}.</li>
 * </ul>
 * Schemas are never attached to a verb's own response (that would bloat every call); they live here, fetched
 * when wanted. Structure schemas are classpath resources under {@code /schema/<name>.schema.json}; the
 * directional contracts are assembled from the live command tree. Drift-locked by {@code SchemaVerbTest}.
 */
@Command(name = "schema", mixinStandardHelpOptions = true,
        description = "Emit a structure schema (result|diagnostic) or a verb's directional contract "
                + "(`schema <target> <op>`), on demand.")
final class SchemaCommand extends BaseCommand {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** The cross-cutting structures with a published raw JSON Schema (CI-locked against drift). */
    static final List<String> STRUCTURES = List.of("result", "diagnostic");

    /** The batch op-record frames, served as raw JSON Schema (the list frame + a worked, CI-executed example). */
    static final List<String> FRAMES = List.of("apply", "batch");

    /** Ops that consume a rich JSON input file → the input schema resource that documents it. */
    static final Map<String, String> INPUT_OPS = Map.of(
            "rule add", "add",
            "computation add", "add",
            "computation modify", "add",
            "field add", "field-add");

    /** Ops that consume a {@code --doc} document INSTANCE → the schema describing that instance tree. */
    static final Map<String, String> DOC_INPUT = Map.of(
            "doc eval", "document-instance",
            "doc compute", "document-instance",
            "rule test", "document-instance");

    /** Read ops whose {@code data} payload has a published schema → surfaced so the agent learns the output shape. */
    static final Map<String, String> DATA_SCHEMA = Map.of(
            "doc describe", "describe",
            "doc validate", "validate",
            "rule check", "validate",
            "rule read", "read",
            "rule explain", "explain",
            "doc eval", "eval-doc",
            "doc compute", "compute-doc");

    /**
     * Verbs for which {@code schema <verb>} serves <b>no</b> directional contract — because they do not return
     * the result envelope. Two reasons, mapped here as the single source of truth (read by {@link
     * ManifestCommand} to decide the schema pointer, and by {@code CliConformanceMatrixTest} as the reasoned
     * exemption ledger): the four <b>self-describing</b> verbs emit their own JSON (run them to see it), and
     * {@code export}/{@code where-used} return a <b>rendered artifact</b> / a <b>custom payload</b> rather than
     * the {@code {outcome,…}} envelope. Everything else is an envelope op with a discoverable contract.
     */
    static final Map<String, String> CONTRACT_EXEMPT = Map.of(
            "manifest", "emits the verb/param map itself — run it",
            "schema", "emits schemas itself — run it",
            "operators", "emits the operator catalog itself — run it",
            "diagnostics", "emits the diagnostic registry itself — run it",
            "export", "renders an artifact (model card / dot / …), not the result envelope",
            "where-used", "returns a referrers payload, not the result envelope (a dedicated output schema is a follow-up)");

    /** Leaf op-names whose verb returns a read payload in the envelope's {@code data}. */
    private static final Set<String> READ_OPS =
            Set.of("read", "explain", "format", "test", "describe", "eval", "compute", "meta");

    @Parameters(arity = "0..*", paramLabel = "<schema>",
            description = "result | diagnostic (structures), or a verb's directional contract "
                    + "(e.g. `rule add`, `computation modify`, `field read`)")
    List<String> tokens = new ArrayList<>();

    @Override
    public Integer call() {
        if (tokens.isEmpty()) {
            out().print("Structure schemas: " + String.join(", ", STRUCTURES)
                    + "\nBatch frames: " + String.join(", ", FRAMES)
                    + "\nVerb contracts: `schema <target> <op>` (e.g. `schema rule add`, `schema field read`).\n");
            return 0;
        }
        if (tokens.size() == 1 && (STRUCTURES.contains(tokens.get(0)) || FRAMES.contains(tokens.get(0)))) {
            return serveStructure(tokens.get(0));
        }
        String verb = String.join(" ", tokens);
        // a directional contract resolves for any runnable leaf op that returns the result envelope — the
        // target-first ops (`<target> <op>`) and the single-token envelope verbs (e.g. `meta`). The
        // CONTRACT_EXEMPT verbs (self-describing / non-envelope) are explored via their own output instead.
        if (!resolvesToLeaf(tokens) || CONTRACT_EXEMPT.containsKey(verb)) {
            return fail("unknown schema: " + verb + "   (structures: " + String.join(", ", STRUCTURES)
                    + "; or a verb contract like `rule add`, `field read`, `meta`)");
        }
        return contract(verb);
    }

    private int serveStructure(String name) {
        String resource = "/schema/" + name + ".schema.json";
        try (InputStream in = SchemaCommand.class.getResourceAsStream(resource)) {
            if (in == null) {
                return fail("schema resource missing: " + resource);
            }
            out().print(new String(in.readAllBytes(), StandardCharsets.UTF_8));
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
        // a `--doc` document instance the verb evaluates (eval/compute/test) — the shape it expects
        String docInput = DOC_INPUT.get(verb);
        if (docInput != null) {
            JsonNode schema = readSchema(docInput);
            if (schema != null) {
                c.set("docInput", schema);
            }
        }
        c.put("returns", "result");
        c.put("resultSchema", "result");
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
        out().print(c.toPrettyString() + "\n");
        return 0;
    }

    /** Resolve the tokens against the live command tree; true iff they name a runnable leaf op. */
    private boolean resolvesToLeaf(List<String> toks) {
        Map<String, CommandLine> subs = spec.root().subcommands();
        CommandSpec cur = null;
        for (String t : toks) {
            CommandLine cl = subs.get(t);
            if (cl == null) {
                return false;
            }
            cur = cl.getCommandSpec();
            subs = cur.subcommands();
        }
        // a runnable, non-hidden leaf — the hidden legacy flat aliases aren't part of the canonical surface
        // (the manifest skips them too), so `schema add` must not resolve to the hidden flat `add`.
        return cur != null && cur.subcommands().isEmpty() && !cur.usageMessage().hidden();
    }

    private JsonNode readSchema(String name) {
        try (InputStream in = SchemaCommand.class.getResourceAsStream("/schema/" + name + ".schema.json")) {
            return in == null ? null : MAPPER.readTree(in);
        } catch (IOException e) {
            return null;
        }
    }
}
