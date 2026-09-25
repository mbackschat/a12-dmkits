package io.github.mbackschat.a12.dm.rulekit.validate.laws;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.LinkedHashMap;
import java.util.Map;

import org.junit.jupiter.api.Test;

/**
 * A read OUTSIDE a {@code Having} filter is judged like any other, even when its text repeats the filter body.
 *
 * <p>A filter body's leaf is about the filtered entity and draws nothing on its own, so a reader that sets the
 * body aside must set aside those characters and no others. The last row is the one that tells a reader
 * cutting the body out BY POSITION from one cutting it out by TEXT: its outside conjunct spells the body
 * exactly, and A12 still names the unstarred read in it.
 *
 * <p>Own domain (the shared order fixture).
 */
class FilterBodyTextRepeatedOutsideLawsTest {

    private static final String FILTERED_SUM = "Sum(Items*/Count Having [Items/Sku] == \"X\") > 0";

    /** The filter body, verbatim, as a conjunct of its own. */
    private static final String BODY_AS_A_CONJUNCT = "[Items/Sku] == \"X\"";

    @Test
    void aConjunctSpellingTheFilterBodyIsStillRead() {
        Map<String, String> actual = new LinkedHashMap<>();
        actual.put("the filter alone — the control", verdict(FILTERED_SUM));
        actual.put("the body's text alone, outside any filter", verdict(BODY_AS_A_CONJUNCT));
        actual.put("the filter, then its body's text outside it",
                verdict(FILTERED_SUM + " And " + BODY_AS_A_CONJUNCT));

        Map<String, String> expected = new LinkedHashMap<>();
        expected.put("the filter alone — the control", KernelLaws.ADMITTED);
        expected.put("the body's text alone, outside any filter", "MVK_NO_WILDCARD");
        expected.put("the filter, then its body's text outside it", "MVK_NO_WILDCARD");
        assertEquals(expected, actual, "whether a conjunct spelling the filter body is read");
    }

    /** On a rule that does NOT iterate, so an unstarred read of {@code Items} has no row to stand on. */
    private static String verdict(String conjunct) {
        return KernelLaws.orderVerdict("/Order", "../Quantity", "FieldFilled(Quantity) And " + conjunct);
    }
}
