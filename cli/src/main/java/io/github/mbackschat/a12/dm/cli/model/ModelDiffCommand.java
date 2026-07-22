package io.github.mbackschat.a12.dm.cli.model;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import io.github.mbackschat.a12.dm.adapter.review.ModelDiff;
import io.github.mbackschat.a12.dm.rulekit.dsl.ConditionEditScript;
import io.github.mbackschat.a12.dm.cli.BaseCommand;
import io.github.mbackschat.a12.dm.cli.ResultEnvelope;
import io.github.mbackschat.a12.dm.cli.model.GitBaseline.GitBaselineException;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

/**
 * {@code dmtool model diff <baseFile> <headFile>} — a <b>structural</b> diff of two model files
 * (MODEL-REVIEW-SPEC §5). Parses BOTH into the model and compares structure, so a pure reformat is never
 * reported; lists fields, groups, rules, computations, typedefs, includes/imports, and model configuration
 * <b>added / removed / modified</b> between base and head, each with the per-aspect {@code deltas} that changed.
 *
 * <p>Two input modes: the pure two-file core ({@code <baseFile> <headFile>}), or the {@code --since <ref>}
 * git convenience ({@code -m <model> model diff --since HEAD}) that diffs the model's current file against its
 * content at a git ref — the review entrypoint "what changed since HEAD?" (§9c; the engine stays git-free,
 * only the CLI materializes the baseline). A read op: the payload rides {@code data} (shape: {@code model-diff.schema}).
 */
@Command(name = "diff", mixinStandardHelpOptions = true,
        description = "Structural diff of two models: fields, groups, rules, computations, typedefs, includes/imports, and model configuration "
                + "added, removed, or "
                + "modified, with complete typed aspects and review-impact reasons, in `data`. A pure reformat is never "
                + "reported (it compares structure, not text). Pass two files (base then head), or "
                + "`-m <model> --since <ref>` to diff against a git ref. Shape: `model-diff.schema`.")
final class ModelDiffCommand extends BaseCommand {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Parameters(index = "0", arity = "0..1", paramLabel = "<baseFile>",
            description = "the BASE model file — the before-state (e.g. the committed version); omit with --since")
    String baseFile;

    @Parameters(index = "1", arity = "0..1", paramLabel = "<headFile>",
            description = "the HEAD model file — the after-state (e.g. the agent's edited version); omit with --since")
    String headFile;

    @Option(names = "--since", paramLabel = "<ref>",
            description = "diff the -m model's current file (HEAD) against its content at this git <ref> "
                    + "(e.g. HEAD) — BASE is `git show <ref>:<model>`. Requires -m; omit the two file positionals.")
    String since;

    @Override
    public Integer call() {
        String baseJson;
        String headJson;
        if (since != null) {
            if (baseFile != null || headFile != null) {
                return fail("--since diffs the -m model against a git ref; do not also pass <baseFile> <headFile>");
            }
            String modelPath = model();
            if (modelPath == null) {
                return fail("--since needs -m <model> (the file to diff against its content at the git <ref>)");
            }
            try {
                headJson = Files.readString(Path.of(modelPath));
            } catch (IOException e) {
                return fail("cannot read model: " + e.getMessage());
            }
            try {
                baseJson = GitBaseline.contentAt(Path.of(modelPath), since);
            } catch (GitBaselineException e) {
                return fail(e.getMessage());
            }
        } else {
            if (baseFile == null || headFile == null) {
                return fail("pass two model files (<baseFile> <headFile>), or -m <model> --since <ref>");
            }
            try {
                baseJson = Files.readString(Path.of(baseFile));
                headJson = Files.readString(Path.of(headFile));
            } catch (IOException e) {
                return fail("cannot read model files: " + e.getMessage());
            }
        }
        try {
            ModelDiff diff = ModelDiff.between(baseJson, headJson);
            ObjectNode data = MAPPER.createObjectNode();
            ArrayNode changes = data.putArray("changes");
            java.util.Map<String, Integer> refactorOf = refactorIndexByChangeId(diff.refactors());
            int added = 0;
            int removed = 0;
            int modified = 0;
            int reordered = 0;
            int relocated = 0;
            int highRisk = 0;
            for (ModelDiff.ElementChange change : diff.changes()) {
                ObjectNode node = changes.addObject();
                node.put("element", change.element().name());
                node.put("id", change.id());
                node.put("change", change.change().name());
                node.put("risk", change.risk().name());
                ArrayNode reasons = node.putArray("riskReasons");
                change.reasons().forEach(reasons::add);
                Integer refactor = refactorOf.get(change.id());
                if (refactor != null) {
                    node.put("partOfRefactor", refactor.intValue());
                }
                ArrayNode deltas = node.putArray("deltas");
                for (ModelDiff.Delta delta : change.deltas()) {
                    ObjectNode d = deltas.addObject();
                    d.put("aspect", delta.aspect());
                    if (delta.before() != null) {
                        d.put("before", delta.before());
                        writeDeltaValue(d, "beforeValue", delta.beforeValue().orElseThrow());
                    }
                    if (delta.after() != null) {
                        d.put("after", delta.after());
                        writeDeltaValue(d, "afterValue", delta.afterValue().orElseThrow());
                    }
                    if (delta.aspect().equals("condition")) {
                        writeConditionEdits(d, diff.conditionEdits().get(change.id()));
                    }
                }
                switch (change.change()) {
                    case ADDED -> added++;
                    case REMOVED -> removed++;
                    case MODIFIED -> modified++;
                    case REORDERED -> reordered++;
                    case RENAMED, MOVED -> relocated++;
                }
                if (change.risk() == ModelDiff.Risk.HIGH) {
                    highRisk++;
                }
            }
            ArrayNode refactors = data.putArray("refactors");
            for (ModelDiff.Refactor refactor : diff.refactors()) {
                ObjectNode node = refactors.addObject();
                node.put("kind", refactor.kind().name());
                node.put("from", refactor.from());
                node.put("to", refactor.to());
                ArrayNode referrers = node.putArray("referrers");
                refactor.referrers().forEach(referrers::add);
            }
            String summary = added + " added, " + removed + " removed, " + modified + " modified"
                    + (relocated > 0 ? ", " + relocated + " renamed/moved" : "")
                    + (reordered > 0 ? ", " + reordered + " reordered" : "")
                    + (highRisk > 0 ? " (" + highRisk + " high-risk)" : "");
            ResultEnvelope env = ResultEnvelope.read("model", "diff", summary, true, List.of(), data);
            out().print(env.toJsonString());
            return env.exitCode();
        } catch (RuntimeException e) {
            return failOrRethrowDomainRejection("diff failed: ", e);
        }
    }

    /** Writes the additive typed view beside the stable string rendering. */
    private static void writeDeltaValue(ObjectNode delta, String name, ModelDiff.DeltaValue value) {
        ObjectNode typed = delta.putObject(name);
        switch (value) {
            case ModelDiff.TextValue text -> {
                typed.put("type", "TEXT");
                typed.put("value", text.value());
            }
            case ModelDiff.BooleanValue bool -> {
                typed.put("type", "BOOLEAN");
                typed.put("value", bool.value());
            }
            case ModelDiff.IntegerValue integer -> {
                typed.put("type", "INTEGER");
                typed.put("value", integer.value());
            }
            case ModelDiff.DecimalValue decimal -> {
                typed.put("type", "DECIMAL");
                typed.put("value", decimal.value());
            }
            case ModelDiff.StringListValue strings -> {
                typed.put("type", "STRING_LIST");
                ArrayNode items = typed.putArray("value");
                strings.value().forEach(items::add);
            }
        }
    }

    /** Attach the located edit-script (MR1) to a condition delta — {@code which} clause/operand changed. */
    private static void writeConditionEdits(ObjectNode conditionDelta, ConditionEditScript script) {
        if (script == null) {
            return;
        }
        ArrayNode edits = conditionDelta.putArray("conditionEdits");
        for (ConditionEditScript.LocatedEdit edit : script.edits()) {
            ObjectNode e = edits.addObject();
            e.put("op", edit.op().name());
            e.put("address", edit.address());
            e.put("nodeKind", edit.nodeKind());
            if (edit.before() != null) {
                e.put("before", edit.before());
            }
            if (edit.after() != null) {
                e.put("after", edit.after());
            }
            e.put("summary", edit.summary());
        }
    }

    /** Maps a change id (a refactor's rename anchor {@code from}, or one of its {@code referrers}) to that
     *  refactor's index — each folded change then carries a {@code partOfRefactor} back-pointer (first wins). */
    private static java.util.Map<String, Integer> refactorIndexByChangeId(List<ModelDiff.Refactor> refactors) {
        java.util.Map<String, Integer> index = new java.util.LinkedHashMap<>();
        for (int i = 0; i < refactors.size(); i++) {
            ModelDiff.Refactor r = refactors.get(i);
            index.putIfAbsent(r.from(), i);
            for (String referrer : r.referrers()) {
                index.putIfAbsent(referrer, i);
            }
        }
        return index;
    }
}
