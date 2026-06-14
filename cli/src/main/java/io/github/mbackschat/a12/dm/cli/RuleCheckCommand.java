package io.github.mbackschat.a12.dm.cli;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import com.mgmtp.a12.kernel.md.model.api.services.IDocumentModelReferenceResolver;

import io.github.mbackschat.a12.dm.rulekit.dsl.BoundModel;
import io.github.mbackschat.a12.dm.rulekit.validate.Diagnostic;
import io.github.mbackschat.a12.dm.rulekit.validate.DraftRule;
import io.github.mbackschat.a12.dm.rulekit.validate.RuleValidator;
import io.github.mbackschat.a12.dm.rulekit.validate.ValidationResult;
import picocli.CommandLine.Command;
import picocli.CommandLine.Mixin;
import picocli.CommandLine.Option;

/**
 * {@code dmtool rule check} — the authoring feedback loop: validate a candidate rule (condition + error
 * field + code, in a group) against the real kernel (+ the lint backstop) without persisting. A read op:
 * the candidate's validity rides {@code valid} (distinct from {@code ok} = the check executed); the kernel
 * + lint findings ride {@code diagnostics}. A {@code --field} that isn't a model path fails fast with a
 * corrective suggestion (the file-path-vs-model-path slip).
 */
@Command(name = "check", mixinStandardHelpOptions = true,
        description = "Validate a candidate rule against the real kernel (+ lint), without persisting.")
final class RuleCheckCommand extends BaseCommand {

    @Option(names = "--field", required = true, paramLabel = "<fieldPathInModel>",
            description = "the error field as a path INSIDE the model, e.g. /Group/Field (not a file path)")
    String field;

    @Option(names = "--condition", required = true, paramLabel = "<DSL>",
            description = "the candidate condition in A12 DSL — true on a VIOLATION (the error scenario)")
    String condition;

    @Option(names = "--code", required = true, paramLabel = "<errorCode>", description = "the rule's error code")
    String code;

    @Option(names = "--group", paramLabel = "<groupPathInModel>",
            description = "the rule's iteration scope (group path inside the model); defaults to the field's parent")
    String group;

    @Option(names = "--severity", defaultValue = "ERROR", paramLabel = "ERROR|WARNING",
            description = "rule severity (default: ${DEFAULT-VALUE})")
    String severity;

    @Option(names = "--name", defaultValue = "CheckRule", paramLabel = "<name>",
            description = "candidate rule name (default: ${DEFAULT-VALUE})")
    String name;

    @Mixin
    WorkspaceOption workspace = new WorkspaceOption();

    @Override
    public Integer call() {
        String model = model();
        if (model == null) {
            return fail("pass -m <model.json> (before or after the verb)");
        }
        String effectiveGroup = group;
        if (effectiveGroup == null) {
            int slash = field.lastIndexOf('/');
            if (slash <= 0) {
                return fail("--field must be an absolute path (e.g. /Group/Field)");
            }
            effectiveGroup = field.substring(0, slash);
        }
        if (!field.startsWith(effectiveGroup + "/")) {
            return fail("--field " + field + " must be inside --group " + effectiveGroup);
        }
        String errorEntityRelPath = "../" + field.substring(effectiveGroup.length() + 1);

        String json;
        try {
            json = Files.readString(Path.of(model));
        } catch (IOException e) {
            return fail("cannot read model: " + e.getMessage());
        }
        IDocumentModelReferenceResolver resolver = workspace.resolverFor(model);

        // Pre-flight: --field/--group name a path INSIDE the model, not a file (both start with '/').
        // Best-effort — if the model can't bind standalone (e.g. unresolved includes), defer to the kernel.
        try {
            BoundModel bound = BoundModel.ofJson(json, resolver);
            String fieldProblem = ModelPaths.fieldProblem(bound, field);
            if (fieldProblem != null) {
                String suggestion = ModelPaths.suggestField(bound, condition);
                return fail(suggestion == null ? fieldProblem : fieldProblem + " " + suggestion);
            }
            if (group != null) {
                String groupProblem = ModelPaths.groupProblem(bound, effectiveGroup);
                if (groupProblem != null) {
                    return fail(groupProblem);
                }
            }
        } catch (RuntimeException skip) {
            // defer to the kernel validation below
        }

        List<Diagnostic> findings = new ArrayList<>();
        try {
            DraftRule draft = new DraftRule(effectiveGroup, name, errorEntityRelPath, condition, code, severity);
            ValidationResult result = new RuleValidator().check(json, draft, resolver);
            findings.addAll(result.diagnostics());
            if (result.ok()) {
                findings.addAll(LintBackstop.lintCandidate(json, effectiveGroup, name, errorEntityRelPath,
                        code, condition, severity, field, resolver));
            }
        } catch (RuntimeException e) {
            findings.add(Diagnostic.precheck(Optional.empty(), String.valueOf(e.getMessage()),
                    new Diagnostic.Where(effectiveGroup + "/" + name, Optional.empty()),
                    Optional.empty(), Optional.empty()));
        }

        ValidationResult merged = ValidationResult.of(findings);
        ResultEnvelope env = ResultEnvelope.read("rule", "check",
                merged.ok() ? "candidate is kernel-valid" : "candidate has " + findings.size() + " finding(s)",
                merged.ok(), findings, null);
        out().print(env.toJsonString());
        return env.exitCode();
    }
}
