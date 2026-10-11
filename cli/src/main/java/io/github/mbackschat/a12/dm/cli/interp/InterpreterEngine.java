package io.github.mbackschat.a12.dm.cli.interp;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

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
import io.github.mbackschat.a12.dm.interpreter.model.UndeclaredReference;
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

    /**
     * How much of the model a runtime verb's undeclared-reference gate (G46) speaks for — always exactly what the
     * verb about to run will evaluate, never more and never less.
     *
     * <p>The bound is load-bearing in both directions. Reporting LESS than the verb reaches would let a bad
     * reference through and put the verb back to answering confidently about a rule that cannot exist. Reporting
     * MORE would refuse a call that is sound as issued: a focused question is not the place to report an
     * unrelated rule's typo, and asking the whole-model question there would force every rule's AST, defeating
     * the interpreter's parse-only-what-you-touch preparation that keeps a single-rule call cheap.
     *
     * <p>Each entry point below names its own rule domain — the rule the caller asked about, every stored rule,
     * or none at all ({@code model compute} validates nothing). This enum settles the one remaining choice, and
     * only a FOCUSED verb has it: {@code --no-computations} skips the compute-and-apply step, and a focused rule
     * is then the only thing evaluated. <b>Whole-model validation has no such choice</b> — it evaluates the
     * implicit computation-validation rules ({@code [F] != <operation>}, one per computation — IF32/IF65) whether
     * or not the compute step ran, so a computation's operation is reached either way and stays in scope.
     */
    public enum Computations {
        /** The form-engine flow: compute → apply → evaluate. The default of every eval verb. */
        APPLIED,
        /** {@code --no-computations}: evaluate the stored values as they are. */
        SKIPPED;

        public static Computations of(boolean applied) {
            return applied ? APPLIED : SKIPPED;
        }
    }

    /**
     * The gate for a WHOLE-MODEL evaluation ({@code model eval}): every stored rule and every computation.
     *
     * <p>Kernel-free, which is what keeps the EVAL path native-safe — the kernel's Groovy codegen cannot go
     * native. <b>Not</b> because the native binary lacks the kernel; it carries it and serves the
     * consistency oracle from it, on both artifacts. This javadoc claimed the opposite until 2026-09-16
     * ("the native binary where the MVK consistency oracle is unavailable"); the evidence lives in
     * {@code docs/NATIVE-IMAGE-GRAALVM.md} §3.8 and is deliberately not repeated here.
     */
    public static List<UndeclaredReference> undeclaredReferences(String modelJson) {
        return new DmInterpreter(InterpreterModelLoader.load(modelJson)).undeclaredReferences();
    }

    /** The same whole-model gate with a non-stored candidate appended — {@code model eval --condition}. */
    public static List<UndeclaredReference> undeclaredReferencesWithCandidate(String modelJson,
            CandidateRule candidate) {
        DmInterpreter interpreter = new DmInterpreter(InterpreterModelLoader.load(modelJson));
        return union(interpreter.undeclaredReferences(focusedRule(candidate)),
                interpreter.undeclaredReferences());
    }

    /**
     * The gate for {@code model compute}, which runs the computations and validates nothing — so a stored rule's
     * undeclared reference is out of scope here however broken it is.
     */
    public static List<UndeclaredReference> undeclaredComputationReferences(String modelJson) {
        return new DmInterpreter(InterpreterModelLoader.load(modelJson)).undeclaredComputationReferences();
    }

    /**
     * The gate for a FOCUSED stored-rule evaluation ({@code rule eval <rulePathInModel>}): that rule's condition,
     * plus the computations when the form-engine flow applies them. An unknown {@code rulePath} yields no
     * findings — every caller refuses a missing rule ({@code RK_NO_SUCH_RULE}) before reaching here, so there is
     * no rule to answer about rather than nothing to report.
     */
    public static List<UndeclaredReference> undeclaredRuleReferences(String modelJson, String rulePath,
            Computations computations) {
        ModelDef def = InterpreterModelLoader.load(modelJson);
        DmInterpreter interpreter = new DmInterpreter(def);
        return def.getRules().stream().filter(rule -> rule.getPath().equals(rulePath)).findFirst()
                .map(rule -> union(interpreter.undeclaredReferences(rule), applied(interpreter, computations)))
                .orElseGet(List::of);
    }

    /** The same focused gate for a non-stored {@code --condition}/{@code --field} candidate ({@code rule eval}). */
    public static List<UndeclaredReference> undeclaredCandidateReferences(String modelJson,
            CandidateRule candidate, Computations computations) {
        DmInterpreter interpreter = new DmInterpreter(InterpreterModelLoader.load(modelJson));
        return union(interpreter.undeclaredReferences(focusedRule(candidate)),
                applied(interpreter, computations));
    }

    private static List<UndeclaredReference> applied(DmInterpreter interpreter, Computations computations) {
        return switch (computations) {
            case APPLIED -> interpreter.undeclaredComputationReferences();
            case SKIPPED -> List.of();
        };
    }

    private static List<UndeclaredReference> union(List<UndeclaredReference> first,
            List<UndeclaredReference> second) {
        return Stream.concat(first.stream(), second.stream()).distinct().toList();
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
    public static ObjectNode evalCandidateData(String modelJson, String documentJson, CandidateRule candidate,
            Map<String, PredefinedType> predefinedTypes, Map<String, CustomCondition> customConditions,
            Map<String, CustomFieldType> customFieldTypes, boolean applyComputations) {
        return evalCandidateData(modelJson, documentJson, candidate, predefinedTypes, customConditions,
                customFieldTypes, applyComputations, null);
    }

    /** Candidate evaluation with optional partial-validation relevance. */
    public static ObjectNode evalCandidateData(String modelJson, String documentJson, CandidateRule candidate,
            Map<String, PredefinedType> predefinedTypes, Map<String, CustomCondition> customConditions,
            Map<String, CustomFieldType> customFieldTypes, boolean applyComputations, String relevantJson) {
        RuleDef rule = candidateRule(candidate, candidate.name());
        ModelDef def = InterpreterModelLoader.load(modelJson).withRule(rule);
        return evalFor(def, documentJson, candidate.locale(), rule.getPath(),
                predefinedTypes, customConditions, customFieldTypes, applyComputations, relevantJson);
    }

    /**
     * The non-stored candidate rule a runtime verb was asked about ({@code --condition} + {@code --field}) — the
     * rule it would be if it were stored. A value object rather than six positional strings because the same six
     * feed three entry points (the reference gate, whole-model candidate eval, focused candidate eval) and, being
     * same-typed, are silently swap-prone at every call site.
     *
     * @param severity the candidate's severity; {@code model eval} takes it from the caller, the focused routes
     *                 evaluate every candidate as an ERROR because a verdict does not depend on it
     */
    public record CandidateRule(String locale, String group, String name, String errorEntityRelPath,
            String condition, String code, String severity) {

        public static Builder builder() {
            return new Builder();
        }

        /** The public construction path — named setters, so no two same-typed arguments can trade places. */
        public static final class Builder {
            private String locale;
            private String group;
            private String name;
            private String errorEntityRelPath;
            private String condition;
            private String code;
            private String severity = "ERROR";

            private Builder() {
            }

            public Builder locale(String locale) {
                this.locale = locale;
                return this;
            }

            public Builder group(String group) {
                this.group = group;
                return this;
            }

            public Builder name(String name) {
                this.name = name;
                return this;
            }

            public Builder errorEntityRelPath(String errorEntityRelPath) {
                this.errorEntityRelPath = errorEntityRelPath;
                return this;
            }

            public Builder condition(String condition) {
                this.condition = condition;
                return this;
            }

            public Builder code(String code) {
                this.code = code;
                return this;
            }

            /** Leave unset for the ERROR default the focused routes always use. */
            public Builder severity(String severity) {
                this.severity = severity;
                return this;
            }

            public CandidateRule build() {
                return new CandidateRule(locale, group, name, errorEntityRelPath, condition, code, severity);
            }
        }
    }

    /**
     * The one candidate-rule representation consumed by both model-scope and focused runtime evaluation. The
     * {@code message} differs by route: whole-model eval renders the candidate's {@code name} (the kernel's
     * fallback for a rule with no template), the focused routes render its {@code code}.
     */
    private static RuleDef candidateRule(CandidateRule candidate, String message) {
        return new RuleDef(candidate.group() + "/" + candidate.name(), candidate.group(), candidate.condition(),
                candidate.code(), candidate.errorEntityRelPath(),
                Severity.valueOf(candidate.severity().toUpperCase()), Map.of(candidate.locale(), message));
    }

    /** The candidate as the focused routes build it — reference gate and {@code rule eval} must see one rule. */
    private static RuleDef focusedRule(CandidateRule candidate) {
        return candidateRule(candidate, candidate.code());
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
    public static ObjectNode computeData(String modelJson, String documentJson, String locale,
            Map<String, PredefinedType> predefinedTypes, Map<String, CustomCondition> customConditions,
            Map<String, CustomFieldType> customFieldTypes) {
        ModelDef def = InterpreterModelLoader.load(modelJson);
        Document doc = InterpreterModelLoader.document(documentJson, def);
        var evaluation = new DmInterpreter(def, null, predefinedTypes, customConditions, customFieldTypes)
                .computeWithSupport(doc, locale);
        List<Computed> computed = evaluation.getResults();

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
        addUnsupported(data, evaluation.getUnsupported());
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
    public static ObjectNode ruleTestCandidateData(String modelJson, String documentJson, CandidateRule candidate,
            Map<String, PredefinedType> predefinedTypes, Map<String, CustomCondition> customConditions,
            Map<String, CustomFieldType> customFieldTypes, boolean applyComputations) {
        ModelDef def = InterpreterModelLoader.load(modelJson);
        Document doc = InterpreterModelLoader.document(documentJson, def);
        String locale = candidate.locale();
        RuleDef rule = focusedRule(candidate);
        return focusedRuleData(def, doc, locale, rule.getPath(), predefinedTypes, customConditions,
                customFieldTypes, applyComputations,
                (interpreter, evaluated) -> interpreter.evalCandidate(rule, evaluated, locale));
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
        data.put("verdict", result.getVerdict().name().toLowerCase(Locale.ROOT));
        // A firing publishes the SAME message channels `model eval` does — `type` above all, which is a semantic
        // observable rather than presentation: for a uniqueness predicate it is the only difference between a
        // filtered and an unfiltered firing on identical data. Projecting only the rendered text made measuring
        // it require persisting the rule and running whole-document eval, the model-dirtying cycle this verb's
        // candidate mode exists to avoid.
        Message firing = result.getFiring();
        if (firing != null) {
            data.put("code", firing.getCode());
            data.setAll(messageChannels(firing));
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
            n.put("subject", u.getSubject().name().toLowerCase(Locale.ROOT));
            n.put("reason", u.getReason());
        }
    }

    private static void addMessage(ObjectNode mn, Message m) {
        mn.put("code", m.getCode());
        mn.put("rule", m.getRulePath());
        mn.setAll(messageChannels(m));
    }

    /**
     * The message channels beyond the two identity keys ({@code code} and the rule path), rendered once for both
     * the whole-document {@code messages[]} entry and the focused {@code rule eval} firing — so the two cannot
     * describe one firing differently. Each caller places the identity keys itself, because their order in the
     * published object differs.
     *
     * <p>Every address is rendered from the RETAINED message pointer, not from a string — see {@link A12Pointers}
     * for why re-reading the house form cannot tell an exact address from an unresolved one.
     */
    private static ObjectNode messageChannels(Message m) {
        ObjectNode node = MAPPER.createObjectNode();
        node.put("field", A12Pointers.canonical(m.getErrorPointer()));
        node.put("severity", m.getSeverity().name());
        node.put("type", typeError(m.getType()));
        node.put("message", m.getText());
        putPointers(node.putArray("referenced"), m.getReferencedPointers());
        if (!m.getFillToFixPointers().isEmpty()) {
            putPointers(node.putArray("fillToFix"), m.getFillToFixPointers());
        }
        return node;
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
