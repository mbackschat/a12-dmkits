package io.github.mbackschat.a12.dm.rulekit.validate.laws;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.LinkedHashMap;
import java.util.Map;

import org.junit.jupiter.api.Test;

import io.github.mbackschat.a12.dm.modelkit.DocumentModelDraft;
import io.github.mbackschat.a12.dm.modelkit.FieldKind;
import io.github.mbackschat.a12.dm.rulekit.validate.ValidationResult;

/**
 * WHAT A12 says when a group predicate is handed a FIELD — the reach of {@code MVK_NO_FIELDS_ALLOWED}.
 *
 * <p>The code is {@code erased} in the Java kits — a {@code GroupRef} is not a {@code FieldRef}, so the
 * shape is unauthorable through the typed surface, and the ledger's reason reads "group predicates take
 * groups only". <b>That reason is measured false for five of the eight carriers.</b> The group-list
 * QUANTIFIERS — {@code AllGroupsFilled}, {@code NotAllGroupsFilled}, {@code GroupsNotCollectivelyFilled},
 * {@code AtLeastOneGroupFilled}, {@code NoGroupFilled} — all ADMIT a field operand. Only the count
 * {@code NumberOfFilledGroups} and the two scalars {@code GroupFilled}/{@code GroupNotFilled} refuse one.
 *
 * <p>The measurement matters in two directions at once. The Java erasure is STRICTER than A12 on those five
 * carriers, which is a deliberate-or-not over-strictness the ledger now has to say which it is; and the
 * portable checker, which reads STORED DM-JSON where nothing is typed, must report the class on exactly the
 * three carriers that draw it and stay silent on the five that do not — a family assembled from the code's
 * NAME would refuse five legal shapes.
 *
 * <p>Own domain (a lending library).
 */
class GroupListOperandKindLawsTest {

    private static final String NO_FIELDS = "MVK_NO_FIELDS_ALLOWED";

    private static final String LIBRARY = DocumentModelDraft.named("lending-library").locale("en_US")
            .rootGroup("Loan", l -> l
                    .field("Barcode", FieldKind.STRING)
                    .group("Borrower", b -> b
                            .field("MemberId", FieldKind.STRING)
                            .group("Contact", c -> c.field("Email", FieldKind.STRING)))
                    .group("Branch", b -> b.field("BranchCode", FieldKind.STRING))
                    .group("Items", i -> i.repeatable(30)
                            .field("Title", FieldKind.STRING)
                            .field("DueDays", FieldKind.NUMBER)))
            .rootGroup("Archive", a -> a.field("Shelf", FieldKind.STRING))
            .build().toJson();

    @Test
    void whichGroupCarriersRefuseAFieldOperand() {
        Map<String, String> actual = new LinkedHashMap<>();
        actual.put("all | two groups — the control", guarded("AllGroupsFilled(Borrower, Branch)"));
        actual.put("all | a FIELD beside a group", guarded("AllGroupsFilled(Borrower/MemberId, Branch)"));
        actual.put("all | the field named SECOND", guarded("AllGroupsFilled(Branch, Borrower/MemberId)"));
        actual.put("notAll | a FIELD beside a group",
                guarded("NotAllGroupsFilled(Borrower/MemberId, Branch)"));
        actual.put("notCollectively | a FIELD beside a group",
                guarded("GroupsNotCollectivelyFilled(Borrower/MemberId, Branch)"));
        actual.put("atLeastOne | a FIELD beside a group",
                guarded("AtLeastOneGroupFilled(Borrower/MemberId, Branch)"));
        actual.put("noGroup | a FIELD beside a group", guarded("NoGroupFilled(Borrower/MemberId, Branch)"));
        actual.put("count | a FIELD beside a group",
                guarded("NumberOfFilledGroups(Borrower/MemberId, Branch) > 0"));
        actual.put("scalar GroupFilled | a FIELD", guarded("GroupFilled(Borrower/MemberId)"));
        actual.put("scalar GroupNotFilled | a FIELD", guarded("GroupNotFilled(Borrower/MemberId)"));

        Map<String, String> expected = new LinkedHashMap<>();
        expected.put("all | two groups — the control", "admitted");
        // ADMITTED — the five QUANTIFIERS take a field operand, which the code's name and the Java kits'
        // type-level erasure both say they do not. Only the count and the two scalars below refuse one.
        expected.put("all | a FIELD beside a group", "admitted");
        expected.put("all | the field named SECOND", "admitted");
        expected.put("notAll | a FIELD beside a group", "admitted");
        expected.put("notCollectively | a FIELD beside a group", "admitted");
        expected.put("atLeastOne | a FIELD beside a group", "admitted");
        expected.put("noGroup | a FIELD beside a group", "admitted");
        expected.put("count | a FIELD beside a group", NO_FIELDS);
        expected.put("scalar GroupFilled | a FIELD", NO_FIELDS);
        expected.put("scalar GroupNotFilled | a FIELD", NO_FIELDS);
        assertEquals(expected, actual, "which group carriers refuse a field operand");
    }

    /**
     * What A12 answers on a QUANTIFIER list holding a field — every gate except a kind gate, which is absent.
     *
     * <p>Each row is the same field operand beside a different second operand, so the column that moves is
     * the gate rather than the kind. The containment row is the one with consequences elsewhere: a field
     * beside its own enclosing GROUP is an overlap, so A12's containment relation crosses the field/group
     * boundary and a checker comparing resolved paths by step is right to report it.
     */
    @Test
    void whatAQuantifierListHoldingAFieldDraws() {
        Map<String, String> actual = new LinkedHashMap<>();
        actual.put("a field beside an UNRESOLVABLE operand",
                guarded("AllGroupsFilled(Borrower/MemberId, Nowhere)"));
        actual.put("the SAME field twice", guarded("AllGroupsFilled(Borrower/MemberId, Borrower/MemberId)"));
        actual.put("a field beside a ROOT group", guarded("AllGroupsFilled(Borrower/MemberId, /Archive)"));
        actual.put("a field beside an unstarred REPEATABLE group",
                guarded("AllGroupsFilled(Borrower/MemberId, Items)"));
        actual.put("a field beside its own enclosing group",
                guarded("AllGroupsFilled(Borrower/MemberId, Borrower)"));
        actual.put("a field as the SOLE operand, where the carrier wants two",
                guarded("AllGroupsFilled(Borrower/MemberId)"));

        Map<String, String> expected = new LinkedHashMap<>();
        expected.put("a field beside an UNRESOLVABLE operand", "MVK_INVALID_ENTITY");
        expected.put("the SAME field twice", "MVK_DUPLICATE_PARAM1");
        expected.put("a field beside a ROOT group", "MVK_ROOT_GROUP_REFERENCED");
        expected.put("a field beside an unstarred REPEATABLE group", "MVK_NO_WILDCARD");
        expected.put("a field beside its own enclosing group", "MVK_DUPLICATE_PARAM2");
        expected.put("a field as the SOLE operand, where the carrier wants two", "MVK_PARAMSIZE_INVALID2");
        assertEquals(expected, actual, "what a quantifier list holding a field draws");
    }

    /**
     * …and on the three carriers that DO have a kind gate, where that gate sits among the others.
     *
     * <p>A checker judging several gates has to report the class A12 issues; naming two would present one
     * mistake as two fixes.
     */
    @Test
    void whatPreEmptsTheKindGateOnTheCarriersThatHaveOne() {
        Map<String, String> actual = new LinkedHashMap<>();
        actual.put("count | the same field twice",
                guarded("NumberOfFilledGroups(Borrower/MemberId, Borrower/MemberId) > 0"));
        actual.put("count | a field beside an UNRESOLVABLE operand",
                guarded("NumberOfFilledGroups(Borrower/MemberId, Nowhere) > 0"));
        actual.put("count | a field beside a ROOT group",
                guarded("NumberOfFilledGroups(Borrower/MemberId, /Archive) > 0"));
        actual.put("count | a field beside its own enclosing group",
                guarded("NumberOfFilledGroups(Borrower/MemberId, Borrower) > 0"));
        actual.put("count | a field as the SOLE operand",
                guarded("NumberOfFilledGroups(Borrower/MemberId) > 0"));
        actual.put("scalar GroupFilled | a name that resolves to nothing",
                guarded("GroupFilled(Nowhere)"));
        actual.put("scalar GroupFilled | a field, error field INSIDE the repeatable group",
                inside("GroupFilled(Borrower/MemberId)"));

        Map<String, String> expected = new LinkedHashMap<>();
        expected.put("count | the same field twice", "MVK_DUPLICATE_PARAM1");
        expected.put("count | a field beside an UNRESOLVABLE operand", "MVK_INVALID_ENTITY");
        expected.put("count | a field beside a ROOT group", "MVK_ROOT_GROUP_WITH_OTHER_PARAMETERS");
        expected.put("count | a field beside its own enclosing group", "MVK_DUPLICATE_PARAM2");
        // The one gate the kind gate outruns: a sole FIXED operand would otherwise draw the arity class.
        expected.put("count | a field as the SOLE operand", NO_FIELDS);
        expected.put("scalar GroupFilled | a name that resolves to nothing", "MVK_INVALID_ENTITY");
        expected.put("scalar GroupFilled | a field, error field INSIDE the repeatable group", NO_FIELDS);
        assertEquals(expected, actual, "what pre-empts the kind gate on the carriers that have one");
    }

    /** …and the error field's LOCUS, which re-selects the neighbouring classes on this carrier. */
    @Test
    void theErrorFieldsLocusAndTheKindGate() {
        Map<String, String> actual = new LinkedHashMap<>();
        actual.put("a FIELD beside a group, error field INSIDE the repeatable group",
                inside("AllGroupsFilled(Borrower/MemberId, Branch)"));
        actual.put("a field INSIDE the repeatable group as an operand, unstarred",
                guarded("AllGroupsFilled(Items/Title, Branch)"));
        actual.put("…the same, STARRED", guarded("AllGroupsFilled(Items*/Title, Branch)"));

        Map<String, String> expected = new LinkedHashMap<>();
        expected.put("a FIELD beside a group, error field INSIDE the repeatable group", "admitted");
        // The unstarred row is answered by the WILDCARD gate, not by a kind gate — a field operand inside a
        // repeatable group is the same demand a group operand there makes.
        expected.put("a field INSIDE the repeatable group as an operand, unstarred", "MVK_NO_WILDCARD");
        expected.put("…the same, STARRED", "MVK_NO_WILDCARDS_G_ALLOWED");
        assertEquals(expected, actual, "the error field's locus and the kind gate");
    }

    /** The model-level anchoring: the error field sits outside every repeatable group. */
    private static String guarded(String condition) {
        return verdict("/Loan", "../Barcode", "FieldFilled(Barcode) And " + condition);
    }

    /** …and the same condition with the error field INSIDE the repeatable group. */
    private static String inside(String condition) {
        return verdict("/Loan", "../Items/Title", "FieldFilled(Items/Title) And " + condition);
    }

    /** {@code admitted}, or the COMPLETE emitted code list — a partial list would hide a co-firing gate. */
    private static String verdict(String group, String errorField, String condition) {
        ValidationResult r = KernelLaws.checkModel(LIBRARY, group, errorField, condition);
        return r.ok() ? "admitted" : String.join(" + ", r.diagnostics().stream()
                .map(d -> d.code().orElse("(no code)")).toList());
    }
}
