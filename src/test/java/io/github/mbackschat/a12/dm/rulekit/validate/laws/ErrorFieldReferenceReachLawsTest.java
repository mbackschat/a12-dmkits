package io.github.mbackschat.a12.dm.rulekit.validate.laws;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.LinkedHashMap;
import java.util.Map;

import org.junit.jupiter.api.Test;

import io.github.mbackschat.a12.dm.modelkit.DocumentModelDraft;
import io.github.mbackschat.a12.dm.modelkit.FieldKind;
import io.github.mbackschat.a12.dm.rulekit.validate.ValidationResult;

/**
 * WHAT counts as a condition referencing its error field — the reach of {@code MVK_ERROR_FIELD_NOT_REFERENCED}.
 *
 * <p>{@link ErrorFieldRelativePathLawsTest} measured that a resolvable error-field path is necessary and not
 * sufficient, on one row: a condition naming a sibling draws this code. That row says the gate EXISTS and
 * says nothing about what satisfies it, which is the half a checker needs — because the checker must decide
 * the question for every condition it reads, and every spelling it fails to recognise as a reference becomes
 * a refusal of a model A12 admits.
 *
 * <p>So the rows below vary the SPELLING of the reference with the target held fixed: a short name, an
 * absolute path, a wildcard step, the enclosing group rather than the field, a mention inside a filter, and a
 * mention under a negated operator. Each ADMITTED row is one an implementation must not report.
 *
 * <p><b>Two rows bound the containment relation and they run opposite ways.</b> Naming the GROUP a target
 * field sits in reaches it, so comparing the target's own path is too strict; naming a FIELD inside a target
 * group does not, so treating containment as symmetric is too loose. The relation is "the reference is the
 * target or an ancestor of it".
 *
 * <p><b>And a reference inside a {@code Having} filter does not count</b>, which is the row an implementation
 * reading condition TEXT cannot act on: the names a filter contributes are indistinguishable from operand
 * names once the text is scanned. Treating them as references is the direction that stays silent, so that is
 * what the portable checker does — an under-report it states rather than a refusal it guesses.
 *
 * <p>Own domain (a parcel shipment).
 */
class ErrorFieldReferenceReachLawsTest {

    private static final String NOT_REFERENCED = "MVK_ERROR_FIELD_NOT_REFERENCED";

    private static final String SHIPMENT = DocumentModelDraft.named("parcel-shipment").locale("en_US")
            .rootGroup("Shipment", s -> s
                    .field("Carrier", FieldKind.STRING)
                    .group("Customs", c -> c.field("Declaration", FieldKind.STRING))
                    .group("Parcels", p -> p.repeatable(9)
                            .field("Weight", FieldKind.NUMBER)
                            // A second numeric field, so the HAVING row can aggregate over something that
                            // is NOT the error field and mention the target only inside the filter.
                            .field("Volume", FieldKind.NUMBER)
                            .field("Contents", FieldKind.STRING)))
            .build().toJson();

    /** The kernel's verdict on the rule (`/Shipment`, [errorFieldRelPath], [condition]). */
    private static String verdict(String errorFieldRelPath, String condition) {
        ValidationResult r = KernelLaws.checkModel(SHIPMENT, "/Shipment", errorFieldRelPath, condition);
        return r.ok() ? KernelLaws.ADMITTED : r.diagnostics().stream()
                .map(d -> d.code().orElse("(no code: " + d.summary() + ")")).distinct()
                .reduce((a, b) -> a + "," + b).orElse("?");
    }

    /** The spellings of a reference to a ROOT-level error field, with the target the same in every row. */
    @Test
    void whichSpellingsReachAFlatErrorField() {
        Map<String, String> actual = new LinkedHashMap<>();
        actual.put("the bare name — the control", verdict("../Carrier", "FieldFilled(Carrier)"));
        actual.put("an ABSOLUTE path to the same field",
                verdict("../Carrier", "FieldFilled(/Shipment/Carrier)"));
        actual.put("a sibling field instead", verdict("../Carrier", "FieldFilled(Customs/Declaration)"));
        // Does an unresolvable operand pre-empt this gate, or co-fire with it? A checker has to know, since
        // it judges both and would otherwise report a second fix for one mistake — or miss one.
        actual.put("a condition naming nothing that resolves",
                verdict("../Carrier", "FieldFilled(NoSuchField)"));
        actual.put("the target in the SECOND arm of an Or",
                verdict("../Carrier", "FieldFilled(Customs/Declaration) Or FieldFilled(Carrier)"));
        actual.put("the target under a NEGATED operator",
                verdict("../Carrier", "FieldNotFilled(Carrier)"));
        // The self-reference: it names the rule's own group, which the error field must lie under. rulekit's
        // builder treats it as satisfying the gate, so it is measured here rather than inherited — a checker
        // that did not recognise it would refuse the model this row admits.
        actual.put("the rule's own group via the self-reference",
                verdict("../Carrier", "GroupFilled(RuleGroup)"));
        actual.put("the target as a comparison operand",
                verdict("../Carrier", "[/Shipment/Carrier] == \"DHL\""));

        Map<String, String> expected = new LinkedHashMap<>();
        expected.put("the bare name — the control", KernelLaws.ADMITTED);
        expected.put("an ABSOLUTE path to the same field", KernelLaws.ADMITTED);
        expected.put("a sibling field instead", NOT_REFERENCED);
        expected.put("a condition naming nothing that resolves", "MVK_INVALID_ENTITY");
        expected.put("the target in the SECOND arm of an Or", KernelLaws.ADMITTED);
        expected.put("the target under a NEGATED operator", KernelLaws.ADMITTED);
        expected.put("the rule's own group via the self-reference", KernelLaws.ADMITTED);
        expected.put("the target as a comparison operand", KernelLaws.ADMITTED);
        assertEquals(expected, actual, "which spellings reach a flat error field");
    }

    /**
     * …and the spellings that reach a field inside a REPEATABLE group, where a path can carry a wildcard.
     *
     * <p>This is the carrier that decides whether an implementation may compare resolved paths at all: if a
     * starred spelling reaches the target, the comparison has to strip the star, and if it does not, stripping
     * it would admit a model A12 refuses.
     */
    @Test
    void whichSpellingsReachAnErrorFieldInsideARepeatableGroup() {
        Map<String, String> actual = new LinkedHashMap<>();
        actual.put("the unstarred path — the control",
                verdict("../Parcels/Weight", "FieldFilled(Parcels/Weight)"));
        actual.put("a STARRED path to the same field",
                verdict("../Parcels/Weight", "AtLeastOneFieldFilled(Parcels*/Weight)"));
        actual.put("a sibling field in the same group",
                verdict("../Parcels/Weight", "FieldFilled(Parcels/Contents)"));
        actual.put("the enclosing GROUP rather than the field",
                verdict("../Parcels/Weight", "GroupFilled(Parcels)"));
        actual.put("a GROUP as the error target, named itself",
                verdict("../Parcels", "GroupFilled(Parcels)"));
        actual.put("a GROUP as the error target, with a field INSIDE it named",
                verdict("../Parcels", "FieldFilled(Parcels/Weight)"));
        actual.put("the target inside a HAVING filter only",
                verdict("../Parcels/Weight",
                        "Sum(Parcels*/Volume Having [Parcels/Weight] > 0) > 0"));

        Map<String, String> expected = new LinkedHashMap<>();
        expected.put("the unstarred path — the control", KernelLaws.ADMITTED);
        expected.put("a STARRED path to the same field", KernelLaws.ADMITTED);
        expected.put("a sibling field in the same group", NOT_REFERENCED);
        // ADMITTED, and the row a path comparison would get wrong: naming the group the error field sits
        // in satisfies the gate, so an implementation matching the field's own path refuses a legal model.
        expected.put("the enclosing GROUP rather than the field", KernelLaws.ADMITTED);
        expected.put("a GROUP as the error target, named itself", KernelLaws.ADMITTED);
        // The containment relation runs ONE WAY: an ancestor of the target reaches it, a descendant does
        // not. Both directions are needed — the ancestor row says a path comparison is too strict, this one
        // says a containment test in both directions would be too loose.
        expected.put("a GROUP as the error target, with a field INSIDE it named", NOT_REFERENCED);
        // A filter's own references do NOT satisfy the gate, which is the boundary on the other side: the
        // reference has to be an operand of the condition, not a term inside a `Having`.
        expected.put("the target inside a HAVING filter only", NOT_REFERENCED);
        assertEquals(expected, actual, "which spellings reach an error field inside a repeatable group");
    }

    /**
     * What the SELF-REFERENCE reaches — the rows the grid above could not separate.
     *
     * <p>Its one self-reference row stands alone in its condition, so "the self-reference satisfies the gate"
     * and "a condition naming no entity at all is not judged" predict the same ADMITTED and nothing in the
     * set tells them apart. A portable checker built on the second reading refuses every rule that pairs the
     * self-reference with an ordinary operand — which two models in the reference corpus do, and the Kernel
     * builds both (`adapter.laws.CheckerSilenceOnCleanModelsTest`, 2026-09-13).
     *
     * <p>So the first row puts the self-reference BESIDE the exact operand the grid above measured as NOT
     * reaching, changing one thing from that row. The second asks how far its reach extends: the rule's own
     * group is an ancestor of everything below it, so a target two levels down decides whether the reach is
     * the subtree or only the group's own fields.
     */
    @Test
    void whatTheRulesOwnGroupReferenceReaches() {
        Map<String, String> actual = new LinkedHashMap<>();
        actual.put("a non-reaching operand ALONE — the control from the grid above",
                verdict("../Carrier", "FieldFilled(Customs/Declaration)"));
        actual.put("the same operand WITH the self-reference beside it",
                verdict("../Carrier", "GroupFilled(RuleGroup) And FieldFilled(Customs/Declaration)"));
        actual.put("the self-reference alone, target two levels down",
                verdict("../Customs/Declaration", "GroupFilled(RuleGroup)"));
        actual.put("the self-reference beside a non-reaching operand, target two levels down",
                verdict("../Customs/Declaration", "GroupFilled(RuleGroup) And FieldFilled(Carrier)"));

        Map<String, String> expected = new LinkedHashMap<>();
        expected.put("a non-reaching operand ALONE — the control from the grid above", NOT_REFERENCED);
        expected.put("the same operand WITH the self-reference beside it", KernelLaws.ADMITTED);
        expected.put("the self-reference alone, target two levels down", KernelLaws.ADMITTED);
        expected.put("the self-reference beside a non-reaching operand, target two levels down",
                KernelLaws.ADMITTED);
        assertEquals(expected, actual, "what the rule's own group reference reaches");
    }

    /**
     * A condition that READS A REPETITION pre-empts this gate entirely — {@code MVK_NO_WILDCARD} is reported
     * instead, and this class does not co-fire with it.
     *
     * <p><b>Found by a differential rather than by reading, which is why it is here.</b> The mutation sweep
     * repointed each committed model's first rule at an unreferenced field; seven models answered
     * {@code MVK_NO_WILDCARD} where the checker's reference family said this class, and every one of the seven
     * had a rule aggregating over a repeatable group. No grid in this file could have shown it: every row
     * above holds the condition flat, so "the error field is unreferenced" and "the condition reads no
     * repetition" were one thing across all of them — the constant no control inside the set can catch.
     *
     * <p>The two controls are what make it a precedence claim rather than an observation: remove the
     * repetition read and this class returns, restore the reference and the model is admitted. So the
     * variable is the repetition, not the spelling.
     */
    @Test
    void aRepetitionReadPreEmptsTheUnreferencedTargetClass() {
        Map<String, String> actual = new LinkedHashMap<>();
        actual.put("flat unreferenced target | UNSTARRED repetition read",
                verdict("../Carrier", "FieldFilled(Parcels/Weight)"));
        actual.put("flat unreferenced target | STARRED aggregate over the same list",
                verdict("../Carrier", "Sum(Parcels*/Weight) > 0"));
        actual.put("flat unreferenced target | nothing repeating",
                verdict("../Carrier", "FieldFilled(Customs/Declaration)"));
        actual.put("flat REFERENCED target | UNSTARRED repetition read",
                verdict("../Carrier", "FieldFilled(Carrier) And FieldFilled(Parcels/Weight)"));
        actual.put("flat REFERENCED target | STARRED aggregate",
                verdict("../Carrier", "FieldFilled(Carrier) And Sum(Parcels*/Weight) > 0"));

        Map<String, String> expected = new LinkedHashMap<>();
        expected.put("flat unreferenced target | UNSTARRED repetition read", "MVK_NO_WILDCARD");
        expected.put("flat unreferenced target | STARRED aggregate over the same list", NOT_REFERENCED);
        expected.put("flat unreferenced target | nothing repeating", NOT_REFERENCED);
        expected.put("flat REFERENCED target | UNSTARRED repetition read", "MVK_NO_WILDCARD");
        expected.put("flat REFERENCED target | STARRED aggregate", KernelLaws.ADMITTED);
        assertEquals(expected, actual, "which class an unpinned flat target draws");
    }
}
