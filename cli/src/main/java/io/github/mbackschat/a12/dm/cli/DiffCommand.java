package io.github.mbackschat.a12.dm.cli;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.mgmtp.a12.kernel.md.model.api.services.IDocumentModelReferenceResolver;

import io.github.mbackschat.a12.dm.adapter.Ingest;
import io.github.mbackschat.a12.dm.adapter.IngestedComputation;
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
 * The {@code diff} verb (CLI-SPEC §4) — the F8 modify-by-re-express loop over the CLI: re-express an
 * existing element and review the <b>before/after</b> (with lossiness disclosed) before writing it back.
 * Two modes:
 *
 * <ul>
 *   <li>{@code --rule <name> --condition "<DSL>"} — re-express a rule's condition;</li>
 *   <li>{@code --computation <name> --spec <computation-spec.json>} — re-express a whole computation
 *       (the {@code add} computation-spec shape; see {@code schema add}).</li>
 * </ul>
 *
 * <p>Structured render-vs-render when the current element is modeled (F10 {@code Converted}); a
 * kernel-canonical textual diff when a rule is {@code Opaque}. {@code --output} also writes the modified
 * model back (the F8 #3 step).
 */
@Command(name = "diff", mixinStandardHelpOptions = true,
        description = "Re-express a rule (--rule --condition) or computation (--computation --spec) and show the before/after diff (F8); --output writes it back.")
final class DiffCommand extends BaseCommand {

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
            description = "write the modified model back to this file")
    String outputPath;

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
        return ruleMode ? diffRule(modelJson, resolver) : diffComputation(modelJson, resolver);
    }

    private Integer diffRule(String modelJson, IDocumentModelReferenceResolver resolver) {
        if (ruleId == null || condition == null) {
            return fail("a rule diff needs both --rule and --condition");
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
            if (original.ingest(ruleId) instanceof Ingest.Converted
                    && modified.ingest(ruleId) instanceof Ingest.Converted modConverted) {
                diff = original.diff(ruleId, modConverted.condition());
            } else {
                diff = RenderedDiff.between(original.canonicalConditionText(ruleId),
                        modified.canonicalConditionText(ruleId),
                        List.of("the rule's condition isn't fully modeled by rulekit (Opaque) — this is a textual diff"));
            }
        } catch (RuntimeException e) {
            return fail("diff failed: " + e.getMessage());
        }
        return emit(diff, modifiedJson, ruleId);
    }

    private Integer diffComputation(String modelJson, IDocumentModelReferenceResolver resolver) {
        if (computationId == null || specPath == null) {
            return fail("a computation diff needs both --computation and --spec");
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
                    + " (a diff re-expresses the same computation — match computedField/name)");
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
            IngestedComputation modified = KernelAdapter.ofJson(modifiedJson, resolver).ingestComputation(computationId);
            if (!(modified instanceof IngestedComputation.Converted modConverted)) {
                return fail("the re-expressed computation did not ingest cleanly (Opaque) — cannot diff");
            }
            diff = KernelAdapter.ofJson(modelJson, resolver).diffComputation(computationId, modConverted.computation());
        } catch (RuntimeException e) {
            return fail("diff failed: " + e.getMessage());
        }
        return emit(diff, modifiedJson, computationId);
    }

    private Integer emit(RenderedDiff diff, String modifiedJson, String elementId) {
        try {
            out().print(diff.toJson() + "\n");
            if (outputPath != null) {
                Files.writeString(Path.of(outputPath), modifiedJson);
                err().println("written " + elementId + " → " + outputPath);
            }
            return 0;
        } catch (IOException e) {
            return fail("cannot write output: " + e.getMessage());
        }
    }

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
