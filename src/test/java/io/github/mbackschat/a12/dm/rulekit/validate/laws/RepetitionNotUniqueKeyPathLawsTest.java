package io.github.mbackschat.a12.dm.rulekit.validate.laws;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.LinkedHashMap;
import java.util.Map;

import org.junit.jupiter.api.Test;

import io.github.mbackschat.a12.dm.modelkit.DocumentModelDraft;
import io.github.mbackschat.a12.dm.modelkit.FieldKind;
import io.github.mbackschat.a12.dm.rulekit.validate.ValidationResult;

/**
 * KERNEL-FINDINGS §9 — <b>which diagnostic class a {@code RepetitionNotUnique} KEY PATH shape draws</b>
 * (a12-kernel-lean SPEC-2026-08-11-04). {@link RepetitionNotUniqueKeyKindLawsTest} owns which field KINDS
 * are admitted; this one owns the paths, and asserts the COMPLETE emitted code list per shape so a class
 * that merely co-fires cannot pass for the class that selects.
 *
 * <p>RNU key admission mostly REUSES the group-list families' vocabulary — {@code MVK_DUPLICATE_PARAM1}
 * for a repeated key, {@code MVK_INVALID_ENTITY} for an unresolvable path, {@code MVK_NO_WILDCARDS_ALLOWED}
 * for a starred one, {@code MVK_NO_WILDCARD} for an error field that leaves a repetition level unpinned.
 * One class is its own, and it is where structurally different shapes <b>collapse</b>:
 * {@code MVK_REPEATABLE_GROUP_MISSING} is drawn by all THREE of a second key in a non-repeatable group,
 * a sole key in a non-repeatable group, and the rule group sitting AT the repeated group rather than above
 * it — the last in both the absolute and the relative key spelling.
 *
 * <p><b>A parallel repeatable group is not part of that collapse and is not one class either.</b> Measured
 * here on an index-free fixture, a second key in a parallel repeatable group draws {@code MVK_NO_WILDCARD}
 * from the parent AND from inside one of the two groups. The distinct {@code MVK_NOT_IN_SAME_PATH} class
 * fires on the index-keyed logistics fixture instead ({@code IterationLawsTest.repetitionNotUniqueKeysMustLieOnOnePath});
 * what makes it reachable there is not established by this table, so no mechanism is claimed for it.
 */
class RepetitionNotUniqueKeyPathLawsTest {

    private static final String CART = DocumentModelDraft.named("cart").locale("en_US")
            .rootGroup("Basket", b -> b
                    .field("BasketRef", FieldKind.STRING)
                    .group("Buyer", buyer -> buyer.field("BuyerEmail", FieldKind.STRING))
                    .group("Lines", l -> l.repeatable(30)
                            .field("Sku", FieldKind.STRING)
                            .field("Variant", FieldKind.STRING)
                            .field("Quantity", FieldKind.NUMBER)
                            .field("LineNote", FieldKind.STRING)
                            // Two DEPTH carriers, added 2026-09-13 for the gate-order grid's nested rows. The
                            // twelve rows of the table below were re-run unchanged after they were added, so
                            // they are inert to every shape that does not name them.
                            .group("Parts", parts -> parts.repeatable(5)
                                    .field("PartCode", FieldKind.STRING)
                                    // A non-repeatable level BELOW the repeatable one, so a key can sit two
                                    // steps under it — the only shape that separates "a repeatable group
                                    // anywhere between" from "the key's own parent is repeatable".
                                    .group("Detail", d -> d.field("DetailNote", FieldKind.STRING)))
                            .group("Meta", meta -> meta.field("MetaNote", FieldKind.STRING)))
                    .group("Coupons", c -> c.repeatable(10)
                            .field("CouponCode", FieldKind.STRING)))
            .build().toJson();

    @Test
    void theKeyPathClassesReuseTheGroupListVocabularyAndCollapseThreeShapesIntoOne() {
        Map<String, String> actual = new LinkedHashMap<>();
        actual.put("two keys in the one repeatable group",
                verdict("/Basket", "../Lines/Sku", "RepetitionNotUnique(Lines/Sku, Lines/Variant)"));
        actual.put("a repeated key",
                verdict("/Basket", "../Lines/Sku", "RepetitionNotUnique(Lines/Sku, Lines/Sku)"));
        actual.put("an unresolvable key path",
                verdict("/Basket", "../Lines/Sku", "RepetitionNotUnique(Lines/Nope)"));
        actual.put("a starred key path",
                verdict("/Basket", "../Lines/Sku", "RepetitionNotUnique(Lines*/Sku)"));
        actual.put("rule group is the repeated group | absolute key",
                verdict("/Basket/Lines", "../Sku", "RepetitionNotUnique(/Basket/Lines/Sku)"));
        actual.put("rule group is the repeated group | relative key",
                verdict("/Basket/Lines", "../Sku", "RepetitionNotUnique(Sku)"));
        actual.put("a non-repeatable error field",
                verdict("/Basket", "../BasketRef", "RepetitionNotUnique(Lines/Sku)"));
        actual.put("a second key in a non-repeatable group",
                verdict("/Basket", "../Lines/Sku", "RepetitionNotUnique(Lines/Sku, Buyer/BuyerEmail)"));
        actual.put("a sole key in a non-repeatable group",
                verdict("/Basket", "../Buyer/BuyerEmail", "RepetitionNotUnique(Buyer/BuyerEmail)"));
        actual.put("a second key in a parallel repeatable group | from the parent",
                verdict("/Basket", "../Lines/Sku", "RepetitionNotUnique(Lines/Sku, Coupons/CouponCode)"));
        actual.put("a second key in a parallel repeatable group | from inside one of them",
                verdict("/Basket/Lines", "../Sku",
                        "RepetitionNotUnique(/Basket/Lines/Sku, /Basket/Coupons/CouponCode)"));
        actual.put("keys naming a sibling rather than the error field",
                verdict("/Basket", "../Lines/LineNote", "RepetitionNotUnique(Lines/Sku)"));

        Map<String, String> expected = new LinkedHashMap<>();
        expected.put("two keys in the one repeatable group", "admitted");
        expected.put("a repeated key", "MVK_DUPLICATE_PARAM1");
        expected.put("an unresolvable key path", "MVK_INVALID_ENTITY");
        expected.put("a starred key path", "MVK_NO_WILDCARDS_ALLOWED");
        expected.put("rule group is the repeated group | absolute key", "MVK_REPEATABLE_GROUP_MISSING");
        expected.put("rule group is the repeated group | relative key", "MVK_REPEATABLE_GROUP_MISSING");
        expected.put("a non-repeatable error field", "MVK_NO_WILDCARD");
        expected.put("a second key in a non-repeatable group", "MVK_REPEATABLE_GROUP_MISSING");
        expected.put("a sole key in a non-repeatable group", "MVK_REPEATABLE_GROUP_MISSING");
        expected.put("a second key in a parallel repeatable group | from the parent", "MVK_NO_WILDCARD");
        expected.put("a second key in a parallel repeatable group | from inside one of them", "MVK_NO_WILDCARD");
        expected.put("keys naming a sibling rather than the error field", "MVK_ERROR_FIELD_NOT_REFERENCED");
        assertEquals(expected, actual, "RepetitionNotUnique key-path admission");
    }

    /**
     * WHICH second key pre-empts the collapse — the carrier, or merely being a second key.
     *
     * <p>The table above leaves one link unmeasured, and a checker turns on exactly that link. The rule group
     * sitting AT the repeated group draws {@code MVK_REPEATABLE_GROUP_MISSING} with a sole key, and the same
     * position draws {@code MVK_NO_WILDCARD} once a second key sits in a parallel repeatable group — so
     * something pre-empts it, and two readings fit every row that exists: a second key of ANY carrier
     * pre-empts, or only a second REPEATABLE carrier does. Both grids agree wherever they overlap, which is
     * why this needs its own rows rather than a re-reading of those.
     *
     * <p>It is a 2x4 within one model, varying the rule group's position against the second key's carrier and
     * holding the error field on the repeated group's own field, so no cell moves a variable the cell beside
     * it does not. The four cells that repeat the table above are kept as controls: they prove this grid
     * reproduces it rather than measuring something adjacent.
     */
    @Test
    void whatPreEmptsTheCollapseIsTheSecondKeysCarrierRatherThanItsExistence() {
        Map<String, String> actual = new LinkedHashMap<>();
        actual.put("above | sole key in the repeated group",
                verdict("/Basket", "../Lines/Sku", "RepetitionNotUnique(Lines/Sku)"));
        actual.put("above | second key in the same repeated group",
                verdict("/Basket", "../Lines/Sku", "RepetitionNotUnique(Lines/Sku, Lines/Variant)"));
        actual.put("above | second key in a non-repeatable group",
                verdict("/Basket", "../Lines/Sku", "RepetitionNotUnique(Lines/Sku, Buyer/BuyerEmail)"));
        actual.put("above | second key in a parallel repeatable group",
                verdict("/Basket", "../Lines/Sku", "RepetitionNotUnique(Lines/Sku, Coupons/CouponCode)"));
        actual.put("at | sole key in the repeated group",
                verdict("/Basket/Lines", "../Sku", "RepetitionNotUnique(Sku)"));
        actual.put("at | second key in the same repeated group",
                verdict("/Basket/Lines", "../Sku", "RepetitionNotUnique(Sku, Variant)"));
        actual.put("at | second key in a non-repeatable group",
                verdict("/Basket/Lines", "../Sku", "RepetitionNotUnique(Sku, /Basket/Buyer/BuyerEmail)"));
        actual.put("at | second key in a parallel repeatable group",
                verdict("/Basket/Lines", "../Sku", "RepetitionNotUnique(Sku, /Basket/Coupons/CouponCode)"));
        actual.put("at | key below a NESTED repeatable group",
                verdict("/Basket/Lines", "../Parts/PartCode", "RepetitionNotUnique(Parts/PartCode)"));
        actual.put("at | key in a NESTED non-repeatable group",
                verdict("/Basket/Lines", "../Meta/MetaNote", "RepetitionNotUnique(Meta/MetaNote)"));
        actual.put("at | key TWO levels below a nested repeatable group",
                verdict("/Basket/Lines", "../Parts/Detail/DetailNote",
                        "RepetitionNotUnique(Parts/Detail/DetailNote)"));
        actual.put("above | TWO keys each lacking a repetition",
                verdict("/Basket", "../Buyer/BuyerEmail", "RepetitionNotUnique(Buyer/BuyerEmail, BasketRef)"));

        Map<String, String> expected = new LinkedHashMap<>();
        expected.put("above | sole key in the repeated group", "admitted");
        expected.put("above | second key in the same repeated group", "admitted");
        expected.put("above | second key in a non-repeatable group", "MVK_REPEATABLE_GROUP_MISSING");
        expected.put("above | second key in a parallel repeatable group", "MVK_NO_WILDCARD");
        expected.put("at | sole key in the repeated group", "MVK_REPEATABLE_GROUP_MISSING");
        expected.put("at | second key in the same repeated group", "MVK_REPEATABLE_GROUP_MISSING");
        expected.put("at | second key in a non-repeatable group", "MVK_NOT_IN_RULEGROUP");
        expected.put("at | second key in a parallel repeatable group", "MVK_NO_WILDCARD");
        expected.put("at | key below a NESTED repeatable group", "admitted");
        expected.put("at | key in a NESTED non-repeatable group", "MVK_REPEATABLE_GROUP_MISSING");
        expected.put("at | key TWO levels below a nested repeatable group", "admitted");
        expected.put("above | TWO keys each lacking a repetition", "MVK_REPEATABLE_GROUP_MISSING");
        assertEquals(expected, actual, "RepetitionNotUnique key gate order");
    }

    /** {@code admitted}, or the COMPLETE emitted code list — a partial list would hide a co-firing gate. */
    private static String verdict(String group, String errorField, String condition) {
        ValidationResult r = KernelLaws.checkModel(CART, group, errorField, condition);
        return r.ok() ? "admitted" : String.join(" + ", r.diagnostics().stream()
                .map(d -> d.code().orElse("(no code)")).toList());
    }
}
