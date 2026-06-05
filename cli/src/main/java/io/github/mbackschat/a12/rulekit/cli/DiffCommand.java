package io.github.mbackschat.a12.rulekit.cli;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import io.github.mbackschat.a12.rulekit.adapter.Ingest;
import io.github.mbackschat.a12.rulekit.adapter.KernelAdapter;
import io.github.mbackschat.a12.rulekit.dsl.BoundModel;
import io.github.mbackschat.a12.rulekit.dsl.RenderedDiff;
import io.github.mbackschat.a12.rulekit.write.DmJson;
import io.github.mbackschat.a12.rulekit.write.ModelWriter;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

/**
 * The {@code diff} verb (CLI-SPEC §4) — the F8 modify-by-re-express loop over the CLI: re-express an
 * existing rule's condition and review the <b>before/after</b> (with lossiness disclosed) before
 * writing it back. Structured render-vs-render when the current condition is modeled (F10
 * {@code Converted}); a kernel-canonical textual diff when it is {@code Opaque}. {@code --output} also
 * writes the modified model back (the F8 #3 step).
 */
@Command(name = "diff", mixinStandardHelpOptions = true,
        description = "Re-express a rule's condition and show the before/after diff (F8); --output writes it back.")
final class DiffCommand extends BaseCommand {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Parameters(index = "0", paramLabel = "<model.json>", description = "the DM-JSON model file")
    String model;

    @Option(names = "--rule", required = true, paramLabel = "<id>", description = "full name of the rule to re-express")
    String ruleId;

    @Option(names = "--condition", required = true, paramLabel = "<DSL>", description = "the new condition in A12 DSL")
    String condition;

    @Option(names = {"--output", "-o"}, paramLabel = "<file>",
            description = "write the modified model back to this file")
    String outputPath;

    @Override
    public Integer call() {
        String modelJson;
        try {
            modelJson = Files.readString(Path.of(model));
        } catch (IOException e) {
            return fail("cannot read model: " + e.getMessage());
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
            BoundModel.ofJson(modifiedJson);
        } catch (RuntimeException e) {
            return reject(ruleId, e.getMessage());
        }

        RenderedDiff diff;
        try {
            KernelAdapter original = KernelAdapter.ofJson(modelJson);
            KernelAdapter modified = KernelAdapter.ofJson(modifiedJson);
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

        try {
            out().print(diff.toJson() + "\n");
            if (outputPath != null) {
                Files.writeString(Path.of(outputPath), modifiedJson);
                err().println("written " + ruleId + " → " + outputPath);
            }
            return 0;
        } catch (IOException e) {
            return fail("cannot write output: " + e.getMessage());
        }
    }

    private int reject(String ruleId, String reason) {
        try {
            ObjectNode root = MAPPER.createObjectNode();
            root.put("valid", false);
            root.put("rule", ruleId);
            root.put("reason", "the new condition is rejected by the kernel (use `check` for diagnostics): " + reason);
            err().println(MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(root));
        } catch (com.fasterxml.jackson.core.JsonProcessingException ignored) {
            // best-effort
        }
        return 1;
    }
}
