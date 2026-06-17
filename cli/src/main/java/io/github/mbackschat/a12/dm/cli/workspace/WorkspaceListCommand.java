package io.github.mbackschat.a12.dm.cli.workspace;

import io.github.mbackschat.a12.dm.cli.BaseCommand;
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

import com.mgmtp.a12.kernel.md.model.api.services.IDocumentModelReferenceResolver;

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

    @Parameters(index = "0", paramLabel = "<dir>",
            description = "the workspace directory to scan for DM-JSON document models")
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
        Map<String, Boolean> validity = validate ? validateAll(root, models) : Map.of();

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
        ResultEnvelope env = ResultEnvelope.read("workspace", "list",
                "listed " + models.size() + " model" + (models.size() == 1 ? "" : "s") + " under " + dir,
                true, List.of(), data);
        out().print(env.toJsonString());
        return env.exitCode();
    }

    private ObjectNode entry(Workspace.Model m, Map<String, Boolean> validity) {
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
            n.put("valid", validity.getOrDefault(m.path(), false));
        }
        return n;
    }

    /** Kernel-validate each model, resolving its references against the whole directory tree. */
    private Map<String, Boolean> validateAll(Path root, List<Workspace.Model> models) {
        IDocumentModelReferenceResolver resolver = ReferenceResolvers.directory(root);
        Map<String, Boolean> out = new LinkedHashMap<>();
        for (Workspace.Model m : models) {
            boolean valid;
            try {
                ModelLoader.load(new StringReader(Files.readString(root.resolve(m.path()))), resolver);
                valid = true;
            } catch (IOException | RuntimeException invalid) {
                valid = false;
            }
            out.put(m.path(), valid);
        }
        return out;
    }

    private static void putStrings(ArrayNode array, List<String> values) {
        values.forEach(array::add);
    }

    /** Render the index as an aligned text table (the {@code --format table} human view of the same facts). */
    private String table(List<Workspace.Model> models, Map<String, Boolean> validity) {
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
                row.add(Boolean.TRUE.equals(validity.get(m.path())) ? "valid" : "INVALID");
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
