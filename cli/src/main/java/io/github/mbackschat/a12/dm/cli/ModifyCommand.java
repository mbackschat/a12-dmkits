package io.github.mbackschat.a12.dm.cli;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.mgmtp.a12.kernel.md.model.api.services.IDocumentModelReferenceResolver;

import io.github.mbackschat.a12.dm.adapter.ReadResult;
import io.github.mbackschat.a12.dm.adapter.ReadComputation;
import io.github.mbackschat.a12.dm.adapter.KernelAdapter;
import io.github.mbackschat.a12.dm.rulekit.dsl.BoundModel;
import io.github.mbackschat.a12.dm.rulekit.dsl.RenderedDiff;
import io.github.mbackschat.a12.dm.rulekit.write.DmJson;
import io.github.mbackschat.a12.dm.rulekit.write.ModelWriter;
import picocli.CommandLine.Command;
import picocli.CommandLine.Mixin;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

/**
 * The {@code modify} verb (CLI-SPEC §4) — the F8 <b>modify-by-re-express</b> loop over the CLI, and the
 * <b>only</b> re-express verb: re-express an existing rule's condition ({@code --rule --condition}) or a
 * whole computation ({@code --computation --spec}), validate it against the kernel, and <b>write the
 * modified model back</b> — <em>in place</em> by default (overwriting {@code <model.json>}), or to
 * {@code -o <file>}. It always prints the before/after {@code RenderedDiff} to stdout (so the change is
 * visible); {@code --dry-run} previews <em>only</em> and writes nothing. An invalid re-expression is
 * rejected by the kernel before any write.
 *
 * <p>History: a separate read-only {@code diff} verb briefly existed (2026-06-08) but was retired the same
 * day — it was redundant with {@code modify --dry-run} (identical code path + output), so {@code modify}
 * is now the single re-express verb: default writes, {@code --dry-run} previews.
 */
@Command(name = "modify", mixinStandardHelpOptions = true,
        description = "Re-express a rule (--rule --condition) or computation (--computation --spec) and WRITE the "
                + "modified model back (in place by default; -o redirects). --dry-run previews only (read-only).")
final class ModifyCommand extends BaseCommand {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Parameters(index = "0", paramLabel = "<model.json>",
            description = "the DM-JSON model FILE (a file path) — distinct from --rule/--computation, paths inside it")
    String model;

    @Option(names = "--rule", paramLabel = "<rulePathInModel>",
            description = "the rule's full name to re-express — its slash-path in the model, e.g. /Order/SomeRule "
                    + "(group path + rule name; NOT the short name 'SomeRule', the opaque element id, or a file path)")
    String ruleId;

    @Option(names = "--condition", paramLabel = "<DSL>",
            description = "the new condition in A12 DSL — true on a VIOLATION (the error scenario), "
                    + "e.g. FieldNotFilled(X) not FieldFilled(X) for a requiredness rule")
    String condition;

    @Option(names = "--computation", paramLabel = "<computationPathInModel>",
            description = "the computation's full name to re-express — its slash-path in the model, e.g. /Order/TotalComp")
    String computationId;

    @Option(names = "--spec", paramLabel = "<computation-spec.json>",
            description = "the re-expressed computation as a computation-spec JSON file (see `schema add`)")
    String specPath;

    @Option(names = {"--output", "-o"}, paramLabel = "<file>",
            description = "write the modified model here instead of in place (overwriting <model.json>)")
    String outputPath;

    @Option(names = "--dry-run",
            description = "preview only — print the before/after diff and write NOTHING (read-only)")
    boolean dryRun;

    @Mixin
    IncludeDirOption includes = new IncludeDirOption();

    @Override
    public Integer call() {
        boolean ruleMode = ruleId != null || condition != null;
        boolean computationMode = computationId != null || specPath != null;
        if (ruleMode == computationMode) {
            return fail("pass exactly one of: --rule <name> --condition <DSL>  |  --computation <name> --spec <file>");
        }
        String modelJson;
        try {
            modelJson = Files.readString(Path.of(model));
        } catch (IOException e) {
            return fail("cannot read model: " + e.getMessage());
        }
        IDocumentModelReferenceResolver resolver = includes.resolverFor(model);
        return ruleMode ? reExpressRule(modelJson, resolver) : reExpressComputation(modelJson, resolver);
    }

    private Integer reExpressRule(String modelJson, IDocumentModelReferenceResolver resolver) {
        if (ruleId == null || condition == null) {
            return fail("a rule re-expression needs both --rule and --condition");
        }
        try {
            JsonNode root = MAPPER.readTree(modelJson);
            if (DmJson.findElement(root, "Rule", ruleId) == null) {
                return fail("no rule '" + ruleId + "' in the model");
            }
        } catch (IOException e) {
            return fail("cannot parse model: " + e.getMessage());
        }

        String modifiedJson;
        try {
            modifiedJson = ModelWriter.replaceCondition(modelJson, ruleId, condition);
        } catch (RuntimeException e) {
            return fail("cannot apply the new condition: " + e.getMessage());
        }
        try {
            BoundModel.ofJson(modifiedJson, resolver);
        } catch (RuntimeException e) {
            return reject(ruleId, e.getMessage());
        }

        RenderedDiff diff;
        try {
            KernelAdapter original = KernelAdapter.ofJson(modelJson, resolver);
            KernelAdapter modified = KernelAdapter.ofJson(modifiedJson, resolver);
            if (original.read(ruleId) instanceof ReadResult.Converted
                    && modified.read(ruleId) instanceof ReadResult.Converted modConverted) {
                diff = original.diff(ruleId, modConverted.condition());
            } else {
                diff = RenderedDiff.between(original.canonicalConditionText(ruleId),
                        modified.canonicalConditionText(ruleId),
                        List.of("the rule's condition isn't fully modeled by rulekit (Opaque) — this is a textual diff"));
            }
        } catch (RuntimeException e) {
            return fail("re-expression failed: " + e.getMessage());
        }
        return emit(diff, modifiedJson, ruleId);
    }

    private Integer reExpressComputation(String modelJson, IDocumentModelReferenceResolver resolver) {
        if (computationId == null || specPath == null) {
            return fail("a computation re-expression needs both --computation and --spec");
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
        if (!parsed.fullName().equals(computationId)) {
            return fail("the --spec re-expresses " + parsed.fullName() + ", not the --computation " + computationId
                    + " (a re-expression keeps the same computation — match computedField/name)");
        }

        String modifiedJson;
        try {
            modifiedJson = ModelWriter.replaceComputation(modelJson, computationId, parsed.draft(), parsed.messages());
        } catch (RuntimeException e) {
            return fail("cannot apply the new computation: " + e.getMessage());
        }
        try {
            BoundModel.ofJson(modifiedJson, resolver);
        } catch (RuntimeException e) {
            return reject(computationId, e.getMessage());
        }

        RenderedDiff diff;
        try {
            ReadComputation modified = KernelAdapter.ofJson(modifiedJson, resolver).readComputation(computationId);
            if (!(modified instanceof ReadComputation.Converted modConverted)) {
                return fail("the re-expressed computation did not read cleanly (Opaque) — cannot render");
            }
            diff = KernelAdapter.ofJson(modelJson, resolver).diffComputation(computationId, modConverted.computation());
        } catch (RuntimeException e) {
            return fail("re-expression failed: " + e.getMessage());
        }
        return emit(diff, modifiedJson, computationId);
    }

    /** Print the before/after diff; write the modified model back unless {@code --dry-run}. */
    private Integer emit(RenderedDiff diff, String modifiedJson, String elementId) {
        out().print(diff.toJson() + "\n");
        if (dryRun) {
            err().println("(dry run — " + elementId + " not written)");
            return 0;
        }
        String target = outputPath != null ? outputPath : model;
        try {
            Files.writeString(Path.of(target), modifiedJson);
        } catch (IOException e) {
            return fail("cannot write output: " + e.getMessage());
        }
        err().println("modified " + elementId + " → " + target);
        return 0;
    }

    /** The kernel rejected the re-expression: {@code {valid:false,…}} to stderr, exit 1. */
    private int reject(String elementId, String reason) {
        try {
            ObjectNode root = MAPPER.createObjectNode();
            root.put("valid", false);
            root.put("element", elementId);
            root.put("reason", "the re-expression is rejected by the kernel (use `check` for diagnostics): " + reason);
            err().println(MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(root));
        } catch (com.fasterxml.jackson.core.JsonProcessingException ignored) {
            // best-effort
        }
        return 1;
    }
}
