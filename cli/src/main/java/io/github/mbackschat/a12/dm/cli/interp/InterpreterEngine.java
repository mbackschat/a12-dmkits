package io.github.mbackschat.a12.dm.cli.interp;

import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import io.github.mbackschat.a12.dm.interpreter.model.Computed;
import io.github.mbackschat.a12.dm.interpreter.model.ComputeOutcome;
import io.github.mbackschat.a12.dm.interpreter.DmInterpreter;
import io.github.mbackschat.a12.dm.interpreter.UnsupportedConstructException;
import io.github.mbackschat.a12.dm.interpreter.eval.Document;
import io.github.mbackschat.a12.dm.interpreter.model.CustomCondition;
import io.github.mbackschat.a12.dm.interpreter.model.CustomFieldType;
import io.github.mbackschat.a12.dm.interpreter.model.Message;
import io.github.mbackschat.a12.dm.interpreter.model.ModelDef;
import io.github.mbackschat.a12.dm.interpreter.model.MsgType;
import io.github.mbackschat.a12.dm.interpreter.model.PredefinedType;
import io.github.mbackschat.a12.dm.interpreter.model.RuleDef;
import io.github.mbackschat.a12.dm.interpreter.model.RuleResult;
import io.github.mbackschat.a12.dm.interpreter.model.Severity;
import io.github.mbackschat.a12.dm.interpreter.model.UnsupportedRule;
import io.github.mbackschat.a12.dm.interpreter.model.ValidationOutcome;
import io.github.mbackschat.a12.dm.interpreter.model.Verdict;

/**
 * The interpreter-backed engine behind the runtime verbs' DEFAULT path ({@code model eval} / {@code model
 * compute} / {@code rule test}) — the native-safe alternative to the kernel's Groovy runtime. It loads a
 * DM-JSON model + a {@code {"fields":{…}}} instance via {@link InterpreterModelLoader}, runs
 * {@link DmInterpreter}, and renders the same {@code data} shape as the kernel path so output is consistent
 * across engines. Two honest differences from the kernel: the {@code field} pointer is the resolved path
 * (not the kernel's verbose pointer object), and a rule using an unmodeled/external construct is reported
 * under {@code data.unsupported} rather than evaluated (use {@code --kernel} on the JVM for full fidelity).
 */
public final class InterpreterEngine {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private InterpreterEngine() {
    }

    /** {@code model eval} over the whole model (optionally narrowing the {@code rule} verdict to {@code targetRule}).
     *  {@code applyComputations} runs the form-engine flow (compute → apply → validate, the default); false is the
     *  bare {@code validateFull} ({@code --no-computations}). */
    public static ObjectNode evalData(String modelJson, Map<String, Object> fields, String locale, String targetRule,
            Map<String, PredefinedType> predefinedTypes, Map<String, CustomCondition> customConditions,
            Map<String, CustomFieldType> customFieldTypes, boolean applyComputations) {
        ModelDef def = InterpreterModelLoader.load(modelJson);
        Document doc = InterpreterModelLoader.document(fields, def);
        DmInterpreter interp = new DmInterpreter(def, null, predefinedTypes, customConditions, customFieldTypes);
        Document evalDoc = applyComputations ? interp.applyComputations(doc, locale) : doc;
        ValidationOutcome outcome = interp.validate(evalDoc, locale);

        ObjectNode data = MAPPER.createObjectNode();
        ArrayNode fired = data.putArray("fired");
        outcome.getMessages().stream().map(Message::getCode).distinct().forEach(fired::add);
        ArrayNode messages = data.putArray("messages");
        for (Message m : outcome.getMessages()) {
            addMessage(messages.addObject(), m);
        }
        addUnsupported(data, outcome.getUnsupported());
        if (targetRule != null) {
            boolean ruleFired = outcome.getMessages().stream().anyMatch(m -> m.getRulePath().equals(targetRule));
            ObjectNode rule = data.putObject("rule");
            rule.put("name", targetRule);
            rule.put("fired", ruleFired);
        }
        // A construct the engine can't evaluate (a CustomCondition with no registered impl, or a custom-typed
        // cell with no --predefined-types entry) is reported in `data.unsupported` (G29) — NOT silently absent
        // from `fired`, which would read as a clean pass. `model eval --strict-custom` then refuses (the command).
        return data;
    }

    /** {@code model eval --condition/--field}: an injected, non-stored candidate rule. */
    public static ObjectNode evalCandidateData(String modelJson, Map<String, Object> fields, String locale,
            String group, String name, String errorEntityRelPath, String condition, String code, String severity,
            Map<String, PredefinedType> predefinedTypes, Map<String, CustomCondition> customConditions,
            Map<String, CustomFieldType> customFieldTypes, boolean applyComputations) {
        ModelDef def = InterpreterModelLoader.load(modelJson);
        Document doc = InterpreterModelLoader.document(fields, def);
        RuleDef candidate = new RuleDef(group + "/" + name, group, condition, code, errorEntityRelPath,
                Severity.valueOf(severity.toUpperCase()), Map.of());
        DmInterpreter interp = new DmInterpreter(def, null, predefinedTypes, customConditions, customFieldTypes);
        Document evalDoc = applyComputations ? interp.applyComputations(doc, locale) : doc;
        RuleResult result = interp.evalCandidate(candidate, evalDoc, locale);

        boolean fired = result.getVerdict() == Verdict.FIRED;
        ObjectNode data = MAPPER.createObjectNode();
        ArrayNode firedArr = data.putArray("fired");
        ArrayNode messages = data.putArray("messages");
        if (fired) {
            firedArr.add(code);
            ObjectNode mn = messages.addObject();
            mn.put("code", code);
            mn.put("rule", candidate.getPath());
            mn.put("field", errorPath(candidate));
            mn.put("severity", severity.toUpperCase());
            mn.put("type", typeError(result.getType()));
            // A candidate carries no errorMessage template; fall back to its name (as the kernel does).
            mn.put("message", result.getMessage().isEmpty() ? name : result.getMessage());
        }
        ObjectNode rule = data.putObject("rule");
        rule.put("name", candidate.getPath());
        rule.put("fired", fired);
        return data;
    }

    /** {@code model compute}: each computed field's outcome (value / cleared / error). */
    public static ObjectNode computeData(String modelJson, Map<String, Object> fields, String locale) {
        ModelDef def = InterpreterModelLoader.load(modelJson);
        Document doc = InterpreterModelLoader.document(fields, def);
        List<Computed> computed = new DmInterpreter(def).compute(doc, locale);

        ObjectNode data = MAPPER.createObjectNode();
        ArrayNode arr = data.putArray("computed");
        for (Computed c : computed) {
            ObjectNode cn = arr.addObject();
            cn.put("field", c.getPath());
            cn.put("outcome", outcomeName(c.getOutcome()));
            if (c.getValue() != null) {
                cn.put("value", c.getValue());
            }
        }
        return data;
    }

    /** {@code rule test}: the FIRED / PASSED / SUPPRESSED verdict for one stored rule (or {@code unsupported}). */
    public static ObjectNode ruleTestData(String modelJson, Map<String, Object> fields, String locale, String rulePath,
            Map<String, PredefinedType> predefinedTypes, Map<String, CustomCondition> customConditions,
            Map<String, CustomFieldType> customFieldTypes, boolean applyComputations) {
        ModelDef def = InterpreterModelLoader.load(modelJson);
        Document doc = InterpreterModelLoader.document(fields, def);
        RuleResult result;
        try {
            DmInterpreter interp = new DmInterpreter(def, null, predefinedTypes, customConditions, customFieldTypes);
            Document evalDoc = applyComputations ? interp.applyComputations(doc, locale) : doc;
            result = interp.testRule(rulePath, evalDoc, locale);
        } catch (UnsupportedConstructException e) {
            // The rule uses a construct the engine can't evaluate (a CustomCondition with no impl) → report it
            // unsupported instead of crashing, mirroring whole-document eval's tolerance (G29).
            ObjectNode data = MAPPER.createObjectNode();
            data.put("rule", rulePath);
            data.put("fired", false);
            data.put("verdict", "unsupported");
            addUnsupported(data, List.of(new UnsupportedRule(rulePath, e.getMessage())));
            return data;
        }

        ObjectNode data = MAPPER.createObjectNode();
        data.put("rule", result.getRulePath());
        data.put("fired", result.getVerdict() == Verdict.FIRED);
        data.put("verdict", result.getVerdict().name().toLowerCase(java.util.Locale.ROOT));
        if (result.getMessage() != null && !result.getMessage().isEmpty()) {
            data.put("message", result.getMessage());
        }
        if (!result.getSuppressedBy().isEmpty()) {
            ArrayNode arr = data.putArray("suppressedBy");
            result.getSuppressedBy().forEach(s -> {
                ObjectNode n = arr.addObject();
                n.put("field", s.getField());
                n.put("formalErrorCode", s.getFormalErrorCode());
            });
        }
        return data;
    }

    /** Surface the constructs the engine could NOT evaluate (G29) as {@code data.unsupported} — present only
     *  when non-empty, like {@code suppressedBy}. Each entry is {@code {name, reason}}: {@code name} is the
     *  rule's slash-path (a CustomCondition rule) or the cell's instance pointer (an unregistered custom type). */
    private static void addUnsupported(ObjectNode data, List<UnsupportedRule> unsupported) {
        if (unsupported.isEmpty()) {
            return;
        }
        ArrayNode arr = data.putArray("unsupported");
        for (UnsupportedRule u : unsupported) {
            ObjectNode n = arr.addObject();
            n.put("name", u.getRulePath());
            n.put("reason", u.getReason());
        }
    }

    private static void addMessage(ObjectNode mn, Message m) {
        mn.put("code", m.getCode());
        mn.put("rule", m.getRulePath());
        mn.put("field", m.getErrorPath());   // path + repetition indices (formatPointer form)
        mn.put("severity", m.getSeverity().name());
        mn.put("type", typeError(m.getType()));
        mn.put("message", m.getText());
        putPointers(mn.putArray("referenced"), m.getReferenced());
        if (!m.getFillToFix().isEmpty()) {
            putPointers(mn.putArray("fillToFix"), m.getFillToFix());
        }
    }

    private static void putPointers(ArrayNode arr, java.util.List<String> pointers) {
        pointers.forEach(arr::add);
    }

    /** Map the interpreter's polarity to the kernel's message-type vocabulary. */
    private static String typeError(MsgType type) {
        return type == MsgType.OMISSION ? "OMISSION_ERROR" : "VALUE_ERROR";
    }

    /** Map the compute outcome to the kernel's vocabulary ({@code value} / {@code cleared} / {@code error}). */
    private static String outcomeName(ComputeOutcome outcome) {
        return switch (outcome) {
            case VALUE -> "value";
            case CLEARED -> "cleared";
            case ERRORED -> "error";
        };
    }

    private static String errorPath(RuleDef rule) {
        String base = rule.getPath();
        String rel = rule.getErrorEntityRelPath();
        while (rel.startsWith("../")) {
            base = base.substring(0, base.lastIndexOf('/'));
            rel = rel.substring(3);
        }
        return rel.isEmpty() ? base : base + "/" + rel;
    }
}
