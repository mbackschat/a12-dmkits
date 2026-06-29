package io.github.mbackschat.a12.dm.cli.model;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import io.github.mbackschat.a12.dm.adapter.review.ModelDiff;
import io.github.mbackschat.a12.dm.cli.BaseCommand;
import io.github.mbackschat.a12.dm.cli.ResultEnvelope;
import picocli.CommandLine.Command;
import picocli.CommandLine.Parameters;

/**
 * {@code dmtool model diff <baseFile> <headFile>} — a <b>structural</b> diff of two model files
 * (MODEL-REVIEW-SPEC §5). Parses BOTH into the model and compares structure, so a pure reformat is never
 * reported; lists the fields and rules <b>added / removed / modified</b> between base and head, each with the
 * per-aspect {@code deltas} that changed (a rule's condition/severity/code; a field's kind + per-kind config).
 *
 * <p>Unlike most verbs it takes <b>two positional file paths</b> rather than a single {@code -m} model (the
 * pure two-file core; the {@code --since <ref>} git convenience is a follow-up — MODEL-REVIEW-LEDGER). A read
 * op: the payload rides the envelope's {@code data} (shape: {@code model-diff.schema}).
 */
@Command(name = "diff", mixinStandardHelpOptions = true,
        description = "Structural diff of two model files (base then head): the fields and rules added, removed, "
                + "or modified, with per-aspect deltas (condition/severity/kind/config), in `data`. A pure "
                + "reformat is never reported (it compares structure, not text). Shape: `model-diff.schema`.")
final class ModelDiffCommand extends BaseCommand {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Parameters(index = "0", paramLabel = "<baseFile>",
            description = "the BASE model file — the before-state (e.g. the committed version)")
    String baseFile;

    @Parameters(index = "1", paramLabel = "<headFile>",
            description = "the HEAD model file — the after-state (e.g. the agent's edited version)")
    String headFile;

    @Override
    public Integer call() {
        String baseJson;
        String headJson;
        try {
            baseJson = Files.readString(Path.of(baseFile));
            headJson = Files.readString(Path.of(headFile));
        } catch (IOException e) {
            return fail("cannot read model files: " + e.getMessage());
        }
        try {
            ModelDiff diff = ModelDiff.between(baseJson, headJson);
            ObjectNode data = MAPPER.createObjectNode();
            ArrayNode changes = data.putArray("changes");
            int added = 0;
            int removed = 0;
            int modified = 0;
            int highRisk = 0;
            for (ModelDiff.ElementChange change : diff.changes()) {
                ObjectNode node = changes.addObject();
                node.put("element", change.element().name());
                node.put("id", change.id());
                node.put("change", change.change().name());
                node.put("risk", change.risk().name());
                ArrayNode reasons = node.putArray("riskReasons");
                change.reasons().forEach(reasons::add);
                ArrayNode deltas = node.putArray("deltas");
                for (ModelDiff.Delta delta : change.deltas()) {
                    ObjectNode d = deltas.addObject();
                    d.put("aspect", delta.aspect());
                    if (delta.before() != null) {
                        d.put("before", delta.before());
                    }
                    if (delta.after() != null) {
                        d.put("after", delta.after());
                    }
                }
                switch (change.change()) {
                    case ADDED -> added++;
                    case REMOVED -> removed++;
                    case MODIFIED -> modified++;
                }
                if (change.risk() == ModelDiff.Risk.HIGH) {
                    highRisk++;
                }
            }
            String summary = added + " added, " + removed + " removed, " + modified + " modified"
                    + (highRisk > 0 ? " (" + highRisk + " high-risk)" : "");
            ResultEnvelope env = ResultEnvelope.read("model", "diff", summary, true, List.of(), data);
            out().print(env.toJsonString());
            return env.exitCode();
        } catch (RuntimeException e) {
            return fail("diff failed: " + e.getMessage());
        }
    }
}
