package io.github.mbackschat.a12.dm.cli.interp;

import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import io.github.mbackschat.a12.dm.interpreter.Computed;
import io.github.mbackschat.a12.dm.interpreter.ComputeOutcome;
import io.github.mbackschat.a12.dm.interpreter.DmInterpreter;
import io.github.mbackschat.a12.dm.interpreter.Document;
import io.github.mbackschat.a12.dm.interpreter.Message;
import io.github.mbackschat.a12.dm.interpreter.ModelDef;
import io.github.mbackschat.a12.dm.interpreter.MsgType;
import io.github.mbackschat.a12.dm.interpreter.RuleDef;
import io.github.mbackschat.a12.dm.interpreter.RuleResult;
import io.github.mbackschat.a12.dm.interpreter.Severity;
import io.github.mbackschat.a12.dm.interpreter.ValidationOutcome;
import io.github.mbackschat.a12.dm.interpreter.Verdict;

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

    /** {@code model eval} over the whole model (optionally narrowing the {@code rule} verdict to {@code targetRule}). */
    public static ObjectNode evalData(String modelJson, Map<String, Object> fields, String locale, String targetRule) {
        ModelDef def = InterpreterModelLoader.load(modelJson);
        Document doc = InterpreterModelLoader.document(fields, def);
        ValidationOutcome outcome = new DmInterpreter(def).validate(doc, locale);

        ObjectNode data = MAPPER.createObjectNode();
        ArrayNode fired = data.putArray("fired");
        outcome.getMessages().stream().map(Message::getCode).distinct().forEach(fired::add);
        ArrayNode messages = data.putArray("messages");
        for (Message m : outcome.getMessages()) {
            addMessage(messages.addObject(), m);
        }
        if (targetRule != null) {
            boolean ruleFired = outcome.getMessages().stream().anyMatch(m -> m.getRulePath().equals(targetRule));
            ObjectNode rule = data.putObject("rule");
            rule.put("name", targetRule);
            rule.put("fired", ruleFired);
        }
        // A rule using an unmodeled/external construct (e.g. CustomCondition) is simply absent from `fired`
        // (it was not evaluated — like a non-firing rule), keeping the `data` shape identical across engines.
        // The unsupported set stays available programmatically via ValidationOutcome.getUnsupported().
        return data;
    }

    /** {@code model eval --condition/--field}: an injected, non-stored candidate rule. */
    public static ObjectNode evalCandidateData(String modelJson, Map<String, Object> fields, String locale,
            String group, String name, String errorEntityRelPath, String condition, String code, String severity) {
        ModelDef def = InterpreterModelLoader.load(modelJson);
        Document doc = InterpreterModelLoader.document(fields, def);
        RuleDef candidate = new RuleDef(group + "/" + name, group, condition, code, errorEntityRelPath,
                Severity.valueOf(severity.toUpperCase()), Map.of());
        RuleResult result = new DmInterpreter(def).evalCandidate(candidate, doc, locale);

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

    /** {@code rule test}: the FIRED / PASSED / SUPPRESSED verdict for one stored rule. */
    public static ObjectNode ruleTestData(String modelJson, Map<String, Object> fields, String locale, String rulePath) {
        ModelDef def = InterpreterModelLoader.load(modelJson);
        Document doc = InterpreterModelLoader.document(fields, def);
        RuleResult result = new DmInterpreter(def).testRule(rulePath, doc, locale);

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
