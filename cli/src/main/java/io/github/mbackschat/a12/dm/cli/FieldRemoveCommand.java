package io.github.mbackschat.a12.dm.cli;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.mgmtp.a12.kernel.md.model.api.services.IDocumentModelReferenceResolver;

import io.github.mbackschat.a12.dm.adapter.KernelAdapter;
import io.github.mbackschat.a12.dm.core.ModelConsistencyException;
import io.github.mbackschat.a12.dm.edit.EditSession;
import io.github.mbackschat.a12.dm.modelkit.ModelDocument;
import picocli.CommandLine.Command;
import picocli.CommandLine.Mixin;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

/**
 * {@code dmtool field remove <fieldPath>} — the safe-delete gate (CLI-SPEC §6): a field still referenced by
 * a rule/computation is <b>refused</b> ({@code outcome:refused}, exit 2) with the referrers in {@code data}
 * and an {@code RK_REFERENCED} diagnostic, unless {@code --cascade} removes those referrers first. The
 * proactive reference check is the adapter's exact {@code whereUsed}; the result re-binds through the kernel.
 */
@Command(name = "remove", mixinStandardHelpOptions = true,
        description = "Remove a field and write the model back; a referenced field is refused unless --cascade.")
final class FieldRemoveCommand extends BaseCommand {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @ParamKind(ParamKind.Kind.FIELD_PATH)
    @Parameters(index = "0", paramLabel = "<fieldPathInModel>",
            description = "the field's full name-path, e.g. /Order/Quantity")
    String field;

    @Option(names = "--cascade",
            description = "also remove the rules/computations that reference the field (else a referenced field is refused)")
    boolean cascade;

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
        IDocumentModelReferenceResolver resolver = includes.resolverFor(model);
        Optional<Refusal> notFound = new SafetyGate()
                .require(SafetyChecks.exists(ModelDocument.read(modelJson), field, false))
                .firstFailure();
        if (notFound.isPresent()) {
            return refuse("field", "remove", notFound.get());
        }
        EditSession session = EditSession.read(modelJson, resolver);
        ArrayNode cascaded = MAPPER.createArrayNode();
        try {
            KernelAdapter adapter = KernelAdapter.ofJson(modelJson, resolver);
            Set<String> refRules = new TreeSet<>(adapter.whereUsed(field));
            Set<String> refComputations = new TreeSet<>(adapter.whereUsedInComputations(field));
            if (!refRules.isEmpty() || !refComputations.isEmpty()) {
                if (!cascade) {
                    return refuse("field", "remove", SafetyChecks.referencedBy(field, refRules, refComputations,
                            "pass --cascade to remove the referrers too, or remove them first"));
                }
                for (String r : refRules) {
                    session.removeRule(r);
                    cascaded.add(r);
                }
                for (String c : refComputations) {
                    session.removeComputation(c);
                    cascaded.add(c);
                }
            }
            session.removeField(field);
        } catch (RuntimeException e) {
            return fail(e.getMessage());
        }

        String resultJson;
        try {
            resultJson = session.validate().toJson();
        } catch (ModelConsistencyException e) {
            return emitKernelRejection("field", "remove", "removal left the model invalid", e);
        }
        ObjectNode changed = MAPPER.createObjectNode();
        changed.put("removed", field);
        changed.set("cascaded", cascaded);
        if (dryRun) {
            ResultEnvelope env = ResultEnvelope.preview("field", "remove", "would remove " + field, changed);
            out().print(env.toJsonString());
            return env.exitCode();
        }
        String dest = outputPath != null ? outputPath : model;
        try {
            Files.writeString(Path.of(dest), resultJson);
        } catch (IOException e) {
            return fail("cannot write output: " + e.getMessage());
        }
        ResultEnvelope env = ResultEnvelope.applied("field", "remove", "removed " + field, changed, dest);
        out().print(env.toJsonString());
        return env.exitCode();
    }
}
