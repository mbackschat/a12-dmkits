package io.github.mbackschat.a12.dm.cli;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.mgmtp.a12.kernel.md.model.api.services.IDocumentModelReferenceResolver;

import io.github.mbackschat.a12.dm.core.ModelConsistencyException;
import io.github.mbackschat.a12.dm.modelkit.FieldKind;
import io.github.mbackschat.a12.dm.modelkit.ModelDocument;
import picocli.CommandLine.Command;
import picocli.CommandLine.Mixin;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

/**
 * The {@code add-typedef} verb (CLI-SPEC §5d) — declare a reusable type definition over an easy kind, and
 * <b>write the model back</b> (in place; {@code -o} redirects, {@code --dry-run} previews). Fields then
 * reference it via {@code field add --typedef <id>}.
 */
@Command(name = "add-typedef", hidden = true, mixinStandardHelpOptions = true,
        description = "Declare a reusable type definition (--id) over an easy --kind and WRITE the model back "
                + "(in place; -o redirects, --dry-run previews). Use it on a field with `field add --typedef <id>`.")
final class AddTypedefCommand extends BaseCommand {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Parameters(index = "0", paramLabel = "<model.json>", description = "the DM-JSON model FILE")
    String model;

    @Option(names = "--id", required = true, paramLabel = "<id>", description = "the type definition's id")
    String id;

    @Option(names = "--kind", required = true, paramLabel = "<kind>",
            description = "the underlying kind: STRING | NUMBER | DATE | BOOLEAN | CONFIRM")
    FieldKind kind;

    @Option(names = {"--output", "-o"}, paramLabel = "<file>",
            description = "write the result here instead of in place")
    String outputPath;

    @Option(names = "--dry-run", description = "preview only — print the summary and write NOTHING")
    boolean dryRun;

    @Mixin
    WorkspaceOption workspace = new WorkspaceOption();

    @Override
    public Integer call() {
        String modelJson;
        try {
            modelJson = Files.readString(Path.of(model));
        } catch (IOException e) {
            return fail("cannot read model: " + e.getMessage());
        }
        IDocumentModelReferenceResolver resolver = workspace.resolverFor(model);

        String resultJson;
        try {
            resultJson = ModelDocument.read(modelJson).addTypeDefinition(id, kind).validate(resolver).toJson();
        } catch (ModelConsistencyException e) {
            return StructureWrite.reject(this, id, e);
        } catch (RuntimeException e) {
            return fail(e.getMessage());
        }

        ObjectNode summary = MAPPER.createObjectNode();
        summary.put("added", id);
        summary.put("kind", kind.name());
        return StructureWrite.write(this, summary, resultJson, id, model, outputPath, dryRun);
    }
}
