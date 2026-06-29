package io.github.mbackschat.a12.dm.cli.model;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import io.github.mbackschat.a12.dm.adapter.review.ModelReport;
import io.github.mbackschat.a12.dm.cli.BaseCommand;
import io.github.mbackschat.a12.dm.cli.ResultEnvelope;
import io.github.mbackschat.a12.dm.cli.WorkspaceOption;
import io.github.mbackschat.a12.dm.core.ModelConsistencyException;
import io.github.mbackschat.a12.dm.rulekit.context.ModelCard;
import picocli.CommandLine.Command;
import picocli.CommandLine.Mixin;

/**
 * {@code dmtool model report} — the comprehension read (MODEL-REVIEW-SPEC §4): in one call, the model's
 * <b>identity</b>, a <b>structure outline</b> (field paths + kinds, group repeatability), the field→referrers
 * <b>usage</b> map, and a <b>glossed rules/computations catalog</b> — every rule/computation in plain language
 * (a tool-computed gloss) with its <b>polarity</b> (a rule fires on the violation) and its <b>verbatim stored
 * message</b>, so a reviewer sees what each condition DOES next to what it CLAIMS. The understand-the-whole-model
 * companion to {@code model diff}. A read op: the payload rides {@code data} (shape: {@code model-report.schema}).
 */
@Command(name = "report", mixinStandardHelpOptions = true,
        description = "Comprehension report: identity, a structure outline, the field→referrers usage map, and a "
                + "glossed rules/computations catalog (plain-language gloss + polarity + the stored message), in "
                + "`data`. The understand-the-whole-model companion to `model diff`. Shape: `model-report.schema`.")
final class ModelReportCommand extends BaseCommand {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Mixin
    WorkspaceOption workspace = new WorkspaceOption();

    @Override
    public Integer call() {
        String model = model();
        if (model == null) {
            return fail("pass -m <model.json> (before or after the verb)");
        }
        String json;
        try {
            json = Files.readString(Path.of(model));
        } catch (IOException e) {
            return fail("cannot read model: " + e.getMessage());
        }
        try {
            ModelReport report = ModelReport.of(json, workspace.resolverFor(model));
            ResultEnvelope env = ResultEnvelope.read("model", "report",
                    report.rules().size() + " rule(s), " + report.computations().size() + " computation(s); "
                            + report.usage().unreferenced().size() + " unreferenced field(s)",
                    true, List.of(), toData(report));
            out().print(env.toJsonString());
            return env.exitCode();
        } catch (ModelConsistencyException e) {
            throw e; // G14: invalid-on-disk → the Cli seam renders the structured `rejected` envelope
        } catch (RuntimeException e) {
            return fail("report failed: " + e.getMessage());
        }
    }

    private static ObjectNode toData(ModelReport report) {
        ObjectNode data = MAPPER.createObjectNode();

        ModelReport.Identity id = report.identity();
        ObjectNode identity = data.putObject("identity");
        identity.put("id", id.id());
        identity.put("modelType", id.modelType());
        identity.put("modelVersion", id.modelVersion());
        ArrayNode locales = identity.putArray("locales");
        id.locales().forEach(locales::add);

        ObjectNode structure = data.putObject("structure");
        ArrayNode fields = structure.putArray("fields");
        for (ModelCard.Field f : report.structure().fields()) {
            fields.addObject().put("path", f.path()).put("kind", f.kind());
        }
        ArrayNode groups = structure.putArray("groups");
        for (ModelCard.Group g : report.structure().groups()) {
            groups.addObject().put("path", g.path()).put("repeatable", g.repeatable())
                    .put("maxRepetitions", g.maxRepetitions());
        }

        ObjectNode usage = data.putObject("usage");
        ObjectNode referrers = usage.putObject("referrers");
        for (Map.Entry<String, List<String>> e : report.usage().referrers().entrySet()) {
            ArrayNode arr = referrers.putArray(e.getKey());
            e.getValue().forEach(arr::add);
        }
        ArrayNode unreferenced = usage.putArray("unreferenced");
        report.usage().unreferenced().forEach(unreferenced::add);

        ArrayNode rules = data.putArray("rules");
        for (ModelReport.RuleEntry r : report.rules()) {
            ObjectNode node = rules.addObject();
            node.put("rule", r.rule()).put("scope", r.scope()).put("explained", r.explained());
            if (r.gloss() != null) {
                node.put("gloss", r.gloss());
            }
            node.put("canonicalText", r.canonicalText());
            node.putObject("polarity").put("firesOn", r.polarity().firesOn()).put("note", r.polarity().note());
            node.put("severity", r.severity());
            node.put("code", r.code());
            if (r.errorField() != null) {
                node.put("errorField", r.errorField());
            }
            putMessages(node, r.messages());
        }

        ArrayNode computations = data.putArray("computations");
        for (ModelReport.ComputationEntry c : report.computations()) {
            ObjectNode node = computations.addObject();
            node.put("computation", c.computation()).put("scope", c.scope()).put("explained", c.explained());
            if (c.computedField() != null) {
                node.put("computedField", c.computedField());
            }
            if (c.gloss() != null) {
                node.put("gloss", c.gloss());
            }
            if (c.canonicalText() != null) {
                node.put("canonicalText", c.canonicalText());
            }
            putMessages(node, c.messages());
        }
        return data;
    }

    private static void putMessages(ObjectNode parent, List<ModelReport.Message> messages) {
        ArrayNode arr = parent.putArray("messages");
        for (ModelReport.Message m : messages) {
            arr.addObject().put("locale", m.locale()).put("text", m.text());
        }
    }
}
