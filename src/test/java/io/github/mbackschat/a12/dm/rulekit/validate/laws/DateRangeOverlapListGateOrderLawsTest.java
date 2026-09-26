package io.github.mbackschat.a12.dm.rulekit.validate.laws;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.LinkedHashMap;
import java.util.Map;

import org.junit.jupiter.api.Test;

import io.github.mbackschat.a12.dm.modelkit.DocumentModelDraft;

/**
 * KERNEL-FINDINGS [KF380] — <b>{@code AtLeastOneDateRangeOverlaps}' {@code In} list is judged as an entity list of
 * its own before it meets the scalar</b>: its own repeat, then a root among its members, then its base type
 * against the scalar's, and only then a repeat across the two sides.
 *
 * <p>Each row puts two of those gates in reach at once, so its class names the earlier one: a root-only list
 * that also holds the scalar and mixes kinds (root before type and before the cross-side repeat); a list that
 * repeats the scalar beside the root holding it (own repeat before root); a list that repeats the scalar
 * beside a String (type before the cross-side repeat); and a list that repeats a String (own repeat before
 * type). The lone cross-side repeat and a second date range are the controls.
 *
 * <p>The root is repeatable so a starred root can be a list member. Reported by a12-kernel-lean as
 * {@code SPEC-2026-09-26-05}; measured here on our own fixture. Own domain (an insurance contract).
 */
class DateRangeOverlapListGateOrderLawsTest {

    private static final String CONTRACT = DocumentModelDraft.named("overlap-list-order").locale("en_US")
            .rootGroup("Contract", c -> c.repeatable(5)
                    .stringField("Ref", s -> { })
                    .stringField("Serial", s -> { })
                    .dateRangeField("Term", r -> r.format("dd.MM.yyyy").separator("-"))
                    .dateRangeField("Grace", r -> r.format("dd.MM.yyyy").separator("-")))
            .build().toJson();

    private static String verdict(String list) {
        return KernelLaws.verdictModel(CONTRACT, "/Contract", "../Ref",
                "FieldFilled(Ref) And AtLeastOneDateRangeOverlaps(Term In " + list + ")");
    }

    @Test
    void theListIsAskedItsOwnRepeatThenItsRootThenItsTypeBeforeARepeatAcrossTheSides() {
        Map<String, String> actual = new LinkedHashMap<>();
        actual.put("control: another date range", verdict("Grace"));
        actual.put("control: the scalar repeated across the sides", verdict("Term"));
        actual.put("the root alone, holding the scalar", verdict("/Contract"));
        actual.put("the scalar beside the starred root", verdict("Term, /Contract*"));
        actual.put("the scalar beside a String", verdict("Term, Serial"));
        actual.put("a String alone", verdict("Serial"));
        actual.put("a String repeated", verdict("Serial, Serial"));

        Map<String, String> expected = new LinkedHashMap<>();
        expected.put("control: another date range", KernelLaws.ADMITTED);
        expected.put("control: the scalar repeated across the sides", "MVK_DUPLICATE_PARAM1");
        expected.put("the root alone, holding the scalar", "MVK_ROOT_GROUP_REFERENCED");
        expected.put("the scalar beside the starred root", "MVK_DUPLICATE_PARAM2");
        expected.put("the scalar beside a String", "MVK_VARYING_TYPES_NOT_ALLOWED");
        expected.put("a String alone", "MVK_VARYING_TYPES_NOT_ALLOWED");
        expected.put("a String repeated", "MVK_DUPLICATE_PARAM1");
        assertEquals(expected, actual, "the In list's gate order");
    }
}
