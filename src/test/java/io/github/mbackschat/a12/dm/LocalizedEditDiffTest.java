package io.github.mbackschat.a12.dm;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import io.github.mbackschat.a12.dm.modelkit.ModelDocument;
import io.github.mbackschat.a12.dm.rulekit.dsl.LocalizedText;
import io.github.mbackschat.a12.dm.rulekit.write.ModelWriter;
import org.junit.jupiter.api.Test;

/**
 * MODEL-REVIEW Phase-0 guard (MODEL-REVIEW-SPEC §2b): on a NORMALIZED model, a single edit must touch ONLY
 * that element's region of the file — the property that makes a raw {@code git diff} (and {@code git blame})
 * of a model legible. Locks the localized-edit half of the writer invariant (the twin of
 * {@link RoundTripIdempotentTest}).
 */
class LocalizedEditDiffTest {

    @Test
    void addingOneRuleTouchesOnlyItsOwnRegion() {
        String once = ModelDocument.read(Fixtures.orderJson()).toJson();
        String edited = ModelWriter.addRule(once, "/Order", "ProbeRule", "../OrderDate", "PROBE",
                "FieldFilled(OrderDate)", "ERROR", List.of(new LocalizedText("en_US", "probe")));

        String[] before = once.split("\n", -1);
        String[] after = edited.split("\n", -1);
        int prefix = 0;
        while (prefix < before.length && prefix < after.length && before[prefix].equals(after[prefix])) {
            prefix++;
        }
        int suffix = 0;
        while (suffix < before.length - prefix && suffix < after.length - prefix
                && before[before.length - 1 - suffix].equals(after[after.length - 1 - suffix])) {
            suffix++;
        }
        // every line of the original is accounted for by the common prefix + suffix — i.e. the edit
        // perturbed ZERO lines outside the region where the new rule was inserted.
        int commonPrefix = prefix;
        int commonSuffix = suffix;
        assertEquals(before.length, commonPrefix + commonSuffix,
                () -> "a single rule add perturbed lines outside its region: prefix=" + commonPrefix
                        + ", suffix=" + commonSuffix + ", original lines=" + before.length);
        assertTrue(after.length > before.length, "adding a rule should add lines");
    }
}
