package io.github.mbackschat.a12.dm.rulekit.validate.laws;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.LinkedHashMap;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import io.github.mbackschat.a12.dm.modelkit.DocumentModelDraft;
import io.github.mbackschat.a12.dm.modelkit.FieldKind;
import io.github.mbackschat.a12.dm.rulekit.validate.ValidationResult;

/**
 * WHERE a repetition wildcard on a NON-REPEATABLE group is refused, and with which class.
 *
 * <p>{@code MVK_INVALID_WILDCARD} is pre-checked in the Java kits and its basis was widened in 2026-08-12
 * from one carrier to two, concluding that "the star gate is carrier-independent after all". A
 * carrier-independence claim is a scope-transfer claim, and two carriers are two carriers — so the positions
 * below are measured rather than inherited, because the portable checker would apply the law to EVERY starred
 * reference it reads and a position where A12 says something else turns that into a wrong class.
 *
 * <p>The discriminating column is the CONTROL: each row is run twice, once over a non-repeatable group and
 * once over a repeatable one, so a slot that bars stars outright is told apart from one that refuses only
 * this group.
 *
 * <p>Own domain (a hotel booking).
 */
class StarredNonRepeatableReachLawsTest {

    private static final String INVALID = "MVK_INVALID_WILDCARD";

    private static final String BOOKING = DocumentModelDraft.named("hotel-booking").locale("en_US")
            .rootGroup("Booking", b -> b
                    .field("Reference", FieldKind.STRING)
                    .group("Guest", g -> g
                            .field("FullName", FieldKind.STRING)
                            .field("Nights", FieldKind.NUMBER))
                    .group("Nights", n -> n.repeatable(14)
                            .field("RoomRate", FieldKind.NUMBER)
                            .field("RoomType", FieldKind.STRING)
                            // A FIXED group inside a repeatable one, so a star can be put on a segment that
                            // is itself under a legitimately starred one.
                            .group("Rate", r -> r.field("Amount", FieldKind.NUMBER)))
                    .group("Extras", e -> e.field("Breakfast", FieldKind.STRING))
                    // The computation carrier's computed field, so the same fixture serves both carriers.
                    .field("Total", FieldKind.NUMBER))
            .build().toJson();

    /**
     * Each position twice: the starred FIXED group `Guest`, then the starred REPEATABLE group `Nights`.
     *
     * <p>Without the second column a slot that refuses every star reads exactly like one that refuses this
     * group, and those two need different implementations.
     */
    @Test
    void whereAStarOnAFixedGroupIsRefusedAndWhatTheSameStarDrawsOnARepeatableOne() {
        Map<String, String> actual = new LinkedHashMap<>();
        actual.put("scalar GroupFilled | FIXED", guarded("GroupFilled(Guest*)"));
        actual.put("scalar GroupFilled | repeatable", guarded("GroupFilled(Nights*)"));
        actual.put("quantifier operand | FIXED", guarded("AllGroupsFilled(Guest*, Extras)"));
        actual.put("quantifier operand | repeatable", guarded("AllGroupsFilled(Nights*, Extras)"));
        actual.put("count operand | FIXED", guarded("NumberOfFilledGroups(Guest*) > 0"));
        actual.put("count operand | repeatable", guarded("NumberOfFilledGroups(Nights*) > 0"));
        actual.put("a starred path PREFIX in a field list | FIXED",
                guarded("AtLeastOneFieldFilled(Guest*/FullName, Extras/Breakfast)"));
        actual.put("a starred path PREFIX in a field list | repeatable",
                guarded("AtLeastOneFieldFilled(Nights*/RoomType, Extras/Breakfast)"));
        actual.put("a starred path prefix in a scalar field slot | FIXED",
                guarded("FieldFilled(Guest*/FullName)"));
        actual.put("a starred path prefix in a scalar field slot | repeatable",
                guarded("FieldFilled(Nights*/RoomType)"));
        actual.put("an aggregate's starred operand | FIXED", guarded("Sum(Guest*/Nights) > 0"));
        actual.put("an aggregate's starred operand | repeatable", guarded("Sum(Nights*/RoomRate) > 0"));

        Map<String, String> expected = new LinkedHashMap<>();
        expected.put("scalar GroupFilled | FIXED", INVALID);
        expected.put("scalar GroupFilled | repeatable", "MVK_NO_WILDCARDS_ALLOWED");
        expected.put("quantifier operand | FIXED", INVALID);
        expected.put("quantifier operand | repeatable", "MVK_NO_WILDCARDS_G_ALLOWED");
        expected.put("count operand | FIXED", INVALID);
        expected.put("count operand | repeatable", "admitted");
        expected.put("a starred path PREFIX in a field list | FIXED", INVALID);
        expected.put("a starred path PREFIX in a field list | repeatable", "admitted");
        expected.put("a starred path prefix in a scalar field slot | FIXED", INVALID);
        expected.put("a starred path prefix in a scalar field slot | repeatable",
                "MVK_NO_WILDCARDS_ALLOWED");
        expected.put("an aggregate's starred operand | FIXED", INVALID);
        expected.put("an aggregate's starred operand | repeatable", "admitted");
        assertEquals(expected, actual, "where a star on a fixed group is refused");
    }

    /**
     * The other things a star can land on, since a checker judges every starred SEGMENT it reads.
     *
     * <p>The two nested rows are the ones a per-reference rule has to get right: the outer star is legitimate
     * and only the inner one is not, so a rule reading the reference as a whole rather than segment by
     * segment answers them wrongly in one direction or the other.
     */
    @Test
    void theOtherSegmentsAStarCanLandOn() {
        Map<String, String> actual = new LinkedHashMap<>();
        actual.put("a star on a FIELD", guarded("FieldFilled(Guest/FullName*)"));
        actual.put("a star on the ROOT group", guarded("GroupFilled(/Booking*)"));
        actual.put("a star on a FIXED group nested inside a repeatable one",
                guarded("Sum(Nights*/Rate*/Amount) > 0"));
        actual.put("…the same with only the legitimate star — the control",
                guarded("Sum(Nights*/Rate/Amount) > 0"));
        actual.put("a star on the inner FIXED group with none on the repeatable one",
                guarded("Sum(Nights/Rate*/Amount) > 0"));
        // Two pre-emption questions a per-reference rule must answer: a reference whose LEAF does not
        // resolve, and a star inside a `Having` filter, which the checker's reference reader also sees.
        actual.put("a starred fixed group whose LEAF does not resolve",
                guarded("FieldFilled(Guest*/NoSuchField)"));
        actual.put("a star on a fixed group inside a HAVING filter",
                guarded("Sum(Nights*/RoomRate Having [Guest*/Nights] > 0) > 0"));

        Map<String, String> expected = new LinkedHashMap<>();
        expected.put("a star on a FIELD", INVALID);
        expected.put("a star on the ROOT group", INVALID);
        expected.put("a star on a FIXED group nested inside a repeatable one", INVALID);
        expected.put("…the same with only the legitimate star — the control", "admitted");
        expected.put("a star on the inner FIXED group with none on the repeatable one", INVALID);
        // The wildcard gate runs BEFORE the entity gate, which is the opposite order from every other pair
        // measured on an operand list: the leaf is genuinely absent and A12 says nothing about it.
        expected.put("a starred fixed group whose LEAF does not resolve", INVALID);
        expected.put("a star on a fixed group inside a HAVING filter", INVALID);
        assertEquals(expected, actual, "the other segments a star can land on");
    }

    /**
     * The two segments the checker's wildcard family deliberately does NOT name, measured so it can.
     *
     * <p>A star is legal only on a repeatable group, and the grids above establish that everything else draws
     * one class — but they only ever star a NAME. A path also has two non-name segments: the relative
     * up-step, and the rule-context entity. A12 has a separate code for each in its own ledger
     * ({@code MVK_INVALID_WILDCARD_REL}, {@code MVK_INVALID_WILDCARD_FOR_RULEGROUP}), which is exactly why
     * the family stayed silent on them rather than assuming the class it knows — a wrong class is a wrong
     * finding even when the rejection is right.
     *
     * <p>Each starred row carries its unstarred control, because a spelling that is refused for a reason
     * unrelated to the star would otherwise read as a wildcard verdict.
     *
     * <p><b>Two controls shared one row name until 2026-09-13</b>, so the nested carrier's control silently
     * overwrote nothing and was itself overwritten by the root-level one — the grid asserted eight rows while
     * reading as nine, and the promise in the paragraph above held for one row less than it claimed. It
     * measures `admitted`, which is what the lost expectation had said; `GridRowKeyGuardTest` now refuses a
     * repeated row name so a grid cannot quietly shrink again.
     */
    @Test
    void whatAStarOnANonNameSegmentDraws() {
        Map<String, String> actual = new LinkedHashMap<>();
        actual.put("a star on the rule-context entity", guarded("GroupFilled(RuleGroup*)"));
        actual.put("…the same unstarred — the control", guarded("GroupFilled(RuleGroup)"));
        actual.put("a star on a leading up-step, from a rule one level down",
                nested("GroupFilled(..*/Extras)"));
        actual.put("…the same step unstarred, one level down — the control",
                nested("GroupFilled(../Extras)"));
        actual.put("a star on a leading up-step with nothing after it", nested("GroupFilled(..*)"));
        actual.put("a starred up-step from a ROOT-level rule", guarded("GroupFilled(..*/Extras)"));
        actual.put("…the same step unstarred, from ROOT — the control", guarded("GroupFilled(../Extras)"));
        actual.put("a star on a MID-PATH up-step", guarded("GroupFilled(Guest/..*/Extras)"));
        actual.put("…the same mid-path step unstarred — the control",
                guarded("GroupFilled(Guest/../Extras)"));

        Map<String, String> expected = new LinkedHashMap<>();
        expected.put("a star on the rule-context entity", "MVK_INVALID_WILDCARD_FOR_RULEGROUP");
        expected.put("…the same unstarred — the control", "admitted");
        expected.put("a star on a leading up-step, from a rule one level down", "MVK_INVALID_WILDCARD_REL");
        expected.put("…the same step unstarred, one level down — the control", "admitted");
        expected.put("a star on a leading up-step with nothing after it", "MVK_INVALID_WILDCARD_REL");
        // The depth check runs FIRST: from a root-level rule the step leaves the model before any wildcard
        // gate sees it, so this row says nothing about the star — which is why the nested carrier exists.
        expected.put("a starred up-step from a ROOT-level rule", "MVK_TOO_MANY_PARENT_GROUPS");
        expected.put("…the same step unstarred, from ROOT — the control", "MVK_TOO_MANY_PARENT_GROUPS");
        // A `..` is only ever a LEADING run: both rows are refused by the parser, starred or not, so there is
        // no mid-path shape for a wildcard gate to judge. Recorded because a checker walking every segment
        // would otherwise look for one.
        expected.put("a star on a MID-PATH up-step", "MVK_UNEXPECTED_TOKEN");
        expected.put("…the same mid-path step unstarred — the control", "MVK_UNEXPECTED_TOKEN");
        assertEquals(expected, actual, "what a star on a non-name segment draws");
    }

    /**
     * …and the COMPUTATION carrier, which the rule rows above say nothing about.
     *
     * <p>A checker reads a computation's operation and its preconditions with the same reader it uses on a
     * rule's condition, so it would apply this law there too — and a carrier is a carrier. Authored as raw
     * model JSON because the typed surface pre-checks exactly this shape and cannot express it.
     */
    @Test
    void theSameStarOnTheComputationCarrier() {
        Map<String, String> actual = new LinkedHashMap<>();
        actual.put("a computation's OPERATION", computed("Sum(Guest*/Nights)", null));
        actual.put("a computation's common PRECONDITION", computed("1.00", "GroupFilled(Guest*)"));
        actual.put("an operation over a legitimately starred repeatable — the control",
                computed("Sum(Nights*/RoomRate)", null));

        Map<String, String> expected = new LinkedHashMap<>();
        expected.put("a computation's OPERATION", INVALID);
        expected.put("a computation's common PRECONDITION", INVALID);
        expected.put("an operation over a legitimately starred repeatable — the control",
                KernelLaws.ADMITTED);
        assertEquals(expected, actual, "the same star on the computation carrier");
    }

    /**
     * The same fixture with one COMPUTATION added, carrying [operation] and, when given, [precondition].
     *
     * <p>Injected into the drafted model rather than hand-written beside it: a hand-written root group is
     * missing members the Kernel's builder dereferences without checking, so it fails to BUILD for reasons
     * that have nothing to do with the row under test — measured, and the failure names none of them.
     */
    private static String computed(String operation, String precondition) {
        try {
            ObjectNode root = (ObjectNode) MAPPER.readTree(BOOKING);
            ObjectNode computation = (ObjectNode) ((ArrayNode) root.path("content").path("modelRoot")
                    .path("rootGroups").get(0).path("Group").path("elements")).addObject();
            computation.put("type", "Computation").put("id", "cstar").put("name", "StayTotal");
            ObjectNode body = computation.putObject("Computation");
            body.put("computedFieldRelPath", "../Total");
            if (precondition != null) {
                body.put("commonPrecondition", precondition);
            }
            body.putArray("computationAlternatives").addObject().put("operation", operation);
            body.putArray("errorMessage").addObject().put("locale", "en_US").put("text", "the stay total");
            return KernelLaws.verdictOf(root.toString());
        } catch (IOException unreadable) {
            throw new UncheckedIOException("the fixture must parse", unreadable);
        }
    }

    private static final com.fasterxml.jackson.databind.ObjectMapper MAPPER =
            new com.fasterxml.jackson.databind.ObjectMapper();

    /** The model-level anchoring: the error field sits outside every repeatable group. */
    private static String guarded(String condition) {
        return verdict("/Booking", "../Reference", "FieldFilled(Reference) And " + condition);
    }

    /**
     * The same rule one level down, so a LEADING {@code ..} resolves instead of leaving the model.
     *
     * <p>A depth overflow is checked before any wildcard gate, so the root-level anchoring cannot measure
     * what a starred up-step draws — it only ever reports the overflow.
     */
    private static String nested(String condition) {
        return verdict("/Booking/Guest", "../FullName", "FieldFilled(FullName) And " + condition);
    }

    /** {@code admitted}, or the COMPLETE emitted code list — a partial list would hide a co-firing gate. */
    private static String verdict(String group, String errorField, String condition) {
        ValidationResult r = KernelLaws.checkModel(BOOKING, group, errorField, condition);
        return r.ok() ? "admitted" : String.join(" + ", r.diagnostics().stream()
                .map(d -> d.code().orElse("(no code)")).toList());
    }
}
