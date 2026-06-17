package io.github.mbackschat.a12.dm.cli;

import io.github.mbackschat.a12.dm.cli.computation.ComputationSpec;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import io.github.mbackschat.a12.dm.rulekit.dsl.BoundModel;
import io.github.mbackschat.a12.dm.rulekit.dsl.LocalizedText;
import io.github.mbackschat.a12.dm.rulekit.validate.Diagnostic;
import io.github.mbackschat.a12.dm.rulekit.validate.DraftComputation;
import io.github.mbackschat.a12.dm.rulekit.validate.DraftRule;
import io.github.mbackschat.a12.dm.rulekit.validate.RuleValidator;
import io.github.mbackschat.a12.dm.rulekit.validate.ValidationResult;
import io.github.mbackschat.a12.dm.rulekit.write.ModelWriter;
import com.mgmtp.a12.kernel.md.model.api.services.IDocumentModelReferenceResolver;
import picocli.CommandLine.Command;
import picocli.CommandLine.Mixin;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

/**
 * The {@code add} verb (CLI-SPEC §4) — persist a rule into a model from a JSON rule-spec, completing
 * the BA loop (check → add → saved model). Validates the condition against the real kernel before
 * persisting, then re-validates the persisted model. On success the new DM-JSON goes to stdout (or
 * {@code --output <file>}); on rejection nothing is persisted and diagnostics go to stderr (exit 1).
 *
 * <p>The rule-spec carries the full envelope: {@code field} (absolute), {@code condition} (DSL),
 * {@code code}, {@code messages} (≥1, each {@code {locale,text}}), optional {@code severity} (default
 * ERROR) and {@code name}. A <b>computation-spec</b> (detected by a {@code computedField} key) persists a
 * computation instead: {@code computedField}, {@code alternatives} (≥1 {@code (precondition?, operation)}),
 * {@code messages}, optional {@code commonPrecondition}/{@code name}. See {@code schema add} for both shapes.
 */
@Command(name = "add", hidden = true, mixinStandardHelpOptions = true,
        description = "Persist a rule (rule-spec) or computation (computation-spec) from JSON, kernel-validated before + after. See `schema add`.")
final class AddCommand extends BaseCommand {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Parameters(index = "0", paramLabel = "<model.json>", description = "the DM-JSON model file")
    String model;

    @Parameters(index = "1", paramLabel = "<rule-spec.json>",
            description = "the rule envelope as JSON (see `schema add`)")
    String specPath;

    @Option(names = {"--output", "-o"}, paramLabel = "<file>",
            description = "write the new DM-JSON to this file instead of stdout")
    String outputPath;

    @Mixin
    WorkspaceOption workspace = new WorkspaceOption();

    @Override
    public Integer call() {
        String modelJson;
        JsonNode spec;
        try {
            modelJson = Files.readString(Path.of(model));
            spec = MAPPER.readTree(Files.readString(Path.of(specPath)));
        } catch (IOException e) {
            return fail("cannot read input: " + e.getMessage());
        }

        if (ComputationSpec.looksLikeComputation(spec)) {
            return addComputation(modelJson, spec);
        }

        String field = text(spec, "field");
        String condition = text(spec, "condition");
        String code = text(spec, "code");
        if (field == null || condition == null || code == null) {
            return fail("rule-spec needs field, condition, and code (see `schema add`)");
        }
        // an explanatory ;; comment, attached as a leading line on the condition (the kernel tolerates it)
        condition = io.github.mbackschat.a12.dm.rulekit.dsl.Comments.withLeadingComment(text(spec, "comment"), condition);
        List<LocalizedText> messages = messages(spec);
        if (messages.isEmpty()) {
            return fail("rule-spec needs at least one message {locale,text} (see `schema add`)");
        }
        String severity = spec.hasNonNull("severity") ? spec.get("severity").asText() : "ERROR";
        String name = spec.hasNonNull("name") ? spec.get("name").asText() : code;

        int slash = field.lastIndexOf('/');
        if (slash <= 0) {
            return fail("field must be an absolute path (e.g. /Group/Field)");
        }
        String group = field.substring(0, slash);
        String errorEntityRelPath = "../" + field.substring(group.length() + 1);

        // 1) validate the condition against the real kernel (placeholders mask messages here)
        IDocumentModelReferenceResolver resolver = workspace.resolverFor(model);
        ValidationResult check = new RuleValidator().check(modelJson,
                new DraftRule(group, name, errorEntityRelPath, condition, code, severity), resolver);
        if (!check.ok()) {
            return reject("condition rejected by the kernel", check);
        }

        // 2) persist with the real messages, then re-validate (catches message/locale coverage)
        String persisted = ModelWriter.addRule(modelJson, group, name, errorEntityRelPath,
                code, condition, severity, messages);
        BoundModel reModel;
        try {
            reModel = BoundModel.ofJson(persisted, resolver);
        } catch (RuntimeException e) {
            return fail("persisted rule is invalid (e.g. missing a message for a declared locale): "
                    + e.getMessage());
        }

        // 2b) best-effort lint backstop — never blocks a kernel-valid persist
        lintBackstop(reModel, group + "/" + name, field);

        // 3) emit
        try {
            if (outputPath != null) {
                Files.writeString(Path.of(outputPath), persisted);
                ObjectNode ok = MAPPER.createObjectNode();
                ok.put("persisted", true);
                ok.put("rule", group + "/" + name);
                ok.put("file", outputPath);
                out().print(MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(ok) + "\n");
            } else {
                out().print(persisted);
            }
            return 0;
        } catch (IOException e) {
            return fail("cannot write output: " + e.getMessage());
        }
    }

    /** Persist a computation from a computation-spec — the {@code add} computation path (kernel-validated before + after). */
    private Integer addComputation(String modelJson, JsonNode spec) {
        ComputationSpec.Parsed parsed = ComputationSpec.parse(spec);
        if (!parsed.ok()) {
            return fail(parsed.error());
        }
        IDocumentModelReferenceResolver resolver = workspace.resolverFor(model);

        // 1) validate the computation against the real kernel
        ValidationResult check = new RuleValidator().check(modelJson, parsed.draft(), resolver);
        if (!check.ok()) {
            return reject("computation rejected by the kernel", check);
        }

        // 2) persist with the real messages, then re-validate (catches message/locale coverage)
        String persisted = ModelWriter.addComputation(modelJson, parsed.draft(), parsed.messages());
        try {
            BoundModel.ofJson(persisted, resolver);
        } catch (RuntimeException e) {
            return fail("persisted computation is invalid (e.g. missing a message for a declared locale): "
                    + e.getMessage());
        }

        // 3) emit
        try {
            if (outputPath != null) {
                Files.writeString(Path.of(outputPath), persisted);
                ObjectNode ok = MAPPER.createObjectNode();
                ok.put("persisted", true);
                ok.put("computation", parsed.fullName());
                ok.put("file", outputPath);
                out().print(MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(ok) + "\n");
            } else {
                out().print(persisted);
            }
            return 0;
        } catch (IOException e) {
            return fail("cannot write output: " + e.getMessage());
        }
    }

    private void lintBackstop(BoundModel model, String ruleFullName, String errorFieldPath) {
        List<Diagnostic> findings = LintBackstop.lintPersisted(model, ruleFullName, errorFieldPath);
        if (findings.isEmpty()) {
            return;
        }
        try {
            ObjectNode root = MAPPER.createObjectNode();
            ArrayNode warnings = root.putArray("warnings");
            for (Diagnostic d : findings) {
                ObjectNode w = warnings.addObject();
                w.put("severity", d.severity().name());
                d.code().ifPresent(c -> w.put("code", c));
                w.put("summary", d.summary());
                d.fix().ifPresent(f -> w.put("fix", f));
            }
            err().println(MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(root));
        } catch (com.fasterxml.jackson.core.JsonProcessingException ignored) {
            // best-effort: lint never blocks a valid, persisted rule
        }
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

    private int reject(String summary, ValidationResult result) {
        try {
            ObjectNode root = MAPPER.createObjectNode();
            root.put("persisted", false);
            root.put("reason", summary);
            root.set("diagnostics", MAPPER.readTree(result.toJson()));
            err().println(MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(root));
        } catch (com.fasterxml.jackson.core.JsonProcessingException ignored) {
            // best-effort
        }
        return 1;
    }
}
