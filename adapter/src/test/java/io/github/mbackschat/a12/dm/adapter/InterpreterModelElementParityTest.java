package io.github.mbackschat.a12.dm.adapter;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;

import org.junit.jupiter.api.Test;

import com.mgmtp.a12.kernel.core.tool.a12internal.api.ado.IGroup;
import com.mgmtp.a12.kernel.core.tool.a12internal.api.ado.IRule;
import com.mgmtp.a12.kernel.core.tool.a12internal.api.ado.serviceparams.ICalculationInfo;

/**
 * The interpreter MODEL-ELEMENT parity guard for the **rule / computation / group** slots — the companion to
 * {@link InterpreterFieldConstraintParityTest} (which covers the field-type constraint slots). Together they are
 * the forcing function whose absence let IF20/IF21/IF23 hide: every declared, no-arg accessor of the kernel's
 * {@link IRule}, {@link ICalculationInfo} (the computation model) and {@link IGroup} must be **classified exactly
 * once** — MODELED (the interpreter loads/evaluates it) or OUT_OF_SCOPE with a reason. A kernel
 * upgrade that ADDS a model-element slot fails this build until the new slot is accounted for, so it cannot be
 * silently dropped (the IF21 tiered-alternatives / IF23 severity-INFO class).
 */
class InterpreterModelElementParityTest {

    private enum Disposition { MODELED, OUT_OF_SCOPE }

    private record Classification(Disposition disposition, String note) {
    }

    private static final Map<String, Classification> CLASSIFIED = new LinkedHashMap<>();

    static {
        // ── IRule ──
        modeled("IRule.getFullName", "RuleDef.path");
        modeled("IRule.getErrorConditionText", "RuleDef.conditionText (parsed to the AST)");
        modeled("IRule.getErrorMessages", "RuleDef.messages (per-locale, rendered with $field.value$ interpolation)");
        modeled("IRule.getErrorEntity", "RuleDef.errorEntityRelPath (the blamed field)");
        modeled("IRule.getErrorCode", "RuleDef.errorCode");
        modeled("IRule.getSeverityType", "RuleDef.severity — Severity{ERROR,WARNING,INFO} incl. INFO (IF23)");
        outOfScope("IRule.getAnnotations", "editor metadata (descriptions/annotations via the `meta` verb) — not eval-relevant");
        outOfScope("IRule.getCustomRefErrorEntitiesIfFilled",
                "modeler-defined error-entity refinement (which field(s) a rule BLAMES by filled-state; ATTRIBUTION only, "
                        + "not fire/no-fire) — UNWIRED in kernel 30.8.1: the md-model rule mapping returns empty pending "
                        + "A12K-2281/2361, every rule impl returns empty, and RuleParser short-circuits when both are empty "
                        + "(always). So the kernel always uses its default error-entity attribution, which the interpreter "
                        + "matches (parity by construction; CustomConditionDiffTest locks the error field). KERNEL-FINDINGS "
                        + "'getCustomRefErrorEntities… UNWIRED'.");
        outOfScope("IRule.getCustomRefErrorEntitiesIfNotFilled", "the not-filled branch of the same UNWIRED custom-ref error-entity refinement.");

        // ── ICalculationInfo (the computation model) ──
        modeled("ICalculationInfo.getCalculatedField", "ComputationDef.targetPath");
        modeled("ICalculationInfo.getPartialCalculations", "ComputationDef.alternatives (precondition?, operation) — IF21");
        modeled("ICalculationInfo.getCommonPrecondition", "ComputationDef.commonPrecondition — IF21");
        modeled("ICalculationInfo.getName", "the computation's name — carried on the element, not eval-relevant; the targetPath identifies it");
        modeled("ICalculationInfo.getRoundingMode",
                "ComputationDef.roundingMode — beside a band it rounds the written value, and EXACT floors it to the "
                        + "target's digits (IF341, KF474)");

        // ── IGroup ──
        modeled("IGroup.getIndexFieldNames", "ModelDef.indexFields (the repetition row-key; arms uniqueIndex + parallel iteration)");
        outOfScope("IGroup.getCustomType", "group usage type (attachment / multi-select / complex-type) — form-UI, not eval-relevant");
        outOfScope("IGroup.getSortInfo",
                "editor/USB DISPLAY ordering — NOT consumed by kernel-core-runtime/calc (eval iterates document order), "
                        + "so the interpreter correctly ignores it. The dmkit AUTHORING surface shipped "
                        + "(modelkit GroupBuilder.sortField + CLI group modify --sort-field — API-FINDINGS finding 51, G28).");
    }

    private static void modeled(String accessor, String note) {
        CLASSIFIED.put(accessor, new Classification(Disposition.MODELED, note));
    }

    private static void outOfScope(String accessor, String note) {
        CLASSIFIED.put(accessor, new Classification(Disposition.OUT_OF_SCOPE, note));
    }

    private static Set<String> accessorsOf(Class<?> iface) {
        return Arrays.stream(iface.getDeclaredMethods())
                .filter(m -> m.getParameterCount() == 0)
                .filter(m -> !m.isSynthetic())
                .filter(m -> m.getReturnType() != void.class)
                .filter(m -> m.getName().startsWith("get") || m.getName().startsWith("is"))
                .map(m -> iface.getSimpleName() + "." + m.getName())
                .collect(Collectors.toCollection(TreeSet::new));
    }

    private static Set<String> live() {
        Set<String> live = new TreeSet<>();
        live.addAll(accessorsOf(IRule.class));
        live.addAll(accessorsOf(ICalculationInfo.class));
        live.addAll(accessorsOf(IGroup.class));
        return live;
    }

    @Test
    void everyModelElementAccessorIsClassified() {
        Set<String> unclassified = new TreeSet<>(live());
        unclassified.removeAll(CLASSIFIED.keySet());
        assertTrue(unclassified.isEmpty(),
                "Kernel rule/computation/group accessor(s) with no classification — the kernel added a model-element "
                        + "slot the interpreter may silently drop (the IF21/IF23 class). MODEL it or classify it "
                        + "OUT_OF_SCOPE with a reason:\n  " + String.join("\n  ", unclassified));
    }

    @Test
    void noClassificationNamesAMissingAccessor() {
        Set<String> orphans = new TreeSet<>(CLASSIFIED.keySet());
        orphans.removeAll(live());
        assertTrue(orphans.isEmpty(),
                "Classified accessor(s) that no longer exist on the kernel interface (renamed/removed — stale):\n  "
                        + String.join("\n  ", orphans));
    }
}
