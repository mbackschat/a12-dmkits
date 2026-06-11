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
import picocli.CommandLine.Parameters;

/**
 * The {@code add-field} verb (CLI-SPEC §4/§5d) — add a field to an existing group (an easy {@code --kind},
 * or typed by an existing type definition via {@code --typedef}) and <b>write the model back</b> (in
 * place; {@code -o} redirects, {@code --dry-run} previews — the {@code modify} convention). The result is
 * re-bound through the kernel; a missing group fails fast (exit 2), a kernel-invalid result surfaces the
 * kernel diagnostics (exit 1).
 */
@Command(name = "add-field", hidden = true, mixinStandardHelpOptions = true,
        description = "Add a field (--kind STRING|NUMBER|DATE|BOOLEAN|CONFIRM, or --typedef <id>) to --group and "
                + "WRITE the model back (in place; -o redirects, --dry-run previews).")
final class AddFieldCommand extends BaseCommand {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Parameters(index = "0", paramLabel = "<model.json>", description = "the DM-JSON model FILE")
    String model;

    @Option(names = "--group", required = true, paramLabel = "<path>",
            description = "the group to add the field to, by full name-path, e.g. /Order")
    String group;

    @Option(names = "--name", required = true, paramLabel = "<name>", description = "the new field's name")
    String name;

    @ArgGroup(multiplicity = "1")
    FieldType type;

    /** Exactly one of: an easy kind, or a type-definition reference. */
    static final class FieldType {
        @Option(names = "--kind", paramLabel = "<kind>",
                description = "the field kind: STRING | NUMBER | DATE | BOOLEAN | CONFIRM")
        FieldKind kind;

        @Option(names = "--typedef", paramLabel = "<typeDefId>",
                description = "type the field by an existing type definition (declared with `add-typedef`)")
        String typedef;
    }

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
            return StructureWrite.reject(this, target, e);
        } catch (RuntimeException e) {
            return fail(e.getMessage());
        }

        ObjectNode summary = MAPPER.createObjectNode();
        summary.put("added", target);
        summary.put("kind", type.typedef != null ? "TypeDefType" : type.kind.name());
        return StructureWrite.write(this, summary, resultJson, target, model, outputPath, dryRun);
    }
}
