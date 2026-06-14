package io.github.mbackschat.a12.dm.cli;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import io.github.mbackschat.a12.dm.modelkit.ModelDocument;
import io.github.mbackschat.a12.dm.modelkit.Workspace;
import picocli.CommandLine.Command;
import picocli.CommandLine.Mixin;

/**
 * {@code dmtool model info} — the single-model header dashboard. A read op: the payload rides {@code data} as
 * the model's <b>identity</b> ({@code id}/{@code modelType}/{@code modelVersion}/{@code label}/{@code
 * locales}/non-convention {@code annotations}), its <b>super/subtype graph</b> (the {@code abstract}/{@code
 * superTypes}/{@code subTypes} convention, parsed), every <b>outbound reference</b> (include / type-def
 * import) <b>cross-resolved to its file</b> via {@code --include-dir} (default the model's folder), and
 * <b>compact counts</b>. It deliberately owns the one facet no other read verb does — identity + references
 * + the super/subtype graph — and emits only <em>counts</em>, never the field/rule/config <em>contents</em>
 * that {@code model describe} / {@code model read} / {@code config read} / {@code typedef read} own. Kernel-free
 * (tolerant of unresolved references). Reuses the {@code workspace list} engine ({@link Workspace}).
 */
@Command(name = "info", mixinStandardHelpOptions = true,
        description = "Read the model's identity, super/subtype graph, and outbound references "
                + "(includes/type-def imports, resolved to files) + compact counts into `data`.")
final class ModelInfoCommand extends BaseCommand {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** The header-annotation names that encode the super/subtype convention — parsed into their own fields, not echoed in `annotations`. */
    private static final Set<String> CONVENTION = Set.of("abstract", "superTypes", "subTypes");

    @Mixin
    IncludeDirOption includes = new IncludeDirOption();

    @Override
    public Integer call() {
        String model = model();
        String json = readModelJson(model);
        if (json == null) {
            return 2; // readModelJson already printed the corrective failure
        }
        ModelDocument doc;
        try {
            doc = ModelDocument.read(json);
        } catch (RuntimeException e) {
            return fail("read failed: " + e.getMessage());
        }
        // resolve outbound references against the include-dir tree(s) (default: the model's own folder),
        // kernel-free — the same id→file resolution `workspace list` does, reused via Workspace.index
        Workspace.Model self = Workspace.index(doc, "", resolutionIndex(model));

        ObjectNode data = MAPPER.createObjectNode();
        data.put("id", self.id());
        data.put("modelType", self.modelType());
        data.put("modelVersion", doc.modelVersion());
        if (self.label() != null) {
            data.put("label", self.label());
        }
        putStrings(data.putArray("locales"), self.locales());
        data.put("abstract", self.isAbstract());
        putStrings(data.putArray("superTypes"), self.superTypes());
        putStrings(data.putArray("subTypes"), self.subTypes());
        ObjectNode annotations = data.putObject("annotations");
        doc.modelAnnotations().forEach((name, value) -> {
            if (!CONVENTION.contains(name)) {
                annotations.put(name, value);
            }
        });
        ArrayNode incs = data.putArray("includes");
        for (Workspace.Reference r : self.includes()) {
            incs.addObject().put("alias", r.alias()).put("ref", r.ref()).put("resolvedPath", r.resolvedPath());
        }
        ArrayNode imports = data.putArray("typedefImports");
        for (Workspace.Reference r : self.typedefImports()) {
            imports.addObject().put("ref", r.ref()).put("resolvedPath", r.resolvedPath());
        }
        ObjectNode counts = data.putObject("counts");
        counts.put("groups", self.groups());
        counts.put("fields", self.fields());
        counts.put("rules", doc.ruleCount());
        counts.put("computations", doc.computationCount());
        counts.put("typeDefinitions", self.providesTypedefs().size());

        ResultEnvelope env = ResultEnvelope.read("model", "info", "read model info for " + self.id(),
                true, List.of(), data);
        out().print(env.toJsonString());
        return env.exitCode();
    }

    /** The id→relative-path map for resolving this model's references, over the shared workspace-root precedence. */
    private Map<String, String> resolutionIndex(String modelPath) {
        Map<String, String> idToPath = new LinkedHashMap<>();
        for (Path root : includes.roots(modelPath)) {
            if (!Files.isDirectory(root)) {
                continue;
            }
            for (Workspace.Model m : Workspace.scan(root, true)) {
                idToPath.putIfAbsent(m.id(), m.path()); // first root / first id wins, like the directory resolver
            }
        }
        return idToPath;
    }

    private static void putStrings(ArrayNode array, List<String> values) {
        values.forEach(array::add);
    }
}
