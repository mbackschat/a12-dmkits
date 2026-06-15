package io.github.mbackschat.a12.dm.cli;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

import com.fasterxml.jackson.databind.JsonNode;
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
 * {@code dmtool field modify <spec.json>} — change an existing field's <b>type / config in place</b> (e.g.
 * add a STRING {@code pattern}, tighten a NUMBER's scale, extend an ENUM's value set) and write the model
 * back. The field is located by the spec's {@code group} + {@code name}; its {@code fieldType} is replaced
 * with the spec's kind + per-kind config — so a constraint can be added <em>without delete-and-recreate</em>
 * (FEATURE-GAPS §2 G6). It re-types the field only; field <em>metadata</em> (label/requiredness/descriptions)
 * is untouched. Same rich spec shape as {@code field add} ({@code schema field modify}); the result is
 * re-bound through the kernel, so a kernel-invalid change is a {@code rejected} envelope (nothing written).
 * In place by default; {@code -o} redirects, {@code --dry-run} previews.
 */
@Command(name = "modify", mixinStandardHelpOptions = true,
        description = "Change a field's type/config in place (re-type / add constraints) via a <spec.json> "
                + "— no delete-and-recreate; -o redirects, --dry-run previews.")
final class FieldModifyCommand extends BaseCommand {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Parameters(index = "0", paramLabel = "<spec.json>",
            description = "a rich field spec (JSON): group + name locate the field; kind + per-kind config "
                    + "become its new type (see `schema field modify`)")
    String specPath;

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
        if (specPath == null) {
            return fail("pass a <spec.json> (group + name locate the field; kind + config become its new type)");
        }
        String modelJson;
        try {
            modelJson = Files.readString(Path.of(model));
        } catch (IOException e) {
            return fail("cannot read model: " + e.getMessage());
        }
        IDocumentModelReferenceResolver resolver = workspace.resolverFor(model);
        ModelDocument doc = ModelDocument.read(modelJson);

        String fieldPath;
        ObjectNode changed = MAPPER.createObjectNode();
        try {
            JsonNode spec = MAPPER.readTree(Files.readString(Path.of(specPath)));
            FieldSpec.Parsed parsed = FieldSpec.parse(spec);
            fieldPath = parsed.group() + "/" + parsed.field().name();
            // the field-exists gate runs first, so modifying a missing field gets the structured refusal
            // (not delete-and-recreate, not a kernel stack trace)
            Optional<Refusal> refusal = new SafetyGate()
                    .require(SafetyChecks.exists(doc, fieldPath, false))
                    .firstFailure();
            if (refusal.isPresent()) {
                return refuse("field", "modify", refusal.get());
            }
            doc.setFieldType(fieldPath, parsed.field().type());
            changed.put("modified", fieldPath);
            changed.put("kind", spec.hasNonNull("typedef") ? "TypeDefType" : spec.path("kind").asText());
        } catch (IllegalArgumentException badSpec) {
            return fail("invalid field spec: " + badSpec.getMessage());
        } catch (IOException e) {
            return fail("cannot read field spec: " + e.getMessage());
        }

        String resultJson;
        try {
            resultJson = doc.validate(resolver).toJson();
        } catch (ModelConsistencyException e) {
            return emitKernelRejection("field", "modify", "the change is rejected by the kernel", e);
        } catch (RuntimeException e) {
            return fail(e.getMessage());
        }
        if (dryRun) {
            ResultEnvelope env = ResultEnvelope.preview("field", "modify", "would re-type " + fieldPath, changed);
            out().print(env.toJsonString());
            return env.exitCode();
        }
        String dest = outputPath != null ? outputPath : model;
        try {
            Files.writeString(Path.of(dest), resultJson);
        } catch (IOException e) {
            return fail("cannot write output: " + e.getMessage());
        }
        ResultEnvelope env = ResultEnvelope.applied("field", "modify", "re-typed " + fieldPath, changed, dest);
        out().print(env.toJsonString());
        return env.exitCode();
    }
}
