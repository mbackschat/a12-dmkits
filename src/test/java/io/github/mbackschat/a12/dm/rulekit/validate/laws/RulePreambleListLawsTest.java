package io.github.mbackschat.a12.dm.rulekit.validate.laws;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.LinkedHashMap;
import java.util.Map;

import org.junit.jupiter.api.Test;

import io.github.mbackschat.a12.dm.Fixtures;
import io.github.mbackschat.a12.dm.rulekit.validate.ValidationResult;

/**
 * A rule condition's preamble takes a comma-separated LIST of suppressed warnings on kernel 31.3.1 (the grammar's
 * {@code preamble}, which a computation's {@code additionalParserInformation} shares), each name judged against the
 * supported set; kernel 31.1.1 took one name. The unwaived comparison is the control that the scale gate fires.
 */
class RulePreambleListLawsTest {

    private static final String ORDER = Fixtures.orderJson();
    private static final String CROSS_SCALE = "[Quantity] == [UnitPrice]";
    private static final String SCALE = "MVK_INVALID_COMPARE_DEC_PLACES";
    private static final String ENUM = "MVK_ERROR_INVALID_ENUM_CALCULATION";

    @Test
    void aRulePreambleTakesAListOfSupportedWarnings() {
        Map<String, String> actual = new LinkedHashMap<>();
        actual.put("no preamble — the control", verdict(CROSS_SCALE));
        actual.put("the scale gate waived", verdict("@SuppressWarning(" + SCALE + ") " + CROSS_SCALE));
        actual.put("the scale gate beside the enumeration class", verdict("@SuppressWarning(" + SCALE + "," + ENUM + ") " + CROSS_SCALE));
        actual.put("the enumeration class first", verdict("@SuppressWarning(" + ENUM + ", " + SCALE + ") " + CROSS_SCALE));
        actual.put("the enumeration class alone", verdict("@SuppressWarning(" + ENUM + ") " + CROSS_SCALE));
        actual.put("the scale gate beside an unsupported name", verdict("@SuppressWarning(" + SCALE + ",MVK_BOGUS) " + CROSS_SCALE));

        Map<String, String> expected = new LinkedHashMap<>();
        expected.put("no preamble — the control", SCALE);
        expected.put("the scale gate waived", KernelLaws.ADMITTED);
        expected.put("the scale gate beside the enumeration class", KernelLaws.ADMITTED);
        expected.put("the enumeration class first", KernelLaws.ADMITTED);
        expected.put("the enumeration class alone", SCALE);
        expected.put("the scale gate beside an unsupported name", "MVK_INVALID_SUPPRESSED_WARNING");
        assertEquals(expected, actual, "what a rule's preamble list waives");
    }

    private static String verdict(String condition) {
        ValidationResult r = KernelLaws.checkModel(ORDER, "/Order", "../Quantity", condition);
        return r.ok() ? KernelLaws.ADMITTED : r.diagnostics().stream()
                .map(d -> d.code().orElse("(no code: " + d.summary() + ")")).distinct()
                .reduce((a, b) -> a + "," + b).orElse("?");
    }
}
