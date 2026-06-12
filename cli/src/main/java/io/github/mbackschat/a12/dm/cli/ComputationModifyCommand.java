package io.github.mbackschat.a12.dm.cli;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mgmtp.a12.kernel.md.model.api.services.IDocumentModelReferenceResolver;

import io.github.mbackschat.a12.dm.adapter.KernelAdapter;
import io.github.mbackschat.a12.dm.adapter.ReadComputation;
import io.github.mbackschat.a12.dm.rulekit.dsl.BoundModel;
import io.github.mbackschat.a12.dm.rulekit.dsl.RenderedDiff;
import io.github.mbackschat.a12.dm.rulekit.validate.Diagnostic;
import io.github.mbackschat.a12.dm.rulekit.write.ModelWriter;
import picocli.CommandLine.Command;
import picocli.CommandLine.Mixin;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

/**
 * {@code dmtool computation modify <computationPath> --spec <file>} — re-express a whole computation,
 * keeping the same computed field, validate against the kernel, and write the model back. The before/after
 * {@code RenderedDiff} rides {@code changed}; a kernel-rejected re-expression yields a {@code rejected}
 * envelope. The {@code --spec} must re-express the SAME computation (matching computedField/name).
 */
@Command(name = "modify", mixinStandardHelpOptions = true,
        description = "Re-express a computation (--spec) and write the model back (in place; -o/--dry-run).")
final class ComputationModifyCommand extends BaseCommand {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @ParamKind(ParamKind.Kind.COMPUTATION_PATH)
    @Parameters(index = "0", paramLabel = "<computationPathInModel>",
            description = "the computation's full name — its slash-path in the model, e.g. /Order/TotalComp")
    String computation;

    @ParamKind(ParamKind.Kind.FILE)
    @Option(names = "--spec", required = true, paramLabel = "<computation-spec.json>",
            description = "the re-expressed computation as a computation-spec JSON file")
    String specPath;

    @Option(names = {"--output", "-o"}, paramLabel = "<file>",
            description = "write the result here instead of in place (overwriting the model)")
    String outputPath;

    @Option(names = "--dry-run", description = "preview only — print the diff and write NOTHING")
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
        Optional<Refusal> refusal = new SafetyGate()
                .require(SafetyChecks.computationExists(KernelAdapter.ofJson(modelJson, resolver), computation))
                .firstFailure();
        if (refusal.isPresent()) {
            return refuse("computation", "modify", refusal.get());
        }
        JsonNode spec;
        try {
            spec = MAPPER.readTree(Files.readString(Path.of(specPath)));
        } catch (IOException e) {
            return fail("cannot read --spec: " + e.getMessage());
        }
        ComputationSpec.Parsed parsed = ComputationSpec.parse(spec);
        if (!parsed.ok()) {
            return fail(parsed.error());
        }
        if (!parsed.fullName().equals(computation)) {
            return fail("the --spec re-expresses " + parsed.fullName() + ", not the computation " + computation
                    + " (a re-expression keeps the same computation — match computedField/name)");
        }

        String modifiedJson;
        try {
            modifiedJson = ModelWriter.replaceComputation(modelJson, computation, parsed.draft(), parsed.messages());
        } catch (RuntimeException e) {
            return fail("cannot apply the new computation: " + e.getMessage());
        }
        try {
            BoundModel.ofJson(modifiedJson, resolver);
        } catch (RuntimeException e) {
            Diagnostic d = Diagnostic.precheck(Optional.empty(),
                    "the re-expression is rejected by the kernel: " + e.getMessage(),
                    new Diagnostic.Where(computation, Optional.empty()), Optional.empty(), Optional.empty());
            ResultEnvelope env = ResultEnvelope.rejected("computation", "modify",
                    "re-expression rejected by the kernel", List.of(d));
            out().print(env.toJsonString());
            return env.exitCode();
        }

        JsonNode changed;
        try {
            ReadComputation modified = KernelAdapter.ofJson(modifiedJson, resolver).readComputation(computation);
            if (!(modified instanceof ReadComputation.Converted modConverted)) {
                return fail("the re-expressed computation did not read cleanly (Opaque) — cannot render");
            }
            RenderedDiff diff = KernelAdapter.ofJson(modelJson, resolver)
                    .diffComputation(computation, modConverted.computation());
            changed = MAPPER.readTree(diff.toJson());
        } catch (IOException | RuntimeException e) {
            return fail("re-expression failed: " + e.getMessage());
        }

        if (dryRun) {
            ResultEnvelope env = ResultEnvelope.preview("computation", "modify",
                    "would re-express " + computation, changed);
            out().print(env.toJsonString());
            return env.exitCode();
        }
        String dest = outputPath != null ? outputPath : model;
        try {
            Files.writeString(Path.of(dest), modifiedJson);
        } catch (IOException e) {
            return fail("cannot write output: " + e.getMessage());
        }
        ResultEnvelope env = ResultEnvelope.applied("computation", "modify",
                "re-expressed " + computation, changed, dest);
        out().print(env.toJsonString());
        return env.exitCode();
    }
}
