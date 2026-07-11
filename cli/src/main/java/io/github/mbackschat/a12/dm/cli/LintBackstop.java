package io.github.mbackschat.a12.dm.cli;

import java.util.ArrayList;
import java.util.List;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.mbackschat.a12.dm.core.ModelResolver;

import io.github.mbackschat.a12.dm.adapter.ReadResult;
import io.github.mbackschat.a12.dm.adapter.KernelAdapter;
import io.github.mbackschat.a12.dm.rulekit.dsl.BoundModel;
import io.github.mbackschat.a12.dm.rulekit.dsl.LocalizedText;
import io.github.mbackschat.a12.dm.rulekit.lint.Linter;
import io.github.mbackschat.a12.dm.rulekit.validate.Diagnostic;
import io.github.mbackschat.a12.dm.rulekit.write.ModelWriter;

/**
 * Shared best-effort lint backstop for the authoring verbs ({@code add}, {@code check}). The kernel
 * accepts any well-formed condition regardless of polarity, so the silent inverted-requiredness trap
 * — a requirement written as {@code FieldFilled(E)} on its own error field {@code E} — is rulekit's
 * to catch. We read the rule's condition back through the kernel parser (F10) to rebuild its AST,
 * then run the AST {@link Linter}. Advisory only: any failure returns no findings — lint never blocks
 * a kernel-valid rule.
 */
public final class LintBackstop {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private LintBackstop() {
    }

    /** Lint a rule already persisted (and loadable) in {@code model}; never throws. */
    public static List<Diagnostic> lintPersisted(BoundModel model, String ruleFullName, String errorFieldPath) {
        try {
            ReadResult read = KernelAdapter.of(model).read(ruleFullName);
            if (!(read instanceof ReadResult.Converted converted)) {
                return List.of();
            }
            return new Linter().lint(model, errorFieldPath, converted.condition());
        } catch (RuntimeException e) {
            return List.of();
        }
    }

    /**
     * Lint a candidate (not-yet-persisted) rule: temporarily persist it into {@code modelJson} with
     * placeholder messages — just enough to load and read — then delegate to
     * {@link #lintPersisted}. This is what lets {@code check} surface the same lint findings as
     * {@code add} without saving anything. Never throws.
     */
    public static List<Diagnostic> lintCandidate(String modelJson, String group, String name,
            String errorEntityRelPath, String code, String condition, String severity,
            String errorFieldPath, ModelResolver resolver) {
        try {
            List<LocalizedText> placeholders = placeholderMessages(modelJson, name);
            String persisted = ModelWriter.addRule(modelJson, group, name, errorEntityRelPath,
                    code, condition, severity, placeholders);
            BoundModel model = BoundModel.ofJson(persisted, resolver);
            return lintPersisted(model, group + "/" + name, errorFieldPath);
        } catch (RuntimeException e) {
            return List.of();
        }
    }

    /** One placeholder message per declared model locale (default en_US) — mirrors RuleValidator. */
    private static List<LocalizedText> placeholderMessages(String modelJson, String text) {
        List<LocalizedText> messages = new ArrayList<>();
        try {
            JsonNode root = MAPPER.readTree(modelJson);
            for (JsonNode locale : root.path("header").path("locales")) {
                String code = locale.path("code").asText(null);
                if (code != null && !code.isBlank()) {
                    messages.add(new LocalizedText(code, text));
                }
            }
        } catch (Exception ignored) {
            // fall through to the default below
        }
        if (messages.isEmpty()) {
            messages.add(new LocalizedText("en_US", text));
        }
        return messages;
    }
}
