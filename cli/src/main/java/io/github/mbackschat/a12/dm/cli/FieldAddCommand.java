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
import picocli.CommandLine.ArgGroup;
import picocli.CommandLine.Command;
import picocli.CommandLine.Mixin;
import picocli.CommandLine.Option;

/**
 * {@code dmtool field add --group <path> --name <n> (--kind <K> | --typedef <id>)} — add a field and write
 * the model back (in place; {@code -o}/{@code --dry-run}). Re-bound through the kernel; a kernel-invalid
 * result is a {@code rejected} envelope carrying the kernel diagnostics.
 */
@Command(name = "add", mixinStandardHelpOptions = true,
        description = "Add a field (--kind STRING|NUMBER|DATE|BOOLEAN|CONFIRM, or --typedef <id>) to --group.")
final class FieldAddCommand extends BaseCommand {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @ParamKind(ParamKind.Kind.GROUP_PATH)
    @Option(names = "--group", required = true, paramLabel = "<path>",
            description = "the group to add the field to, by full name-path, e.g. /Order")
    String group;

    @ParamKind(ParamKind.Kind.NEW_NAME)
    @Option(names = "--name", required = true, paramLabel = "<name>", description = "the new field's name")
    String name;

    @ArgGroup(multiplicity = "1")
    FieldType type;

    /** Exactly one of: an easy kind, or a type-definition reference. */
    static final class FieldType {
        @ParamKind(ParamKind.Kind.FIELD_KIND)
        @Option(names = "--kind", paramLabel = "<kind>",
                description = "the field kind: STRING | NUMBER | DATE | BOOLEAN | CONFIRM")
        FieldKind kind;

        @ParamKind(ParamKind.Kind.TYPEDEF_ID)
        @Option(names = "--typedef", paramLabel = "<typeDefId>",
                description = "type the field by an existing type definition")
        String typedef;
    }

    @Option(names = {"--output", "-o"}, paramLabel = "<file>",
            description = "write the result here instead of in place (overwriting the model)")
    String outputPath;

    @Option(names = "--dry-run", description = "preview only — write NOTHING")
    boolean dryRun;

    @Mixin
    IncludeDirOption includes = new IncludeDirOption();

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
        IDocumentModelReferenceResolver resolver = includes.resolverFor(model);
        String target = group + "/" + name;
        String resultJson;
        try {
            ModelDocument doc = ModelDocument.read(modelJson);
            if (type.typedef != null) {
                doc.addTypedefField(group, name, type.typedef);
            } else {
                doc.addField(group, name, type.kind);
            }
            resultJson = doc.validate(resolver).toJson();
        } catch (ModelConsistencyException e) {
            return emitKernelRejection("field", "add", "the field is rejected by the kernel", e);
        } catch (RuntimeException e) {
            return fail(e.getMessage());
        }
        ObjectNode changed = MAPPER.createObjectNode();
        changed.put("added", target);
        changed.put("kind", type.typedef != null ? "TypeDefType" : type.kind.name());
        if (dryRun) {
            ResultEnvelope env = ResultEnvelope.preview("field", "add", "would add field " + target, changed);
            out().print(env.toJsonString());
            return env.exitCode();
        }
        String dest = outputPath != null ? outputPath : model;
        try {
            Files.writeString(Path.of(dest), resultJson);
        } catch (IOException e) {
            return fail("cannot write output: " + e.getMessage());
        }
        ResultEnvelope env = ResultEnvelope.applied("field", "add", "added field " + target, changed, dest);
        out().print(env.toJsonString());
        return env.exitCode();
    }
}
