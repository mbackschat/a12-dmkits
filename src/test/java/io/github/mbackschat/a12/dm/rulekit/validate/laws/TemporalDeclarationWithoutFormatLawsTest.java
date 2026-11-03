package io.github.mbackschat.a12.dm.rulekit.validate.laws;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.LinkedHashMap;
import java.util.Map;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import org.junit.jupiter.api.Test;

import io.github.mbackschat.a12.dm.core.ModelConsistencyException;
import io.github.mbackschat.a12.dm.modelkit.DocumentModelDraft;
import io.github.mbackschat.a12.dm.rulekit.dsl.BoundModel;

/**
 * KERNEL-FINDINGS §6 — <b>a temporal field may declare no format at all, and the kernel then resolves its
 * KIND's default</b>; on kernel 31.1.1 an <b>empty format string</b> is the same declaration, and a <b>blank</b> one
 * is refused outright.
 *
 * <p><b>Absent and empty were two declarations on 30.8.1 and are one on 31.1.1.</b> An empty type payload
 * ({@code "DateType": {}}) is kernel-valid and behaves as the kind's default spelling. A present {@code "format": ""}
 * died in the kernel's own type converter on 30.8.1; on 31.1.1 A12's expand step copies the model through its own
 * serializer, which omits an empty string, so the copy reads the default and every gate below answers as for an
 * absent format ([KF658], {@code adapter.ExpandCopyEmptyMemberLawsTest} for the raw-versus-expanded discriminator). A
 * BLANK format is not empty, survives the copy and still dies there. Our writer cannot produce any of these —
 * {@code DocumentModelDraft} substitutes the kind default for a null format — so the shapes are built here by editing
 * the emitted DM-JSON, which is also the honest record of why no builder row exists.
 *
 * <p><b>Three gates pin the resolved default, and no one of them alone would.</b> The format-STRING gate
 * ({@code FieldValuesNotUnique}) shows the resolved value compares EQUAL to the default spelling rather than
 * being exempted from a gate it has no string for. The component-SET gate
 * ({@code NumberOfDifferentValues}) shows the three kinds do not share one default. The extractor gate shows
 * which components the resolved default carries. A consumer holding only the first could not tell a resolved
 * string from a waiver, and one holding only the second could not tell the defaults apart from each other.
 *
 * <p>Reported by a12-kernel-lean as {@code SPEC-2026-09-06-02}, whose own rows could bound the default's
 * equality class but not exhibit it; the extractor column is added here.
 *
 * <p>Own domain (an insurance audit trail).
 */
class TemporalDeclarationWithoutFormatLawsTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static final String GROUP = "/Audit";
    private static final String ERROR_REL = "../Actor";

    /** The kernel's class for a temporal operand list whose declared FORMAT STRINGS differ. */
    private static final String FORMAT_STRING_GATE = "MVK_ONLY_STRING_ENUM_NUMBER_DATE_ALLOWED";
    /** The kernel's class for a temporal operand list whose declared COMPONENT SETS differ. */
    private static final String COMPONENT_SET_GATE = "MVK_DATEFORMATS_NOT_COMPATIBLE";
    /** The kernel's class for an extractor whose operand does not carry the component it reads. */
    private static final String WRONG_COMPONENTS = "MVK_WRONG_DATE_FORMAT_FOR_OP";

    private static final String DECLARED = DocumentModelDraft.named("audit-trail").locale("en_US")
            .rootGroup("Audit", a -> a
                    .stringField("Actor", s -> { })
                    .dateField("NoFmtDate", d -> d.format("yyyy-MM-dd"))
                    .timeField("NoFmtTime", t -> t.format("HH:mm:ss"))
                    .dateTimeField("NoFmtStamp", dt -> dt.format("yyyy-MM-dd'T'HH:mm:ss"))
                    .dateField("IsoDate", d -> d.format("yyyy-MM-dd"))
                    .dateField("GermanDate", d -> d.format("dd.MM.yyyy"))
                    .timeField("FullClock", t -> t.format("HH:mm:ss"))
                    .dateTimeField("FullStamp", dt -> dt.format("yyyy-MM-dd'T'HH:mm:ss")))
            .build().toJson();

    /** The three {@code NoFmt*} fields with an EMPTY type payload — the shape our own writer cannot emit. */
    private static final String NO_FORMAT = rewriteNoFmtPayloads(payload -> { });

    /** The same three with a present EMPTY format string, which kernel 31.1.1 reads as the absent one. */
    private static final String EMPTY_FORMAT = rewriteNoFmtPayloads(payload -> payload.put("format", ""));

    /** The same three with a BLANK format string — not empty, so it survives A12's expand copy. */
    private static final String BLANK_FORMAT = rewriteNoFmtPayloads(payload -> payload.put("format", " "));

    /** Every gate row of the three tests below, so the empty and absent declarations can be put to all of them. */
    private static final java.util.List<String> GATE_ROWS = java.util.List.of(
            "FieldFilled(NoFmtDate)", "FieldValuesNotUnique(NoFmtDate, IsoDate)",
            "FieldValuesNotUnique(NoFmtDate, GermanDate)", "FieldValuesNotUnique(NoFmtTime, FullClock)",
            "FieldValuesNotUnique(NoFmtStamp, FullStamp)", "NumberOfDifferentValues(NoFmtDate, NoFmtStamp) == 1",
            "NumberOfDifferentValues(NoFmtDate, NoFmtTime) == 1", "NumberOfDifferentValues(NoFmtDate, GermanDate) == 1",
            "NumberOfDifferentValues(NoFmtTime, FullClock) == 1", "YearFromDate(NoFmtDate) == 2024",
            "HoursFromTime(NoFmtDate) == 0", "YearFromDate(NoFmtTime) == 2024", "HoursFromTime(NoFmtTime) == 0",
            "YearFromDate(NoFmtStamp) == 2024", "HoursFromTime(NoFmtStamp) == 0");

    private static String rewriteNoFmtPayloads(java.util.function.Consumer<ObjectNode> edit) {
        try {
            JsonNode root = MAPPER.readTree(DECLARED);
            JsonNode elements = root.path("content").path("modelRoot").path("rootGroups").get(0)
                    .path("Group").path("elements");
            for (JsonNode element : elements) {
                if (!element.path("name").asText().startsWith("NoFmt")) {
                    continue;
                }
                ObjectNode fieldType = (ObjectNode) element.path("Field").path("fieldType");
                ObjectNode payload = MAPPER.createObjectNode();
                edit.accept(payload);
                fieldType.set(fieldType.path("type").asText(), payload);
            }
            return MAPPER.writeValueAsString(root);
        } catch (Exception e) {
            throw new IllegalStateException("could not build the format-less variant", e);
        }
    }

    private static String verdict(String model, String condition) {
        var result = KernelLaws.checkModel(model, GROUP, ERROR_REL, "FieldFilled(Actor) And " + condition);
        return result.ok() ? KernelLaws.ADMITTED : result.diagnostics().stream()
                .map(d -> d.code().orElse("(no code: " + d.summary() + ")"))
                .reduce((a, b) -> a + "," + b).orElse("(rejected, no diagnostics)");
    }

    @Test
    void aTemporalFieldMayDeclareNoFormatAtAll() {
        assertEquals(KernelLaws.ADMITTED, verdict(NO_FORMAT, "FieldFilled(NoFmtDate)"),
                "an empty DateType/TimeType/DateTimeType payload is kernel-valid");
    }

    /**
     * The format-STRING gate: the resolved default compares equal to the default SPELLING, so a format-less
     * DATE joins {@code yyyy-MM-dd} and is refused beside {@code dd.MM.yyyy} exactly as a declared one is.
     */
    @Test
    void theResolvedDefaultIsAStringAndEqualsTheKindsDefaultSpelling() {
        Map<String, String> actual = new LinkedHashMap<>();
        actual.put("format-less DATE beside yyyy-MM-dd", verdict(NO_FORMAT,
                "FieldValuesNotUnique(NoFmtDate, IsoDate)"));
        actual.put("format-less DATE beside dd.MM.yyyy", verdict(NO_FORMAT,
                "FieldValuesNotUnique(NoFmtDate, GermanDate)"));
        actual.put("format-less TIME beside HH:mm:ss", verdict(NO_FORMAT,
                "FieldValuesNotUnique(NoFmtTime, FullClock)"));
        actual.put("format-less DATETIME beside the ISO instant", verdict(NO_FORMAT,
                "FieldValuesNotUnique(NoFmtStamp, FullStamp)"));
        actual.put("two DECLARED spellings — the gate is armed", verdict(NO_FORMAT,
                "FieldValuesNotUnique(IsoDate, GermanDate)"));

        Map<String, String> expected = new LinkedHashMap<>();
        expected.put("format-less DATE beside yyyy-MM-dd", KernelLaws.ADMITTED);
        expected.put("format-less DATE beside dd.MM.yyyy", FORMAT_STRING_GATE);
        expected.put("format-less TIME beside HH:mm:ss", KernelLaws.ADMITTED);
        expected.put("format-less DATETIME beside the ISO instant", KernelLaws.ADMITTED);
        expected.put("two DECLARED spellings — the gate is armed", FORMAT_STRING_GATE);
        assertEquals(expected, actual, "the resolved default is the kind's default format STRING");
    }

    /** The component-SET gate: the three kinds resolve three different defaults, not one shared one. */
    @Test
    void theThreeKindsDoNotShareOneDefault() {
        Map<String, String> actual = new LinkedHashMap<>();
        actual.put("format-less DATE beside format-less DATETIME", verdict(NO_FORMAT,
                "NumberOfDifferentValues(NoFmtDate, NoFmtStamp) == 1"));
        actual.put("format-less DATE beside format-less TIME", verdict(NO_FORMAT,
                "NumberOfDifferentValues(NoFmtDate, NoFmtTime) == 1"));
        actual.put("format-less DATE beside dd.MM.yyyy — same SET, different spelling", verdict(NO_FORMAT,
                "NumberOfDifferentValues(NoFmtDate, GermanDate) == 1"));
        actual.put("format-less TIME beside HH:mm:ss", verdict(NO_FORMAT,
                "NumberOfDifferentValues(NoFmtTime, FullClock) == 1"));

        Map<String, String> expected = new LinkedHashMap<>();
        expected.put("format-less DATE beside format-less DATETIME", COMPONENT_SET_GATE);
        expected.put("format-less DATE beside format-less TIME", COMPONENT_SET_GATE);
        expected.put("format-less DATE beside dd.MM.yyyy — same SET, different spelling", KernelLaws.ADMITTED);
        expected.put("format-less TIME beside HH:mm:ss", KernelLaws.ADMITTED);
        assertEquals(expected, actual, "each kind resolves its own default's component set");
    }

    /** The extractor gate: which components the resolved default actually carries. */
    @Test
    void theResolvedDefaultCarriesItsOwnKindsComponents() {
        Map<String, String> actual = new LinkedHashMap<>();
        actual.put("YearFromDate on the format-less DATE", verdict(NO_FORMAT, "YearFromDate(NoFmtDate) == 2024"));
        actual.put("HoursFromTime on the format-less DATE", verdict(NO_FORMAT, "HoursFromTime(NoFmtDate) == 0"));
        actual.put("YearFromDate on the format-less TIME", verdict(NO_FORMAT, "YearFromDate(NoFmtTime) == 2024"));
        actual.put("HoursFromTime on the format-less TIME", verdict(NO_FORMAT, "HoursFromTime(NoFmtTime) == 0"));
        actual.put("YearFromDate on the format-less DATETIME", verdict(NO_FORMAT,
                "YearFromDate(NoFmtStamp) == 2024"));
        actual.put("HoursFromTime on the format-less DATETIME", verdict(NO_FORMAT,
                "HoursFromTime(NoFmtStamp) == 0"));

        Map<String, String> expected = new LinkedHashMap<>();
        expected.put("YearFromDate on the format-less DATE", KernelLaws.ADMITTED);
        expected.put("HoursFromTime on the format-less DATE", WRONG_COMPONENTS);
        expected.put("YearFromDate on the format-less TIME", WRONG_COMPONENTS);
        expected.put("HoursFromTime on the format-less TIME", KernelLaws.ADMITTED);
        expected.put("YearFromDate on the format-less DATETIME", KernelLaws.ADMITTED);
        expected.put("HoursFromTime on the format-less DATETIME", KernelLaws.ADMITTED);
        assertEquals(expected, actual, "DATE resolves a complete date, TIME a full clock, DATETIME a full stamp");
    }

    /**
     * An empty format is the absent one on every gate row (kernel 31.1.1, [KF658]). The rows include refusals, so
     * equality here is not two admissions agreeing by default.
     */
    @Test
    void anEmptyFormatAnswersEveryGateAsAnAbsentOne() {
        Map<String, String> empty = new LinkedHashMap<>();
        Map<String, String> absent = new LinkedHashMap<>();
        GATE_ROWS.forEach(row -> {
            empty.put(row, verdict(EMPTY_FORMAT, row));
            absent.put(row, verdict(NO_FORMAT, row));
        });
        assertTrue(absent.values().stream().anyMatch(v -> !v.equals(KernelLaws.ADMITTED)),
                () -> "the gate rows must include a refusal: " + absent);
        assertEquals(absent, empty, "an empty format and an absent one, row for row");
    }

    /**
     * The neighbouring shape, and the reason this class also guards a REPORTING contract: a blank format
     * string is refused by the kernel's own type converter, before any structured problem exists. Left to escape
     * it reaches a caller as an internal-defect envelope, which asserts the opposite of the truth — the input is
     * invalid and the kernel is what said so. On 31.1.1 the throw comes out of A12's expand rather than out of
     * {@code checkConsistency}, which the adapter folds the same way (KF259).
     */
    @Test
    void aBlankFormatStringIsARejectedINPUTAndNotAnInternalDefect() {
        ModelConsistencyException rejected = assertThrows(ModelConsistencyException.class,
                () -> BoundModel.ofJson(BLANK_FORMAT),
                "a blank temporal format must arrive as a model rejection, not as a raw kernel throw");
        assertTrue(rejected.notifications().stream()
                        .anyMatch(n -> n.message().contains("the kernel could not build this model")),
                () -> "the rejection must carry the kernel's own reason: " + rejected.notifications());
        // The MECHANISM, not just the envelope. KERNEL-FINDINGS KF259 and two source comments say a blank
        // format dies on a `notBlank` precondition inside the kernel's converter; until 2026-09-07 that was
        // stated in three places and asserted in none. The kernel's own words are Commons Lang's
        // `Validate.notBlank` failure, which is what makes the claim checkable rather than a source reading.
        assertTrue(rejected.getMessage().contains("The validated character sequence is blank"),
                () -> "the kernel's own notBlank reason must survive into the rejection: "
                        + rejected.getMessage());
    }
}
