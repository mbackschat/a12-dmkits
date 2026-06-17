package io.github.mbackschat.a12.dm.cli.typedef;

import io.github.mbackschat.a12.dm.cli.BaseCommand;
import io.github.mbackschat.a12.dm.cli.ParamKind;
import io.github.mbackschat.a12.dm.cli.ResultEnvelope;
import io.github.mbackschat.a12.dm.cli.WorkspaceOption;

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

/**
 * {@code dmtool typedef add --id <id> --kind <K>} — declare a reusable type definition over an easy kind and
 * write the model back (in place; {@code -o}/{@code --dry-run}). Fields then reference it via
 * {@code field add --typedef <id>}. Re-bound through the kernel; a kernel-invalid result is a {@code rejected}
 * envelope.
 */
@Command(name = "add", mixinStandardHelpOptions = true,
        description = "Declare a reusable type definition (--id) over an easy --kind.")
final class TypedefAddCommand extends BaseCommand {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @ParamKind(ParamKind.Kind.NEW_NAME)
    @Option(names = "--id", required = true, paramLabel = "<id>", description = "the type definition's id")
    String id;

    @ParamKind(ParamKind.Kind.FIELD_KIND)
    @Option(names = "--kind", required = true, paramLabel = "<kind>",
            description = "the underlying kind: STRING | NUMBER | DATE | BOOLEAN | CONFIRM")
    FieldKind kind;

    @Option(names = {"--output", "-o"}, paramLabel = "<file>",
            description = "write the result here instead of in place (overwriting the model)")
    String outputPath;

    @Option(names = "--dry-run", description = "preview only — write NOTHING")
    boolean dryRun;

    @Mixin
    WorkspaceOption workspace = new WorkspaceOption();

    @Override
    public Integer call() {
        String model = model();
        if (model == null) {
            return fail("pass -m <model.json> (before or after the verb)");
        }
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
            return emitKernelRejection("typedef", "add", "the type definition is rejected by the kernel", e);
        } catch (RuntimeException e) {
            return fail(e.getMessage());
        }
        ObjectNode changed = MAPPER.createObjectNode();
        changed.put("added", id);
        changed.put("kind", kind.name());
        if (dryRun) {
            ResultEnvelope env = ResultEnvelope.preview("typedef", "add", "would add typedef " + id, changed);
            out().print(env.toJsonString());
            return env.exitCode();
        }
        String dest = outputPath != null ? outputPath : model;
        try {
            Files.writeString(Path.of(dest), resultJson);
        } catch (IOException e) {
            return fail("cannot write output: " + e.getMessage());
        }
        ResultEnvelope env = ResultEnvelope.applied("typedef", "add", "added typedef " + id, changed, dest);
        out().print(env.toJsonString());
        return env.exitCode();
    }
}
