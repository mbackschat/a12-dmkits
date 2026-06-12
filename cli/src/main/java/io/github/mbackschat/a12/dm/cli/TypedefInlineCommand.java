package io.github.mbackschat.a12.dm.cli;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.mgmtp.a12.kernel.md.model.api.services.IDocumentModelReferenceResolver;

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
    IncludeDirOption includes = new IncludeDirOption();

    @Override
    public Integer call() {
        String model = model();
        String modelJson = readModelJson(model);
        if (modelJson == null) {
            return 2;
        }
        ModelDocument doc = ModelDocument.read(modelJson);
        IDocumentModelReferenceResolver resolver = includes.resolverFor(model);
        List<String> expanded = doc.fieldsUsingTypeDefinition(id);   // captured before the definition is dropped
        String resultJson;
        try {
            doc.inlineTypeDefinition(id);
            resultJson = doc.validate(resolver).toJson();
        } catch (ModelConsistencyException e) {
            return emitKernelRejection("typedef", "inline", "inline left the model invalid", e);
        } catch (RuntimeException e) {
            return fail(e.getMessage());
        }

        ObjectNode changed = MAPPER.createObjectNode();
        changed.put("inlined", id);
        ArrayNode fields = changed.putArray("expandedFields");
        expanded.forEach(fields::add);
        if (dryRun) {
            ResultEnvelope env = ResultEnvelope.preview("typedef", "inline",
                    "would inline typedef " + id, changed);
            out().print(env.toJsonString());
            return env.exitCode();
        }
        String dest = outputPath != null ? outputPath : model;
        try {
            Files.writeString(Path.of(dest), resultJson);
        } catch (IOException e) {
            return fail("cannot write output: " + e.getMessage());
        }
        ResultEnvelope env = ResultEnvelope.applied("typedef", "inline",
                "inlined typedef " + id, changed, dest);
        out().print(env.toJsonString());
        return env.exitCode();
    }
}
