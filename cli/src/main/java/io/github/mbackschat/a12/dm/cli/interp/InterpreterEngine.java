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
import io.github.mbackschat.a12.dm.core.ModelReader;
import io.github.mbackschat.a12.dm.core.ModelResolver;

/**
 * The interpreter-backed engine behind the runtime verbs ({@code model eval} / {@code model compute} /
 * {@code rule eval}) — the sole runtime eval engine on every target, native-safe (no Groovy). It loads a
 * DM-JSON model + a {@code {"fields":{…}}} instance via {@link InterpreterModelLoader}, runs
 * {@link DmInterpreter}, and renders the canonical {@code data} shape. Two honest differences from the kernel
 * (the {@code :adapter} differential oracle): the {@code field} pointer is the resolved path (not the kernel's
 * verbose pointer object), and a rule using an unmodeled/external construct is reported under {@code
 * data.unsupported} rather than evaluated — supply its impl via {@code --custom-conditions-js} /
 * {@code --predefined-types} to evaluate it.
 */
public final class InterpreterEngine {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private InterpreterEngine() {
    }

    /**
     * Pre-expand a model for the kernel-free interpreter path (decoupling Stage 2 / A4): mount includes and
     * resolve imported type-definitions <em>kernel-free</em> via {@link ModelReader}, so an includes-bearing
     * model is evaluated with its mounted content present — closing the silent-includes-drop defect (the raw
     * model handed straight to {@link InterpreterModelLoader} lost the mount). The returned DM-JSON is
     * self-contained + name-path-keyed, which the interpreter's loader already evaluates correctly.
     *
     * <p>A mount is remapped as a MOVE: a reference-aware pass binds every included rule/computation fragment
     * in its pre-mount context, rewrites moved absolute and flag-enabled unique short-name targets, and leaves
     * literals/comments untouched (IF146). A surviving genuine unmapped reference throws
     * {@link IncludeRelativizationException} so the verb refuses loudly rather than mis-evaluate.
     */
    public static String expandForEval(String modelJson, ModelResolver resolver) {
        ModelReader reader = ModelReader.expand(modelJson, resolver);
        List<String> needsRewrite = reader.includedElementsWithUnmappedReferences();
        if (!needsRewrite.isEmpty()) {
            throw new IncludeRelativizationException(needsRewrite);
        }
        return reader.toJson();
    }

    /** The full names of the rules the interpreter can see in {@code modelJson} — kernel-free, so the runtime
     *  verbs can gate an unknown {@code --rule} with a structured refusal before evaluating. */
    public static List<String> ruleFullNames(String modelJson) {
        return InterpreterModelLoader.load(modelJson).getRules().stream().map(RuleDef::getPath).toList();
    }

    /** {@code model eval} over the whole model (optionally narrowing the {@code rule} verdict to {@code targetRule}).
     *  {@code applyComputations} runs the form-engine flow (compute → apply → validate, the default); false is the
     *  bare {@code validateFull} ({@code --no-computations}). */
    public static ObjectNode evalData(String modelJson, Map<String, Object> fields, String locale, String targetRule,
            Map<String, PredefinedType> predefinedTypes, Map<String, CustomCondition> customConditions,
            Map<String, CustomFieldType> customFieldTypes, boolean applyComputations) {
        return evalFor(InterpreterModelLoader.load(modelJson), fields, locale, targetRule,
                predefinedTypes, customConditions, customFieldTypes, applyComputations);
    }

    /**
     * {@code model eval --condition/--field}: an injected, non-stored candidate rule. The candidate is
     * appended to the model ({@link ModelDef#withRule}) and evaluated through the SAME whole-model
     * {@link #evalFor} path as a stored rule — so it reports one <b>row-indexed</b> message per firing,
     * identical to persisting the rule (G42), never a single index-less collapse; {@code rule} narrows to it.
     * A candidate carries no message template, so it renders its {@code name} (matching the kernel's fallback).
     */
    public static ObjectNode evalCandidateData(String modelJson, Map<String, Object> fields, String locale,
            String group, String name, String errorEntityRelPath, String condition, String code, String severity,
            Map<String, PredefinedType> predefinedTypes, Map<String, CustomCondition> customConditions,
            Map<String, CustomFieldType> customFieldTypes, boolean applyComputations) {
        RuleDef candidate = new RuleDef(group + "/" + name, group, condition, code, errorEntityRelPath,
                Severity.valueOf(severity.toUpperCase()), Map.of(locale, name));
        ModelDef def = InterpreterModelLoader.load(modelJson).withRule(candidate);
        return evalFor(def, fields, locale, candidate.getPath(),
                predefinedTypes, customConditions, customFieldTypes, applyComputations);
    }

    /**
     * The shared whole-model runtime-eval assembly — full {@link DmInterpreter#validate} (after the form-engine
     * compute-apply when {@code applyComputations}), rendering the canonical {@code data} shape: the distinct
     * fired codes, every per-row {@link Message} (row-indexed via {@link #addMessage}), the {@code unsupported}
     * report (G29), and — when {@code targetRule} is non-null — the {@code rule} narrowing ({@code name}/{@code
     * fired}). Both the persisted-rule path ({@link #evalData}) and the injected-candidate path
     * ({@link #evalCandidateData}) end here, so candidate and persisted evaluation are bit-identical.
     */
    private static ObjectNode evalFor(ModelDef def, Map<String, Object> fields, String locale, String targetRule,
            Map<String, PredefinedType> predefinedTypes, Map<String, CustomCondition> customConditions,
            Map<String, CustomFieldType> customFieldTypes, boolean applyComputations) {
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

    /** {@code rule eval}: the FIRED / PASSED / SUPPRESSED verdict for one stored rule (or {@code unsupported}). */
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

}
