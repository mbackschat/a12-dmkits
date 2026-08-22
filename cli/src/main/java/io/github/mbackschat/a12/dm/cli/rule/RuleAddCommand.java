package io.github.mbackschat.a12.dm.cli.rule;

import io.github.mbackschat.a12.dm.cli.BaseCommand;
import io.github.mbackschat.a12.dm.cli.ConsistencyEvidence;
import io.github.mbackschat.a12.dm.cli.LintBackstop;
import io.github.mbackschat.a12.dm.cli.ModelPaths;
import io.github.mbackschat.a12.dm.cli.ModelWrite;
import io.github.mbackschat.a12.dm.cli.JsonInput;
import io.github.mbackschat.a12.dm.cli.ParamKind;
import io.github.mbackschat.a12.dm.cli.RejectionBasis;
import io.github.mbackschat.a12.dm.cli.ResultEnvelope;
import io.github.mbackschat.a12.dm.cli.WorkspaceOption;
import io.github.mbackschat.a12.dm.cli.interp.TextConditionCorrective;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.mbackschat.a12.dm.core.EntityPaths;
import io.github.mbackschat.a12.dm.core.ModelResolver;

import io.github.mbackschat.a12.dm.edit.EditSession;
import io.github.mbackschat.a12.dm.rulekit.dsl.BoundModel;
import io.github.mbackschat.a12.dm.rulekit.dsl.LocalizedText;
import io.github.mbackschat.a12.dm.rulekit.validate.Diagnostic;
import io.github.mbackschat.a12.dm.rulekit.validate.DraftRule;
import io.github.mbackschat.a12.dm.rulekit.validate.RuleValidator;
import io.github.mbackschat.a12.dm.rulekit.validate.ValidationResult;
import io.github.mbackschat.a12.dm.rulekit.write.ModelWriter;
import picocli.CommandLine.Command;
import picocli.CommandLine.Mixin;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

/**
 * {@code dmtool rule add <rule-spec.json>} — persist a rule from a JSON rule-spec, kernel-validated before
 * (the condition) and after (message/locale coverage), then written back (in place; {@code -o} redirects,
 * {@code --dry-run} previews). A rejected condition yields a {@code rejected} envelope (nothing written);
 * a kernel-valid persist yields {@code applied}, with the {@code RK_INVERTED_REQUIREDNESS} / unguarded-number
 * lint findings carried as advisory diagnostics so the polarity trap surfaces here too.
 */
@Command(name = "add", mixinStandardHelpOptions = true,
        description = "Persist a rule from a JSON rule-spec (kernel-validated before + after). See `schema rule add`.")
final class RuleAddCommand extends BaseCommand {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Parameters(index = "0", paramLabel = "<rule-spec.json>",
            description = "the rule envelope as JSON (field, condition, code, messages, …) — see "
                    + "`schema rule add`. Or `-` to read the JSON from stdin instead of a file (once per invocation).")
    String specPath;

    @Option(names = {"--output", "-o"}, paramLabel = "<file>",
            description = "write the result here instead of in place (overwriting the model)")
    String outputPath;

    @Option(names = "--dry-run", description = "preview only — print what would be added and write NOTHING")
    boolean dryRun;

    @Mixin
    WorkspaceOption workspace = new WorkspaceOption();

    @Override
    public Integer call() {
        String model = model();
        if (model == null) {
            return fail("pass -m <model.json> (before or after the verb)");
        }
        String modelJson;
        JsonNode spec;
        try {
            modelJson = Files.readString(Path.of(model));
            spec = MAPPER.readTree(JsonInput.read(specPath, "<rule-spec.json>"));
        } catch (IOException e) {
            return fail("cannot read input: " + e.getMessage());
        }
        Optional<Integer> rejected = rejectInvalidSpec("rule-add", spec);
        if (rejected.isPresent()) {
            return rejected.get();
        }

        String field = text(spec, "field");
        String condition = text(spec, "condition");
        String code = text(spec, "code");
        if (field == null || condition == null || code == null) {
            return fail("rule-spec needs field, condition, and code (see `schema rule add`)");
        }
        // an explanatory ;; comment, attached as a leading line on the condition (the kernel tolerates it)
        condition = io.github.mbackschat.a12.dm.rulekit.dsl.Comments.withLeadingComment(text(spec, "comment"), condition);
        // G7b: "Allow Differing Decimal Places" — prepend the kernel's one suppressible-warning directive so a
        // an otherwise-incompatible numeric == / != derived-scale pair is accepted (the directive must lead)
        if (spec.path("allowDifferingDecimals").asBoolean(false)) {
            condition = "@SuppressWarning(MVK_INVALID_COMPARE_DEC_PLACES) " + condition;
        }
        List<LocalizedText> messages = messages(spec);
        if (messages.isEmpty()) {
            return fail("rule-spec needs at least one message {locale,text} (see `schema rule add`)");
        }
        String severity = spec.hasNonNull("severity") ? spec.get("severity").asText() : "ERROR";
        String name = spec.hasNonNull("name") ? spec.get("name").asText() : code;
        int slash = field.lastIndexOf('/');
        if (!ModelPaths.isAbsoluteEntityPath(field)) {
            return fail("field must be an absolute path (e.g. /Group/Field)");
        }
        // the rule group (iteration scope) defaults to the error field's parent; `group` overrides it (mirrors
        // `rule check --group`) for an operator that must sit at the PARENT of a repeated group — RepetitionNotUnique.
        String group = spec.hasNonNull("group") ? spec.get("group").asText() : field.substring(0, slash);
        if (!EntityPaths.isStrictlyInside(field, group)) {
            return fail("field " + field + " must be inside the rule group " + group);
        }
        String errorEntityRelPath = "../" + field.substring(group.length() + 1);
        ModelResolver resolver = workspace.resolverFor(model);

        ValidationResult check = new RuleValidator().check(modelJson,
                new DraftRule(group, name, errorEntityRelPath, condition, code, severity), resolver);
        if (!check.ok()) {
            // G30: operand-aware corrective on the rejected text path (see TextConditionCorrective / RuleCheckCommand).
            List<Diagnostic> diagnostics =
                    TextConditionCorrective.enrich(check.diagnostics(), condition, group, modelJson, resolver);
            ResultEnvelope env = ResultEnvelope.rejected(resultIdentity(),
                    "condition rejected by the kernel (" + diagnostics.size() + " finding(s))",
                    diagnostics, RejectionBasis.from(ConsistencyEvidence.of(check)));
            out().print(env.toJsonString());
            return env.exitCode();
        }

        String ruleFullName = group + "/" + name;
        String persisted = ModelWriter.addRule(modelJson, group, name, errorEntityRelPath,
                code, condition, severity, messages);
        // G39: optional rule metadata (annotation / internal-/external-description) at add time — applied via
        // EditSession, the same path group add/modify use; the BoundModel re-bind below validates the result.
        persisted = applyMetadata(persisted, ruleFullName, spec, resolver);
        BoundModel reModel;
        try {
            reModel = BoundModel.ofJson(persisted, resolver);
        } catch (RuntimeException e) {
            return fail("persisted rule is invalid (e.g. missing a message for a declared locale): "
                    + e.getMessage());
        }
        List<Diagnostic> warnings = LintBackstop.lintPersisted(reModel, ruleFullName, field);

        ObjectNode changed = MAPPER.createObjectNode();
        changed.put("rule", ruleFullName);
        return emitMutation(ModelWrite.of(resultIdentity())
                .summaries("would add rule " + ruleFullName,
                        "added rule " + ruleFullName)
                .changed(changed)
                .advisories(warnings)
                .json(persisted)
                .gatedBy(ConsistencyEvidence.of(reModel))
                .dryRun(dryRun)
                .destination(outputPath, model));
    }

    /** Apply the optional rule metadata keys ({@code annotation} / {@code internal-description} /
     *  {@code external-description}, each an object) from the spec via {@link EditSession}; a no-op when absent. */
    private static String applyMetadata(String json, String rulePath, JsonNode spec,
            ModelResolver resolver) {
        if (!spec.hasNonNull("annotation") && !spec.hasNonNull("internal-description")
                && !spec.hasNonNull("external-description")) {
            return json;
        }
        EditSession session = EditSession.read(json, resolver);
        if (spec.hasNonNull("annotation")) {
            spec.get("annotation").fields().forEachRemaining(e ->
                    session.setAnnotation(rulePath, e.getKey(), e.getValue().asText()));
        }
        if (spec.hasNonNull("internal-description")) {
            spec.get("internal-description").fields().forEachRemaining(e ->
                    session.setInternalDescription(rulePath, e.getKey(), e.getValue().asText()));
        }
        if (spec.hasNonNull("external-description")) {
            spec.get("external-description").fields().forEachRemaining(e ->
                    session.setExternalDescription(rulePath, e.getKey(), e.getValue().asText()));
        }
        return session.toJson();
    }

    private static List<LocalizedText> messages(JsonNode spec) {
        List<LocalizedText> messages = new ArrayList<>();
        JsonNode array = spec.path("messages");
        if (array.isArray()) {
            for (JsonNode m : array) {
                if (m.hasNonNull("locale") && m.hasNonNull("text")) {
                    messages.add(new LocalizedText(m.get("locale").asText(), m.get("text").asText()));
                }
            }
        }
        return messages;
    }

    private static String text(JsonNode spec, String field) {
        return spec.hasNonNull(field) ? spec.get(field).asText() : null;
    }
}
