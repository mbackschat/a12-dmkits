package io.github.mbackschat.a12.dm.adapter.laws;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;
import java.util.Optional;

import io.github.mbackschat.a12.dm.adapter.KernelAdapter;
import io.github.mbackschat.a12.dm.rulekit.validate.Diagnostic;

import org.junit.jupiter.api.Test;

/**
 * Two kernel derivations over a model's RULE COLLECTION report problems that no other route of ours asks for,
 * and this locks that they are now kept: the dynamic-enumeration check
 * ({@code getDynamicEnumerationStringValues}) and the rule-driven model changes
 * ({@code getChangesAsSpecifiedByRules}). Measured against kernel 30.8.1, recorded as
 * {@code KERNEL-FINDINGS} KF277.
 *
 * <p><b>Why a lock is warranted rather than "the code obviously calls it".</b> Neither derivation's checker is
 * reachable from a {@code DocumentModelConsistencyRule}, so the classes below are unobservable through
 * {@code checkConsistency} however the model is shaped. The exclusivity is measured here rather than argued:
 * every model in this class is consistency-CLEAN, which is what makes the diagnostic the only signal there is.
 *
 * <p><b>The positive control is the load-bearing test, not a courtesy.</b> "The derivation reports one problem
 * per rule that is not a dynamic enumeration in form" and "the derivation errors on everything" predict the
 * same thing on a suite of ordinary rules, so a grid of malformed rows alone cannot tell them apart — that is
 * the instrument confound. {@link #theCanonicalFormIsAcceptedAndItsConstantValuesAreExtracted()} builds a rule
 * the derivation ACCEPTS, and its extracted values are what discriminate.
 */
class RuleCollectionDerivationDiagnosticLawsTest {

    private static final String BASE = RuntimeLaws.model("models/order.dm.json");

    /** The canonical dyn-enum form on {@code /Order/ProductCode}: the gate, then one {@code != constant}. */
    private static final String CANONICAL =
            "FieldFilled(/Order/ProductCode) And [/Order/ProductCode] != \"PREMIUM\"";

    private static KernelAdapter opened(String modelJson) {
        assertEquals(List.of(), RuntimeLaws.consistencyMessages(modelJson),
                "every model here must be consistency-clean — that is what makes the derivation the only signal");
        return KernelAdapter.ofJson(modelJson);
    }

    private static String oneRule(String ruleName, String errorRelPath, String condition) {
        return RuntimeLaws.withRule(BASE, "/Order", ruleName, errorRelPath, "E_" + ruleName, condition);
    }

    private static List<String> codesOf(List<Diagnostic> diagnostics) {
        return diagnostics.stream().map(d -> d.code().orElse("<none>")).toList();
    }

    @Test
    void theCanonicalFormIsAcceptedAndItsConstantValuesAreExtracted() {
        var oneValue = opened(oneRule("Kind", "../ProductCode", CANONICAL)).dynamicEnumerations();
        assertEquals(List.of(), codesOf(oneValue.diagnostics()));
        assertEquals(java.util.Map.of("/Order/ProductCode", List.of("PREMIUM")), oneValue.valuesByField());

        // A second calculation operation appends its constant, in condition order — the reason valuesByField
        // is a list and not a set.
        var twoValues = opened(oneRule("Kind2", "../ProductCode",
                CANONICAL + " And [/Order/ProductCode] != \"BASIC\"")).dynamicEnumerations();
        assertEquals(List.of(), codesOf(twoValues.diagnostics()));
        assertEquals(java.util.Map.of("/Order/ProductCode", List.of("PREMIUM", "BASIC")),
                twoValues.valuesByField());
    }

    /**
     * One row per way of being malformed in the dyn-enum shape, each drawing its OWN class — which is what
     * makes the channel worth surfacing at all. A single blanket code would carry no more information than the
     * empty {@code valuesByField} already does.
     */
    @Test
    void eachWayOfBeingMalformedInTheDynEnumShapeDrawsItsOwnClass() {
        record Row(String name, String errorRelPath, String condition, String code) {
        }
        List<Row> rows = List.of(
                new Row("Gate", "../ProductCode", "FieldFilled(/Order/ProductCode)",
                        "MVK_DYNENUM_INVALID_GATE"),
                new Row("CalcOp", "../ProductCode",
                        "FieldFilled(/Order/ProductCode) And FieldFilled(/Order/CustomerName)",
                        "MVK_DYNENUM_INVALID_CALCOP"),
                new Row("CalcOpOp", "../ProductCode",
                        "FieldFilled(/Order/ProductCode) And [/Order/ProductCode] == \"PREMIUM\"",
                        "MVK_DYNENUM_INVALID_CALCOP_OP"),
                new Row("WrongField", "../ProductCode",
                        "FieldFilled(/Order/CustomerName) And [/Order/ProductCode] != \"PREMIUM\"",
                        "MVK_ERROR_WRONG_DYNENUM_FIELD"),
                // /Order/UnitPrice is maxFractionalDigits: 2 — the shape admits only an unrestricted String,
                // an enumeration, or a NUMBER with no fractional digits and no bounds.
                new Row("BadType", "../UnitPrice",
                        "FieldFilled(/Order/UnitPrice) And [/Order/UnitPrice] != 5",
                        "MVK_DYNENUM_INVALID_TYPE"),
                // With no safe guard the set of secured operands is EMPTY, so any field on the right of the
                // comparison is unsecured — a constant is the only legal right side in that shape.
                new Row("Unsecured", "../ProductCode",
                        "FieldFilled(/Order/ProductCode) And [/Order/ProductCode] != [/Order/CustomerName]",
                        "MVK_DYNENUM_INVALID_FIELD_REFERENCE"));

        for (Row row : rows) {
            var derived = opened(oneRule(row.name(), row.errorRelPath(), row.condition()))
                    .dynamicEnumerations();
            assertEquals(List.of(row.code()), codesOf(derived.diagnostics()), row.name());
            assertEquals(java.util.Map.of(), derived.valuesByField(), row.name());
        }
    }

    /**
     * The count is per RULE. Reading it as a model-level verdict is the misuse this block invites, and the
     * mixed row is the shape a12-kernel-lean's acceptance condition names: one canonical rule beside one
     * malformed in that shape yields both the extracted values AND the one class.
     */
    @Test
    void theDiagnosticCountScalesWithTheRuleCollectionRatherThanTheModel() {
        String twoOrdinary = RuntimeLaws.withRule(
                oneRule("KindA", "../ProductCode", "FieldFilled(/Order/ProductCode)"),
                "/Order", "KindB", "../CustomerName", "E_KindB", "FieldFilled(/Order/CustomerName)");
        assertEquals(List.of("MVK_DYNENUM_INVALID_GATE", "MVK_DYNENUM_INVALID_GATE"),
                codesOf(opened(twoOrdinary).dynamicEnumerations().diagnostics()));

        String mixed = RuntimeLaws.withRule(oneRule("KindC", "../ProductCode", CANONICAL),
                "/Order", "KindD", "../CustomerName", "E_KindD",
                "FieldFilled(/Order/CustomerName) And [/Order/CustomerName] == \"ACME\"");
        var derived = opened(mixed).dynamicEnumerations();
        assertEquals(List.of("MVK_DYNENUM_INVALID_CALCOP_OP"), codesOf(derived.diagnostics()));
        assertEquals(java.util.Map.of("/Order/ProductCode", List.of("PREMIUM")), derived.valuesByField());
    }

    /**
     * The collection-wide call leaves the rule UNATTRIBUTED, because the kernel's own problem names a rule
     * only where its message does. The per-rule channel is the attributable question, and stamping the answer
     * is the whole reason it exists as a second method.
     */
    @Test
    void onlyThePerRuleChannelAttributesTheClassToARule() {
        String model = oneRule("Kind7", "../ProductCode", "FieldFilled(/Order/ProductCode)");
        var adapter = opened(model);

        List<Diagnostic> collectionWide = adapter.dynamicEnumerations().diagnostics();
        assertEquals(1, collectionWide.size());
        assertEquals(Optional.empty(), Optional.ofNullable(collectionWide.get(0).where().rule()));

        List<Diagnostic> perRule = adapter.dynamicEnumerationDiagnostics("/Order/Kind7").diagnostics();
        assertEquals(List.of("MVK_DYNENUM_INVALID_GATE"), codesOf(perRule));
        assertEquals("/Order/Kind7", perRule.get(0).where().rule());
    }

    /**
     * The OTHER half of the reachability gap, behind a third facade method: a rule that narrows a repeatable
     * group's repeatability to exactly 1. Both spellings of "1" reach it — {@code > 1} directly, {@code >= 2}
     * after the checker's decrement — and {@code > 2} is the negative control, a legal narrowing that reports
     * nothing. The error entity is the GROUP, because the checker only visits a BARE comparison and the
     * consistency gate separately requires the error entity to appear in the condition.
     */
    @Test
    void aRuleNarrowingARepeatableGroupToOneIsRefusedOnBothItsSpellings() {
        record Row(String name, String comparison, List<String> codes) {
        }
        List<Row> rows = List.of(
                new Row("Gt1", "> 1", List.of("MVK_MAX_GROUP_RESTRICTION_TO_ONE")),
                new Row("Ge2", ">= 2", List.of("MVK_MAX_GROUP_RESTRICTION_TO_ONE")),
                new Row("Gt2", "> 2", List.of()));

        for (Row row : rows) {
            String model = oneRule(row.name(), "../Items",
                    "NumberOfFilledGroups(/Order/Items*) " + row.comparison());
            assertEquals(row.codes(), codesOf(opened(model).ruleDrivenModelChangeProblems()), row.name());
        }
    }

    /**
     * The class FAMILIES do not cross: no model reaches a restriction class through the dyn-enum derivation or
     * a dyn-enum class through the model-change one, which is why flipping one call's reporter — the change the
     * report proposed — would have surfaced neither the other's classes. The two channels are not exclusive at
     * the MODEL level, though, and that is worth pinning rather than assuming: the dyn-enum derivation checks
     * every rule in the collection, so a model whose only rule narrows a group draws BOTH a restriction class
     * and a dyn-enum one. The dyn-enum class there is {@code MVK_DYNENUM_INVALID_TYPE} rather than the
     * {@code _GATE} its bare-comparison condition would suggest, because the shape's field-type check runs
     * before its form check and the rule's error entity is a group, not a field.
     */
    @Test
    void theTwoDerivationsClassFamiliesNeverCrossThoughOneModelCanTripBoth() {
        var dynEnumOnly = opened(oneRule("Kind9", "../ProductCode", "FieldFilled(/Order/ProductCode)"));
        assertEquals(List.of("MVK_DYNENUM_INVALID_GATE"),
                codesOf(dynEnumOnly.dynamicEnumerations().diagnostics()));
        assertEquals(List.of(), codesOf(dynEnumOnly.ruleDrivenModelChangeProblems()));

        var both = opened(oneRule("KindH", "../Items", "NumberOfFilledGroups(/Order/Items*) > 1"));
        assertEquals(List.of("MVK_MAX_GROUP_RESTRICTION_TO_ONE"),
                codesOf(both.ruleDrivenModelChangeProblems()));
        assertEquals(List.of("MVK_DYNENUM_INVALID_TYPE"),
                codesOf(both.dynamicEnumerations().diagnostics()));
    }
}
