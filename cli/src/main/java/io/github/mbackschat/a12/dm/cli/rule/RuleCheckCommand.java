package io.github.mbackschat.a12.dm.cli.rule;

import io.github.mbackschat.a12.dm.core.EntityPath;
import io.github.mbackschat.a12.dm.core.KernelCode;
import io.github.mbackschat.a12.dm.cli.BaseCommand;
import io.github.mbackschat.a12.dm.cli.CheckEngine;
import io.github.mbackschat.a12.dm.cli.ConsistencyEvidence;
import io.github.mbackschat.a12.dm.cli.EngineOption;
import io.github.mbackschat.a12.dm.cli.LintBackstop;
import io.github.mbackschat.a12.dm.cli.ModelPaths;
import io.github.mbackschat.a12.dm.cli.ParamKind;
import io.github.mbackschat.a12.dm.cli.PortableCheck;
import io.github.mbackschat.a12.dm.cli.RejectionBasis;
import io.github.mbackschat.a12.dm.cli.ResultEnvelope;
import io.github.mbackschat.a12.dm.cli.WorkspaceOption;
import io.github.mbackschat.a12.dm.cli.interp.TextConditionCorrective;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.mbackschat.a12.dm.core.EntityPaths;
import io.github.mbackschat.a12.dm.core.ModelResolver;
import io.github.mbackschat.a12.dm.consistency.ConsistencyStatus;
import io.github.mbackschat.a12.dm.consistency.EditReport;
import io.github.mbackschat.a12.dm.consistency.ReportJsonKt;

import io.github.mbackschat.a12.dm.adapter.KernelAdapter;
import io.github.mbackschat.a12.dm.rulekit.context.ModelContext;
import io.github.mbackschat.a12.dm.rulekit.dsl.BoundModel;
import io.github.mbackschat.a12.dm.rulekit.dsl.LocalizedText;
import io.github.mbackschat.a12.dm.rulekit.validate.Diagnostic;
import io.github.mbackschat.a12.dm.rulekit.validate.DraftRule;
import io.github.mbackschat.a12.dm.rulekit.validate.RkCode;
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
            description = "the rule's iteration scope (group path inside the model); defaults to the field's parent"
                    + "; this verb additionally requires the error field to lie INSIDE it, which is stricter than the kernel — the kernel demands containment only once the rule ITERATES, so a non-iterating rule it would admit is refused here (a deliberate narrowing, not a kernel law)")
    String group;

    @Option(names = "--severity", defaultValue = "ERROR", paramLabel = "ERROR|WARNING|INFO",
            description = "rule severity (default: ${DEFAULT-VALUE})")
    String severity;

    @Option(names = "--name", defaultValue = "CheckRule", paramLabel = "<name>",
            description = "candidate rule name (default: ${DEFAULT-VALUE})")
    String name;

    @ParamKind(ParamKind.Kind.SCALAR)
    @Option(names = "--message", paramLabel = "<locale>=<text>",
            description = "also check a per-locale error MESSAGE against the kernel's error-text checker "
                    + "(repeatable). Locales you omit keep a placeholder, so this reports findings for the text "
                    + "you gave rather than a missing message for a locale you are not authoring. Without it the "
                    + "candidate's messages are not checked at all — the placeholder is always valid.")
    Map<String, String> messages = new LinkedHashMap<>();

    @Option(names = "--suggest-error-field",
            description = "also list the valid error-field candidates (the condition's referenced fields inside "
                    + "its iteration scope) in data — the up-front pick that avoids the MVK_ERROR_FIELD_NOT_REFERENCED reject")
    boolean suggestErrorField;

    @Mixin
    WorkspaceOption workspace = new WorkspaceOption();

    @Mixin
    EngineOption engine = new EngineOption();

    private static final ObjectMapper MAPPER =
            new ObjectMapper();

    @Override
    public Integer call() {
        String model = model();
        if (!ModelPaths.isAbsoluteEntityPath(field)) {
            return refuseOptionValue(RkCode.INVALID_VALUE,
                    "--field must be an absolute path (e.g. /Group/Field), got: " + field,
                    "--field", null, ModelPaths.absoluteEntityPathFix("--field", field, group));
        }
        String effectiveGroup = group;
        if (effectiveGroup == null) {
            int slash = field.lastIndexOf('/');
            effectiveGroup = field.substring(0, slash);
        }
        if (!EntityPaths.isStrictlyInside(field, effectiveGroup)) {
            return refuseOptionValue(RkCode.ERROR_FIELD_OUTSIDE_GROUP,
                    "--field " + field + " must be inside --group " + effectiveGroup,
                    "--field", field, RkCode.ERROR_FIELD_OUTSIDE_GROUP.fix());
        }
        String errorEntityRelPath =
                EntityPath.of(effectiveGroup).elementReference(EntityPath.of(field));

        String json = readModelJson(model);
        if (json == null) {
            return 2;
        }
        ModelResolver resolver = workspace.resolverFor(model);

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

        CheckEngine requested = engine.selected();
        return switch (requested) {
            case A12_KERNEL -> checkWithKernel(json, effectiveGroup, errorEntityRelPath, resolver, requested);
            case PORTABLE_CHECKER ->
                    checkWithPortableChecker(model, json, effectiveGroup, errorEntityRelPath, requested);
        };
    }

    /** The A12 kernel arm — this verb's original and default behaviour, unchanged. */
    private Integer checkWithKernel(String json, String effectiveGroup, String errorEntityRelPath,
            ModelResolver resolver, CheckEngine requested) {
        List<Diagnostic> findings = new ArrayList<>();
        // The validator run is this verb's gate receipt. It stays empty when the validator THREW, because then no
        // kernel verdict was ever reached — the previous shape derived the marker from ValidationResult.of(findings),
        // i.e. from the ambient backend, so it published KERNEL_CONFIRMED on exactly that path too.
        ConsistencyEvidence evidence = ConsistencyEvidence.none();
        try {
            DraftRule draft = candidate(effectiveGroup, errorEntityRelPath);
            List<String> undeclared = new RuleValidator().undeclaredMessageLocales(json, draft);
            if (!undeclared.isEmpty()) {
                return refuseUndeclaredLocales(json, undeclared);
            }
            ValidationResult result = new RuleValidator().check(json, draft, resolver);
            evidence = ConsistencyEvidence.of(result);
            // G30: upgrade a rejected text candidate's union fix to the operand-matched corrective (the typed
            // path's SpanResolver has no AST here) by classifying operands via the interpreter parser + ModelIndex.
            findings.addAll(TextConditionCorrective.enrich(result.diagnostics(), condition, effectiveGroup, json, resolver));
            if (result.ok()) {
                findings.addAll(LintBackstop.lintCandidate(json, effectiveGroup, name, errorEntityRelPath,
                        code, condition, severity, field, resolver));
            }
        } catch (RuntimeException e) {
            findings.add(Diagnostic.precheck(Optional.empty(), String.valueOf(e.getMessage()),
                    new Diagnostic.Where(effectiveGroup + "/" + name, Optional.empty()),
                    Optional.empty(), Optional.empty()));
        }

        ObjectNode data = suggestErrorField
                ? errorFieldSuggestions(json, effectiveGroup, errorEntityRelPath, resolver)
                : null;

        if (evidence.gateRan()) {
            evidence = evidence.requestedBy(requested);
        }
        ValidationResult merged = ValidationResult.of(findings);
        // With a receipt this is a real verdict about the candidate. Without one the validator never returned, so
        // the only honest report is a rejection the CLI itself made — same exit 1, no invented verdict. `data`
        // rides along either way: the candidate list is exactly what a rejected author needs next.
        ResultEnvelope env = evidence.gateRan()
                ? ResultEnvelope.consistencyVerdict(resultIdentity(),
                        merged.ok() ? "candidate is kernel-valid" : "candidate has " + findings.size() + " finding(s)",
                        merged.ok(), findings, data, evidence)
                : ResultEnvelope.rejected(resultIdentity(),
                        "the candidate could not be checked — " + findings.size() + " finding(s)", data, findings,
                        RejectionBasis.preflight());
        out().print(env.toJsonString());
        return env.exitCode();
    }

    /**
     * The kernel-free portable arm — the candidate is spliced in and the checker's answer is ATTRIBUTED.
     *
     * <p><b>Why an attribution rather than the spliced model's report.</b> The portable checker reports over a
     * whole model, so publishing the spliced report as the candidate's verdict would reject a perfectly good
     * candidate whenever the model already carried an ERROR of its own — a verdict about someone else's
     * declaration, delivered as a verdict about this candidate. {@code checkEdit} separates the two, and the
     * baseline's own findings ride the payload as a disclosure so an author checking against an
     * already-broken model still learns that.
     *
     * <p>The splice reuses {@link RuleValidator#inject} — the same step the kernel arm runs before validating,
     * so both engines judge the identical bytes and a disagreement between them is about the engines rather
     * than about two different models.
     */
    private Integer checkWithPortableChecker(String model, String json, String effectiveGroup,
            String errorEntityRelPath, CheckEngine requested) {
        DraftRule draft = candidate(effectiveGroup, errorEntityRelPath);
        List<String> undeclared = new RuleValidator().undeclaredMessageLocales(json, draft);
        if (!undeclared.isEmpty()) {
            return refuseUndeclaredLocales(json, undeclared);
        }

        PortableCheck check = PortableCheck.ofEdit(json, new RuleValidator().inject(json, draft), model);
        EditReport edit = check.edit().orElseThrow(() -> new IllegalStateException(
                "the edit arm must carry an attribution; PortableCheck.ofEdit is the only producer"));
        ConsistencyEvidence evidence = ConsistencyEvidence.of(check).requestedBy(requested);
        ObjectNode data = (ObjectNode) readGeneratedJson(ReportJsonKt.toJson(edit));

        // The candidate's verdict is the attribution's, never the edited model's. INVALID is a real rejection
        // of THIS candidate; anything else is "nothing established", which is exit 3 rather than a green 0 —
        // the checker cannot report a candidate acceptable at all.
        if (edit.getStatus() instanceof ConsistencyStatus.Invalid) {
            return print(ResultEnvelope.consistencyVerdict(resultIdentity(),
                    "the portable checker rejects the candidate — " + edit.getIntroduced().size()
                            + " finding(s) it introduced",
                    false, List.of(), data, evidence));
        }
        return print(ResultEnvelope.consistencyInconclusive(resultIdentity(),
                "the portable checker established no verdict on the candidate; this is NOT a statement that "
                        + "the candidate is valid",
                List.of(), data, evidence));
    }

    /**
     * Whether A12 refuses {@code candidate} as this rule's error field for not being referenced. The kernel's
     * directly-referenced set includes a read made only inside an unmarked {@code Having} body, which A12 does not
     * count as the rule's own (KERNEL-FINDINGS KF488), and the sets it does judge by are internal to its parser,
     * so each candidate is asked of the kernel rather than re-derived here.
     */
    private boolean referenceRefused(RuleValidator validator, String json, String effectiveGroup, String candidate,
            ModelResolver resolver) {
        DraftRule draft = candidate(effectiveGroup, EntityPath.of(effectiveGroup).elementReference(EntityPath.of(candidate)));
        return validator.check(json, draft, resolver).diagnostics().stream()
                .anyMatch(d -> d.code().filter(KernelCode.MVK_ERROR_FIELD_NOT_REFERENCED.name()::equals).isPresent());
    }

    /** This invocation's candidate rule, messages included — built once so both routes check the same thing. */
    private DraftRule candidate(String effectiveGroup, String errorEntityRelPath) {
        return DraftRule.builder()
                .groupPath(effectiveGroup).name(name).errorEntityRelPath(errorEntityRelPath)
                .errorCondition(condition).errorCode(code).severity(severity)
                .messages(messages.entrySet().stream()
                        .map(e -> new LocalizedText(e.getKey(), e.getValue())).toList())
                .build();
    }

    /**
     * Refuse a {@code --message} locale the model does not declare, rather than checking a candidate whose
     * message the kernel will not look at. Measured basis in
     * {@link RuleValidator#undeclaredMessageLocales}: the same invalid parameter is rejected in a declared
     * locale and accepted in an undeclared one, so passing it through would report {@code valid:true} about an
     * unexamined text.
     */
    private Integer refuseUndeclaredLocales(String json, List<String> undeclared) {
        List<String> declared = new RuleValidator().declaredLocales(json);
        Diagnostic d = Diagnostic.precheck(Optional.of(RkCode.UNDECLARED_MESSAGE_LOCALE.code()),
                "the model does not declare " + String.join(", ", undeclared)
                        + "; it declares " + String.join(", ", declared),
                Diagnostic.Where.arg(null, "--message"),
                Optional.of(RkCode.UNDECLARED_MESSAGE_LOCALE.fix()), Optional.empty());
        return print(ResultEnvelope.refused(resultIdentity(),
                "the candidate names an undeclared message locale", List.of(d)));
    }

    /**
     * The {@code --suggest-error-field} payload (G4): the valid error-field candidates for this candidate
     * condition — the referenced fields inside its iteration scope ({@link ModelContext#errorFieldCandidates}),
     * plus whether the chosen {@code --field} is among them. The candidate is injected and bound
     * <em>leniently</em> ({@link BoundModel#ofJsonLenient} — expand-only, no consistency gate), so a
     * kernel-rejected candidate — the whole point: a <em>bad error field</em>, which would make the
     * consistency-checking {@code BoundModel.ofJson} throw — still gets read for its references and the
     * corrective list. Best-effort: a condition the parser/converter can't model yields an empty list with a
     * {@code note}, never a failure.
     */
    private ObjectNode errorFieldSuggestions(String json, String effectiveGroup, String errorEntityRelPath,
            ModelResolver resolver) {
        ObjectNode data = MAPPER.createObjectNode();
        data.put("field", field);
        String candidateFullName = effectiveGroup + "/" + name;
        try {
            RuleValidator validator = new RuleValidator();
            String injected = validator.inject(json, candidate(effectiveGroup, errorEntityRelPath));
            BoundModel lenient = BoundModel.ofJsonLenient(injected, resolver);   // tolerates the inconsistent candidate
            // The kernel's own getDirectlyReferencedEntities (via referencedFields) — robust where the
            // rulekit converter NPEs on an inconsistent candidate; it returns the condition's references
            // regardless of error-field validity, which is exactly what we filter into candidates.
            java.util.Set<String> references = KernelAdapter.of(lenient).referencedFields(candidateFullName);
            java.util.List<String> candidates = new ModelContext(lenient).errorFieldCandidates(references).stream()
                    .filter(candidate -> !referenceRefused(validator, json, effectiveGroup, candidate, resolver)).toList();
            ArrayNode arr = data.putArray("candidates");
            candidates.forEach(arr::add);
            data.put("fieldIsCandidate", candidates.contains(field));
            if (candidates.isEmpty()) {
                data.put("note", "the condition references no field inside its iteration scope — reference one "
                        + "there to use as the error field");
            } else if (!candidates.contains(field)) {
                // probe 06-6: `candidates` are the ITERATION-scope refs (a per-row error field). A model-level
                // rule can legitimately point at a non-repeating field the condition references elsewhere (e.g.
                // FieldFilled(ThatField)), which the kernel accepts though it isn't in this list — so
                // fieldIsCandidate:false is not a verdict. The `valid` flag from the check is authoritative.
                data.put("note", "`candidates` lists the iteration-scope references (a per-row error field). "
                        + "Your --field may still be valid as a MODEL-LEVEL error field if the condition "
                        + "references it elsewhere (e.g. FieldFilled(" + (field == null ? "ThatField"
                        : field.substring(field.lastIndexOf('/') + 1)) + ")); the `valid` verdict is authoritative.");
            }
        } catch (RuntimeException e) {
            data.putArray("candidates");
            data.put("note", "could not compute candidates (the condition may be malformed): " + e.getMessage());
        }
        return data;
    }
}
