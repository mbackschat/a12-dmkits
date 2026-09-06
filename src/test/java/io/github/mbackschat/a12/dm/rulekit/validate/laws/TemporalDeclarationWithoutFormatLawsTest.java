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
 * KIND's default</b>; and the neighbouring shape, an <b>empty format string</b>, is refused outright.
 *
 * <p><b>Absent and empty are two different declarations, and that is the whole point of this class.</b> An
 * empty type payload ({@code "DateType": {}}) is kernel-valid and behaves as the kind's default spelling; a
 * present-but-blank {@code "format": ""} dies in the kernel's own type converter. Our writer cannot produce
 * either — {@code DocumentModelDraft} substitutes the kind default for a null format — so both shapes are
 * built here by editing the emitted DM-JSON, which is also the honest record of why no builder row exists.
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

    /** The same three with a present-but-BLANK format string — the neighbouring shape, and a different one. */
    private static final String BLANK_FORMAT = rewriteNoFmtPayloads(payload -> payload.put("format", ""));

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
     * The neighbouring shape, and the reason this class also guards a REPORTING contract: a blank format
     * string is refused by the kernel's own type converter, inside {@code checkConsistency} and before any
     * structured problem exists. Left to escape it reaches a caller as an internal-defect envelope, which
     * asserts the opposite of the truth — the input is invalid and the kernel is what said so.
     */
    @Test
    void aBlankFormatStringIsARejectedINPUTAndNotAnInternalDefect() {
        ModelConsistencyException rejected = assertThrows(ModelConsistencyException.class,
                () -> BoundModel.ofJson(BLANK_FORMAT),
                "a blank temporal format must arrive as a model rejection, not as a raw kernel throw");
        assertTrue(rejected.notifications().stream()
                        .anyMatch(n -> n.message().contains("the kernel could not build this model")),
                () -> "the rejection must carry the kernel's own reason: " + rejected.notifications());
    }
}
