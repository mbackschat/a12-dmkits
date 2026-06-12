package io.github.mbackschat.a12.dm.cli;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.mgmtp.a12.kernel.md.model.api.services.IDocumentModelReferenceResolver;

import io.github.mbackschat.a12.dm.core.ModelConsistencyException;
import io.github.mbackschat.a12.dm.modelkit.ModelDocument;
import picocli.CommandLine.Command;
import picocli.CommandLine.Mixin;
import picocli.CommandLine.Option;

/**
 * {@code dmtool typedef extract --from <f1,f2,…> --id <newId>} — a Tier-1 structural refactor (CLI-SPEC
 * §6): factor a set of fields that share one concrete type into a reusable type definition and repoint
 * them at it (DRY). The safety gate runs first: a taken id is refused ({@code RK_NAME_EXISTS}); fields
 * that do not share one concrete type are refused ({@code RK_TYPE_MISMATCH}). Nothing is coerced.
 */
@Command(name = "extract", mixinStandardHelpOptions = true,
        description = "Factor fields sharing one concrete type into a new type definition and repoint them.")
final class TypedefExtractCommand extends BaseCommand {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Option(names = "--from", required = true, split = ",", paramLabel = "<fieldPath,...>",
            description = "the field paths to factor out (comma-separated); they must share one concrete type")
    List<String> from;

    @Option(names = "--id", required = true, paramLabel = "<newId>", description = "the new type-definition id")
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

        // the refactor safety gate (CLI-SPEC §6): a taken id, or fields without one concrete type, refuse.
        Optional<Refusal> refusal = new SafetyGate()
                .require(SafetyChecks.typeDefIdAvailable(doc, id))
                .require(SafetyChecks.fieldsShareConcreteType(doc, from))
                .firstFailure();
        if (refusal.isPresent()) {
            return refuse("typedef", "extract", refusal.get());
        }

        IDocumentModelReferenceResolver resolver = includes.resolverFor(model);
        String resultJson;
        try {
            doc.extractTypeDefinition(id, from);
            resultJson = doc.validate(resolver).toJson();
        } catch (ModelConsistencyException e) {
            return emitKernelRejection("typedef", "extract", "extract left the model invalid", e);
        } catch (RuntimeException e) {
            return fail(e.getMessage());
        }

        ObjectNode changed = MAPPER.createObjectNode();
        changed.put("created", id);
        ArrayNode fields = changed.putArray("fromFields");
        from.forEach(fields::add);
        if (dryRun) {
            ResultEnvelope env = ResultEnvelope.preview("typedef", "extract",
                    "would extract " + from.size() + " field(s) into typedef " + id, changed);
            out().print(env.toJsonString());
            return env.exitCode();
        }
        String dest = outputPath != null ? outputPath : model;
        try {
            Files.writeString(Path.of(dest), resultJson);
        } catch (IOException e) {
            return fail("cannot write output: " + e.getMessage());
        }
        ResultEnvelope env = ResultEnvelope.applied("typedef", "extract",
                "extracted " + from.size() + " field(s) into typedef " + id, changed, dest);
        out().print(env.toJsonString());
        return env.exitCode();
    }
}
