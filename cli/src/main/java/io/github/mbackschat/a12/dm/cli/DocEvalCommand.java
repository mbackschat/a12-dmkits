package io.github.mbackschat.a12.dm.cli;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.mgmtp.a12.kernel.md.model.api.services.IDocumentModelReferenceResolver;

import io.github.mbackschat.a12.dm.adapter.EvalMessage;
import io.github.mbackschat.a12.dm.adapter.EvalResult;
import io.github.mbackschat.a12.dm.adapter.KernelAdapter;
import io.github.mbackschat.a12.dm.rulekit.validate.DraftRule;
import io.github.mbackschat.a12.dm.rulekit.validate.RuleValidator;
import io.github.mbackschat.a12.dm.rulekit.validate.ValidationResult;
import picocli.CommandLine.Command;
import picocli.CommandLine.Mixin;
import picocli.CommandLine.Option;

/**
 * {@code dmtool doc eval --doc <document.json>} — the runtime counterpart of {@code rule check}: run the
 * kernel's {@code validateFull} over a document INSTANCE and report which rules FIRED (the empirical
 * polarity check). Read-only: the {@code fired}/{@code messages}/{@code rule} payload rides {@code data}.
 * Narrow with {@code --rule} (existing) or a candidate ({@code --condition} + {@code --field}, injected,
 * not persisted). Computations are NOT run — use {@code doc compute}.
 */
@Command(name = "eval", mixinStandardHelpOptions = true,
        description = "Run the kernel over a document INSTANCE; report which rules fire in `data` (runtime).")
final class DocEvalCommand extends BaseCommand {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Option(names = "--doc", required = true, paramLabel = "<document.json>",
            description = "the document INSTANCE (field data) — a nested JSON tree under \"fields\". See `schema doc eval`.")
    String doc;

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

    @Mixin
    IncludeDirOption includes = new IncludeDirOption();

    @Override
    public Integer call() {
        String model = model();
        if (model == null) {
            return fail("pass -m <model.json> (before or after the verb)");
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
            JsonNode root = MAPPER.readTree(Files.readString(Path.of(doc)));
            JsonNode fieldsNode = root.has("fields") ? root.get("fields") : root;
            if (!fieldsNode.isObject()) {
                return fail("the document needs a top-level \"fields\" object (a nested field tree)");
            }
            fields = MAPPER.convertValue(fieldsNode, new TypeReference<Map<String, Object>>() { });
        } catch (IOException e) {
            return fail("cannot read document: " + e.getMessage());
        }
        IDocumentModelReferenceResolver resolver = includes.resolverFor(model);
        Locale locale = Locale.forLanguageTag(localeTag.replace('_', '-'));

        String evalJson = modelJson;
        String targetRule = ruleId;
        if (candidate) {
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
            DraftRule draft = new DraftRule(effectiveGroup, name, errorEntityRelPath, condition, code, severity);
            ValidationResult check = new RuleValidator().check(modelJson, draft, resolver);
            if (!check.ok()) {
                ResultEnvelope env = ResultEnvelope.rejected("doc", "eval",
                        "the candidate rule is rejected by the kernel (use `rule check` for diagnostics)",
                        check.diagnostics());
                out().print(env.toJsonString());
                return env.exitCode();
            }
            evalJson = new RuleValidator().inject(modelJson, draft);
            targetRule = effectiveGroup + "/" + name;
        } else if (ruleId != null) {
            try {
                Set<String> rules = KernelAdapter.ofJson(modelJson, resolver).ruleFullNames();
                if (!rules.contains(ruleId)) {
                    return fail("'" + ruleId + "' is not a rule in this model — rules: " + rules);
                }
            } catch (RuntimeException e) {
                return fail("cannot load the model: " + e.getMessage());
            }
        }

        EvalResult result;
        try {
            result = KernelAdapter.ofJson(evalJson, resolver).evaluate(fields, locale);
        } catch (IllegalArgumentException badDoc) {
            return fail(badDoc.getMessage());
        } catch (RuntimeException e) {
            return fail("evaluation failed: " + e.getMessage());
        }

        ObjectNode data = MAPPER.createObjectNode();
        ArrayNode fired = data.putArray("fired");
        result.firedCodes().forEach(fired::add);
        ArrayNode messages = data.putArray("messages");
        for (EvalMessage m : result.messages()) {
            ObjectNode mn = messages.addObject();
            mn.put("code", m.errorCode());
            m.rulePath().ifPresent(r -> mn.put("rule", r));
            mn.put("field", m.fieldPointer());
            mn.put("severity", m.severity());
            mn.put("type", m.messageType());
        }
        if (targetRule != null) {
            String tr = targetRule;
            boolean ruleFired = result.messages().stream()
                    .anyMatch(m -> m.rulePath().filter(tr::equals).isPresent());
            ObjectNode rule = data.putObject("rule");
            rule.put("name", tr);
            rule.put("fired", ruleFired);
        }
        ResultEnvelope env = ResultEnvelope.read("doc", "eval",
                result.firedCodes().size() + " rule(s) fired", true, List.of(), data);
        out().print(env.toJsonString());
        return env.exitCode();
    }
}
