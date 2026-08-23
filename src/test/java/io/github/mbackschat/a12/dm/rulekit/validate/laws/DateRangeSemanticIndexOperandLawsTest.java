package io.github.mbackschat.a12.dm.rulekit.validate.laws;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.stream.Collectors;

import org.junit.jupiter.api.Test;

import io.github.mbackschat.a12.dm.modelkit.DocumentModelDraft;
import io.github.mbackschat.a12.dm.modelkit.FieldKind;
import io.github.mbackschat.a12.dm.rulekit.dsl.BoundModel;
import io.github.mbackschat.a12.dm.rulekit.dsl.Condition;
import io.github.mbackschat.a12.dm.rulekit.dsl.DateRangeIndexedValue;
import io.github.mbackschat.a12.dm.rulekit.dsl.Dsl;
import io.github.mbackschat.a12.dm.rulekit.dsl.DslRenderer;
import io.github.mbackschat.a12.dm.rulekit.validate.DraftRule;
import io.github.mbackschat.a12.dm.rulekit.validate.RuleValidator;
import io.github.mbackschat.a12.dm.rulekit.validate.ValidationResult;

/**
 * KERNEL-FINDINGS §6/§9 — a DATE_RANGE operand may be selected by a <b>semantic index</b>, on every carrier
 * the direct field reaches, and the index's own placement and key-literal rules have their own classes
 * (SPEC-2026-08-23-07).
 *
 * <p><b>What the keyed form buys, and why the direct-only reading is a defect.</b> A starred endpoint operand
 * is refused at EITHER locus, so no wildcard form of it exists; an iterating rule and a keyed lookup are the
 * only two ways to reach a per-row range. A checker that reads the endpoint operand as "a direct field" — the
 * shape our own catalog prose describes — rejects kernel-legal models on four carriers at once.
 *
 * <p>The typed surface could not express this at all before {@code DateRangeRef.forIndex} (DEV-API-GAPS DG38):
 * every other kind's ref had the factory and the range's did not, so the keyed range cell was
 * <em>unrepresentable</em> rather than refused — the type-level form of the same defect.
 *
 * <p>Own domain (e-commerce seasonal promotions).
 */
class DateRangeSemanticIndexOperandLawsTest {

    private static final String ADMITTED = "ADMITTED";
    private static final String SHOP = "/Shop";
    private static final String RUNS = "/Shop/Runs";

    private static final String MODEL = model("promo-index", FieldKind.STRING);
    private static final String NUMBER_KEYED = model("promo-index-num", FieldKind.NUMBER);

    private static final BoundModel M = BoundModel.ofJson(MODEL);
    private static final BoundModel N = BoundModel.ofJson(NUMBER_KEYED);

    private static String model(String name, FieldKind indexKind) {
        return DocumentModelDraft.named(name).locale("en_US")
                .rootGroup("Shop", s -> s
                        .stringField("Reference", f -> { })
                        .stringField("LookupKey", f -> { })
                        .dateField("CheckDate", d -> d.format("yyyy-MM-dd"))
                        .dateRangeField("SeasonWindow", r -> r.format("yyyy-MM-dd").separator("/"))
                        .group("Runs", g -> g.repeatable(20).indexField("RunKey")
                                .field("RunKey", indexKind)
                                .stringField("RunNote", f -> { })
                                .dateRangeField("RunWindow", r -> r.format("yyyy-MM-dd").separator("/"))))
                .build().toJson();
    }

    private static DateRangeIndexedValue keyed() {
        return M.dateRange(RUNS + "/RunWindow").forIndex("spring");
    }

    private static DateRangeIndexedValue fieldKeyed() {
        return M.dateRange(RUNS + "/RunWindow").forIndex(M.field(SHOP + "/LookupKey"));
    }

    /**
     * Four carriers × two key forms × three loci. The third locus is the sharpest: a rule ITERATING the very
     * group the lookup keys into still admits the keyed operand, so the index is not shadowed by iteration.
     */
    @Test
    void aKeyedRangeOperandIsAdmittedOnEveryCarrierAtEveryLocus() {
        Map<String, String> actual = new LinkedHashMap<>();
        for (Map.Entry<String, DateRangeIndexedValue> key
                : Map.of("literal key", keyed(), "field key", fieldKeyed()).entrySet()) {
            for (Map.Entry<String, Condition> carrier : carriers(key.getValue()).entrySet()) {
                String label = key.getKey() + " · " + carrier.getKey();
                actual.put(label + " — form locus", form(carrier.getValue()));
                actual.put(label + " — iterating another group's row", iteratingTheKeyedGroup(carrier.getValue()));
            }
        }

        Map<String, String> expected = actual.keySet().stream()
                .collect(Collectors.toMap(k -> k, k -> ADMITTED, (a, b) -> a, LinkedHashMap::new));
        assertEquals(expected, actual, "the keyed range cell is admitted wherever the direct field is");
    }

    /**
     * The wildcard half, measured for THIS carrier rather than transferred from the construction operator: a
     * starred endpoint operand has no legal locus at all, and a group operand is a different class.
     */
    @Test
    void aStarredEndpointOperandHasNoLegalLocus() {
        String starred = "FieldFilled(/Shop/Reference) And StartOfDateRange(/Shop/Runs*/RunWindow) == [/Shop/CheckDate]";
        String rowStarred = "FieldFilled(/Shop/Runs/RunNote) And "
                + "StartOfDateRange(/Shop/Runs*/RunWindow) == [/Shop/CheckDate]";
        String group = "FieldFilled(/Shop/Reference) And StartOfDateRange(/Shop/Runs) == [/Shop/CheckDate]";

        Map<String, String> actual = new LinkedHashMap<>();
        actual.put("starred, non-iterating locus", raw(MODEL, SHOP, "../Reference", starred));
        actual.put("starred, iterating locus", raw(MODEL, RUNS, "../RunNote", rowStarred));
        actual.put("a GROUP operand", raw(MODEL, SHOP, "../Reference", group));

        Map<String, String> expected = new LinkedHashMap<>();
        expected.put("starred, non-iterating locus", "MVK_NO_WILDCARDS_ALLOWED");
        expected.put("starred, iterating locus", "MVK_NO_WILDCARDS_ALLOWED");
        expected.put("a GROUP operand", "MVK_NO_WILDCARD");
        assertEquals(expected, actual, "no wildcard form of the endpoint operand exists, at either locus");
    }

    /**
     * A field-valued key read from INSIDE the indexed group has its own class. The two admitted twins hold
     * everything else fixed — same locus, same carrier, same lookup — and vary only where the key field lives,
     * which is what makes the refusal a placement rule rather than a resolution failure.
     */
    @Test
    void aKeyFieldInsideTheIndexedGroupIsItsOwnClass() {
        Map<String, String> actual = new LinkedHashMap<>();
        actual.put("key field INSIDE the indexed group", iteratingTheKeyedGroup(
                Dsl.startOfDateRange(M.dateRange(RUNS + "/RunWindow").forIndex(M.field(RUNS + "/RunNote")))
                        .eq(M.date(SHOP + "/CheckDate"))));
        actual.put("key field outside it — twin", iteratingTheKeyedGroup(
                Dsl.startOfDateRange(fieldKeyed()).eq(M.date(SHOP + "/CheckDate"))));
        actual.put("literal key — twin", iteratingTheKeyedGroup(
                Dsl.startOfDateRange(keyed()).eq(M.date(SHOP + "/CheckDate"))));

        Map<String, String> expected = new LinkedHashMap<>();
        expected.put("key field INSIDE the indexed group", "MVK_SEMANTIC_INDEX_CONTAINED_IN_INDEX");
        expected.put("key field outside it — twin", ADMITTED);
        expected.put("literal key — twin", ADMITTED);
        assertEquals(expected, actual, "the key's PLACEMENT is the rule, not whether the key resolves");
    }

    /**
     * The literal key is a QUOTED token whatever the index field's kind, so the quoting in the syntax table is
     * load-bearing. Note the two refusals are different mechanisms: an unquoted numeric literal fails to
     * RESOLVE as an entity, while a quoted literal that does not fit the index field's declaration draws the
     * declaration-format class.
     */
    @Test
    void theLiteralKeyIsQuotedEvenAgainstANumberIndexField() {
        String bare = "FieldFilled(/Shop/Reference) And "
                + "StartOfDateRange(/Shop/Runs/RunWindow For 2) == [/Shop/CheckDate]";
        Map<String, String> actual = new LinkedHashMap<>();
        actual.put("unquoted numeric key", raw(NUMBER_KEYED, SHOP, "../Reference", bare));
        actual.put("the same key quoted", raw(NUMBER_KEYED, SHOP, "../Reference", new DslRenderer().render(
                Dsl.fieldFilled(N.field(SHOP + "/Reference"))
                        .and(Dsl.startOfDateRange(N.dateRange(RUNS + "/RunWindow").forIndex("2"))
                                .eq(N.date(SHOP + "/CheckDate"))))));
        actual.put("a quoted literal the index declaration rejects", raw(NUMBER_KEYED, SHOP, "../Reference",
                new DslRenderer().render(Dsl.fieldFilled(N.field(SHOP + "/Reference"))
                        .and(Dsl.startOfDateRange(N.dateRange(RUNS + "/RunWindow").forIndex("spring"))
                                .eq(N.date(SHOP + "/CheckDate"))))));

        Map<String, String> expected = new LinkedHashMap<>();
        expected.put("unquoted numeric key", "MVK_INVALID_ENTITY");
        expected.put("the same key quoted", ADMITTED);
        expected.put("a quoted literal the index declaration rejects", "MVK_INDEX_VALUE_INVALID");
        assertEquals(expected, actual, "quoting is the syntax; fitting the declaration is a separate gate");
    }

    // ---- carriers and loci -----------------------------------------------------------------------------

    private static Map<String, Condition> carriers(DateRangeIndexedValue keyedRange) {
        Map<String, Condition> carriers = new LinkedHashMap<>();
        carriers.put("extracted endpoint",
                Dsl.startOfDateRange(keyedRange).eq(M.date(SHOP + "/CheckDate")));
        carriers.put("the endpoint's numeric component",
                Dsl.monthFromDate(Dsl.startOfDateRange(keyedRange)).ge(1));
        carriers.put("stored range equality", keyedRange.eq(M.dateRange(SHOP + "/SeasonWindow")));
        carriers.put("the endpoint pair",
                Dsl.startOfDateRange(keyedRange).before(Dsl.endOfDateRange(keyedRange)));
        return carriers;
    }

    private static String form(Condition condition) {
        return verdict(MODEL, SHOP, "../Reference",
                Dsl.fieldFilled(M.field(SHOP + "/Reference")).and(condition));
    }

    /** The sharp locus: the rule iterates the very group the lookup keys into. */
    private static String iteratingTheKeyedGroup(Condition condition) {
        return verdict(MODEL, RUNS, "../RunNote",
                Dsl.fieldFilled(M.field(RUNS + "/RunNote")).and(condition));
    }

    private static String verdict(String json, String group, String errorField, Condition condition) {
        return raw(json, group, errorField, new DslRenderer().render(condition));
    }

    private static String raw(String json, String group, String errorField, String condition) {
        ValidationResult r = new RuleValidator().check(json,
                new DraftRule(group, "IndexProbe", errorField, condition, "INDEX", "ERROR"));
        return r.ok() ? ADMITTED
                : r.diagnostics().stream().map(d -> d.code().orElse("(no code)")).collect(Collectors.joining(","));
    }
}
