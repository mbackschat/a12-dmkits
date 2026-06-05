package io.github.mbackschat.a12.rulekit.cli;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import io.github.mbackschat.a12.rulekit.adapter.Ingest;
import io.github.mbackschat.a12.rulekit.adapter.KernelAdapter;
import io.github.mbackschat.a12.rulekit.dsl.BoundModel;
import io.github.mbackschat.a12.rulekit.dsl.LocalizedText;
import io.github.mbackschat.a12.rulekit.lint.Linter;
import io.github.mbackschat.a12.rulekit.validate.Diagnostic;
import io.github.mbackschat.a12.rulekit.validate.DraftRule;
import io.github.mbackschat.a12.rulekit.validate.RuleValidator;
import io.github.mbackschat.a12.rulekit.validate.ValidationResult;
import io.github.mbackschat.a12.rulekit.write.ModelWriter;

/**
 * The {@code add} verb (CLI-SPEC §4) — persist a rule into a model from a JSON rule-spec, completing
 * the BA loop (check → add → saved model). Validates the condition against the real kernel before
 * persisting, then re-validates the persisted model (which catches message/locale-coverage problems
 * the condition-check masks with placeholders). On success the new DM-JSON goes to stdout (or
 * {@code --output <file>}); on rejection nothing is persisted and diagnostics go to stderr (exit 1).
 *
 * <p>Usage: {@code add <model.json> <rule-spec.json> [--output <file>]}. The rule-spec carries the
 * full envelope: {@code field} (absolute), {@code condition} (DSL), {@code code}, {@code messages}
 * (≥1, each {@code {locale,text}}), optional {@code severity} (default ERROR) and {@code name}.
 */
final class AddCommand {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String USAGE =
            "usage: add <model.json> <rule-spec.json> [--output <file>]\n"
            + "  rule-spec: {\"field\":\"/G/F\",\"condition\":\"<DSL>\",\"code\":\"ID\","
            + "\"messages\":[{\"locale\":\"en_US\",\"text\":\"…\"}],\"severity\":\"ERROR\",\"name\":\"…\"}";

    private AddCommand() {
    }

    static int run(String[] args, Appendable out, Appendable err) {
        if (args.length < 2) {
            return fail(err, USAGE);
        }
        String modelPath = args[0];
        String specPath = args[1];
        String outputPath = null;
        for (int i = 2; i < args.length; i++) {
            if (("--output".equals(args[i]) || "-o".equals(args[i])) && i + 1 < args.length) {
                outputPath = args[++i];
            } else {
                return fail(err, "unknown option: " + args[i] + "\n" + USAGE);
            }
        }

        String modelJson;
        JsonNode spec;
        try {
            modelJson = Files.readString(Path.of(modelPath));
            spec = MAPPER.readTree(Files.readString(Path.of(specPath)));
        } catch (IOException e) {
            return fail(err, "cannot read input: " + e.getMessage());
        }

        String field = text(spec, "field");
        String condition = text(spec, "condition");
        String code = text(spec, "code");
        if (field == null || condition == null || code == null) {
            return fail(err, "rule-spec needs field, condition, and code\n" + USAGE);
        }
        List<LocalizedText> messages = messages(spec);
        if (messages.isEmpty()) {
            return fail(err, "rule-spec needs at least one message {locale,text}\n" + USAGE);
        }
        String severity = spec.hasNonNull("severity") ? spec.get("severity").asText() : "ERROR";
        String name = spec.hasNonNull("name") ? spec.get("name").asText() : code;

        int slash = field.lastIndexOf('/');
        if (slash <= 0) {
            return fail(err, "field must be an absolute path (e.g. /Group/Field)\n" + USAGE);
        }
        String group = field.substring(0, slash);
        String errorEntityRelPath = "../" + field.substring(group.length() + 1);

        // 1) validate the condition against the real kernel (placeholders mask messages here)
        ValidationResult check = new RuleValidator().check(modelJson,
                new DraftRule(group, name, errorEntityRelPath, condition, code, severity));
        if (!check.ok()) {
            return reject(err, "condition rejected by the kernel", check);
        }

        // 2) persist with the real messages, then re-validate (catches message/locale coverage)
        String persisted = ModelWriter.addRule(modelJson, group, name, errorEntityRelPath,
                code, condition, severity, messages);
        BoundModel reModel;
        try {
            reModel = BoundModel.ofJson(persisted);
        } catch (RuntimeException e) {
            return fail(err, "persisted rule is invalid (e.g. missing a message for a declared locale): "
                    + e.getMessage());
        }

        // 2b) best-effort lint backstop — surface valid-but-suspicious findings (chiefly the silent
        // inverted-polarity trap the kernel can't catch). Advisory: a kernel-valid persist is never blocked.
        lintBackstop(reModel, group + "/" + name, field, err);

        // 3) emit
        try {
            if (outputPath != null) {
                Files.writeString(Path.of(outputPath), persisted);
                ObjectNode ok = MAPPER.createObjectNode();
                ok.put("persisted", true);
                ok.put("rule", group + "/" + name);
                ok.put("file", outputPath);
                append(out, MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(ok) + "\n");
            } else {
                append(out, persisted);
            }
            return 0;
        } catch (IOException e) {
            return fail(err, "cannot write output: " + e.getMessage());
        }
    }

    /**
     * Ingest the just-persisted rule's condition (F10) and run the model-context lint, emitting any
     * findings (chiefly {@code RK_INVERTED_REQUIREDNESS}) as warnings to stderr. Best-effort: if the
     * condition doesn't convert ({@code Opaque}) or anything throws, we stay silent — a kernel-valid
     * persist is never blocked by lint.
     */
    private static void lintBackstop(BoundModel model, String ruleFullName, String errorFieldPath, Appendable err) {
        try {
            Ingest ingest = KernelAdapter.of(model).ingest(ruleFullName);
            if (!(ingest instanceof Ingest.Converted converted)) {
                return;
            }
            List<Diagnostic> findings = new Linter().lint(model, errorFieldPath, converted.condition());
            if (findings.isEmpty()) {
                return;
            }
            ObjectNode root = MAPPER.createObjectNode();
            ArrayNode warnings = root.putArray("warnings");
            for (Diagnostic d : findings) {
                ObjectNode w = warnings.addObject();
                w.put("severity", d.severity().name());
                d.code().ifPresent(c -> w.put("code", c));
                w.put("summary", d.summary());
                d.fix().ifPresent(f -> w.put("fix", f));
            }
            err.append(MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(root)).append('\n');
        } catch (RuntimeException | IOException ignored) {
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

    private static int reject(Appendable err, String summary, ValidationResult result) {
        try {
            ObjectNode root = MAPPER.createObjectNode();
            root.put("persisted", false);
            root.put("reason", summary);
            root.set("diagnostics", MAPPER.readTree(result.toJson()));
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
