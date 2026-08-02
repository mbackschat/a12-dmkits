package io.github.mbackschat.a12.dm.cli.typedef;

import io.github.mbackschat.a12.dm.cli.BaseCommand;
import io.github.mbackschat.a12.dm.cli.ConsistencyEvidence;
import io.github.mbackschat.a12.dm.cli.ModelWrite;
import io.github.mbackschat.a12.dm.cli.ParamKind;
import io.github.mbackschat.a12.dm.cli.Refusal;
import io.github.mbackschat.a12.dm.cli.SafetyChecks;
import io.github.mbackschat.a12.dm.cli.SafetyGate;
import io.github.mbackschat.a12.dm.cli.WorkspaceOption;

import java.util.List;
import java.util.Optional;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.mbackschat.a12.dm.core.ModelResolver;

import io.github.mbackschat.a12.dm.core.ModelConsistencyException;
import io.github.mbackschat.a12.dm.modelkit.ModelDocument;
import picocli.CommandLine.Command;
import picocli.CommandLine.Mixin;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

/**
 * {@code dmtool typedef inline <id>} — the inverse of {@code typedef extract} (CLI-SPEC §6): expand a type
 * definition's concrete type back into every field that uses it, then drop the definition. Always
 * reference-safe (each field keeps the same effective type), so no gate beyond the kernel re-validate; an
 * absent id is a usage error. {@code changed.expandedFields} lists the fields the type was inlined into.
 */
@Command(name = "inline", mixinStandardHelpOptions = true,
        description = "Expand a type definition back into each using field, then drop the definition.")
final class TypedefInlineCommand extends BaseCommand {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @ParamKind(ParamKind.Kind.TYPEDEF_ID)
    @Parameters(index = "0", paramLabel = "<typeDefId>", description = "the type definition to inline")
    String id;

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
        ModelDocument doc = ModelDocument.read(modelJson);
        ModelResolver resolver = workspace.resolverFor(model);
        Optional<Refusal> refusal = new SafetyGate()
                .require(SafetyChecks.typedefExists(doc, id))
                .firstFailure();
        if (refusal.isPresent()) {
            return refuse(refusal.get());
        }
        List<String> expanded = doc.fieldsUsingTypeDefinition(id);   // captured before the definition is dropped
        String resultJson;
        try {
            doc.inlineTypeDefinition(id);
            resultJson = doc.validate(resolver).toJson();
        } catch (ModelConsistencyException e) {
            return emitKernelRejection("inline left the model invalid", e);
        } catch (RuntimeException e) {
            return failEditGate(e);
        }

        ObjectNode changed = MAPPER.createObjectNode();
        changed.put("inlined", id);
        ArrayNode fields = changed.putArray("expandedFields");
        expanded.forEach(fields::add);
        return emitMutation(ModelWrite.of(resultIdentity())
                .summaries("would inline typedef " + id,
                        "inlined typedef " + id)
                .changed(changed)
                .json(resultJson)
                .gatedBy(ConsistencyEvidence.of(doc))
                .dryRun(dryRun)
                .destination(outputPath, model));
    }
}
