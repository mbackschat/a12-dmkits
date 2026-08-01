package io.github.mbackschat.a12.dm.cli.model;


import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import io.github.mbackschat.a12.dm.cli.BaseCommand;
import io.github.mbackschat.a12.dm.cli.ConsistencyEvidence;
import io.github.mbackschat.a12.dm.cli.ModelWrite;
import io.github.mbackschat.a12.dm.modelkit.ModelDocument;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

/**
 * {@code dmtool model normalize} — rewrite the model in its canonical, deterministic form (MODEL-REVIEW-SPEC
 * §2c), so a later edit produces a clean, <b>localized</b> diff instead of a whole-file reformat. The write
 * path is already order-preserving + deterministic (Jackson tree surgery), so this canonicalizes only
 * <b>formatting</b> — element and key order are preserved, <b>no semantics change</b>. Run it once on a
 * foreign-authored model (e.g. exported from the A12 editor) to make the one-time reformat an explicit,
 * reviewable commit rather than churn buried in the first content edit. In place by default; {@code -o}
 * redirects, {@code --dry-run} previews.
 */
@Command(name = "normalize", mixinStandardHelpOptions = true,
        description = "Rewrite the model in canonical, deterministic form (formatting only — order preserved, no "
                + "semantic change), so later edits diff cleanly. In place; -o redirects, --dry-run previews.")
final class ModelNormalizeCommand extends BaseCommand {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Option(names = {"--output", "-o"}, paramLabel = "<file>",
            description = "write the result here instead of in place (overwriting the model)")
    String outputPath;

    @Option(names = "--dry-run", description = "preview only — write NOTHING")
    boolean dryRun;

    @Override
    public Integer call() {
        String model = model();
        String modelJson = readModelJson(model);
        if (modelJson == null) {
            return 2;
        }
        String canonical;
        try {
            canonical = ModelDocument.read(modelJson).toJson();
        } catch (RuntimeException e) {
            return fail("cannot normalize: " + e.getMessage());
        }
        boolean reformatted = !canonical.equals(modelJson);
        ObjectNode changed = MAPPER.createObjectNode();
        changed.put("reformatted", reformatted);
        String summary = reformatted ? "normalized to canonical form" : "already canonical — no change";
        return emitMutation(ModelWrite.of("model", "normalize")
                .summaries(reformatted ? "would normalize to canonical form" : summary,
                        summary)
                .changed(changed)
                .json(canonical)
                .gatedBy(ConsistencyEvidence.none())
                .dryRun(dryRun)
                .destination(outputPath, model));
    }
}
