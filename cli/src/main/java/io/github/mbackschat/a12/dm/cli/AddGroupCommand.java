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
 * The {@code add-group} verb (CLI-SPEC §4/§5d) — add a group under an existing parent and <b>write the
 * model back</b> (in place; {@code -o} redirects, {@code --dry-run} previews). {@code --repeatable <max>}
 * makes it a repeatable group ({@code max >= 2}); omit it for a single group. Re-bound through the kernel.
 */
@Command(name = "add-group", hidden = true, mixinStandardHelpOptions = true,
        description = "Add a group under --parent and WRITE the model back (in place; -o redirects, --dry-run "
                + "previews). --repeatable <max> makes it repeatable (max >= 2).")
final class AddGroupCommand extends BaseCommand {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Parameters(index = "0", paramLabel = "<model.json>", description = "the DM-JSON model FILE")
    String model;

    @Option(names = "--parent", required = true, paramLabel = "<path>",
            description = "the parent group's full name-path, e.g. /Order (empty string for a new root group)")
    String parent;

    @Option(names = "--name", required = true, paramLabel = "<name>", description = "the new group's name")
    String name;

    @Option(names = "--repeatable", paramLabel = "<max>",
            description = "make the group repeatable up to <max> rows (>= 2); omit for a single group")
    Integer repeatable;

    @Option(names = {"--output", "-o"}, paramLabel = "<file>",
            description = "write the result here instead of in place (overwriting <model.json>)")
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
        String target = parent + "/" + name;

        String resultJson;
        try {
            ModelDocument doc = ModelDocument.read(modelJson);
            if (repeatable != null) {
                doc.addRepeatableGroup(parent, name, repeatable);
            } else {
                doc.addGroup(parent, name);
            }
            resultJson = doc.validate(resolver).toJson();
        } catch (ModelConsistencyException e) {
            return StructureWrite.reject(this, target, e);
        } catch (RuntimeException e) {
            return fail(e.getMessage());
        }

        ObjectNode summary = MAPPER.createObjectNode();
        summary.put("added", target);
        summary.put("repeatable", repeatable != null);
        return StructureWrite.write(this, summary, resultJson, target, model, outputPath, dryRun);
    }
}
