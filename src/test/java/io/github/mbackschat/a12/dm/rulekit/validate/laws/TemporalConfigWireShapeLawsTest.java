package io.github.mbackschat.a12.dm.rulekit.validate.laws;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.LinkedHashMap;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import io.github.mbackschat.a12.dm.Fixtures;

/**
 * The two date-config gates, measured through STORED JSON rather than through the typed builder.
 *
 * <p>{@link TemporalDeclarationConfigAdmissionLawsTest} and {@link TemporalAdditionalCheckCarrierLawsTest}
 * establish both laws — the pre-1900 check needs a year-bearing format, a partial precision needs a complete
 * one, and a DATERANGE is exempt from the first — by authoring models through the kit. That is the right
 * oracle for the LAW and says nothing about the MEMBER NAMES a checker reading stored JSON must look for, and
 * a checker cannot borrow a name from the Java field: taking {@code TypeDefType.id} for its wire name is what
 * sent an earlier probe measuring an unknown member and reading the result as a dangling-reference law
 * ([KERNEL-FINDINGS KF308], corrected).
 *
 * <p><b>The misspelling row is what makes the names a measurement.</b> Without it every row is equally
 * consistent with the member being ignored and the model admitted for some other reason — so one row writes
 * a name that is nearly right, and the grid states what A12 does with it.
 *
 * <p>Own domain (the shared order fixture), on {@code /Order/CustomerName} — a plain root field no other
 * declaration names.
 */
class TemporalConfigWireShapeLawsTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static final String PRE_1900 = "MVK_ADDITIONAL_CHECK_INVALID";
    private static final String PARTIAL = "MVK_OPTIONAL_DATE_TYPE_INVALID";

    @Test
    void whichStoredMembersCarryTheTwoDateConfigGates() {
        Map<String, String> actual = new LinkedHashMap<>();
        actual.put("a plain date format — the control",
                verdictFor("DateType", "{\"format\":\"yyyy-MM-dd\"}"));
        // The pre-1900 axis, both sides of its gate.
        actual.put("the check on a year-free format",
                verdictFor("DateType", "{\"format\":\"MM\",\"youngerThan1900Check\":true}"));
        actual.put("the check on a year-bearing format",
                verdictFor("DateType", "{\"format\":\"yyyy-MM\",\"youngerThan1900Check\":true}"));
        actual.put("the check written FALSE on a year-free format",
                verdictFor("DateType", "{\"format\":\"MM\",\"youngerThan1900Check\":false}"));
        // The precision axis, both sides of its gate.
        actual.put("a partial precision on an incomplete format",
                verdictFor("DateType", "{\"format\":\"MM\",\"datePrecision\":\"DAY_OPTIONAL\"}"));
        actual.put("a partial precision on a complete format",
                verdictFor("DateType", "{\"format\":\"yyyy-MM-dd\",\"datePrecision\":\"DAY_OPTIONAL\"}"));
        actual.put("the FULL default written out on an incomplete format",
                verdictFor("DateType", "{\"format\":\"MM\",\"datePrecision\":\"FULL\"}"));
        actual.put("both axes conflicting at once", verdictFor("DateType",
                "{\"format\":\"MM\",\"youngerThan1900Check\":true,\"datePrecision\":\"DAY_OPTIONAL\"}"));
        // The second carrier of the pre-1900 check, which spells its format under its own member.
        actual.put("the check on a year-free FRAGMENT format",
                verdictFor("DateFragmentType", "{\"formatOfFragment\":\"MM\",\"youngerThan1900Check\":true}"));
        actual.put("the check on a year-bearing FRAGMENT format", verdictFor("DateFragmentType",
                "{\"formatOfFragment\":\"dd.MM.yyyy\",\"youngerThan1900Check\":true}"));
        // The third carrier, measured EXEMPT through the kit — restated here in stored form because a checker
        // that read the member on every carrier would refuse this model.
        actual.put("the check on a year-free RANGE pair", verdictFor("DateRangeType",
                "{\"format\":\"MM\",\"rangeSeparator\":\"/\",\"youngerThan1900Check\":true}"));
        // A body with NO format at all, where the builder's default applies. Both gates read that default
        // rather than seeing nothing, so a checker may treat the absent member as the declared default —
        // which is `yyyy-MM-dd`, year-bearing AND complete, so both rows must be admitted.
        actual.put("the check with no format declared",
                verdictFor("DateType", "{\"youngerThan1900Check\":true}"));
        actual.put("a partial precision with no format declared",
                verdictFor("DateType", "{\"datePrecision\":\"DAY_OPTIONAL\"}"));
        // The row that makes the names above a measurement rather than a reading of the Java fields.
        actual.put("a nearly-right member name",
                verdictFor("DateType", "{\"format\":\"MM\",\"youngerThan1900\":true}"));

        Map<String, String> expected = new LinkedHashMap<>();
        expected.put("a plain date format — the control", KernelLaws.ADMITTED);
        expected.put("the check on a year-free format", PRE_1900);
        expected.put("the check on a year-bearing format", KernelLaws.ADMITTED);
        // The gate is on the VALUE, not on the member's presence — which is what lets a checker read the
        // member without having to know whether A12 distinguishes absent from false.
        expected.put("the check written FALSE on a year-free format", KernelLaws.ADMITTED);
        expected.put("a partial precision on an incomplete format", PARTIAL);
        expected.put("a partial precision on a complete format", KernelLaws.ADMITTED);
        expected.put("the FULL default written out on an incomplete format", KernelLaws.ADMITTED);
        // Both, independently: neither gate pre-empts the other, so a checker reproducing one of them is not
        // reproducing the other by accident.
        expected.put("both axes conflicting at once", PRE_1900 + "," + PARTIAL);
        expected.put("the check on a year-free FRAGMENT format", PRE_1900);
        expected.put("the check on a year-bearing FRAGMENT format", KernelLaws.ADMITTED);
        expected.put("the check on a year-free RANGE pair", KernelLaws.ADMITTED);
        expected.put("the check with no format declared", KernelLaws.ADMITTED);
        expected.put("a partial precision with no format declared", KernelLaws.ADMITTED);
        // Kernel 31.1.1's reader passes over a member it does not declare (KERNEL-FINDINGS KF654; 30.8.1 refused
        // it), so a misspelling is the real member left out and the model is checked without it.
        expected.put("a nearly-right member name", KernelLaws.ADMITTED);
        assertEquals(expected, actual, "the stored members carrying the two date-config gates");
    }

    /** The order fixture with {@code /Order/CustomerName} typed [type] and given [rawBody] verbatim. */
    private static String verdictFor(String type, String rawBody) {
        try {
            ObjectNode root = (ObjectNode) MAPPER.readTree(Fixtures.orderJson());
            for (var element : root.path("content").path("modelRoot").path("rootGroups").get(0)
                    .path("Group").path("elements")) {
                if ("CustomerName".equals(element.path("name").asText()) && element.has("Field")) {
                    ((ObjectNode) element.path("Field")).set("fieldType", MAPPER.readTree(
                            "{\"type\":\"" + type + "\",\"" + type + "\":" + rawBody + "}"));
                    return KernelLaws.verdictOf(root.toString());
                }
            }
            throw new IllegalStateException("the fixture no longer declares the root field `CustomerName`");
        } catch (IOException unreadable) {
            throw new UncheckedIOException("the fixture and the raw body must parse", unreadable);
        }
    }
}
