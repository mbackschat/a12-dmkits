package io.github.mbackschat.a12.dm.adapter;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import io.github.mbackschat.a12.dm.modelkit.DocumentModelDraft;
import io.github.mbackschat.a12.dm.modelkit.FieldKind;
import io.github.mbackschat.a12.dm.rulekit.dsl.ConditionLanguage;
import io.github.mbackschat.a12.dm.rulekit.dsl.DslRenderer;
import io.github.mbackschat.a12.dm.rulekit.read.ReadResult;
import io.github.mbackschat.a12.dm.rulekit.validate.DraftRule;
import io.github.mbackschat.a12.dm.rulekit.validate.RuleValidator;

/**
 * The READ half of KERNEL-FINDINGS KF351's star placement: a star BELOW the index group is kernel-legal, so
 * reading it back must convert it. rulekit's index handles refused every star beside an index, and the
 * converter builds those handles, so a legal model came back {@code Opaque}. The rules are injected as text
 * because their point is the model a foreign author wrote; each row is one {@code FirstFilledValue} operand arm.
 */
class SemanticIndexStarBelowIndexGroupReadTest {

    private static final RuleValidator VALIDATOR = new RuleValidator();

    @Test
    void readsAFirstFilledValueFieldListIndexedAboveItsStar() {
        assertConverts("FirstFilledValue(Packages/Parcels*/Label For \"P1\", Title) == \"x\"",
                "FirstFilledValue(/Order/Packages/Parcels*/Label For \"P1\", /Order/Title) == \"x\"");
    }

    @Test
    void readsAFirstFilledValueGroupIndexedAboveItsStar() {
        assertConverts("FirstFilledValue(Packages/Parcels* For \"P1\") == \"x\"",
                "FirstFilledValue(/Order/Packages/Parcels* For \"P1\") == \"x\"");
    }

    /** {@code rendered} is the renderer's default absolute spelling of the same condition. */
    private static void assertConverts(String condition, String rendered) {
        DraftRule rule = new DraftRule("/Order", "StarBelowIndexRule", "../Title",
                "FieldFilled(Title) And " + condition, "C", "ERROR");
        assertTrue(VALIDATOR.check(model(), rule).ok(), "the kernel must admit the shape this row reads back");

        ReadResult read = KernelAdapter.ofJson(VALIDATOR.inject(model(), rule)).read("/Order/StarBelowIndexRule");
        ReadResult.Converted converted = assertInstanceOf(ReadResult.Converted.class, read, read::toString);
        assertEquals("FieldFilled(/Order/Title) And " + rendered,
                new DslRenderer(ConditionLanguage.EN).render(converted.condition()));
    }

    /** Packages declares the index; Parcels is the repeatable level beneath it that the star iterates. */
    private static String model() {
        return DocumentModelDraft.named("parcels").locale("en_US")
                .rootGroup("Order", o -> o
                        .field("Title", FieldKind.STRING)
                        .group("Packages", g -> g.repeatable(5).indexField("PackageCode")
                                .field("PackageCode", FieldKind.STRING)
                                .group("Parcels", p -> p.repeatable(5).field("Label", FieldKind.STRING))))
                .build().toJson();
    }
}
