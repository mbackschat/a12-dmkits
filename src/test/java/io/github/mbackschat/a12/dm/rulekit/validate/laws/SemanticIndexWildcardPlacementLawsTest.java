package io.github.mbackschat.a12.dm.rulekit.validate.laws;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

import org.junit.jupiter.api.Test;

import io.github.mbackschat.a12.dm.ModelDrafts;
import io.github.mbackschat.a12.dm.core.KernelCodeRefusal;
import io.github.mbackschat.a12.dm.rulekit.dsl.BoundModel;
import io.github.mbackschat.a12.dm.rulekit.dsl.Condition;
import io.github.mbackschat.a12.dm.rulekit.dsl.Dsl;
import io.github.mbackschat.a12.dm.rulekit.dsl.FirstFilledOperand;
import io.github.mbackschat.a12.dm.rulekit.dsl.GroupSemanticIndexValue;
import io.github.mbackschat.a12.dm.rulekit.validate.ValidationResult;

/**
 * KERNEL-FINDINGS KF351 — a star beside a semantic index is judged against the INDEX GROUP, the nearest group
 * on the entity's own chain that declares an index field ({@code CheckEntityUtils.getIndexGroup}), and not
 * against the entity the index is written on.
 *
 * <p>With no index group the index is {@code MVK_NO_INDEX_FIELD}. A star on the index group's own level is
 * {@code MVK_SEMANTIC_INDEX_AND_WILDCARD}, and a star above it {@code MVK_SEMANTIC_INDEX_BELOW_WILDCARD}. A star
 * BELOW it is admitted ({@code Packages/Parcels*} indexed on {@code Packages}), and it is the index that binds
 * the unstarred index group: without it the same scope is {@code MVK_NO_WILDCARD}. Measured on two
 * carriers, {@code FirstFilledValue} and {@code NoGroupFilled}, because the index check runs while A12 reads
 * the operand and so is expected to be carrier-blind; each carrier carries its own rows rather than borrow it.
 *
 * <p>rulekit refused every star beside an index, which made the admitted shape unauthorable and, on read,
 * unreadable. Its index handles never see the model, so they now leave the star to the kernel, and the one
 * model-bound entry point refuses exactly where A12 must, uncoded: a key, containment or iteration gate
 * answers ahead of both star classes (KF351), and the record cannot see a key's applicability.
 *
 * <p>Own domain: {@link ModelDrafts#shipping()}.
 */
class SemanticIndexWildcardPlacementLawsTest {

    private static final String MODEL = ModelDrafts.shipping().toJson();

    @Test
    void theStarIsJudgedAgainstTheIndexGroup_onBothCarriers() {
        Map<String, String> expected = new LinkedHashMap<>();
        // a star below the index group: admitted, a group operand and a field operand alike
        expected.put("FirstFilledValue(Packages/Parcels* For \"P1\") == [Title]", ADMITTED);
        expected.put("FirstFilledValue(Packages/Parcels*/Label For \"P1\", Title) == [Title]", ADMITTED);
        expected.put("NoGroupFilled(Packages/Parcels* For \"P1\")", ADMITTED);
        // on the index group's own level
        expected.put("FirstFilledValue(Packages* For \"P1\") == [Title]", AND_WILDCARD);
        expected.put("FirstFilledValue(Packages*/Carrier For \"P1\", Title) == [Title]", AND_WILDCARD);
        expected.put("NoGroupFilled(Packages* For \"P1\")", AND_WILDCARD);
        // above it
        expected.put("FirstFilledValue(Regions*/Stores For \"S1\") == [Title]", BELOW_WILDCARD);
        expected.put("FirstFilledValue(Regions*/Stores/Manager For \"S1\", Title) == [Title]", BELOW_WILDCARD);
        expected.put("NoGroupFilled(Regions*/Stores For \"S1\")", BELOW_WILDCARD);
        // no index group on the chain at all
        expected.put("FirstFilledValue(Lines* For \"L1\") == [Title]", NO_INDEX_FIELD);
        expected.put("FirstFilledValue(Lines*/Sku For \"L1\", Title) == [Title]", NO_INDEX_FIELD);
        expected.put("NoGroupFilled(Lines* For \"L1\")", NO_INDEX_FIELD);
        // the controls: what binds Packages without its star is the index, and the rule's own shape is legal
        expected.put("NoGroupFilled(Packages/Parcels*)", "MVK_NO_WILDCARD");
        expected.put("FirstFilledValue(Packages*/Parcels*/Label, Title) == [Title]", ADMITTED);
        // two stars, one variable each: dropping the index group's star alone admits the first, and the star on
        // the index group's level answers ahead of the one above it (KF404)
        expected.put("NoGroupFilled(Packages*/Parcels* For \"P1\")", AND_WILDCARD);
        expected.put("NoGroupFilled(Regions*/Stores* For \"S1\")", AND_WILDCARD);

        Map<String, String> actual = new LinkedHashMap<>();
        expected.keySet().forEach(condition -> actual.put(condition, verdict(condition)));
        assertEquals(expected, actual);
    }

    /**
     * The star above the index group is refused even where the rule's own iteration already binds that level, so
     * it is not a redundant star the kernel tolerates: from a rule declared in {@code Regions}, the one star is
     * the only difference between the two rows.
     */
    @Test
    void aStarAboveTheIndexGroupIsRefusedWhereTheRuleBindsThatLevel() {
        Map<String, String> expected = new LinkedHashMap<>();
        expected.put("NoGroupFilled(/Order/Regions*/Stores For \"S1\")", BELOW_WILDCARD);
        expected.put("NoGroupFilled(/Order/Regions/Stores For \"S1\")", ADMITTED);

        Map<String, String> actual = new LinkedHashMap<>();
        expected.keySet().forEach(condition -> actual.put(condition, verdict(
                KernelLaws.checkModel(MODEL, "/Order/Regions", "../RegionName",
                        "FieldFilled(RegionName) And " + condition))));
        assertEquals(expected, actual);
    }

    @Test
    void rulekitRefusesOnlyWhereTheKernelMust_andCitesNone() {
        BoundModel m = BoundModel.ofJson(MODEL);
        Map<String, String> expected = new LinkedHashMap<>();
        Map<String, Supplier<?>> authored = new LinkedHashMap<>();

        // the model-bound entry point knows the index group
        authored.put("group below", () -> m.firstFilledGroupFor("/Order/Packages/Parcels*", "P1"));
        expected.put("group below", ADMITTED);
        authored.put("group at", () -> m.firstFilledGroupFor("/Order/Packages*", "P1"));
        expected.put("group at", UNCODED);
        authored.put("group above", () -> m.firstFilledGroupFor("/Order/Regions*/Stores", "S1"));
        expected.put("group above", UNCODED);
        authored.put("group keyed by a field, at", () -> m.firstFilledGroupFor("/Order/Packages*",
                m.field("/Order/Title")));
        expected.put("group keyed by a field, at", UNCODED);
        // …and with no index group leaves the index to the kernel, starred or not, as it always has
        authored.put("group without an index group", () -> m.firstFilledGroupFor("/Order/Lines*", "L1"));
        expected.put("group without an index group", ADMITTED);

        // the model-free handles leave every star to the kernel
        authored.put("field below", () -> FirstFilledOperand.of(m.flatten("/Order/Packages/Parcels*/Label"))
                .forIndex("P1"));
        expected.put("field below", ADMITTED);
        authored.put("field at", () -> FirstFilledOperand.of(m.flatten("/Order/Packages*/Carrier")).forIndex("P1"));
        expected.put("field at", ADMITTED);
        authored.put("entity list below",
                () -> new GroupSemanticIndexValue(m.flattenGroup("/Order/Packages/Parcels*"), "P1"));
        expected.put("entity list below", ADMITTED);
        authored.put("entity list at", () -> new GroupSemanticIndexValue(m.flattenGroup("/Order/Packages*"), "P1"));
        expected.put("entity list at", ADMITTED);

        Map<String, String> actual = new LinkedHashMap<>();
        authored.forEach((row, authoring) -> actual.put(row, clientVerdict(authoring)));
        assertEquals(expected, actual);
    }

    /** The admitted shape end to end: authored through the typed surface, rendered, and accepted by A12. */
    @Test
    void theStarBelowTheIndexGroupRoundTripsThroughTheKernel() {
        BoundModel m = BoundModel.ofJson(MODEL);
        Condition title = m.field("/Order/Title").filled();
        Condition firstParcel = title.and(Dsl.firstFilledString(
                m.firstFilledGroupFor("/Order/Packages/Parcels*", "P1")).eq("x"));
        Condition firstLabel = title.and(Dsl.firstFilledString(
                FirstFilledOperand.of(m.flatten("/Order/Packages/Parcels*/Label")).forIndex("P1"),
                FirstFilledOperand.of(m.field("/Order/Title"))).eq("x"));
        Condition noParcel = title.and(Dsl.noGroupFilled(
                new GroupSemanticIndexValue(m.flattenGroup("/Order/Packages/Parcels*"), "P1")));

        for (Condition condition : List.of(firstParcel, firstLabel, noParcel)) {
            assertEquals(KernelLaws.ADMITTED, KernelLaws.verdictModel(MODEL, "/Order", "../Title", condition),
                    () -> KernelLaws.render("/Order", condition));
        }
    }

    private static String clientVerdict(Supplier<?> authoring) {
        try {
            authoring.get();
            return ADMITTED;
        } catch (KernelCodeRefusal refusal) {
            return refusal.code().name();
        } catch (IllegalArgumentException uncoded) {
            return UNCODED;
        }
    }

    private static final String ADMITTED = "admitted";
    private static final String UNCODED = "uncoded";
    private static final String AND_WILDCARD = "MVK_SEMANTIC_INDEX_AND_WILDCARD";
    private static final String BELOW_WILDCARD = "MVK_SEMANTIC_INDEX_BELOW_WILDCARD";
    private static final String NO_INDEX_FIELD = "MVK_NO_INDEX_FIELD";

    /** {@code admitted}, or the COMPLETE emitted code list — a partial list would hide a co-firing gate. */
    private static String verdict(String condition) {
        return verdict(KernelLaws.checkModel(MODEL, "/Order", "../Title", "FieldFilled(Title) And " + condition));
    }

    private static String verdict(ValidationResult r) {
        return r.ok() ? ADMITTED : String.join(" + ", r.diagnostics().stream()
                .map(d -> d.code().orElse("(no code: " + d.summary() + ")")).map(Object::toString).toList());
    }
}
