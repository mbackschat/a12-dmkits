package io.github.mbackschat.a12.dm.cli.typedef;

import io.github.mbackschat.a12.dm.cli.ConsistencyEvidence;
import io.github.mbackschat.a12.dm.cli.BaseCommand;
import io.github.mbackschat.a12.dm.cli.ParamKind;
import io.github.mbackschat.a12.dm.cli.Refusal;
import io.github.mbackschat.a12.dm.cli.ResultEnvelope;
import io.github.mbackschat.a12.dm.cli.StructureRefactor;
import io.github.mbackschat.a12.dm.cli.WorkspaceOption;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.mbackschat.a12.dm.core.ModelResolver;

import io.github.mbackschat.a12.dm.adapter.KernelAdapter;
import io.github.mbackschat.a12.dm.core.ModelConsistencyException;
import io.github.mbackschat.a12.dm.modelkit.ModelDocument;
import picocli.CommandLine.Command;
import picocli.CommandLine.Mixin;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

/**
 * {@code dmtool typedef rename <id> --to <newId>} — a Tier-1 structural refactor (CLI-SPEC §6): rewrite a
 * type definition's id <b>and</b> every using field's {@code typeDefinitionId} in step. A typedef never
 * appears in a condition, so this needs no AST reference-rewrite. The safety gate runs first: a new id
 * already declared is <b>refused</b> ({@code RK_NAME_EXISTS}, exit 2) and nothing is written; the kernel
 * re-validate is the backstop. {@code changed.repointedFields} lists the fields that moved.
 */
@Command(name = "rename", mixinStandardHelpOptions = true,
        description = "Rename a type definition and repoint every using field; a taken id is refused.")
final class TypedefRenameCommand extends BaseCommand {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @ParamKind(ParamKind.Kind.TYPEDEF_ID)
    @Parameters(index = "0", paramLabel = "<typeDefId>", description = "the type definition's current id")
    String id;

    @ParamKind(ParamKind.Kind.NEW_NAME)
    @Option(names = "--to", required = true, paramLabel = "<newId>", description = "the new type-definition id")
    String newId;

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
        StructureRefactor refactor = StructureRefactor.typedefRename(id, newId);
        ModelDocument doc = ModelDocument.read(modelJson);
        ModelResolver resolver = workspace.resolverFor(model);
        KernelAdapter adapter = KernelAdapter.ofJson(modelJson, resolver);

        // the refactor safety gate (CLI-SPEC §6): check before we change anything, refuse with diagnostics.
        Optional<Refusal> refusal = refactor.firstFailure(doc, adapter);
        if (refusal.isPresent()) {
            return refuse("typedef", "rename", refusal.get());
        }

        List<String> repointed = doc.fieldsUsingTypeDefinition(id);   // captured before the rename moves them
        String resultJson;
        // The gated document is the REWRITTEN one, not the `doc` read above — that one only feeds the
        // safety gate and never sees `validate`, so publishing its (absent) receipt would silently
        // under-report the check this route really ran.
        ModelDocument rewritten;
        try {
            rewritten = refactor.rewriteAndMutate(modelJson).validate(resolver);
            resultJson = rewritten.toJson();
        } catch (ModelConsistencyException e) {
            return emitKernelRejection("typedef", "rename", "rename left the model invalid", e);
        } catch (RuntimeException e) {
            return failEditGate(e);
        }

        ObjectNode changed = MAPPER.createObjectNode();
        changed.put("renamed", id);
        changed.put("to", newId);
        ArrayNode fields = changed.putArray("repointedFields");
        repointed.forEach(fields::add);
        if (dryRun) {
            ResultEnvelope env = ResultEnvelope.preview("typedef", "rename",
                    "would rename typedef " + id + " to " + newId, changed, ConsistencyEvidence.of(rewritten));
            out().print(env.toJsonString());
            return env.exitCode();
        }
        String dest = outputPath != null ? outputPath : model;
        try {
            Files.writeString(Path.of(dest), resultJson);
        } catch (IOException e) {
            return fail("cannot write output: " + e.getMessage());
        }
        ResultEnvelope env = ResultEnvelope.applied("typedef", "rename",
                "renamed typedef " + id + " to " + newId, changed, dest, ConsistencyEvidence.of(rewritten));
        out().print(env.toJsonString());
        return env.exitCode();
    }
}
