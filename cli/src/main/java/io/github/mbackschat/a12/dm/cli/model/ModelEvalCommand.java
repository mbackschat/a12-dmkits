package io.github.mbackschat.a12.dm.cli.model;

import io.github.mbackschat.a12.dm.cli.BaseCommand;
import io.github.mbackschat.a12.dm.cli.CustomSupportOptions;
import io.github.mbackschat.a12.dm.cli.DocInstance;
import io.github.mbackschat.a12.dm.cli.ResultEnvelope;
import io.github.mbackschat.a12.dm.cli.WorkspaceOption;
import io.github.mbackschat.a12.dm.cli.interp.InterpreterEngine;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.mgmtp.a12.kernel.md.model.api.services.IDocumentModelReferenceResolver;

import io.github.mbackschat.a12.dm.adapter.EvalMessage;
import io.github.mbackschat.a12.dm.adapter.EvalResult;
import io.github.mbackschat.a12.dm.adapter.FieldPointer;
import io.github.mbackschat.a12.dm.adapter.KernelAdapter;
import io.github.mbackschat.a12.dm.interpreter.eval.PointerKt;
import io.github.mbackschat.a12.dm.rulekit.validate.DraftRule;
import io.github.mbackschat.a12.dm.rulekit.validate.RuleValidator;
import io.github.mbackschat.a12.dm.rulekit.validate.ValidationResult;
import picocli.CommandLine.Command;
import picocli.CommandLine.Mixin;
import picocli.CommandLine.Option;

/**
 * {@code dmtool model eval --instance <document.json>} — the runtime counterpart of {@code rule check}: run the
 * kernel's {@code validateFull} over a document INSTANCE and report which rules FIRED (the empirical
 * polarity check). Read-only: the {@code fired}/{@code messages}/{@code rule} payload rides {@code data}.
 * Narrow with {@code --rule} (existing) or a candidate ({@code --condition} + {@code --field}, injected,
 * not persisted). Computations are NOT run — use {@code model compute}.
 */
@Command(name = "eval", mixinStandardHelpOptions = true,
        description = "Run a document INSTANCE through the runtime engine; report which rules fire in `data` "
                + "(runtime).")
final class ModelEvalCommand extends BaseCommand {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Option(names = "--instance", required = true, paramLabel = "<document.json>",
            description = "the document INSTANCE (field data) — a nested JSON tree under \"fields\". See `schema model eval`.")
    String instance;

    @Option(names = "--rule", paramLabel = "<rulePathInModel>",
            description = "narrow the report to an EXISTING rule (its full slash-path)")
    String ruleId;

    @Option(names = "--condition", paramLabel = "<DSL>",
            description = "evaluate a CANDIDATE rule (with --field): a condition true on a VIOLATION; injected, not persisted")
    String condition;

    @Option(names = "--field", paramLabel = "<fieldPathInModel>",
            description = "the candidate rule's error field as a path INSIDE the model (with --condition)")
    String field;

    @Option(names = "--group", paramLabel = "<groupPathInModel>",
            description = "the candidate rule's iteration scope (group path); defaults to the field's parent")
    String group;

    @Option(names = "--code", defaultValue = "EVAL_DOC", paramLabel = "<errorCode>",
            description = "the candidate rule's error code (default: ${DEFAULT-VALUE})")
    String code;

    @Option(names = "--severity", defaultValue = "ERROR", paramLabel = "ERROR|WARNING",
            description = "the candidate rule's severity (default: ${DEFAULT-VALUE})")
    String severity;

    @Option(names = "--name", defaultValue = "EvalDocCandidate", paramLabel = "<name>",
            description = "the candidate rule's name (default: ${DEFAULT-VALUE})")
    String name;

    @Option(names = "--locale", defaultValue = "en_US", paramLabel = "<locale>",
            description = "the evaluation locale (default: ${DEFAULT-VALUE})")
    String localeTag;

    @Option(names = "--kernel",
            description = "use the A12 kernel engine instead of the default interpreter (JVM only; "
                    + "not available in the native image, where the interpreter is the only engine)")
    boolean useKernel;

    @Option(names = "--no-computations",
            description = "validate the document's STORED values as-is (the kernel's bare validateFull). By DEFAULT, "
                    + "eval first runs the model's computations and applies their values (the form-engine flow), so a "
                    + "required-or-referenced COMPUTED field is filled before validation — matching the running app. "
                    + "Use this to validate stored values directly, e.g. to check a computed field's stored value "
                    + "against its computation. Computations are reported separately by `model compute`.")
    boolean noComputations;

    @Mixin
    WorkspaceOption workspace = new WorkspaceOption();

    @Mixin
    CustomSupportOptions customSupport = new CustomSupportOptions();

    @Override
    public Integer call() {
        // --kernel is removed from the native command tree (Cli.removeNativeUnavailableOptions), so under the
        // native profile useKernel can never be set — no runtime refusal needed; the JVM keeps both engines.
        String model = model();
        if (model == null) {
            return fail("pass -m <model.json> (before or after the verb)");
        }
        String kernelConflict = customSupport.kernelConflict(useKernel);
        if (kernelConflict != null) {
            return fail(kernelConflict);
        }
        boolean candidate = condition != null || field != null;
        if (candidate && ruleId != null) {
            return fail("pass either --rule <name> OR a candidate (--condition + --field), not both");
        }
        if (candidate && (condition == null || field == null)) {
            return fail("a candidate rule needs both --condition and --field");
        }
        String modelJson;
        Map<String, Object> fields;
        try {
            modelJson = Files.readString(Path.of(model));
        } catch (IOException e) {
            return fail("cannot read model: " + e.getMessage());
        }
        try {
            fields = DocInstance.readFields(instance);
        } catch (IllegalArgumentException badShape) {
            return fail(badShape.getMessage());
        } catch (IOException e) {
            return fail("cannot read document: " + e.getMessage());
        }
        IDocumentModelReferenceResolver resolver = workspace.resolverFor(model);

        // Resolve a candidate's group + error relpath (engine-independent).
        String effectiveGroup = group;
        String errorEntityRelPath = null;
        String targetRule = ruleId;
        if (candidate) {
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
            errorEntityRelPath = "../" + field.substring(effectiveGroup.length() + 1);
            targetRule = effectiveGroup + "/" + name;
        }

        ObjectNode data;
        try {
            if (useKernel) {
                Locale locale = Locale.forLanguageTag(localeTag.replace('_', '-'));
                String evalJson = modelJson;
                if (candidate) {
                    DraftRule draft = new DraftRule(effectiveGroup, name, errorEntityRelPath, condition, code, severity);
                    ValidationResult check = new RuleValidator().check(modelJson, draft, resolver);
                    if (!check.ok()) {
                        ResultEnvelope env = ResultEnvelope.rejected("model", "eval",
                                "the candidate rule is rejected by the kernel (use `rule check` for diagnostics)",
                                check.diagnostics());
                        out().print(env.toJsonString());
                        return env.exitCode();
                    }
                    evalJson = new RuleValidator().inject(modelJson, draft);
                } else if (ruleId != null) {
                    Set<String> rules = KernelAdapter.ofJson(modelJson, resolver).ruleFullNames();
                    if (!rules.contains(ruleId)) {
                        return fail("'" + ruleId + "' is not a rule in this model — rules: " + rules);
                    }
                }
                KernelAdapter ka = KernelAdapter.ofJson(evalJson, resolver);
                data = kernelEvalData(noComputations ? ka.evaluate(fields, locale) : ka.evaluateApplied(fields, locale),
                        targetRule);
            } else if (candidate) {
                data = InterpreterEngine.evalCandidateData(modelJson, fields, localeTag,
                        effectiveGroup, name, errorEntityRelPath, condition, code, severity,
                        customSupport.predefinedTypes(), customSupport.customConditions(),
                        customSupport.customFieldTypes(), !noComputations);
            } else {
                data = InterpreterEngine.evalData(modelJson, fields, localeTag, ruleId,
                        customSupport.predefinedTypes(), customSupport.customConditions(),
                        customSupport.customFieldTypes(), !noComputations);
            }
        } catch (IllegalArgumentException badDoc) {
            return fail(badDoc.getMessage());
        } catch (RuntimeException e) {
            return fail("evaluation failed: " + e.getMessage());
        }

        ResultEnvelope refusal = customSupport.strictRefusal("model", "eval", data);
        if (refusal != null) {
            out().print(refusal.toJsonString());
            return refusal.exitCode();
        }
        ResultEnvelope env = ResultEnvelope.read("model", "eval",
                data.get("fired").size() + " rule(s) fired", true, List.of(), data);
        out().print(env.toJsonString());
        return env.exitCode();
    }

    /** Render a kernel {@link EvalResult} into the shared {@code data} shape (fired / messages / rule). */
    private static ObjectNode kernelEvalData(EvalResult result, String targetRule) {
        ObjectNode data = MAPPER.createObjectNode();
        ArrayNode fired = data.putArray("fired");
        result.firedCodes().forEach(fired::add);
        ArrayNode messages = data.putArray("messages");
        for (EvalMessage m : result.messages()) {
            ObjectNode mn = messages.addObject();
            mn.put("code", m.errorCode());
            m.rulePath().ifPresent(r -> mn.put("rule", r));
            mn.put("field", pointer(m.field()));   // path + indices via the shared formatter (no Impl toString)
            mn.put("severity", m.severity());
            mn.put("type", m.messageType());
            mn.put("message", m.message());
            ArrayNode referenced = mn.putArray("referenced");
            m.referenced().forEach(p -> referenced.add(pointer(p)));
            if (!m.fillToFix().isEmpty()) {
                ArrayNode fillToFix = mn.putArray("fillToFix");
                m.fillToFix().forEach(p -> fillToFix.add(pointer(p)));
            }
        }
        if (targetRule != null) {
            boolean ruleFired = result.messages().stream()
                    .anyMatch(m -> m.rulePath().filter(targetRule::equals).isPresent());
            ObjectNode rule = data.putObject("rule");
            rule.put("name", targetRule);
            rule.put("fired", ruleFired);
        }
        return data;
    }

    /** Render a kernel field pointer the same readable way the interpreter does (the ONE shared formatter). */
    private static String pointer(FieldPointer p) {
        return PointerKt.formatPointer(p.path(), p.reps());
    }
}
