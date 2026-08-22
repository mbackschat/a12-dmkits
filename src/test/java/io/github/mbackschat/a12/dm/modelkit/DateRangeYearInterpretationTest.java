package io.github.mbackschat.a12.dm.modelkit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import io.github.mbackschat.a12.dm.core.ModelIndex;

/**
 * modelkit — a DATE_RANGE's <b>{@code interpretationOfYear}</b> (DM-JSON on {@code DateRangeType}; the
 * kernel's {@code Jahresinterpretation}). It decides which endpoint of a <em>wrapping</em> yearless range
 * keeps the base year, so without it an ordinary winter cover window reads as start-after-end.
 *
 * <p>Kernel is the oracle: a model carrying the key loads + expands. The admitted declaration spellings are
 * {@code FROM} and {@code TO} only ({@code STANDARD} is expressed by OMITTING the property, and the tool
 * API's {@code STANDARD}/{@code PREVIOUS}/{@code SUBSEQUENT} spellings are refused) — measured 2026-08-19 by
 * {@code adapter.laws.WrappingYearlessRangeDiffTest}, which is also where the runtime direction is pinned.
 * The typed enum makes the refused spellings unrepresentable rather than rejecting them at runtime.
 *
 * <p>Own domain: a household policy whose winter cover runs November into January.
 */
class DateRangeYearInterpretationTest {

    private static final String WINDOW = "/Policy/WinterCover";

    private static DocumentModelDraft policy(RangeYearInterpretation interpretation) {
        return DocumentModelDraft.named("policy").locale("en_US").baseYear(2026)
                .rootGroup("Policy", p -> p
                        .field("PolicyRef", FieldKind.STRING)
                        .dateRangeField("WinterCover", range -> range
                                .format("dd.MM").separator("-")
                                .interpretationOfYear(interpretation)))
                .build();
    }

    @Test
    void aWrappingRangeDeclaringAnInterpretationIsKernelValid() {
        String json = policy(RangeYearInterpretation.TO).toJson();
        ModelIndex index;
        try {
            index = ModelIndex.ofSelfContained(json);
        } catch (Exception e) {
            throw new RuntimeException("emitted DM-JSON failed checkConsistency:\n" + json, e);
        }
        assertTrue(index.fieldPaths().anyMatch(WINDOW::equals), "the range field should be indexed");
    }

    @Test
    void emitsTheInterpretationOfYearKey() {
        assertTrue(policy(RangeYearInterpretation.FROM).toJson()
                .contains("\"interpretationOfYear\" : \"FROM\""), policy(RangeYearInterpretation.FROM)::toJson);
    }

    @Test
    void theStandardDefaultOmitsTheKeyEntirely() {
        // STANDARD is not a spelling the declaration accepts — absence IS the default, so emitting the token
        // would produce a model the kernel's own deserializer refuses.
        String explicit = policy(RangeYearInterpretation.STANDARD).toJson();
        assertFalse(explicit.contains("interpretationOfYear"), explicit);

        String unset = DocumentModelDraft.named("policy").locale("en_US")
                .rootGroup("Policy", p -> p.dateRangeField("WinterCover",
                        range -> range.format("dd.MM").separator("-")))
                .build().toJson();
        assertFalse(unset.contains("interpretationOfYear"), unset);
    }

    @Test
    void theInterpretationRoundTripsThroughTheAuthoredState() {
        FieldTypeSpec spec = ModelDocument.read(policy(RangeYearInterpretation.TO).toJson())
                .authoredState().fields().get(WINDOW).type();
        assertEquals(RangeYearInterpretation.TO,
                ((FieldTypeSpec.DateRangeSpec) spec).interpretationOfYear());
    }

    @Test
    void anExternallyAuthoredDeclarationIsNotDroppedOnRead() {
        // The case that was silently dropped: the model comes from the A12 editor, not from our own builder,
        // so nothing upstream of the reader can have supplied the state the reader is meant to recover.
        String authored = policy(RangeYearInterpretation.STANDARD).toJson()
                .replace("\"rangeSeparator\" : \"-\"",
                        "\"rangeSeparator\" : \"-\",\n          \"interpretationOfYear\" : \"FROM\"");
        FieldTypeSpec spec = ModelDocument.read(authored)
                .authoredState().fields().get(WINDOW).type();
        assertEquals(RangeYearInterpretation.FROM,
                ((FieldTypeSpec.DateRangeSpec) spec).interpretationOfYear());
    }

    @Test
    void anUndeclaredInterpretationReadsAsStandardRatherThanNull() {
        FieldTypeSpec spec = ModelDocument.read(policy(RangeYearInterpretation.STANDARD).toJson())
                .authoredState().fields().get(WINDOW).type();
        assertEquals(RangeYearInterpretation.STANDARD,
                ((FieldTypeSpec.DateRangeSpec) spec).interpretationOfYear());
    }

    @Test
    void theTwoArgConstructorStillCompilesAndDefaultsToStandard() {
        // Retained as a delegating overload so record deconstruction in existing consumers keeps working.
        assertEquals(RangeYearInterpretation.STANDARD,
                new FieldTypeSpec.DateRangeSpec("MM", "/").interpretationOfYear());
    }

    @Test
    void anExplicitNullInterpretationNormalizesToStandard() {
        // The record advertises a never-null component, so the canonical constructor has to enforce it: a null
        // reaching the emitter would NPE on json(), and a null reaching a consumer would reintroduce exactly
        // the three-state (null / STANDARD / value) confusion the enum exists to remove.
        assertEquals(RangeYearInterpretation.STANDARD,
                new FieldTypeSpec.DateRangeSpec("MM", "/", null).interpretationOfYear());
    }
}
