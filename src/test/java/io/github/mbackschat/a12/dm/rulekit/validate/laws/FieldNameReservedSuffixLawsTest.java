package io.github.mbackschat.a12.dm.rulekit.validate.laws;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.stream.Collectors;

import org.junit.jupiter.api.Test;

import io.github.mbackschat.a12.dm.core.ModelConsistencyException;
import io.github.mbackschat.a12.dm.modelkit.DocumentModelDraft;
import io.github.mbackschat.a12.dm.rulekit.dsl.BoundModel;

/**
 * KERNEL-FINDINGS §8 — the one field name {@code MVK_NAME_INVALID_A12_DV} is actually about.
 *
 * <p>[MVK-LEDGER] carried that code as an <b>unmeasured predicate</b>: {@link FieldNameAlphabetLawsTest}'s
 * complete printable-ASCII sweep draws OTHER classes for every illegal character, so nothing authored had
 * reached the metamodel CUSTOM CONDITION {@code ungueltigerFeldName} and the honest disposition was that its
 * trigger was unknown. The name suggests an alphabet; the alphabet is not what is left.
 *
 * <p><b>Reading the implementation settles it.</b> {@code CheckFieldName} delegates to
 * {@code NameValidationService.checkFieldNameNoMetaModelChecks}, which has two arms: the identifier pattern
 * {@code [a-zA-Z0-9_:]+([.][a-zA-Z][a-zA-Z0-9_:]*)*}, and — for a name that MATCHES it — a reserved
 * <b>final dotted segment</b>, {@code .value} case-insensitively in the English condition language
 * (source-read, {@code kernel-core-base-utils}). The first arm is what the sweep found pre-empted; the second
 * is a name every alphabet gate admits, which is why no character sweep could ever have reached it.
 *
 * <p>Own domain (a warehouse bin), matching its sibling's fixture.
 */
class FieldNameReservedSuffixLawsTest {

    private static final String RESERVED = "MVK_NAME_INVALID_A12_DV";

    private static final String DOT = "MVK_NAME_INVALID_DOT_COLON_A12_DV";

    @Test
    void theReservedFinalSegmentIsTheOnlyNameThisCodeIsLeftToJudge() {
        Map<String, String> actual = new LinkedHashMap<>();
        actual.put("Slot — the control", declared("Slot"));
        actual.put("value — the reserved word with no dot before it", declared("value"));
        actual.put("Slot.List — a dotted name whose last segment is ordinary", declared("Slot.List"));
        actual.put("Slot.value — the reserved final segment", declared("Slot.value"));
        actual.put("Slot.VALUE — the same segment, upper case", declared("Slot.VALUE"));
        actual.put("Slot.values — one letter past it", declared("Slot.values"));
        actual.put("Slot.value.List — reserved in the MIDDLE", declared("Slot.value.List"));
        actual.put("Slot.List.value — reserved at the END of a longer name", declared("Slot.List.value"));

        Map<String, String> expected = new LinkedHashMap<>();
        expected.put("Slot — the control", KernelLaws.ADMITTED);
        // The pattern's dotted group is OPTIONAL, so an undotted name matches with the group unset and the
        // reserved-word test is never asked — the row a checker keyed on the bare word would refuse.
        expected.put("value — the reserved word with no dot before it", KernelLaws.ADMITTED);
        expected.put("Slot.List — a dotted name whose last segment is ordinary", DOT);
        // A12 reports BOTH, this one first: the dot is refused on its own account by a separate metamodel
        // rule, and the reserved segment by this one. A checker that stops at the first class names half the
        // verdict — and a checker that reports the dot alone names the half that is NOT the reason.
        expected.put("Slot.value — the reserved final segment", RESERVED + "," + DOT);
        expected.put("Slot.VALUE — the same segment, upper case", RESERVED + "," + DOT);
        expected.put("Slot.values — one letter past it", DOT);
        // The test reads the LAST dotted segment, so the same token elsewhere in the name is ordinary.
        expected.put("Slot.value.List — reserved in the MIDDLE", DOT);
        expected.put("Slot.List.value — reserved at the END of a longer name", RESERVED + "," + DOT);
        assertEquals(expected, actual, "which field names the reserved-segment gate is left to judge");
    }

    /** The sibling's helper, verbatim in behaviour: the distinct class keys, or {@code (codeless)}. */
    private static String declared(String fieldName) {
        String json = DocumentModelDraft.named("warehouse-bin").locale("en_US")
                .rootGroup("Bin", b -> b
                        .stringField("Ref", s -> { })
                        .stringField(fieldName, s -> { }))
                .build().toJson();
        try {
            BoundModel.ofJson(json);
            return KernelLaws.ADMITTED;
        } catch (ModelConsistencyException rejected) {
            String classes = rejected.notifications().stream()
                    .map(n -> n.message().trim())
                    .filter(m -> m.matches(".*\\[\\w+]$"))
                    .map(m -> m.replaceAll(".*\\[(\\w+)]$", "$1"))
                    .distinct()
                    .collect(Collectors.joining(","));
            return classes.isEmpty() ? "(codeless)" : classes;
        }
    }
}
