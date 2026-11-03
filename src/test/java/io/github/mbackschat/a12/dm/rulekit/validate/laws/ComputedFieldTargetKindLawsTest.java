package io.github.mbackschat.a12.dm.rulekit.validate.laws;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.LinkedHashMap;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import io.github.mbackschat.a12.dm.modelkit.DocumentModelDraft;
import io.github.mbackschat.a12.dm.modelkit.FieldKind;
import io.github.mbackschat.a12.dm.modelkit.SortOrder;

/**
 * What a computation may compute INTO, and the rule-side mirror that says the answer is not the same.
 *
 * <p>Both slots hold a relative path to a declared element and both resolve the same way, so an
 * implementation naturally judges them alike — and A12 does not. A rule may attach its error to a GROUP
 * ({@link ErrorFieldReferenceReachLawsTest} measures that row ADMITTED); a computation may not compute into
 * one. The pair is measured together here because either row alone licenses the wrong rule for the other
 * slot, and the portable checker reads both from the same stored member kind.
 *
 * <p><b>Why this is reachable at all.</b> {@code MVK_GROUP_AS_CALCULATED_FIELD} is {@code erased} in the Java
 * kits — the computed-field slot takes a field handle, so the shape cannot be authored through the typed
 * surface — which is exactly the bucket the portable checker inherits nothing from: it reads stored DM-JSON,
 * where the member is a string and a group path is one edit away.
 *
 * <p>Own domain (a bicycle rental).
 */
class ComputedFieldTargetKindLawsTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static final String RENTAL = DocumentModelDraft.named("bicycle-rental").locale("en_US")
            .rootGroup("Rental", r -> r
                    .field("Total", FieldKind.NUMBER)
                    .field("Reference", FieldKind.STRING)
                    .group("Station", s -> s.field("Name", FieldKind.STRING).field("Deposit", FieldKind.NUMBER))
                    .group("Hires", h -> h.repeatable(12)
                            .field("Fee", FieldKind.NUMBER)
                            .field("Bicycle", FieldKind.STRING)
                            .field("Slot", FieldKind.NUMBER)
                            .field("Mileage", FieldKind.NUMBER)
                            .field("ReturnHour", FieldKind.NUMBER)
                            // NUMBER on purpose: the operation below writes a number, so a STRING target
                            // would draw the type class and measure the field's TYPE instead of its role.
                            .indexField("Slot")
                            .sortField("ReturnHour", SortOrder.ASC)))
            .build().toJson();

    /** Where a computation may put its result. */
    @Test
    void whatAComputationMayComputeInto() {
        Map<String, String> actual = new LinkedHashMap<>();
        actual.put("a sibling FIELD — the control", computingInto("../Total"));
        actual.put("a FIXED group", computingInto("../Station"));
        actual.put("a REPEATABLE group", computingInto("../Hires"));
        actual.put("a field inside a fixed group", computingInto("../Station/Deposit"));
        actual.put("a path that resolves to nothing", computingInto("../NoSuchField"));

        Map<String, String> expected = new LinkedHashMap<>();
        expected.put("a sibling FIELD — the control", KernelLaws.ADMITTED);
        // NOT a consistency code: the Kernel's BUILDER refuses a group-valued computed target, so
        // `MVK_GROUP_AS_CALCULATED_FIELD` never reaches `checkConsistency` from this shape at all. The
        // distinction decides which vocabulary a portable checker may report it in.
        expected.put("a FIXED group", "NOT BUILDABLE");
        expected.put("a REPEATABLE group", "NOT BUILDABLE");
        expected.put("a field inside a fixed group", KernelLaws.ADMITTED);
        // Kernel 31.1.1's converter states "The computed field … was not found." with no class (KERNEL-FINDINGS
        // KF652); 30.8.1 could not build the model.
        expected.put("a path that resolves to nothing", KernelLaws.REFUSED_CODELESS);
        assertEquals(expected, actual, "what a computation may compute into");
    }

    /**
     * …and the same targets on a RULE, which is the row that keeps the two slots from sharing a rule.
     *
     * <p>A group error target is ADMITTED, so an implementation that refused a group in both slots would
     * refuse a legal model — and one that admitted a group in both would miss the computation's law.
     */
    @Test
    void theSameTargetsOnARuleInstead() {
        Map<String, String> actual = new LinkedHashMap<>();
        actual.put("a sibling FIELD — the control", ruleReportingOn("../Total", "FieldFilled(Total)"));
        actual.put("a FIXED group", ruleReportingOn("../Station", "GroupFilled(Station)"));
        actual.put("a REPEATABLE group", ruleReportingOn("../Hires", "AtLeastOneGroupFilled(Hires)"));

        Map<String, String> expected = new LinkedHashMap<>();
        expected.put("a sibling FIELD — the control", KernelLaws.ADMITTED);
        expected.put("a FIXED group", KernelLaws.ADMITTED);
        expected.put("a REPEATABLE group", KernelLaws.ADMITTED);
        assertEquals(expected, actual, "the same targets on a rule instead");
    }

    /**
     * …and what the same slot may compute into once the group it sits in DECLARES an index.
     *
     * <p>A12 has a key for this — {@code MVK_ERROR_INDEX_AS_CALCULATED_FIELD} — and it had never been put to
     * the Kernel: the ledger carried the law as prose and no lock named the code. The same reader resolves
     * this slot as the rows above, so an implementation that judged only the target's KIND would admit a
     * computation writing into the field that identifies a row.
     *
     * <p>The SORT field is the control that separates "a field the group names" from "the index field": both
     * are direct children named by a group member, and only one of them is refused.
     */
    @Test
    void whatAComputationMayComputeIntoInsideAnIndexedGroup() {
        Map<String, String> actual = new LinkedHashMap<>();
        actual.put("an ordinary sibling — the control", computingInsideHires("../Fee"));
        actual.put("the group's INDEX field", computingInsideHires("../Slot"));
        actual.put("the group's SORT field", computingInsideHires("../ReturnHour"));
        actual.put("a rule reporting on the index field — the control",
                KernelLaws.verdictModel(RENTAL, "/Rental/Hires", "../Slot", "FieldFilled(Slot)"));
        // Whether the law follows the TARGET or only the computation's own group — a checker keying on the
        // resolved field alone would apply it from anywhere, which is a transfer these two rows measure.
        actual.put("the index field, from OUTSIDE its group", computingInto("../Hires/Slot"));
        // NOT `Fee`: the helper's own operation sums it, so computing into it is a SELF-READ and draws
        // `MVK_ERROR_REFERENCE_TO_CALCULATED_FIELD` — a control moving two variables rather than one.
        actual.put("an ordinary field of that group, from OUTSIDE — the control",
                computingInto("../Hires/Mileage"));

        Map<String, String> expected = new LinkedHashMap<>();
        expected.put("an ordinary sibling — the control", KernelLaws.ADMITTED);
        expected.put("the group's INDEX field", "MVK_ERROR_INDEX_AS_CALCULATED_FIELD");
        // The control that makes the row above about the INDEX and not about "a field the group names":
        // a sort field is declared by the same kind of group member and is computed into freely.
        expected.put("the group's SORT field", KernelLaws.ADMITTED);
        expected.put("a rule reporting on the index field — the control", KernelLaws.ADMITTED);
        // The law follows the TARGET, not the computation's own group — so a checker may key on the
        // resolved field alone, which is a transfer measured here rather than assumed from the row above.
        expected.put("the index field, from OUTSIDE its group", "MVK_ERROR_INDEX_AS_CALCULATED_FIELD");
        expected.put("an ordinary field of that group, from OUTSIDE — the control", KernelLaws.ADMITTED);
        assertEquals(expected, actual, "what a computation may compute into inside an indexed group");
    }

    /** One computation declared INSIDE the repeatable {@code Hires} group, computing into [target]. */
    private static String computingInsideHires(String target) {
        try {
            ObjectNode root = (ObjectNode) MAPPER.readTree(RENTAL);
            ArrayNode rentalElements = (ArrayNode) root.path("content").path("modelRoot")
                    .path("rootGroups").get(0).path("Group").path("elements");
            ObjectNode hires = null;
            for (var element : rentalElements) {
                if ("Hires".equals(element.path("name").asText())) {
                    hires = (ObjectNode) element;
                }
            }
            ObjectNode computation = (ObjectNode) ((ArrayNode) hires.path("Group").path("elements"))
                    .addObject();
            computation.put("type", "Computation").put("id", "cidx").put("name", "RowTotal");
            ObjectNode body = computation.putObject("Computation");
            body.put("computedFieldRelPath", target);
            body.putArray("computationAlternatives").addObject().put("operation", "1");
            body.putArray("errorMessage").addObject().put("locale", "en_US").put("text", "the row total");
            return KernelLaws.verdictOf(root.toString());
        } catch (IOException unreadable) {
            throw new UncheckedIOException("the fixture must parse", unreadable);
        }
    }

    /**
     * The model with one computation whose {@code computedFieldRelPath} is [target].
     *
     * <p>Injected into the drafted tree rather than hand-written beside it: a hand-written root group is
     * missing members the Kernel's builder dereferences without checking, so it fails to BUILD for reasons
     * that have nothing to do with the row under test.
     */
    private static String computingInto(String target) {
        try {
            ObjectNode root = (ObjectNode) MAPPER.readTree(RENTAL);
            ObjectNode computation = (ObjectNode) ((ArrayNode) root.path("content").path("modelRoot")
                    .path("rootGroups").get(0).path("Group").path("elements")).addObject();
            computation.put("type", "Computation").put("id", "chire").put("name", "HireTotal");
            ObjectNode body = computation.putObject("Computation");
            body.put("computedFieldRelPath", target);
            body.putArray("computationAlternatives").addObject().put("operation", "Sum(Hires*/Fee)");
            body.putArray("errorMessage").addObject().put("locale", "en_US").put("text", "the hire total");
            return KernelLaws.verdictOf(root.toString());
        } catch (IOException unreadable) {
            throw new UncheckedIOException("the fixture must parse", unreadable);
        }
    }

    /** The same model with one rule reporting on [target] under [condition]. */
    private static String ruleReportingOn(String target, String condition) {
        return KernelLaws.verdictModel(RENTAL, "/Rental", target, condition);
    }
}
