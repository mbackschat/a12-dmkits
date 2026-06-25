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

import com.mgmtp.a12.kernel.core.tool.a12internal.api.ado.INumberType;
import com.mgmtp.a12.kernel.core.tool.a12internal.api.ado.IStringType;

/**
 * The INTERPRETER MODEL-ELEMENT PARITY GUARD for the field-type CONSTRAINT surface (the forcing function whose
 * absence let IF20 hide — the analogue of the authoring-side {@code SlotParityTest}, applied to the interpreter's
 * model). It reflectively enumerates every declared, no-arg constraint accessor on the kernel's {@link IStringType}
 * and {@link INumberType} and asserts each is **classified exactly once** — MODELED (the interpreter loads + checks
 * it, IF20), or DEFERRED / OUT_OF_SCOPE with a reason. So when a kernel upgrade ADDS a field-type constraint, this
 * build FAILS until the new slot is classified (modeled or explicitly accounted for) — it can no longer be silently
 * dropped, which is exactly how the range/length/pattern/min-decimals checks went missing before the 2026-06-26
 * sweep. Same "mine constraints proactively, CI-locked" discipline as the MVK / catalog-parity ledgers.
 *
 * <p>Scope: the **field-type constraint** interfaces (where IF20 lived). The companion model-element slots —
 * {@code IRule.getSeverity} (IF23), {@code ICalculation.getComputationAlternatives}/{@code getCommonPrecondition}
 * (IF21), {@code IGroup.getSortInfo} (G28) — are each individually findings-tracked + (for IF21/IF23) test-locked;
 * extending this reflective guard to those interfaces is a tracked follow-on (INTERPRETER-FINDINGS IF22).
 */
class InterpreterFieldConstraintParityTest {

    private enum Disposition { MODELED, DEFERRED, OUT_OF_SCOPE }

    private record Classification(Disposition disposition, String note) {
    }

    /** Every declared, no-arg {@code get*}/{@code is*} accessor of the field-type constraint interfaces, classified. */
    private static final Map<String, Classification> CLASSIFIED = new LinkedHashMap<>();

    static {
        // ── IStringType ──
        modeled("IStringType.getMinLength", "FieldConstraints.minLength → stringZuKurz (IF20)");
        modeled("IStringType.getMaxLength", "FieldConstraints.maxLength → stringZuLang (IF20)");
        modeled("IStringType.getPattern", "FieldConstraints.pattern → stringFalschesMuster (IF20)");
        outOfScope("IStringType.isExtensibleEnum",
                "runtime/client-SPI quality, no DM-JSON serialization — a field is just a StringType (KERNEL-FINDINGS §8)");
        outOfScope("IStringType.isNoValueValidation",
                "skip-validation flag (base64 blobs etc.) — an editor expert-mode knob (FEATURE-GAPS §4), not a value check");

        // ── INumberType ──
        modeled("INumberType.getMinValue", "FieldConstraints.minValue → zahlZuKlein (IF20)");
        modeled("INumberType.getMaxValue", "FieldConstraints.maxValue → zahlZuGross (IF20)");
        modeled("INumberType.getMaxFractionalDigits", "FieldDef.scale → zahlFalscheAnzahlNachkommastellen (too-many)");
        modeled("INumberType.getMinFractionalDigits",
                "FieldConstraints.minFractionalDigits → zahlFalscheAnzahlNachkommastellen / zahlOhneDezimalTrenner (IF20)");
        outOfScope("INumberType.getMaxNumberOfDigitsBeforeDecimalPlace",
                "confirmed NOT DM-JSON-deserializable 2026-06-26 — runtime-only quality; zahlZuVieleVorkommastellen unreachable (FEATURE-GAPS §4)");
        outOfScope("INumberType.isZeroAllowed",
                "confirmed NOT DM-JSON-deserializable 2026-06-26 — runtime-only quality; zahlIstNull unreachable (FEATURE-GAPS §4)");
        outOfScope("INumberType.getMinLength", "total-digit length — editor expert-mode knob (FEATURE-GAPS §4), not modelkit-authorable");
        outOfScope("INumberType.getMaxLength", "total-digit length — editor expert-mode knob (FEATURE-GAPS §4), not modelkit-authorable");
        deferred("INumberType.isNumberTraitAmount",
                "number trait (amount/percent/permille) — unprobed message-RENDERING candidate, not a firing check (IF22)");
    }

    private static void modeled(String accessor, String note) {
        CLASSIFIED.put(accessor, new Classification(Disposition.MODELED, note));
    }

    private static void deferred(String accessor, String note) {
        CLASSIFIED.put(accessor, new Classification(Disposition.DEFERRED, note));
    }

    private static void outOfScope(String accessor, String note) {
        CLASSIFIED.put(accessor, new Classification(Disposition.OUT_OF_SCOPE, note));
    }

    /** Declared no-arg {@code get*}/{@code is*} accessors of {@code iface}, prefixed {@code <SimpleName>.<method>}. */
    private static Set<String> accessorsOf(Class<?> iface) {
        return Arrays.stream(iface.getDeclaredMethods())
                .filter(m -> m.getParameterCount() == 0)
                .filter(m -> !m.isSynthetic())
                .filter(m -> m.getReturnType() != void.class)
                .filter(m -> m.getName().startsWith("get") || m.getName().startsWith("is"))
                .map(m -> iface.getSimpleName() + "." + m.getName())
                .collect(Collectors.toCollection(TreeSet::new));
    }

    @Test
    void everyFieldTypeConstraintAccessorIsClassified() {
        Set<String> live = new TreeSet<>();
        live.addAll(accessorsOf(IStringType.class));
        live.addAll(accessorsOf(INumberType.class));

        Set<String> unclassified = new TreeSet<>(live);
        unclassified.removeAll(CLASSIFIED.keySet());
        assertTrue(unclassified.isEmpty(),
                "Kernel field-type constraint accessor(s) with no classification — the kernel added a constraint the "
                        + "interpreter may silently drop (this is the IF20 class). MODEL it (IF20) or classify it "
                        + "DEFERRED/OUT_OF_SCOPE with a reason:\n  " + String.join("\n  ", unclassified));
    }

    @Test
    void noClassificationNamesAMissingAccessor() {
        Set<String> live = new TreeSet<>();
        live.addAll(accessorsOf(IStringType.class));
        live.addAll(accessorsOf(INumberType.class));

        Set<String> orphans = new TreeSet<>(CLASSIFIED.keySet());
        orphans.removeAll(live);
        assertTrue(orphans.isEmpty(),
                "Classified accessor(s) that no longer exist on the kernel interface (renamed/removed — the "
                        + "classification is stale):\n  " + String.join("\n  ", orphans));
    }
}
