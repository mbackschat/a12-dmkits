package io.github.mbackschat.a12.dm.cli;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.mgmtp.a12.kernel.md.model.api.services.IDocumentModelReferenceResolver;

import io.github.mbackschat.a12.dm.core.ModelConsistencyException;
import io.github.mbackschat.a12.dm.modelkit.ModelDocument;
import picocli.CommandLine.Command;
import picocli.CommandLine.Mixin;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

/**
 * The {@code add-include} verb (CLI-SPEC §5d) — declare a header model-reference ({@code --alias} →
 * {@code --reference}) and <b>mount</b> it as a group ({@code --name}) under {@code --parent}, then
 * <b>write the model back</b> (in place; {@code -o} redirects, {@code --dry-run} previews). The mounted
 * model is resolved + kernel-validated via {@code --include-dir} (F-RES), so a missing reference fails.
 */
@Command(name = "add-include", mixinStandardHelpOptions = true,
        description = "Declare a model include (--alias → --reference) and mount it as --name under --parent, "
                + "then WRITE the model back. The referenced model is resolved + validated via --include-dir.")
final class AddIncludeCommand extends BaseCommand {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Parameters(index = "0", paramLabel = "<model.json>", description = "the DM-JSON model FILE")
    String model;

    @Option(names = "--alias", required = true, paramLabel = "<alias>",
            description = "the include alias used by the mount")
    String alias;

    @Option(names = "--reference", required = true, paramLabel = "<reference>",
            description = "the referenced model's header id (resolved via --include-dir)")
    String reference;

    @Option(names = "--parent", required = true, paramLabel = "<path>",
            description = "the parent group's full name-path to mount under (e.g. /Order)")
    String parent;

    @Option(names = "--name", required = true, paramLabel = "<name>",
            description = "the mount group's name (the included content resolves under this path)")
    String name;

    @Option(names = {"--output", "-o"}, paramLabel = "<file>",
            description = "write the result here instead of in place")
    String outputPath;

    @Option(names = "--dry-run", description = "preview only — print the summary and write NOTHING")
    boolean dryRun;

    @Mixin
    IncludeDirOption includes = new IncludeDirOption();

    @Override
    public Integer call() {
        String modelJson;
        try {
            modelJson = Files.readString(Path.of(model));
        } catch (IOException e) {
            return fail("cannot read model: " + e.getMessage());
        }
        IDocumentModelReferenceResolver resolver = includes.resolverFor(model);
        String mountPath = parent + "/" + name;

        String resultJson;
        try {
            resultJson = ModelDocument.read(modelJson)
                    .addInclude(alias, reference)
                    .mount(parent, name, alias)
                    .validate(resolver)
                    .toJson();
        } catch (ModelConsistencyException e) {
            return StructureWrite.reject(this, mountPath, e);
        } catch (RuntimeException e) {
            return fail(e.getMessage());
        }

        ObjectNode summary = MAPPER.createObjectNode();
        summary.put("added", alias);
        summary.put("reference", reference);
        summary.put("mountedAt", mountPath);
        return StructureWrite.write(this, summary, resultJson, mountPath, model, outputPath, dryRun);
    }
}
