package io.github.mbackschat.a12.dm.cli;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Map;

import com.fasterxml.jackson.core.JsonProcessingException;
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
import picocli.CommandLine.Parameters;

/**
 * The {@code eval-doc} verb (CLI-SPEC §4) — the <b>runtime</b> counterpart of {@code check}/{@code validate}:
 * run the real kernel's {@code validateFull} over a <em>document instance</em> (actual field data) and
 * report which rules FIRED. Where {@code check} asks "does the kernel <em>accept</em> this rule?", {@code
 * eval-doc} asks "does it <em>fire</em> on this data?". JSON in / JSON out, read-only (never writes the
 * model or the document file).
 *
 * <p>By default it runs the WHOLE model's rules and reports every fired message; narrow to one rule with
 * {@code --rule <fullName>} (an existing rule) or a CANDIDATE ({@code --condition <DSL> --field <path>},
 * injected like {@code check} but never persisted) — then the output also says whether that rule fired.
 *
 * <p><b>Computations are NOT run</b> (the kernel keeps {@code validateFull} and {@code compute} separate):
 * a computed field reads as whatever the {@code --doc} supplies (empty if absent). Use {@code compute-doc}
 * to see computed values, or supply a computed field's value in the document.
 *
 * <p>The document is a NESTED JSON tree under {@code "fields"}: a repeatable group is an array of row
 * objects, a non-repeatable group a nested object, a field its text value ({@code null}/"" = present but
 * unfilled; an absent key = no instance). See {@code schema eval-doc}.
 */
@Command(name = "eval-doc", hidden = true, mixinStandardHelpOptions = true,
        description = "Run the kernel over a document INSTANCE and report which rules fire (runtime; computations not run).")
final class EvalDocCommand extends BaseCommand {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Parameters(index = "0", paramLabel = "<model.json>",
            description = "the DM-JSON model FILE — distinct from --doc (the data) and --field/--rule (paths inside the model)")
    String model;

    @Option(names = "--doc", required = true, paramLabel = "<document.json>",
            description = "the document INSTANCE (field data) to evaluate — a nested JSON tree under \"fields\" "
                    + "(repeatable group = array of rows, field = its value). See `schema eval-doc`.")
    String doc;

    @Option(names = "--rule", paramLabel = "<rulePathInModel>",
            description = "narrow the report to an EXISTING rule (its full slash-path) — output says whether it fired")
    String ruleId;

    @Option(names = "--condition", paramLabel = "<DSL>",
            description = "evaluate a CANDIDATE rule (with --field): a condition true on a VIOLATION; injected, not persisted")
    String condition;

    @Option(names = "--field", paramLabel = "<fieldPathInModel>",
            description = "the candidate rule's error field as a path INSIDE the model, e.g. /Group/Field (with --condition)")
    String field;

    @Option(names = "--group", paramLabel = "<groupPathInModel>",
            description = "the candidate rule's iteration scope (group path); defaults to the field's parent group")
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
    WorkspaceOption workspace = new WorkspaceOption();

    @Override
    public Integer call() {
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
                return fail("the document needs a top-level \"fields\" object (a nested field tree); see `schema eval-doc`");
            }
            fields = MAPPER.convertValue(fieldsNode, new TypeReference<Map<String, Object>>() { });
        } catch (IOException e) {
            return fail("cannot read document: " + e.getMessage());
        }

        IDocumentModelReferenceResolver resolver = workspace.resolverFor(model);
        Locale locale = Locale.forLanguageTag(localeTag.replace('_', '-'));

        // Determine the model to evaluate (candidate-injected or as-is) and which rule, if any, to report on.
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
            // The candidate must be a rule the kernel accepts before we can run it (mirrors `check`).
            ValidationResult check = new RuleValidator().check(modelJson, draft, resolver);
            if (!check.ok()) {
                return reject("the candidate rule is rejected by the kernel (use `check` for full diagnostics)", check);
            }
            evalJson = new RuleValidator().inject(modelJson, draft);
            targetRule = effectiveGroup + "/" + name;
        } else if (ruleId != null) {
            try {
                java.util.Set<String> rules = KernelAdapter.ofJson(modelJson, resolver).ruleFullNames();
                if (!rules.contains(ruleId)) {
                    return fail("'" + ruleId + "' is not a rule in this model — pass a rule's full slash-path. Rules: " + rules);
                }
            } catch (RuntimeException e) {
                return fail("cannot load the model: " + e.getMessage());
            }
        }

        EvalResult result;
        try {
            result = KernelAdapter.ofJson(evalJson, resolver).evaluate(fields, locale);
        } catch (IllegalArgumentException badDoc) {
            return fail(badDoc.getMessage());           // unknown path / shape mismatch in the document
        } catch (RuntimeException e) {
            return fail("evaluation failed: " + e.getMessage());
        }

        return emit(result, targetRule);
    }

    private Integer emit(EvalResult result, String targetRule) {
        ObjectNode root = MAPPER.createObjectNode();
        ArrayNode fired = root.putArray("fired");
        result.firedCodes().forEach(fired::add);
        ArrayNode messages = root.putArray("messages");
        for (EvalMessage m : result.messages()) {
            ObjectNode mn = messages.addObject();
            mn.put("code", m.errorCode());
            m.rulePath().ifPresent(r -> mn.put("rule", r));
            mn.put("field", m.fieldPointer());
            mn.put("severity", m.severity());
            mn.put("type", m.messageType());
            mn.put("message", m.message());
        }
        if (targetRule != null) {
            boolean ruleFired = result.messages().stream()
                    .anyMatch(m -> m.rulePath().filter(targetRule::equals).isPresent());
            ObjectNode rule = root.putObject("rule");
            rule.put("name", targetRule);
            rule.put("fired", ruleFired);
        }
        try {
            out().print(MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(root) + "\n");
            return 0;
        } catch (JsonProcessingException e) {
            return fail("cannot serialize eval-doc result: " + e.getMessage());
        }
    }

    private int reject(String summary, ValidationResult result) {
        try {
            ObjectNode root = MAPPER.createObjectNode();
            root.put("evaluated", false);
            root.put("reason", summary);
            root.set("diagnostics", MAPPER.readTree(result.toJson()));
            err().println(MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(root));
        } catch (IOException ignored) {
            // best-effort (JsonProcessingException is an IOException)
        }
        return 2;
    }
}
