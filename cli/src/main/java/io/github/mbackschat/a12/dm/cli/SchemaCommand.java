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
            "computation modify", "add");

    /** Leaf op-names whose verb returns a read payload in the envelope's {@code data}. */
    private static final Set<String> READ_OPS = Set.of("read", "explain", "describe", "eval", "compute");

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
        // a directional contract is meaningful for the target-first ops (<target> <op>) and the input-taking
        // root op `batch` — not for the discovery/meta verbs, which are explored via their own verb.
        boolean opContract = tokens.size() == 2 || INPUT_OPS.containsKey(verb);
        if (!opContract || !resolvesToLeaf(tokens)) {
            return fail("unknown schema: " + verb + "   (structures: " + String.join(", ", STRUCTURES)
                    + "; or a verb contract like `rule add`, `field read`)");
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
        c.put("returns", "result");
        c.put("resultSchema", "result");
        if (READ_OPS.contains(tokens.get(tokens.size() - 1))) {
            c.put("data", "this read returns its payload in the result envelope's `data` field");
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
        return cur != null && cur.subcommands().isEmpty();
    }

    private JsonNode readSchema(String name) {
        try (InputStream in = SchemaCommand.class.getResourceAsStream("/schema/" + name + ".schema.json")) {
            return in == null ? null : MAPPER.readTree(in);
        } catch (IOException e) {
            return null;
        }
    }
}
