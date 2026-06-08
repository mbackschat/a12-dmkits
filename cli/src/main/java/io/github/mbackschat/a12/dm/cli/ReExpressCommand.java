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
import picocli.CommandLine.Mixin;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

/**
 * Shared core of the F8 <b>modify-by-re-express</b> loop: re-express an existing rule's condition or a
 * whole computation, validate the result against the kernel, and render the <b>before/after</b> (with
 * lossiness disclosed). Two verbs sit on top and differ only in what they do with the validated result:
 *
 * <ul>
 *   <li>{@link DiffCommand} ({@code diff}) — <b>preview only</b>, never writes;</li>
 *   <li>{@link ModifyCommand} ({@code modify}) — <b>writes</b> the modified model back ({@code --dry-run}
 *       falls back to preview).</li>
 * </ul>
 *
 * <p>Both share the mode rules (exactly one of {@code --rule --condition} | {@code --computation --spec}),
 * the include resolver, the kernel rejection of an invalid re-expression, and the before/after rendering;
 * the only variation point is {@link #emit(RenderedDiff, String, String)}.
 */
abstract class ReExpressCommand extends BaseCommand {

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

    private Integer diffComputation(String modelJson, IDocumentModelReferenceResolver resolver) {
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

    /**
     * Do something with the validated re-expression: {@code diff} prints the before/after only;
     * {@code modify} prints it and writes {@code modifiedJson} back (unless {@code --dry-run}).
     */
    protected abstract Integer emit(RenderedDiff diff, String modifiedJson, String elementId);

    /** Print the before/after diff JSON to stdout and return 0 (the shared rendering both verbs use). */
    protected final Integer printDiff(RenderedDiff diff) {
        out().print(diff.toJson() + "\n");
        return 0;
    }

    /** The kernel rejected the re-expression: {@code {valid:false,…}} to stderr, exit 1. */
    protected final int reject(String elementId, String reason) {
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
