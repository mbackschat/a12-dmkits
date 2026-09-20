package io.github.mbackschat.a12.dm.adapter;

import static io.github.mbackschat.a12.dm.rulekit.dsl.Dsl.en;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;

import io.github.mbackschat.a12.dm.core.ReferenceResolvers;
import io.github.mbackschat.a12.dm.edit.EditSession;
import io.github.mbackschat.a12.dm.modelkit.DocumentModelDraft;
import io.github.mbackschat.a12.dm.modelkit.FieldKind;
import io.github.mbackschat.a12.dm.rulekit.dsl.BoundModel;
import io.github.mbackschat.a12.dm.rulekit.read.ReadResult;

/**
 * A stored condition the kernel's parser cannot make sense of against its model degrades to {@code Opaque}
 * with a reason that names the PARSE — never a crash inside our own converter.
 *
 * <p><b>The mislabel this locks was shipped, and it inverted the one distinction
 * {@code KernelAdapter.opaqueFailureReason} exists to draw</b> (found 2026-09-20 while measuring DG1001's
 * refusal boundary). {@code IMVK_Service.parseBedingungNoExpand} is called with {@code SILENT}, so it reports
 * unparseable text by returning {@code null} rather than by throwing. Nothing guarded that null, the first
 * dereference threw, and the tolerant arm labelled the result
 * {@code "converter error (NullPointerException) at ConditionConverter.countEntities"} — a MODEL problem
 * reported as a bug in this library, pointing a reader at a stack frame that is working correctly.
 *
 * <p><b>Why it is a boundary and not just a bug.</b> A kernel-accepted model cannot reach this state, so the
 * fixture has to be bound through {@link BoundModel#ofJsonLenient} — the deliberately ungated read path for a
 * model that does not yet validate, which {@code BoundModel} documents as its purpose. That path skips the
 * CONSISTENCY gate only; the kernel's parser still runs, and the measurement behind DG1001 (2026-09-20) is
 * that it answers first for every law tried — a format-illegal extraction, the star below, and duplicate
 * operands all came back as this null, never as a rulekit gate's refusal. That is why {@link KernelAdapter}
 * carries no {@code KernelCodeRefusal} arm and {@code ReadComputation} has no {@code Refused} state: on the
 * kernel-backed path there is nothing for them to report. {@code ReadResult.Refused} exists for the
 * kernel-FREE bridge, where a rulekit gate is the only verdict available.
 *
 * <p>Own domain (a parcel shipment).</p>
 */
class UnparseableStoredConditionReadTest {

    private static final String RULE = "/Shipment/NothingShipped";

    private static String legal() {
        String base = DocumentModelDraft.named("unparseable-read").locale("en_US")
                .rootGroup("Shipment", s -> s
                        .field("Error", FieldKind.STRING)
                        .group("Parcels", p -> p.repeatable(20).field("Weight", FieldKind.NUMBER)))
                .build().toJson();
        EditSession session = EditSession.read(base);
        session.addRule("/Shipment", "NothingShipped", "../Error", "EMPTY",
                "FieldFilled(Error) And Sum(Parcels*/Weight) == 0", "ERROR",
                List.of(en("A shipment must carry at least some weight.")));
        return BoundModel.ofJson(session.toJson()).toJson();
    }

    /** {@code Parcels} declared non-repeatable, which makes the stored star unparseable against the model. */
    private static String corrupted() {
        String legal = legal();
        String corrupted = legal.replace("\"repeatability\" : 20", "\"repeatability\" : 1");
        assertNotEquals(legal, corrupted, "the corruption under test did not apply");
        return corrupted;
    }

    private static ReadResult read(String modelJson) {
        return KernelAdapter.of(BoundModel.ofJsonLenient(modelJson, ReferenceResolvers.selfContained()))
                .read(RULE);
    }

    @Test
    void anUnparseableStoredConditionDegradesWithAReasonThatNamesTheParse() {
        ReadResult read = read(corrupted());
        ReadResult.Opaque opaque = assertInstanceOf(ReadResult.Opaque.class, read,
                () -> "an unparseable stored condition degrades, it does not crash the read: " + read);

        assertEquals("the kernel could not parse the stored condition against this model", opaque.reason());
        assertFalse(opaque.reason().contains("converter error"),
                "a model problem must not be reported as a bug in our own converter");
        assertTrue(opaque.canonicalText().contains("Parcels*/Weight"),
                () -> "the verbatim stored text must survive the degrade: " + opaque.canonicalText());
        assertEquals(opaque.canonicalText(), opaque.preservedText().orElseThrow());
    }

    /** The positive control: the identical rule on the identical path converts before the corruption. */
    @Test
    void theSameRuleConvertsBeforeTheCorruption() {
        ReadResult read = read(legal());
        assertInstanceOf(ReadResult.Converted.class, read,
                () -> "the degrade must be attributable to the corruption, got: " + read);
    }
}
