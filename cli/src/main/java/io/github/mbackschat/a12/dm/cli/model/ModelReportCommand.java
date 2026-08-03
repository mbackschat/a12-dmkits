package io.github.mbackschat.a12.dm.cli.model;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import io.github.mbackschat.a12.dm.adapter.review.ModelReport;
import io.github.mbackschat.a12.dm.cli.BaseCommand;
import io.github.mbackschat.a12.dm.cli.ConsistencyEvidence;
import io.github.mbackschat.a12.dm.cli.ResultEnvelope;
import io.github.mbackschat.a12.dm.cli.WorkspaceOption;
import io.github.mbackschat.a12.dm.core.ModelResolver;
import io.github.mbackschat.a12.dm.core.Suggest;
import io.github.mbackschat.a12.dm.rulekit.context.ModelCard;
import io.github.mbackschat.a12.dm.rulekit.validate.DraftComputation;
import io.github.mbackschat.a12.dm.rulekit.validate.RkCode;
import picocli.CommandLine.Command;
import picocli.CommandLine.Mixin;
import picocli.CommandLine.Option;

/**
 * {@code dmtool model report} — the comprehension read (MODEL-REVIEW-SPEC §4): in one call, the model's
 * <b>identity</b>, a <b>structure outline</b> (field paths + kinds, group repeatability), the unreferenced
 * (dead-field) set, and a <b>glossed rules/computations catalog</b> — every rule/computation in plain language
 * (a tool-computed gloss) with its <b>polarity</b> (a rule fires on the violation) and its <b>verbatim stored
 * message</b>, so a reviewer sees what each condition DOES next to what it CLAIMS. The understand-the-whole-model
 * companion to {@code model diff}. A read op: the payload rides {@code data} (shape: {@code model-report.schema}).
 */
@Command(name = "report", mixinStandardHelpOptions = true,
        description = "Comprehension report: identity, a structure outline, the dead-field set, and a "
                + "glossed rules/computations catalog (plain-language gloss + polarity + the stored message), in "
                + "`data`. Add --tree for a flat, document-ordered per-node projection; --text draws that projection "
                + "as a hierarchy. The understand-the-whole-model companion to `model diff`. "
                + "Shape: `model-report.schema`.")
final class ModelReportCommand extends BaseCommand {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Mixin
    WorkspaceOption workspace = new WorkspaceOption();

    @Option(names = "--tree", description = "replace the ordinary report payload with a flat per-node tree "
            + "projection (human --text draws the same records as a hierarchy)")
    boolean tree;

    @Option(names = "--root", paramLabel = "<path>", description = "tree subtree root; its node is depth 0 "
            + "(requires --tree; default: the synthetic model root /)")
    String root;

    @Option(names = "--depth", paramLabel = "<N>", description = "maximum descendant depth in the tree "
            + "projection (requires --tree; root is depth 0)")
    Integer depth;

    @Option(names = "--include", split = ",", paramLabel = "<facets>", description = "comma-separated rich "
            + "tree facets: rules,computations,descriptions,annotations,enum, or all (requires --tree)")
    List<String> includes = List.of();

    @Override
    public Integer call() {
        if (!tree && optionMatched("--root", "--depth", "--include")) {
            return fail("--root, --depth, and --include require --tree");
        }
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
            ModelResolver resolver = workspace.resolverFor(model);
            if (tree) {
                ModelTreeData.Projection projection =
                        ModelTreeData.project(json, resolver, root, depth, includes);
                if (!projection.found()) {
                    String hint = Suggest.hint(projection.missingRoot(), projection.candidates());
                    return refuseNotFound(RkCode.NO_SUCH_ELEMENT,
                            "no element at '" + projection.missingRoot() + "'" + hint,
                            projection.missingRoot(),
                            "pass an existing field/group path" + hint + " (see `model describe`)");
                }
                ObjectNode summary = (ObjectNode) projection.data().path("summary");
                // --tree is an UNGATED projection, and stays one. Running the kernel here just to make the two
                // shapes uniform would turn an inspection path into one that REJECTS inconsistent models — the
                // opposite of what an inspection verb is for. So it honestly claims no verification, while the
                // report below carries KERNEL_CONFIRMED: one verb, two evidence classes, which is exactly why
                // evidence is derived per invocation and never from a verb table.
                ResultEnvelope env = ResultEnvelope.queryResult(resultIdentity(), summary.path("fields").asInt() + " field(s), "
                                + summary.path("groups").asInt() + " group(s), "
                                + summary.path("rules").asInt() + " rule(s), "
                                + summary.path("computations").asInt() + " computation(s)",
                        projection.data(), ConsistencyEvidence.none());
                out().print(env.toJsonString());
                return env.exitCode();
            }
            ModelReport report = ModelReport.of(json, resolver);
            ResultEnvelope env = ResultEnvelope.queryResult(resultIdentity(),
                    report.rules().size() + " rule(s), " + report.computations().size() + " computation(s); "
                            + report.usage().unreferenced().size() + " unreferenced field(s)",
                    toData(report), ConsistencyEvidence.of(report));
            out().print(env.toJsonString());
            return env.exitCode();
        } catch (RuntimeException e) {
            return failOrRethrowDomainRejection("report failed: ", e);
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

        // polarity is the same fact for every rule, so carry it ONCE at the top (not per rule)
        ObjectNode polarity = data.putObject("polarity");
        polarity.put("firesOn", ModelReport.VIOLATION_POLARITY.firesOn())
                .put("note", ModelReport.VIOLATION_POLARITY.note());

        // the dead-field audit headline; the full field→referrers map is `model usage` (not duplicated here)
        ArrayNode unreferenced = data.putArray("unreferenced");
        report.usage().unreferenced().forEach(unreferenced::add);

        ArrayNode rules = data.putArray("rules");
        for (ModelReport.RuleEntry r : report.rules()) {
            ObjectNode node = rules.addObject();
            node.put("rule", r.rule()).put("scope", r.scope()).put("explained", r.explained());
            if (r.gloss() != null) {
                node.put("gloss", r.gloss());
            }
            node.put("canonicalText", r.canonicalText());
            node.put("severity", r.severity());
            node.put("code", r.code());
            if (r.errorField() != null) {
                node.put("errorField", r.errorField());
            }
            if (r.reason() != null) {
                node.put("reason", r.reason());
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
            if (c.reason() != null) {
                node.put("reason", c.reason());
            }
            // the preconditions — same key names/shape as `computation read` so the two surfaces agree
            if (c.commonPrecondition() != null) {
                node.put("commonPrecondition", c.commonPrecondition());
            }
            if (!c.alternatives().isEmpty()) {
                ArrayNode alternatives = node.putArray("alternatives");
                for (DraftComputation.Alternative alt : c.alternatives()) {
                    ObjectNode altNode = alternatives.addObject();
                    if (alt.precondition() != null) {
                        altNode.put("precondition", alt.precondition());
                    }
                    altNode.put("operation", alt.operation());
                    if (alt.toleranceRangeOp() != null) {
                        altNode.put("toleranceRangeOp", alt.toleranceRangeOp().wire());
                    }
                }
            }
            ArrayNode suppressions = node.putArray("errorCodesToSuppress");
            report.computationErrorCodesToSuppress(c.computation()).forEach(suppressions::add);
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
