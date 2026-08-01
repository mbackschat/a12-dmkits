package io.github.mbackschat.a12.dm.cli.typedef;

import io.github.mbackschat.a12.dm.cli.BaseCommand;
import io.github.mbackschat.a12.dm.cli.ConsistencyEvidence;
import io.github.mbackschat.a12.dm.cli.ModelWrite;
import io.github.mbackschat.a12.dm.cli.ParamKind;
import io.github.mbackschat.a12.dm.cli.WorkspaceOption;


import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.mbackschat.a12.dm.core.ModelResolver;

import io.github.mbackschat.a12.dm.core.ModelConsistencyException;
import io.github.mbackschat.a12.dm.modelkit.ModelDocument;
import picocli.CommandLine.Command;
import picocli.CommandLine.Mixin;
import picocli.CommandLine.Option;

/**
 * {@code dmtool typedef unimport --reference <r>} — the inverse of {@code typedef import} (the editor's
 * "REMOVE IMPORT"): drop the header {@code modelReferences} entry that imports another model's type
 * definitions. Fails fast if no such import is declared. A field still typed by an imported id would then
 * dangle, so the kernel re-validate rejects that as a {@code rejected} envelope — never a silent break.
 */
@Command(name = "unimport", mixinStandardHelpOptions = true,
        description = "Remove a type-definition import (the inverse of `typedef import`); re-validated, so a still-used import is refused.")
final class TypedefUnimportCommand extends BaseCommand {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @ParamKind(ParamKind.Kind.FREEFORM)
    @Option(names = "--reference", required = true, paramLabel = "<reference>",
            description = "the imported model's header id to stop importing type definitions from")
    String reference;

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
        String modelJson = readModelJson(model);
        if (modelJson == null) {
            return 2;
        }
        ModelResolver resolver = workspace.resolverFor(model);
        ModelDocument doc = ModelDocument.read(modelJson);
        String resultJson;
        try {
            resultJson = doc.removeImport(reference)
                    .validate(resolver)
                    .toJson();
        } catch (ModelConsistencyException e) {
            return emitKernelRejection("typedef", "unimport", "removing the type-def import is rejected by the kernel", e);
        } catch (RuntimeException e) {
            return failEditGate(e);
        }
        ObjectNode changed = MAPPER.createObjectNode();
        changed.put("unimported", reference);
        return emitMutation(ModelWrite.of("typedef", "unimport")
                .summaries("would stop importing type definitions from " + reference,
                        "removed the type-def import of " + reference)
                .changed(changed)
                .json(resultJson)
                .gatedBy(ConsistencyEvidence.of(doc))
                .dryRun(dryRun)
                .destination(outputPath, model));
    }
}
