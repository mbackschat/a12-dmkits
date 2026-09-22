package io.github.mbackschat.a12.dm.rulekit.validate.laws;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.LinkedHashMap;
import java.util.Map;

import org.junit.jupiter.api.Test;

import io.github.mbackschat.a12.dm.modelkit.DocumentModelDraft;
import io.github.mbackschat.a12.dm.modelkit.FieldKind;
import io.github.mbackschat.a12.dm.rulekit.dsl.BoundModel;
import io.github.mbackschat.a12.dm.rulekit.validate.DraftRule;
import io.github.mbackschat.a12.dm.rulekit.validate.RuleValidator;
import io.github.mbackschat.a12.dm.rulekit.validate.ValidationResult;

/**
 * Whether the kernel admits a <b>{@code $}-correlated BOOLEAN or CONFIRM</b> comparison inside a
 * {@code Having} filter — the question left over from the DG44 sweep, and the reason it needed asking.
 *
 * <p><b>Why it was not idle curiosity.</b> Typing a comparison operand rebuilds the model's handle for the
 * field's declared kind from its path, which drops the {@code $} marker unless it is re-applied. Three of the
 * five rebuild sites could re-apply it, because {@code NumberRef}, {@code EnumRef} and {@code DateRangeRef}
 * publish {@code outer()}; {@code BoolRef} and {@code ConfirmRef} did not, so on those two the marker had
 * nowhere to go and a bridge read produced an UNCORRELATED comparison SILENTLY rather than refusing. A
 * type-level erasure reads as "not a thing" instead of "refused", which is the shape the standing
 * client-side-refusal rule says to measure rather than assume — and measuring it showed the kernel admits
 * both, so the erasure was a defect. Both refs gained {@code outer()} on 2026-09-05.
 *
 * <p><b>The carrier is the catalog's own admitted condition, with ONE conjunct swapped.</b> Two earlier
 * fixtures of my own devising were refused before the leaf under test was ever reached — first
 * {@code MVK_NO_WILDCARD} (one repeatable level instead of two), then
 * {@code MVK_NEG_CONDITION_IN_ITERATION} — each measuring a gate upstream of the question. Building the
 * carrier is evidently not free, so it is not built here: the {@code CurrentRepetition} catalog example is
 * kernel-admitted under N7, and this varies its last conjunct and nothing else. The unmodified example is the
 * live control, so a refusal upstream of the leaf shows up as the control failing rather than as an answer.
 *
 * <p>Own domain (warehouse stock counting).
 */
class CorrelatedBooleanComparisonLawsTest {

    private static final RuleValidator VALIDATOR = new RuleValidator();
    private static final String ADMITTED = "ADMITTED";

    /** The catalog example's own shape, plus the two extra leaf kinds this test varies over. */
    private static final String WAREHOUSE = DocumentModelDraft.named("warehouse-correlated").locale("en_US")
            .rootGroup("Warehouse", warehouse -> warehouse
                    .group("Aisles", aisles -> aisles.repeatable(20)
                            .stringField("Code", field -> { })
                            .group("Bins", bins -> bins.repeatable(20)
                                    .numberField("Quantity", field -> { })
                                    .field("Recounted", FieldKind.BOOLEAN)
                                    .field("Signed", FieldKind.CONFIRM))))
            .build().toJson();

    private static final String BIN = "/Warehouse/Aisles/Bins";

    @Test
    void theKernelsVerdictOnACorrelatedComparisonPerDeclaredKind() {
        Map<String, String> actual = new LinkedHashMap<>();
        actual.put("NUMBER — the catalog example verbatim, the live control",
                verdict("[" + BIN + "/Quantity] == [$" + BIN + "/Quantity]"));
        actual.put("BOOLEAN against a literal", verdict("[$" + BIN + "/Recounted] == True"));
        actual.put("CONFIRM against a literal", verdict("[$" + BIN + "/Signed] == True"));

        Map<String, String> expected = new LinkedHashMap<>();
        expected.put("NUMBER — the catalog example verbatim, the live control", ADMITTED);
        expected.put("BOOLEAN against a literal", ADMITTED);
        expected.put("CONFIRM against a literal", ADMITTED);
        assertEquals(expected, actual, "what the kernel admits decides whether the erasure is a defect");
    }

    /**
     * <b>The typed surface can now express what the kernel admits.</b> Until 2026-09-05 {@code BoolRef} and
     * {@code ConfirmRef} published no {@code outer()}, so the rows above were legal models with no shape in
     * rulekit — and the bridge's read dropped the marker silently rather than refusing. Authoring the same
     * two leaves through the typed builder and rendering them is what says the erasure is gone; the kernel
     * verdict above is what says it should be.
     */
    @Test
    void theTypedBuilderRendersTheCorrelatedBooleanAndConfirmLeavesItRefusedBefore() {
        BoundModel model = BoundModel.ofJson(WAREHOUSE);

        Map<String, String> actual = new LinkedHashMap<>();
        actual.put("BOOLEAN", KernelLaws.render(model.bool(BIN + "/Recounted").outer().isTrue()));
        actual.put("CONFIRM", KernelLaws.render(model.confirm(BIN + "/Signed").outer().confirmed()));
        actual.put("BOOLEAN uncorrelated — the control",
                KernelLaws.render(model.bool(BIN + "/Recounted").isTrue()));

        Map<String, String> expected = new LinkedHashMap<>();
        expected.put("BOOLEAN", "[$" + BIN + "/Recounted] == True");
        expected.put("CONFIRM", "[$" + BIN + "/Signed] == True");
        expected.put("BOOLEAN uncorrelated — the control", "[" + BIN + "/Recounted] == True");
        assertEquals(expected, actual, "the marker must reach the rendering, and only when asked for");
    }

    /**
     * The discriminator between "my carrier is wrong" and "the two extra fields changed the verdict": N7's
     * warehouse has exactly {@code Code} and {@code Quantity}, and measures this same condition ADMITTED.
     */
    @Test
    void theSameConditionOnTheUnextendedFixture() {
        String plain = DocumentModelDraft.named("warehouse-plain").locale("en_US")
                .rootGroup("Warehouse", warehouse -> warehouse
                        .group("Aisles", aisles -> aisles.repeatable(20)
                                .stringField("Code", field -> { })
                                .group("Bins", bins -> bins.repeatable(20)
                                        .numberField("Quantity", field -> { }))))
                .build().toJson();
        assertEquals(ADMITTED, verdict(plain, "[" + BIN + "/Quantity] == [$" + BIN + "/Quantity]"),
                "N7 measures this exact condition admitted, so a refusal here means my carrier differs");
    }

    /** The catalog's {@code CurrentRepetition} example, with its final conjunct replaced by the leaf. */
    private static String verdict(String correlatedLeaf) {
        return verdict(WAREHOUSE, correlatedLeaf);
    }

    private static String verdict(String modelJson, String correlatedLeaf) {
        // The leading POSITIVE conjunct is the catalog example's and is load-bearing: without it the
        // whole rule is a negative condition inside an iteration and draws MVK_NEG_CONDITION_IN_ITERATION
        // before the leaf is reached. Dropping it while hand-transcribing cost two rounds.
        String condition = "FieldFilled(" + BIN + "/Quantity) And "
                + "AtLeastOneFieldFilled(/Warehouse/Aisles*/Bins*/Quantity Having "
                + "CurrentRepetition(" + BIN + ") != CurrentRepetition($" + BIN + ") And "
                + correlatedLeaf + ")";
        ValidationResult result = VALIDATOR.check(modelJson,
                new DraftRule("/Warehouse", "CorrelatedProbe", "../Aisles/Bins/Quantity",
                        condition, "WH", "ERROR"));
        return result.ok() ? ADMITTED
                : result.diagnostics().stream().map(d -> d.code().orElse("(no code)")).distinct().sorted()
                        .reduce((a, b) -> a + "," + b).orElse(ADMITTED);
    }
}
