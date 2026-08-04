package io.github.mbackschat.a12.dm.cli.interp;

import java.util.List;
import java.util.Map;
import java.util.Set;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import io.github.mbackschat.a12.dm.interpreter.A12PartiallyKnownMultiPointer;
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
import io.github.mbackschat.a12.dm.interpreter.model.RelevantEntity;
import io.github.mbackschat.a12.dm.interpreter.model.Severity;
import io.github.mbackschat.a12.dm.interpreter.model.UnsupportedRule;
import io.github.mbackschat.a12.dm.interpreter.model.UnsupportedSubject;
import io.github.mbackschat.a12.dm.interpreter.model.ValidationOutcome;
import io.github.mbackschat.a12.dm.interpreter.model.Verdict;
import io.github.mbackschat.a12.dm.core.ModelResolver;

/**
 * The interpreter-backed engine behind the runtime verbs ({@code model eval} / {@code model compute} /
 * {@code rule eval}) — the sole runtime eval engine on every target, native-safe (no Groovy). It loads a
 * DM-JSON model + a document instance in A12's canonical Document JSON via {@link InterpreterModelLoader}, runs
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
     * Prepare a model for the kernel-free interpreter path through the public portable workspace. The returned
     * DM-JSON is self-contained + name-path-keyed and has already passed the strict expanded-input evaluator
     * gate, so every runtime verb shares the same refusal contract.
     *
     * <p>A mount is remapped as a MOVE: the portable assembler binds every included rule/computation fragment
     * in its pre-mount context, rewrites moved absolute and flag-enabled unique short-name targets, and leaves
     * literals/comments untouched (IF146). A surviving genuine unmapped reference becomes a typed preparation
     * refusal, so the verb fails loudly rather than mis-evaluating it.
     */
    public static String prepareForEval(String modelJson, String diagnosticLabel, ModelResolver resolver) {
        return PortableModelPreparation.prepareForRuntime(modelJson, diagnosticLabel, resolver);
    }

    /** The full names of the rules the interpreter can see in {@code modelJson} — kernel-free, so the runtime
     *  verbs can gate an unknown {@code --rule} with a structured refusal before evaluating. */
    public static List<String> ruleFullNames(String modelJson) {
        return InterpreterModelLoader.load(modelJson).getRules().stream().map(RuleDef::getPath).toList();
    }

    /** The full field paths visible in a prepared runtime model, including mounted workspace content. */
    public static List<String> fieldFullNames(String modelJson) {
        return InterpreterModelLoader.load(modelJson).getFields().stream().map(field -> field.getPath()).toList();
    }

    /** {@code model eval} over the whole model (optionally narrowing the {@code rule} verdict to {@code targetRule}).
     *  {@code applyComputations} runs the form-engine flow (compute → apply → validate, the default); false is the
     *  bare {@code validateFull} ({@code --no-computations}). */
    public static ObjectNode evalData(String modelJson, String documentJson, String locale, String targetRule,
            Map<String, PredefinedType> predefinedTypes, Map<String, CustomCondition> customConditions,
            Map<String, CustomFieldType> customFieldTypes, boolean applyComputations) {
        return evalData(modelJson, documentJson, locale, targetRule, predefinedTypes, customConditions,
                customFieldTypes, applyComputations, null);
    }

    /** Whole- or partial-document evaluation; non-null {@code relevantJson} selects {@code validatePart}. */
    public static ObjectNode evalData(String modelJson, String documentJson, String locale, String targetRule,
            Map<String, PredefinedType> predefinedTypes, Map<String, CustomCondition> customConditions,
            Map<String, CustomFieldType> customFieldTypes, boolean applyComputations, String relevantJson) {
        return evalFor(InterpreterModelLoader.load(modelJson), documentJson, locale, targetRule,
                predefinedTypes, customConditions, customFieldTypes, applyComputations, relevantJson);
    }

    /**
     * {@code model eval --condition/--field}: an injected, non-stored candidate rule. The candidate is
     * appended to the model ({@link ModelDef#withRule}) and evaluated through the SAME whole-model
     * {@link #evalFor} path as a stored rule — so it reports one <b>row-indexed</b> message per firing,
     * identical to persisting the rule (G42), never a single index-less collapse; {@code rule} narrows to it.
     * A candidate carries no message template, so it renders its {@code name} (matching the kernel's fallback).
     */
    public static ObjectNode evalCandidateData(String modelJson, String documentJson, String locale,
            String group, String name, String errorEntityRelPath, String condition, String code, String severity,
            Map<String, PredefinedType> predefinedTypes, Map<String, CustomCondition> customConditions,
            Map<String, CustomFieldType> customFieldTypes, boolean applyComputations) {
        return evalCandidateData(modelJson, documentJson, locale, group, name, errorEntityRelPath, condition, code,
                severity, predefinedTypes, customConditions, customFieldTypes, applyComputations, null);
    }

    /** Candidate evaluation with optional partial-validation relevance. */
    public static ObjectNode evalCandidateData(String modelJson, String documentJson, String locale,
            String group, String name, String errorEntityRelPath, String condition, String code, String severity,
            Map<String, PredefinedType> predefinedTypes, Map<String, CustomCondition> customConditions,
            Map<String, CustomFieldType> customFieldTypes, boolean applyComputations, String relevantJson) {
        RuleDef candidate = candidateRule(locale, group, name, errorEntityRelPath, condition, code, severity, name);
        ModelDef def = InterpreterModelLoader.load(modelJson).withRule(candidate);
        return evalFor(def, documentJson, locale, candidate.getPath(),
                predefinedTypes, customConditions, customFieldTypes, applyComputations, relevantJson);
    }

    /** The one candidate-rule representation consumed by both model-scope and focused runtime evaluation. */
    private static RuleDef candidateRule(String locale, String group, String name, String errorEntityRelPath,
            String condition, String code, String severity, String message) {
        return new RuleDef(group + "/" + name, group, condition, code, errorEntityRelPath,
                Severity.valueOf(severity.toUpperCase()), Map.of(locale, message));
    }

    /**
     * The shared whole-model runtime-eval assembly — full {@link DmInterpreter#validate} (after the form-engine
     * compute-apply when {@code applyComputations}), rendering the canonical {@code data} shape: the distinct
     * fired codes, every per-row {@link Message} (row-indexed via {@link #addMessage}), the {@code unsupported}
     * report (G29), and — when {@code targetRule} is non-null — the {@code rule} narrowing ({@code name}/{@code
     * fired}). Both the persisted-rule path ({@link #evalData}) and the injected-candidate path
     * ({@link #evalCandidateData}) end here, so candidate and persisted evaluation are bit-identical.
     */
    private static ObjectNode evalFor(ModelDef def, String documentJson, String locale, String targetRule,
            Map<String, PredefinedType> predefinedTypes, Map<String, CustomCondition> customConditions,
            Map<String, CustomFieldType> customFieldTypes, boolean applyComputations, String relevantJson) {
        Document doc = InterpreterModelLoader.document(documentJson, def);
        DmInterpreter interp = new DmInterpreter(def, null, predefinedTypes, customConditions, customFieldTypes);
        Document evalDoc = applyComputations ? interp.applyComputations(doc, locale) : doc;
        Set<RelevantEntity> relevant = relevantJson == null ? null : RelevantSelection.parse(relevantJson, def);
        ValidationOutcome outcome = relevant == null
                ? interp.validate(evalDoc, locale)
                : interp.validatePart(evalDoc, relevant, locale);

        ObjectNode data = MAPPER.createObjectNode();
        if (relevant != null) {
            data.put("validationMode", "partial");
        }
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
    public static ObjectNode computeData(String modelJson, String documentJson, String locale) {
        ModelDef def = InterpreterModelLoader.load(modelJson);
        Document doc = InterpreterModelLoader.document(documentJson, def);
        List<Computed> computed = new DmInterpreter(def).compute(doc, locale);

        ObjectNode data = MAPPER.createObjectNode();
        ArrayNode arr = data.putArray("computed");
        java.util.Set<String> declarations = new java.util.LinkedHashSet<>();
        for (Computed c : computed) {
            ObjectNode cn = arr.addObject();
            // `field` identifies the ROW; `declaration` identifies the computation. Publishing only the
            // declaration — as this did — made four rows of one repeated computed field indistinguishable except
            // by list position, while the engine had the indices all along.
            cn.put("field", A12Pointers.canonicalInstance(c.getPath(), c.getIndices()));
            cn.put("declaration", A12Pointers.declaration(c.getPath()));
            cn.put("outcome", outcomeName(c.getOutcome()));
            if (c.getValue() != null) {
                cn.put("value", c.getValue());
            }
            declarations.add(c.getPath());
        }
        // The two counts a summary needs and cannot derive from each other: a run over 2 declarations × 5 rows is
        // neither "2" nor "10" alone. Emitted here so the verb's summary states both instead of calling instances
        // "field(s)" — which was wrong on both axes.
        data.put("declarations", declarations.size());
        data.put("instances", computed.size());
        return data;
    }

    /** {@code rule eval}: the FIRED / PASSED / SUPPRESSED verdict for one stored rule (or {@code unsupported}). */
    public static ObjectNode ruleTestData(String modelJson, String documentJson, String locale, String rulePath,
            Map<String, PredefinedType> predefinedTypes, Map<String, CustomCondition> customConditions,
            Map<String, CustomFieldType> customFieldTypes, boolean applyComputations) {
        ModelDef def = InterpreterModelLoader.load(modelJson);
        Document doc = InterpreterModelLoader.document(documentJson, def);
        return focusedRuleData(def, doc, locale, rulePath, predefinedTypes, customConditions, customFieldTypes,
                applyComputations, (interpreter, evaluated) -> interpreter.testRule(rulePath, evaluated, locale));
    }

    /**
     * {@code rule eval --condition/--field}: focused runtime evaluation of one non-stored candidate, rendered
     * through the same fired/passed/suppressed/unsupported data contract as a stored rule.
     */
    public static ObjectNode ruleTestCandidateData(String modelJson, String documentJson, String locale,
            String group, String name, String errorEntityRelPath, String condition, String code,
            Map<String, PredefinedType> predefinedTypes, Map<String, CustomCondition> customConditions,
            Map<String, CustomFieldType> customFieldTypes, boolean applyComputations) {
        ModelDef def = InterpreterModelLoader.load(modelJson);
        Document doc = InterpreterModelLoader.document(documentJson, def);
        RuleDef candidate = candidateRule(locale, group, name, errorEntityRelPath, condition, code, "ERROR", code);
        return focusedRuleData(def, doc, locale, candidate.getPath(), predefinedTypes, customConditions,
                customFieldTypes, applyComputations,
                (interpreter, evaluated) -> interpreter.evalCandidate(candidate, evaluated, locale));
    }

    @FunctionalInterface
    private interface FocusedRuleEvaluator {
        RuleResult evaluate(DmInterpreter interpreter, Document document);
    }

    /** One focused-rule execution and projection for both stored and candidate routes. */
    private static ObjectNode focusedRuleData(ModelDef def, Document doc, String locale, String rulePath,
            Map<String, PredefinedType> predefinedTypes, Map<String, CustomCondition> customConditions,
            Map<String, CustomFieldType> customFieldTypes, boolean applyComputations,
            FocusedRuleEvaluator evaluator) {
        RuleResult result;
        try {
            DmInterpreter interp = new DmInterpreter(def, null, predefinedTypes, customConditions, customFieldTypes);
            Document evalDoc = applyComputations ? interp.applyComputations(doc, locale) : doc;
            result = evaluator.evaluate(interp, evalDoc);
        } catch (UnsupportedConstructException e) {
            // The rule uses a construct the engine can't evaluate (a CustomCondition with no impl) → report it
            // unsupported instead of crashing, mirroring whole-document eval's tolerance (G29).
            ObjectNode data = MAPPER.createObjectNode();
            data.put("rule", rulePath);
            data.put("fired", false);
            data.put("verdict", "unsupported");
            addUnsupported(data, List.of(
                    new UnsupportedRule(rulePath, e.getMessage(), UnsupportedSubject.ELEMENT)));
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
                n.put("field", A12Pointers.canonicalCell(s.getField()));
                n.put("formalErrorCode", s.getFormalErrorCode());
            });
        }
        return data;
    }

    /**
     * Surface the constructs the engine could NOT evaluate (G29) as {@code data.unsupported} — present only when
     * non-empty, like {@code suppressedBy}. Each entry is {@code {name, subject, reason}}.
     *
     * <p><b>{@code subject} exists because {@code name} is two different kinds of address.</b> An unevaluatable
     * rule contributes its model element path; an unregistered custom-typed cell contributes an <em>instance
     * pointer</em>. They were published under one key with nothing to tell them apart, which left a consumer — and
     * this projection — unable to canonicalize the pointer half without mangling the element half. So the kind is
     * stated, and only {@code CELL} is rendered as a pointer.
     */
    private static void addUnsupported(ObjectNode data, List<UnsupportedRule> unsupported) {
        if (unsupported.isEmpty()) {
            return;
        }
        ArrayNode arr = data.putArray("unsupported");
        for (UnsupportedRule u : unsupported) {
            ObjectNode n = arr.addObject();
            String name = switch (u.getSubject()) {
                case CELL -> A12Pointers.canonicalCell(u.getRulePath());
                case ELEMENT -> u.getRulePath();
            };
            n.put("name", name);
            n.put("subject", u.getSubject().name().toLowerCase(java.util.Locale.ROOT));
            n.put("reason", u.getReason());
        }
    }

    private static void addMessage(ObjectNode mn, Message m) {
        mn.put("code", m.getCode());
        mn.put("rule", m.getRulePath());
        // Rendered from the RETAINED message pointer, not from a string — see A12Pointers for why re-reading the
        // house form cannot tell an exact address from an unresolved one.
        mn.put("field", A12Pointers.canonical(m.getErrorPointer()));
        mn.put("severity", m.getSeverity().name());
        mn.put("type", typeError(m.getType()));
        mn.put("message", m.getText());
        putPointers(mn.putArray("referenced"), m.getReferencedPointers());
        if (!m.getFillToFixPointers().isEmpty()) {
            putPointers(mn.putArray("fillToFix"), m.getFillToFixPointers());
        }
    }

    private static void putPointers(ArrayNode arr, java.util.List<A12PartiallyKnownMultiPointer> pointers) {
        A12Pointers.canonical(pointers).forEach(arr::add);
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
