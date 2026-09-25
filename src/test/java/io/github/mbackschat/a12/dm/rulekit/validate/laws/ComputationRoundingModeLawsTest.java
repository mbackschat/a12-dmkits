package io.github.mbackschat.a12.dm.rulekit.validate.laws;

import static io.github.mbackschat.a12.dm.rulekit.dsl.Dsl.de;
import static io.github.mbackschat.a12.dm.rulekit.dsl.Dsl.en;
import static org.junit.jupiter.api.Assertions.assertEquals;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import io.github.mbackschat.a12.dm.Fixtures;
import io.github.mbackschat.a12.dm.rulekit.dsl.RoundingMode;
import io.github.mbackschat.a12.dm.rulekit.dsl.ToleranceBand;
import io.github.mbackschat.a12.dm.rulekit.validate.DraftComputation;
import io.github.mbackschat.a12.dm.rulekit.write.ModelWriter;

/**
 * A computation's {@code roundingMode}: which spellings A12 reads, and which pairings with an alternative's
 * tolerance band it refuses.
 *
 * <p><b>It is a plain String in the stored model and an ENUM one layer down, and the join between them is one
 * of this family's two laws.</b> {@code Computation.roundingMode} is declared {@code String} with no binding
 * constraint, so nothing refuses a spelling while the source is being read; the mapping layer then does
 * {@code computation.getRoundingMode().map(RoundingMode::getByInternalValue).orElse(EXACT)}
 * ({@code md.model.internal.service.mapping.Calculation:68}), and {@code getByInternalValue} ends in
 * {@code orElseThrow(() -> new IllegalArgumentException(…))}. The admitted set is the enum's internal values
 * and not its constant names — {@code Exact}, {@code RoundUp}, {@code RoundDown}, compared with
 * {@code equals}, so the match is case-SENSITIVE and {@code ROUND_UP} is not a spelling of anything.
 *
 * <p><b>That unchecked throw lands in the NOT BUILDABLE tier rather than {@link KernelLaws#RAISED_UNTYPED},
 * and the layer that puts it there is OURS.</b> The throw does escape every typed kernel channel exactly as
 * the source reads; {@code KernelModelPreparation.notifications()} then catches it around
 * {@code checkConsistency} and re-raises it as a codeless consistency exception, which is KF259's fix doing
 * its job. Stated because a source read of the kernel alone predicts the untyped tier and is wrong about
 * where the value ends up — the measurement, not the read, is what settles the tier.
 *
 * <p><b>The second law is that {@code RoundUp} is not universally refused</b> — {@code MVK_ERROR_WRONG_
 * ROUNDING_MODE} is a judgement about the rounding mode AND the comparison together, so it cannot be read off
 * the mode alone. {@link #aRoundingModeIsOnlyWrongAgainstAStrictInequality} is the 2×2 that separates them;
 * without it every row here shares one comparison operator and "RoundUp is refused" is indistinguishable from
 * "RoundUp is refused against THIS comparison".
 *
 * <p>Own domain (the shared subscription fixture; {@code /Subscription/Billing/EffectiveFee} is a
 * {@code NumberType} with two fractional digits, which the tolerance arm requires).
 */
class ComputationRoundingModeLawsTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static final String GROUP = "/Subscription/Billing";

    /** A scale-2 constant into the scale-2 target, so no operand law can move a row that is about a slot. */
    private static final String OPERATION = "42.00";

    @Test
    void aRoundingModeIsAStrictEnumOfTheKernelsINTERNALValues() {
        Map<String, String> actual = new LinkedHashMap<>();
        actual.put("absent — the control", verdict(null));
        actual.put("Exact", verdict("\"Exact\""));
        actual.put("roundup — the right value, wrong case", verdict("\"roundup\""));
        actual.put("ROUND_UP — the enum CONSTANT rather than its internal value", verdict("\"ROUND_UP\""));
        actual.put("Bankers — outside the set", verdict("\"Bankers\""));
        actual.put("empty", verdict("\"\""));
        actual.put("whitespace only", verdict("\"   \""));

        Map<String, String> expected = new LinkedHashMap<>();
        // An absent member defaults to EXACT, so omitting it is legal — the `orElse` half of the mapping.
        expected.put("absent — the control", KernelLaws.ADMITTED);
        expected.put("Exact", KernelLaws.ADMITTED);
        // Every other spelling reaches `orElseThrow`. A12 reports no code for these: the throw is unchecked,
        // so there is no finding and no reader refusal — only a model A12 could not build.
        expected.put("roundup — the right value, wrong case", KernelLaws.NOT_BUILDABLE);
        expected.put("ROUND_UP — the enum CONSTANT rather than its internal value", KernelLaws.NOT_BUILDABLE);
        expected.put("Bankers — outside the set", KernelLaws.NOT_BUILDABLE);
        // Blank is NOT absence here — the member is present, so the mapping runs and throws.
        expected.put("empty", KernelLaws.NOT_BUILDABLE);
        expected.put("whitespace only", KernelLaws.NOT_BUILDABLE);
        assertEquals(expected, actual, "what A12 does with each spelling of a computation's roundingMode");
    }

    /**
     * The 2×2 that makes {@code MVK_ERROR_WRONG_ROUNDING_MODE} a law about a PAIR rather than about the mode.
     *
     * <p>An alternative's {@code toleranceRangeOp} is what chooses the comparison the computation lowers to:
     * absent gives {@code !=} and a band gives the tolerance operator
     * ({@code ComputationAlternativesJoiner.createComparison}). The kernel's gate is
     * {@code cmpOp == NE && roundingMode != EXACT}
     * ({@code core.calc.internal.util.NormalisierteBerechnungsFormHelper:326}) — both conjuncts, so exactly
     * one cell of this grid may be refused. The same model is built four times differing in one builder call,
     * so no second variable can ride along.
     */
    @Test
    void aRoundingModeIsOnlyWrongAgainstAStrictInequality() {
        Map<String, String> actual = new LinkedHashMap<>();
        actual.put("!= · Exact", verdict(null, RoundingMode.EXACT));
        actual.put("!= · RoundUp", verdict(null, RoundingMode.ROUND_UP));
        actual.put("tolerance 1 · Exact", verdict(ToleranceBand.TR1, RoundingMode.EXACT));
        actual.put("tolerance 1 · RoundUp", verdict(ToleranceBand.TR1, RoundingMode.ROUND_UP));
        actual.put("tolerance 10 · RoundDown", verdict(ToleranceBand.TR10, RoundingMode.ROUND_DOWN));

        Map<String, String> expected = new LinkedHashMap<>();
        expected.put("!= · Exact", KernelLaws.ADMITTED);
        // The one refused cell — and its message says why: "When using exact calculation comparisons only the
        // rounding mode 'exact' is allowed" (kernel-core-calc error_messages_en.properties:23).
        expected.put("!= · RoundUp", "MVK_ERROR_WRONG_ROUNDING_MODE");
        expected.put("tolerance 1 · Exact", KernelLaws.ADMITTED);
        // The discriminating cell: the SAME mode that is refused above is admitted here, so the refusal
        // cannot be attributed to the mode. A checker that rejected `RoundUp` on sight would reject this.
        expected.put("tolerance 1 · RoundUp", KernelLaws.ADMITTED);
        expected.put("tolerance 10 · RoundDown", KernelLaws.ADMITTED);
        assertEquals(expected, actual, "the rounding mode and the comparison are judged together");
    }

    /**
     * Whether the gate reads ONE alternative or all of them — the carrier the single-alternative grid cannot
     * reach, and the one a checker has to get right.
     *
     * <p>The mode is a property of the whole computation while the band is a property of each alternative, so
     * a computation with several alternatives holds several pairs at once. Reading the grid above as "the
     * computation has a comparison" would be a scope transfer onto a carrier never measured: it has exactly
     * one alternative, so its comparison and its alternatives' comparisons are the same thing.
     */
    @Test
    void everyAlternativeIsJudgedAgainstTheModeSeparately() {
        Map<String, String> actual = new LinkedHashMap<>();
        actual.put("both banded · RoundUp",
                verdictOfTwo(ToleranceBand.TR1, ToleranceBand.TR2, RoundingMode.ROUND_UP));
        actual.put("banded then != · RoundUp", verdictOfTwo(ToleranceBand.TR1, null, RoundingMode.ROUND_UP));
        actual.put("!= then banded · RoundUp", verdictOfTwo(null, ToleranceBand.TR1, RoundingMode.ROUND_UP));
        actual.put("banded then != · Exact — the liveness control",
                verdictOfTwo(ToleranceBand.TR1, null, RoundingMode.EXACT));

        Map<String, String> expected = new LinkedHashMap<>();
        expected.put("both banded · RoundUp", KernelLaws.ADMITTED);
        // One plain `!=` alternative is enough, in either position — so the gate is per alternative and a
        // checker may not stop at the first one it likes.
        expected.put("banded then != · RoundUp", "MVK_ERROR_WRONG_ROUNDING_MODE");
        expected.put("!= then banded · RoundUp", "MVK_ERROR_WRONG_ROUNDING_MODE");
        // …and the same two-alternative shape is clean under Exact, so the rejections above are the mode's
        // doing rather than anything about carrying two alternatives.
        expected.put("banded then != · Exact — the liveness control", KernelLaws.ADMITTED);
        assertEquals(expected, actual, "a multi-alternative computation holds one pair per alternative");
    }

    /**
     * The gate's other conjunct, measured the same way — {@code toleranceRangeOp} is a String slot bound to a
     * closed set one layer down, exactly as {@code roundingMode} is.
     *
     * <p>Its admitted values are the four {@code DiffersWithToleranceRange<n>} wire spellings
     * ({@code md.model.a12internal.Computation.TR1}…{@code TR10}); anything else reaches the
     * {@code default -> throw new IllegalArgumentException(…)} arm of
     * {@code ComputationAlternativesJoiner.determineLocalizedToleranceRangeOp}. Measured here rather than
     * inferred from the sibling member, because the two throws sit in different phases and only a run says
     * whether both land in the same tier.
     */
    @Test
    void aToleranceBandIsAStrictEnumOfTheKernelsWIREValues() {
        Map<String, String> actual = new LinkedHashMap<>();
        actual.put("absent — the control", bandVerdict(null));
        actual.put("DiffersWithToleranceRange1", bandVerdict("\"DiffersWithToleranceRange1\""));
        actual.put("DiffersWithToleranceRange10", bandVerdict("\"DiffersWithToleranceRange10\""));
        actual.put("DiffersWithToleranceRange3 — a band that does not exist", bandVerdict("\"DiffersWithToleranceRange3\""));
        actual.put("TR1 — our own constant NAME rather than the wire value", bandVerdict("\"TR1\""));
        actual.put("empty", bandVerdict("\"\""));
        actual.put("an explicit null", bandVerdict("null"));
        actual.put("a number", bandVerdict("7"));
        actual.put("a JSON object", bandVerdict("{\"band\":1}"));
        actual.put("a JSON array", bandVerdict("[\"DiffersWithToleranceRange1\"]"));

        Map<String, String> expected = new LinkedHashMap<>();
        expected.put("absent — the control", KernelLaws.ADMITTED);
        expected.put("DiffersWithToleranceRange1", KernelLaws.ADMITTED);
        expected.put("DiffersWithToleranceRange10", KernelLaws.ADMITTED);
        expected.put("DiffersWithToleranceRange3 — a band that does not exist", KernelLaws.NOT_BUILDABLE);
        expected.put("TR1 — our own constant NAME rather than the wire value", KernelLaws.NOT_BUILDABLE);
        expected.put("empty", KernelLaws.NOT_BUILDABLE);
        // As on the sibling member, an explicit null is ABSENCE rather than a reader refusal — the accessor
        // is `Optional.ofNullable`, and an absent band is the plain `!=` form.
        expected.put("an explicit null", KernelLaws.ADMITTED);
        expected.put("a number", KernelLaws.NOT_BUILDABLE);
        expected.put("a JSON object", KernelLaws.UNREADABLE);
        expected.put("a JSON array", KernelLaws.UNREADABLE);
        assertEquals(expected, actual, "what A12 does with each spelling of an alternative's toleranceRangeOp");
    }

    /**
     * The three shapes that are not strings at all, which the row sets above cannot reach.
     *
     * <p>They are separated because the answer could legitimately differ, and it does: an explicit
     * {@code null} refuses the READER on every rule member measured ({@link RuleCodeAndSeverityLawsTest}) and
     * is simply ABSENCE here, because {@code getRoundingMode} is an {@code Optional.ofNullable} accessor
     * rather than an enum binding. The null tier is therefore a property of the accessor, not of DM-JSON.
     */
    @Test
    void aNonStringRoundingModeIsItsOwnQuestion() {
        Map<String, String> actual = new LinkedHashMap<>();
        actual.put("an explicit null", verdict("null"));
        actual.put("a number", verdict("7"));
        actual.put("a JSON object", verdict("{\"mode\":\"RoundUp\"}"));
        actual.put("a JSON array", verdict("[\"RoundUp\"]"));

        Map<String, String> expected = new LinkedHashMap<>();
        expected.put("an explicit null", KernelLaws.ADMITTED);
        // Jackson coerces a scalar into the String member, so this is the rounding mode "7" — an unknown
        // spelling, landing in the same tier as `Bankers` rather than refusing the reader.
        expected.put("a number", KernelLaws.NOT_BUILDABLE);
        expected.put("a JSON object", KernelLaws.UNREADABLE);
        expected.put("a JSON array", KernelLaws.UNREADABLE);
        assertEquals(expected, actual, "a roundingMode that is not a string");
    }

    /** What A12 says about the probe model with `roundingMode` set to [rawJson], or absent when null. */
    private static String verdict(String rawJson) {
        return KernelLaws.verdictOf(probe(RoundingMode.EXACT, rawJson,
                new DraftComputation.Alternative(null, OPERATION, null)));
    }

    /** The same, for the first alternative's `toleranceRangeOp` — the slot the typed band writes. */
    private static String bandVerdict(String rawJson) {
        String model = probe(RoundingMode.EXACT, null, new DraftComputation.Alternative(null, OPERATION, null));
        try {
            ObjectNode root = (ObjectNode) MAPPER.readTree(model);
            ObjectNode alternative = (ObjectNode) computationBody(root)
                    .path("computationAlternatives").get(0);
            if (rawJson != null) {
                alternative.set("toleranceRangeOp", MAPPER.readTree(rawJson));
            }
            return KernelLaws.verdictOf(root.toString());
        } catch (IOException unreadable) {
            throw new UncheckedIOException("the probe model and the raw fragment must parse", unreadable);
        }
    }

    /** The pair grid is authored through the typed draft: every mode it asks about is one the draft spells. */
    private static String verdict(ToleranceBand band, RoundingMode mode) {
        return KernelLaws.verdictOf(probe(mode, null, new DraftComputation.Alternative(null, OPERATION, band)));
    }

    /**
     * Two alternatives, each with its own band — and each with a precondition, which the kernel requires as
     * soon as a computation has more than one ({@code MVK_ERROR_EMPTY_PRECONDITION}).
     */
    private static String verdictOfTwo(ToleranceBand first, ToleranceBand second, RoundingMode mode) {
        return KernelLaws.verdictOf(probe(mode, null,
                new DraftComputation.Alternative("FieldFilled(InvoiceEmail)", OPERATION, first),
                new DraftComputation.Alternative("FieldNotFilled(InvoiceEmail)", OPERATION, second)));
    }

    /**
     * The probe model, built through the typed draft with [mode], and then — when [rawJson] is not null — given
     * a stored spelling the draft cannot express, since only a hand-written or foreign model carries one.
     */
    private static String probe(RoundingMode mode, String rawJson, DraftComputation.Alternative... alternatives) {
        DraftComputation draft = DraftComputation.builder()
                .groupPath(GROUP).name("RoundingProbe").computedFieldRelPath("../EffectiveFee")
                .alternatives(List.of(alternatives)).roundingMode(mode)
                .messages(List.of(en("x"), de("y")))
                .build();
        String written = ModelWriter.addComputation(Fixtures.subscriptionJson(), draft,
                List.of(en("x"), de("y")));
        try {
            ObjectNode root = (ObjectNode) MAPPER.readTree(written);
            ObjectNode computation = computationBody(root);
            if (rawJson != null) {
                computation.set("roundingMode", MAPPER.readTree(rawJson));
            }
            return root.toString();
        } catch (IOException unreadable) {
            throw new UncheckedIOException("the writer's own output and the raw fragment must parse", unreadable);
        }
    }

    /** The probe computation's body, refusing rather than returning a model the substitution never reached. */
    private static ObjectNode computationBody(ObjectNode root) {
        for (var top : root.path("content").path("modelRoot").path("rootGroups")) {
            ObjectNode found = findProbe(top);
            if (found != null) {
                return found;
            }
        }
        throw new IllegalStateException("the probe computation is no longer where this test writes it");
    }

    private static ObjectNode findProbe(JsonNode node) {
        if ("RoundingProbe".equals(node.path("name").asText()) && node.has("Computation")) {
            return (ObjectNode) node.path("Computation");
        }
        for (var child : node.path("Group").path("elements")) {
            ObjectNode found = findProbe(child);
            if (found != null) {
                return found;
            }
        }
        return null;
    }
}
