package io.github.mbackschat.a12.dm.modelkit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import io.github.mbackschat.a12.dm.rulekit.dsl.BoundModel;

/**
 * modelkit — a DATE_RANGE's <b>{@code interpretationOfYear}</b> (DM-JSON on {@code DateRangeType}; the
 * kernel's {@code Jahresinterpretation}). It decides which endpoint of a <em>wrapping</em> yearless range
 * keeps the base year, so without it an ordinary winter cover window reads as start-after-end.
 *
 * <p><b>Kernel is the oracle, and it is invoked here</b> — {@link BoundModel#ofJson}, not
 * {@code ModelIndex}. The admitted declaration spellings are {@code FROM} and {@code TO} only, with
 * {@code STANDARD} expressed by OMITTING the property; the refusal of the tool API's {@code SUBSEQUENT}
 * spelling is asserted by {@link #theKernelRefusesATokenOutsideItsAdmittedSet} rather than cited to prose
 * elsewhere. The typed enum then makes those spellings unrepresentable, so the runtime check is a backstop
 * for foreign DM-JSON, not the authoring gate. The runtime DIRECTION of FROM/TO (which endpoint the base
 * year labels) is a separate fact, pinned against both kernel strategies by
 * {@code adapter.laws.WrappingYearlessRangeDiffTest.agreeOnEndpointYear}.
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

    /**
     * The kernel ARBITRATES the emitted declaration. {@link BoundModel#ofJson} is the route that actually runs
     * {@code checkConsistency} on the value it is handed; {@code ModelIndex.ofSelfContained} does NOT — every
     * kit main source is kernel-free by construction ({@code verifyKitPurity}), so it indexes without gating.
     * This distinction is the whole point of the assertion: the previous version of this test named the kernel
     * in its own failure message and never invoked it, so a bogus {@code interpretationOfYear} token would have
     * passed it. (Same trap, and the same shape, as `AdmissionClaimIsAssertedGuardTest` was written for.)
     */
    @Test
    void theKernelAdmitsAWrappingRangeDeclaringAnInterpretation() {
        for (RangeYearInterpretation interpretation : RangeYearInterpretation.values()) {
            String json = policy(interpretation).toJson();
            try {
                BoundModel.ofJson(json);
            } catch (RuntimeException e) {
                throw new AssertionError("the kernel refused the " + interpretation + " declaration:\n" + json, e);
            }
        }
    }

    /**
     * The negative half, and the reason the positive one is not vacuous: a token outside the kernel's admitted
     * set is REFUSED by the same route. Without this, the assertion above would pass on any emitted string.
     */
    /**
     * MEASURED 2026-08-22, and it is what licenses the reader to fail fast: an EMPTY token is <b>refused</b>,
     * at deserialization. The tempting mechanism said otherwise — Jackson coerces {@code ""} to null for enums
     * by default, so the kernel "plausibly" reads it as absent — and the measurement refutes it. Consequence:
     * no kernel-legal model can carry an unrecognized token, so throwing on one cannot make a legal model
     * unreadable, which is the only thing that would have made a lenient default the safer choice.
     */
    @Test
    void theKernelRefusesAnEmptyTokenRatherThanReadingItAsAbsent() {
        String blank = policy(RangeYearInterpretation.TO).toJson().replace("\"TO\"", "\"\"");
        assertThrows(RuntimeException.class, () -> BoundModel.ofJson(blank), () -> blank);
    }

    @Test
    void theKernelRefusesATokenOutsideItsAdmittedSet() {
        String bogus = policy(RangeYearInterpretation.TO).toJson().replace("\"TO\"", "\"SUBSEQUENT\"");
        assertThrows(RuntimeException.class, () -> BoundModel.ofJson(bogus),
                () -> "SUBSEQUENT must be refused — it is the tool API's spelling, not the model's:\n" + bogus);
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

    /**
     * The reader FAILS FAST on a token it does not recognize, rather than silently degrading it to STANDARD —
     * which would re-emit the field with the key GONE, i.e. exactly the silent drop this whole change exists to
     * close, relocated from "no slot" to "wrong slot". Safe because the assertion above measures that such a
     * model is not kernel-legal in the first place.
     */
    @Test
    void anUnrecognizedTokenIsRefusedInsteadOfDegradedToStandard() {
        String foreign = policy(RangeYearInterpretation.STANDARD).toJson()
                .replace("\"rangeSeparator\" : \"-\"",
                        "\"rangeSeparator\" : \"-\",\n          \"interpretationOfYear\" : \"SUBSEQUENT\"");
        IllegalArgumentException refused = assertThrows(IllegalArgumentException.class,
                () -> ModelDocument.read(foreign).authoredState());
        assertTrue(refused.getMessage().contains("SUBSEQUENT"), refused::getMessage);
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
