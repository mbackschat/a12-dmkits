package io.github.mbackschat.a12.dm.rulekit.validate.laws;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.LinkedHashMap;
import java.util.Map;

import org.junit.jupiter.api.Test;

import io.github.mbackschat.a12.dm.modelkit.DocumentModelDraft;
import io.github.mbackschat.a12.dm.rulekit.dsl.BoundModel;
import io.github.mbackschat.a12.dm.rulekit.dsl.Dsl;

/**
 * The `index(…)` terminal on two Enumeration-indexed groups a rule reads in parallel (a12-kernel-lean
 * {@code SPEC-2026-10-13-12}; KERNEL-FINDINGS KF280): when their index declarations carry the same values under
 * different display labels, the value suffix is refused {@code INVALID_INDEX_FIELD_ENUM_XML_TO_DISPLAY} while the
 * category suffix is admitted. The same labels on both sides are the control.
 *
 * <p>Own domain (insurance covers paired with their limits by grade).
 */
class ParallelEnumIndexDisplayLawsTest {

    private static String policy(String limitAlpha, String limitBeta) {
        return DocumentModelDraft.named("policy-parallel-grades").locale("en_US")
                .rootGroup("Policy", p -> p
                        .group("Covers", c -> c.repeatable(5).indexField("Grade")
                                .enumField("Grade", e -> e.value("A", v -> v.label("en_US", "Alpha"))
                                        .value("B", v -> v.label("en_US", "Beta")).category("Band", "HIGH", "LOW"))
                                .stringField("Remark", f -> { }))
                        .group("Limits", c -> c.repeatable(5).indexField("Grade")
                                .enumField("Grade", e -> e.value("A", v -> v.label("en_US", limitAlpha))
                                        .value("B", v -> v.label("en_US", limitBeta)).category("Band", "HIGH", "LOW"))
                                .numberField("Cap", f -> f.fractionDigits(0))))
                .build().toJson();
    }

    private static String verdict(String model, String message) {
        BoundModel m = BoundModel.ofJson(model);
        String condition = KernelLaws.render(Dsl.fieldFilled(m.field("/Policy/Covers/Remark"))
                .and(Dsl.fieldFilled(m.field("/Policy/Limits/Cap"))));
        return KernelLaws.messageVerdict(model, "/Policy/Covers", "../Remark", condition, "en_US", message);
    }

    @Test
    void differentDisplayLabelsRefuseTheValueSuffixOnly() {
        String relabelled = policy("Ace", "Bee");
        String same = policy("Alpha", "Beta");
        Map<String, String> actual = new LinkedHashMap<>();
        actual.put("labels differ, value suffix", verdict(relabelled, "grade $index(Grade).value$"));
        actual.put("labels differ, category suffix", verdict(relabelled, "band $index(Grade)->Band$"));
        actual.put("labels agree, value suffix", verdict(same, "grade $index(Grade).value$"));

        Map<String, String> expected = new LinkedHashMap<>();
        expected.put("labels differ, value suffix", "INVALID_INDEX_FIELD_ENUM_XML_TO_DISPLAY");
        expected.put("labels differ, category suffix", KernelLaws.ADMITTED);
        expected.put("labels agree, value suffix", KernelLaws.ADMITTED);
        assertEquals(expected, actual, "parallel Enumeration indices compare their display labels");
    }
}
