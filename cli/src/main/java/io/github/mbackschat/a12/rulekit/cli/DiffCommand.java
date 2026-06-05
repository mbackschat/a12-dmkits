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

/**
 * The {@code diff} verb (CLI-SPEC §4) — the F8 modify-by-re-express loop over the CLI: re-express an
 * existing rule's condition and review the <b>before/after</b> (with lossiness disclosed) before
 * writing it back. {@code diff <model> --rule <id> --condition "<new DSL>" [--output <file>]}.
 *
 * <p>Flow: apply the new condition with {@link ModelWriter#replaceCondition} → validate by loading the
 * modified model (a kernel-rejected condition exits 1, nothing emitted; use {@code check} for detailed
 * diagnostics) → build the diff. When the current condition is modeled (F10 {@code Converted}) the diff
 * is the adapter's structured render-vs-render with comment lossiness; when it's {@code Opaque} (e.g. an
 * aggregate not yet ingestable) it falls back to a kernel-canonical textual diff with a lossiness note.
 * The diff JSON goes to stdout; {@code --output} also writes the modified model back (the F8 #3 step).
 */
final class DiffCommand {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String USAGE =
            "usage: diff <model.json> --rule <id> --condition \"<DSL>\" [--output <file>]";

    private DiffCommand() {
    }

    static int run(String[] args, Appendable out, Appendable err) {
        if (args.length == 0) {
            return fail(err, USAGE);
        }
        String modelPath = args[0];
        String ruleId = null;
        String condition = null;
        String outputPath = null;
        for (int i = 1; i < args.length; i++) {
            String flag = args[i];
            if (i + 1 >= args.length) {
                return fail(err, flag + " needs a value\n" + USAGE);
            }
            String value = args[++i];
            switch (flag) {
                case "--rule" -> ruleId = value;
                case "--condition" -> condition = value;
                case "--output", "-o" -> outputPath = value;
                default -> {
                    return fail(err, "unknown option: " + flag + "\n" + USAGE);
                }
            }
        }
        if (ruleId == null || condition == null) {
            return fail(err, "diff needs --rule and --condition\n" + USAGE);
        }

        String modelJson;
        try {
            modelJson = Files.readString(Path.of(modelPath));
        } catch (IOException e) {
            return fail(err, "cannot read model: " + e.getMessage());
        }

        try {
            JsonNode root = MAPPER.readTree(modelJson);
            if (DmJson.findElement(root, "Rule", ruleId) == null) {
                return fail(err, "no rule '" + ruleId + "' in the model");
            }
        } catch (IOException e) {
            return fail(err, "cannot parse model: " + e.getMessage());
        }

        // apply the new condition, then validate by loading the modified model
        String modifiedJson;
        try {
            modifiedJson = ModelWriter.replaceCondition(modelJson, ruleId, condition);
        } catch (RuntimeException e) {
            return fail(err, "cannot apply the new condition: " + e.getMessage());
        }
        try {
            BoundModel.ofJson(modifiedJson);
        } catch (RuntimeException e) {
            return reject(err, ruleId, e.getMessage());
        }

        RenderedDiff diff;
        try {
            KernelAdapter original = KernelAdapter.ofJson(modelJson);
            KernelAdapter modified = KernelAdapter.ofJson(modifiedJson);
            if (original.ingest(ruleId) instanceof Ingest.Converted
                    && modified.ingest(ruleId) instanceof Ingest.Converted modConverted) {
                // structured render-vs-render (the adapter discloses comment lossiness)
                diff = original.diff(ruleId, modConverted.condition());
            } else {
                // one side isn't fully modeled (Opaque) — fall back to a kernel-canonical textual diff
                diff = RenderedDiff.between(original.canonicalConditionText(ruleId),
                        modified.canonicalConditionText(ruleId),
                        List.of("the rule's condition isn't fully modeled by rulekit (Opaque) — this is a textual diff"));
            }
        } catch (RuntimeException e) {
            return fail(err, "diff failed: " + e.getMessage());
        }

        try {
            append(out, diff.toJson() + "\n");
            if (outputPath != null) {
                Files.writeString(Path.of(outputPath), modifiedJson);
                err.append("written ").append(ruleId).append(" → ").append(outputPath).append('\n');
            }
            return 0;
        } catch (IOException e) {
            return fail(err, "cannot write output: " + e.getMessage());
        }
    }

    private static int reject(Appendable err, String ruleId, String reason) {
        try {
            ObjectNode root = MAPPER.createObjectNode();
            root.put("valid", false);
            root.put("rule", ruleId);
            root.put("reason", "the new condition is rejected by the kernel (use `check` for diagnostics): " + reason);
            err.append(MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(root)).append('\n');
        } catch (IOException ignored) {
            // best-effort
        }
        return 1;
    }

    private static void append(Appendable out, String text) {
        try {
            out.append(text);
        } catch (IOException e) {
            throw new IllegalStateException("cannot write output", e);
        }
    }

    private static int fail(Appendable err, String message) {
        try {
            err.append(message).append('\n');
        } catch (IOException ignored) {
            // best-effort diagnostic
        }
        return 2;
    }
}
