package io.github.mbackschat.a12.dm.cli.workspace;

import io.github.mbackschat.a12.dm.cli.BaseCommand;
import io.github.mbackschat.a12.dm.cli.ConsistencyEvidence;
import io.github.mbackschat.a12.dm.cli.ResultEnvelope;

import java.io.IOException;
import java.io.StringReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import io.github.mbackschat.a12.dm.core.ModelResolver;

import io.github.mbackschat.a12.dm.core.ModelConsistencyException;
import io.github.mbackschat.a12.dm.core.ModelLoader;
import io.github.mbackschat.a12.dm.core.ReferenceResolvers;
import io.github.mbackschat.a12.dm.modelkit.Workspace;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

/**
 * {@code dmtool workspace list <dir>} — the agent-friendly "ls" into a folder of document models. A read op:
 * the payload rides {@code data} as a per-model index ({@code id}, {@code modelType}, the super/subtype
 * convention {@code abstract}/{@code superTypes}/{@code subTypes}, and every {@code include}/type-def import
 * <b>cross-resolved to its file within the scan</b>), so an agent learns what a workspace contains and how to
 * wire it without reading raw DM-JSON. Kernel-free by default (tolerant of a half-wired workspace);
 * {@code --validate} additionally flags which models are kernel-valid. {@code --format table} renders the
 * same facts as an aligned human listing instead of the envelope.
 */
@Command(name = "list", mixinStandardHelpOptions = true,
        description = "Scan a directory of document models into a per-model index — ids, super/subtypes, and "
                + "includes/type-def imports cross-resolved to their files. Kernel-free; --validate flags validity.")
final class WorkspaceListCommand extends BaseCommand {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Parameters(index = "0", arity = "0..1", paramLabel = "<dir>",
            description = "the workspace directory to scan for DM-JSON document models (defaults to -w/--workspace, then $DMTOOL_WORKSPACE)")
    String dir;

    @Option(names = {"--recursive", "-r"},
            description = "descend into sub-directories (default: the immediate directory only, like `ls`)")
    boolean recursive;

    @Option(names = "--format", paramLabel = "json|table",
            description = "output format: json (default, the result envelope) or table (an aligned human listing)")
    String format = "json";

    @Option(names = "--validate",
            description = "also kernel-validate each model and flag which are valid (slower; resolves references "
                    + "within the directory)")
    boolean validate;

    @Override
    public Integer call() {
        dir = workspaceDir(dir).orElse(null);
        if (dir == null) {
            return fail("pass a workspace directory positionally (dmtool workspace <op> <dir>), or set -w/--workspace (or $DMTOOL_WORKSPACE)");
        }
        Path root = Path.of(dir);
        if (!Files.isDirectory(root)) {
            return fail("not a directory: " + dir);
        }
        if (!format.equalsIgnoreCase("json") && !format.equalsIgnoreCase("table")) {
            return fail("unknown --format '" + format + "' (json|table)");
        }
        List<Workspace.Model> models;
        try {
            models = Workspace.scan(root, recursive);
        } catch (RuntimeException e) {
            return fail("scan failed: " + e.getMessage());
        }
        Map<String, EntryVerdict> validity = validate ? validateAll(root, models) : Map.of();

        if (format.equalsIgnoreCase("table")) {
            out().print(table(models, validity));
            return 0;
        }
        ObjectNode data = MAPPER.createObjectNode();
        data.put("root", dir);
        data.put("recursive", recursive);
        data.put("count", models.size());
        ArrayNode arr = data.putArray("models");
        models.forEach(m -> arr.add(entry(m, validity)));
        // The OUTER envelope stays an ungated query even under --validate. No single top-level marker can
        // characterise a mixed workspace, and the listing itself is what executed; the per-model verdicts ride
        // `data` and — structurally — a nested verdict never reaches the exit code.
        ResultEnvelope env = ResultEnvelope.queryResult("workspace", "list",
                "listed " + models.size() + " model" + (models.size() == 1 ? "" : "s") + " under " + dir,
                data, ConsistencyEvidence.none());
        out().print(env.toJsonString());
        return env.exitCode();
    }

    /**
     * One model's {@code --validate} outcome. Three states, because the previous {@code Map<String, Boolean>} could
     * express only two — and its {@code getOrDefault(path, false)} read made the third indistinguishable from the
     * second: a model whose FILE could not be read reported exactly the same {@code valid:false} as a model the
     * kernel examined and rejected. Those are different facts and lead to different fixes.
     *
     * @param valid the kernel's verdict; {@code null} when the gate was never reached
     * @param problem what went wrong before the gate, when {@code valid} is {@code null}
     */
    private record EntryVerdict(Boolean valid, String problem) {

        static EntryVerdict kernelValid() {
            return new EntryVerdict(true, null);
        }

        static EntryVerdict kernelInvalid() {
            return new EntryVerdict(false, null);
        }

        /** Never reached the gate — so it reports NEITHER a verdict nor a verification, only why. */
        static EntryVerdict notReached(String problem) {
            return new EntryVerdict(null, problem);
        }
    }

    private ObjectNode entry(Workspace.Model m, Map<String, EntryVerdict> validity) {
        ObjectNode n = MAPPER.createObjectNode();
        n.put("id", m.id());
        n.put("path", m.path());
        n.put("modelType", m.modelType());
        if (m.label() != null) {
            n.put("label", m.label());
        }
        n.put("abstract", m.isAbstract());
        putStrings(n.putArray("superTypes"), m.superTypes());
        putStrings(n.putArray("subTypes"), m.subTypes());
        putStrings(n.putArray("locales"), m.locales());
        ArrayNode includes = n.putArray("includes");
        for (Workspace.Reference r : m.includes()) {
            includes.addObject()
                    .put("alias", r.alias())
                    .put("ref", r.ref())
                    .put("resolvedPath", r.resolvedPath()); // null = unresolved within the scan
        }
        ArrayNode imports = n.putArray("typedefImports");
        for (Workspace.Reference r : m.typedefImports()) {
            imports.addObject()
                    .put("ref", r.ref())
                    .put("resolvedPath", r.resolvedPath());
        }
        putStrings(n.putArray("providesTypedefs"), m.providesTypedefs());
        n.put("groups", m.groups());
        n.put("fields", m.fields());
        if (validate) {
            EntryVerdict verdict = validity.get(m.path());
            // Three states, one shape each: valid + its verification · invalid + its verification · not reached,
            // which carries NEITHER key and says why instead. Publishing the gate mode per entry is what makes
            // the middle case distinguishable from the third at all.
            if (verdict == null || verdict.valid() == null) {
                n.put("checked", false);
                n.put("problem", verdict == null ? "the model was not checked" : verdict.problem());
            } else {
                n.put("checked", true);
                n.put("valid", verdict.valid());
                n.put("verification", io.github.mbackschat.a12.dm.core.Backends.verification().name());
            }
        }
        return n;
    }

    /** Kernel-validate each model, resolving its references against the whole directory tree. */
    private Map<String, EntryVerdict> validateAll(Path root, List<Workspace.Model> models) {
        ModelResolver resolver = ReferenceResolvers.directory(root);
        Map<String, EntryVerdict> out = new LinkedHashMap<>();
        for (Workspace.Model m : models) {
            String json;
            try {
                json = Files.readString(root.resolve(m.path()));
            } catch (IOException unreadable) {
                // Never reached the gate. Collapsing this into `valid:false` is what made an unreadable file
                // indistinguishable from a kernel rejection.
                out.put(m.path(), EntryVerdict.notReached("cannot read model: " + unreadable.getMessage()));
                continue;
            }
            try {
                ModelLoader.loadAndCheck(json, resolver);
                out.put(m.path(), EntryVerdict.kernelValid());
            } catch (ModelConsistencyException rejected) {
                out.put(m.path(), EntryVerdict.kernelInvalid());
            } catch (RuntimeException failed) {
                // A non-consistency failure (unparseable JSON, an unresolvable reference): the gate did not
                // return a verdict, so we report the obstacle rather than inventing one.
                out.put(m.path(), EntryVerdict.notReached(String.valueOf(failed.getMessage())));
            }
        }
        return out;
    }

    private static void putStrings(ArrayNode array, List<String> values) {
        values.forEach(array::add);
    }

    /** Render the index as an aligned text table (the {@code --format table} human view of the same facts). */
    private String table(List<Workspace.Model> models, Map<String, EntryVerdict> validity) {
        List<String> headers = new ArrayList<>(List.of("ID", "TYPE", "ABSTRACT", "SUPERTYPES", "INCLUDES", "IMPORTS", "PATH"));
        if (validate) {
            headers.add("VALID");
        }
        List<List<String>> rows = new ArrayList<>();
        for (Workspace.Model m : models) {
            List<String> row = new ArrayList<>(List.of(
                    m.id(), m.modelType(), m.isAbstract() ? "yes" : "",
                    String.join(",", m.superTypes()), refs(m.includes()), refs(m.typedefImports()), m.path()));
            if (validate) {
                // the human column mirrors the three JSON states, so the table cannot say INVALID about a
                // model the kernel never got to look at
                EntryVerdict verdict = validity.get(m.path());
                row.add(verdict == null || verdict.valid() == null ? "?"
                        : verdict.valid() ? "valid" : "INVALID");
            }
            rows.add(row);
        }
        int[] width = new int[headers.size()];
        for (int i = 0; i < headers.size(); i++) {
            width[i] = headers.get(i).length();
        }
        for (List<String> row : rows) {
            for (int i = 0; i < row.size(); i++) {
                width[i] = Math.max(width[i], row.get(i).length());
            }
        }
        StringBuilder sb = new StringBuilder();
        appendRow(sb, headers, width);
        rows.forEach(row -> appendRow(sb, row, width));
        return sb.toString();
    }

    private static void appendRow(StringBuilder sb, List<String> cells, int[] width) {
        for (int i = 0; i < cells.size(); i++) {
            String cell = cells.get(i);
            sb.append(cell);
            if (i < cells.size() - 1) {
                sb.append(" ".repeat(width[i] - cell.length() + 2)); // pad to the column, 2-space gutter
            }
        }
        sb.append('\n');
    }

    /** Join reference targets for the table, marking an unresolved one with a trailing {@code ?}. */
    private static String refs(List<Workspace.Reference> references) {
        List<String> parts = new ArrayList<>();
        references.forEach(r -> parts.add(r.resolvedPath() == null ? r.ref() + "?" : r.ref()));
        return String.join(",", parts);
    }
}
