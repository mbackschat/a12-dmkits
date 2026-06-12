package io.github.mbackschat.a12.dm.cli;

import java.io.IOException;
import java.io.StringReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.mgmtp.a12.kernel.md.facade.DocumentModelServiceFactory;
import com.mgmtp.a12.kernel.md.model.api.services.IDocumentModelReferenceResolver;
import com.mgmtp.a12.kernel.md.model.api.services.IDocumentModelSerializer;

import io.github.mbackschat.a12.dm.adapter.KernelAdapter;
import io.github.mbackschat.a12.dm.core.ModelConsistencyException;
import io.github.mbackschat.a12.dm.edit.EditSession;
import io.github.mbackschat.a12.dm.modelkit.ModelDocument;
import picocli.CommandLine.Command;
import picocli.CommandLine.Mixin;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

/**
 * {@code dmtool group extract <groupPath> --reference <subModelId>} — the Tier-3 structural refactor
 * (CLI-SPEC §6): lift a subtree into a standalone sub-model and re-mount it at the same name, so every
 * composed path is preserved (reference-safe — it dodges the parked reference-rewrite engine). Writes
 * <b>two</b> files: the slimmed base (in place / {@code -o}) and the sub-model ({@code <reference>.dm.json}
 * in the include-dir). The safety gate refuses a <b>repeatable</b> group ({@code RK_REPEATABLE_INCLUDE_ROOT})
 * and a subtree whose rule references <b>outside</b> it ({@code RK_OUTWARD_REFERENCE}); the composed pair
 * is kernel-revalidated <em>before</em> anything is written.
 */
@Command(name = "extract", mixinStandardHelpOptions = true,
        description = "Extract a group into a sub-model and mount it at the same name (path-preserving include).")
final class GroupExtractCommand extends BaseCommand {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final IDocumentModelSerializer SERIALIZER =
            new DocumentModelServiceFactory().createDocumentModelSerializer();

    @ParamKind(ParamKind.Kind.GROUP_PATH)
    @Parameters(index = "0", paramLabel = "<groupPathInModel>", description = "the group to extract")
    String group;

    @ParamKind(ParamKind.Kind.NEW_NAME)
    @Option(names = "--reference", required = true, paramLabel = "<subModelId>",
            description = "the new sub-model's id (and the include alias the base mounts)")
    String reference;

    @Option(names = {"--output", "-o"}, paramLabel = "<file>",
            description = "write the slimmed base here instead of in place (the sub-model still goes to the include-dir)")
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
        IDocumentModelReferenceResolver dirResolver = includes.resolverFor(model);

        Optional<Refusal> refusal = new SafetyGate()
                .require(SafetyChecks.exists(doc, group, true))
                .require(SafetyChecks.includableAsRoot(doc, group))
                .require(SafetyChecks.subtreeSelfContained(KernelAdapter.ofJson(modelJson, dirResolver), group))
                .firstFailure();
        if (refusal.isPresent()) {
            return refuse("group", "extract", refusal.get());
        }

        String subModel;
        try {
            subModel = doc.extractGroupAsInclude(group, reference);
        } catch (RuntimeException e) {
            return fail(e.getMessage());
        }
        String baseJson = doc.toJson();

        // validate the composed pair BEFORE writing — serve the sub-model from memory, delegate other refs.
        IDocumentModelReferenceResolver composed = ref -> {
            if (reference.equals(ref)) {
                try {
                    return SERIALIZER.deserialize(new StringReader(subModel));
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
            }
            return dirResolver.getDocumentModel(ref);
        };
        try {
            EditSession.read(baseJson, composed).validate();
        } catch (ModelConsistencyException e) {
            return emitKernelRejection("group", "extract", "extraction left the model invalid", e);
        }

        Path parent = Path.of(model).toAbsolutePath().getParent();
        Path targetDir = !includes.includeDirs.isEmpty() ? includes.includeDirs.get(0)
                : (parent != null ? parent : Path.of("."));
        Path subFile = targetDir.resolve(reference + ".dm.json");
        ObjectNode changed = MAPPER.createObjectNode();
        changed.put("extracted", group);
        changed.put("reference", reference);
        changed.put("mountedAt", group);
        changed.put("subModel", subFile.toString());
        if (dryRun) {
            ResultEnvelope env = ResultEnvelope.preview("group", "extract",
                    "would extract " + group + " into sub-model " + reference, changed);
            out().print(env.toJsonString());
            return env.exitCode();
        }
        String dest = outputPath != null ? outputPath : model;
        try {
            Files.writeString(subFile, subModel);
            Files.writeString(Path.of(dest), baseJson);
        } catch (IOException e) {
            return fail("cannot write output: " + e.getMessage());
        }
        ResultEnvelope env = ResultEnvelope.applied("group", "extract",
                "extracted " + group + " into sub-model " + reference + " (" + subFile + ")", changed, dest);
        out().print(env.toJsonString());
        return env.exitCode();
    }
}
