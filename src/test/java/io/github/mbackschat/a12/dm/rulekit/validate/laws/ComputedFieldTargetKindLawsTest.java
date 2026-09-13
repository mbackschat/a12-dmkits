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
                            .field("Bicycle", FieldKind.STRING)))
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
        expected.put("a path that resolves to nothing", "NOT BUILDABLE");
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
