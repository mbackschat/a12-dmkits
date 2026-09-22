package io.github.mbackschat.a12.dm.adapter.laws;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import io.github.mbackschat.a12.dm.rulekit.dsl.BoundModel;
import io.github.mbackschat.a12.dm.rulekit.dsl.Condition;
import io.github.mbackschat.a12.dm.rulekit.dsl.Dsl;

/**
 * WHICH text does each kernel evaluation channel read, when A12's two renderings of one stored decimal
 * DISAGREE? The serializer writes {@code bd.toPlainString()}; the runtime's {@code IData} renders
 * {@code bd.setScale(max(bd.stripTrailingZeros().scale(), minFractionalDigits)).toPlainString()}
 * (KERNEL-FINDINGS "The canonical Document JSON …"). This class measures, channel by channel, which one wins —
 * and it measures BOTH storage regimes, because the answer differs between them:
 *
 * <ul>
 *   <li><b>V2/BigDecimal</b> — the document came through {@code IDocumentV2Serializer.deserializeV2}, so the
 *       cell holds a {@code BigDecimal} and {@code ValidationData.getValue} CONVERTS it;</li>
 *   <li><b>V1/String</b> — the document was built with {@code IDocumentFactory.createFieldInstance(path, reps,
 *       String)}, so the cell holds a {@code String} and {@code ValidationData.getValue} returns it UNCHANGED.</li>
 * </ul>
 *
 * The regime is a property of the stored value's Java TYPE, not of the text — so one and the same text is legal
 * in one regime and a formal error in the other. That is the measurement a reimplementation whose cell holds
 * only text has to answer for (INTERPRETER-FINDINGS IF231).
 *
 * <p>The length bounds are the sharp instrument here: {@code minLength}/{@code maxLength} count the rendered
 * text's characters, so {@code 250} (3) and {@code 250.00} (6) are told apart by a code that has nothing to do
 * with decimal separators — which is how the padding half of the formula is discriminated independently of
 * {@code zahlOhneDezimalTrenner}.
 */
class NumberRenderChannelKernelTest {

    private static final String MODEL = RuntimeLaws.model("models/decimal-render.dm.json");
    private static final BoundModel m = BoundModel.ofJson(MODEL);

    private static final String REFERENCE = "/Shipment/Reference";
    private static final String LOAD = "/Shipment/Parcels/Load";
    private static final String TAG = "/Shipment/Parcels/Tag";
    private static final String UNITS = "/Shipment/Crates/Units";
    private static final String CODE = "/Shipment/Crates/Code";

    private static String render(Condition c) {
        return RuntimeLaws.render(c);
    }

    /** The error field must be referenced in the condition (MVK_ERROR_FIELD_NOT_REFERENCED). */
    private static String withRule(Condition c) {
        return RuntimeLaws.withRule(MODEL, "/Shipment", "P", "../Reference", "P",
                render(m.str(REFERENCE).filled().and(c)));
    }

    // ── the two regimes, as one-line observations ────────────────────────────────────────────────────

    /** V2: the document arrives as Document JSON, so a JSON number is stored as a {@code BigDecimal}. */
    private static String v2(String model, String documentJson) {
        RuntimeLaws.JsonIngress in = RuntimeLaws.jsonIngress(model, documentJson);
        return in.codes() + " rewritten=" + in.rewritten();
    }

    /** V1: the same text stored as a {@code String} through {@code createFieldInstance}. */
    private static String v1(String model, Map<String, String> fields) {
        return new TreeSet<>(RuntimeLaws.firedCodes(model, Set.of("/Shipment"), fields)).toString();
    }

    private static Map<String, String> withReference(String path, String text) {
        Map<String, String> fields = new LinkedHashMap<>();
        fields.put(REFERENCE, "R");
        fields.put(path, text);
        return fields;
    }

    // ── Channel 1+2: the FORMAL check — the number format checks AND the value-string length bounds ──

    @Test
    void theFormalCheckReadsTheRuntimeRenderingInV2AndTheAuthoredTextInV1() {
        record Case(String field, String text) {
        }
        List<Case> cases = List.of(
            // strip direction — the write-faithful text carries a fractional trailing zero
            new Case("Weight", "250.00"),          // maxFrac 0            → runtime "250" is legal, "250.00" is not
            new Case("Price", "250.00"),           // maxFrac 2            → both texts are format-legal
            new Case("PriceCapped", "250.00"),     // maxFrac 2, maxLen 3  → "250" fits, "250.00" is too long
            new Case("PriceFloored", "250.00"),    // maxFrac 2, minLen 4  → "250" is too short, "250.00" is not
            // pad direction — the runtime rendering is LONGER than the write-faithful text
            new Case("Freight", "250"),            // minFrac 2            → runtime "250.00" is legal, "250" is not
            new Case("Freight", "250.0"),
            new Case("Freight", "250.5"),
            new Case("Freight", "250.00"),
            new Case("FreightCapped", "250"),      // minFrac 2, maxLen 5  → "250.00" is too long, "250" is not
            new Case("FreightCapped", "1")         //                      → "1.00" fits: the control
        );
        List<String> report = new ArrayList<>();
        for (Case c : cases) {
            String path = "/Shipment/" + c.field();
            report.add("%-14s %-7s V1=%-38s V2=%s".formatted(c.field(), c.text(),
                    v1(MODEL, Map.of(path, c.text())),
                    v2(MODEL, "{\"Shipment\":{\"" + c.field() + "\":" + c.text() + "}}")));
        }
        assertEquals("""
            Weight         250.00  V1=[zahlHatDezimalTrenner]                V2=[] rewritten={"Shipment":{"Weight":250.00}}
            Price          250.00  V1=[]                                     V2=[] rewritten={"Shipment":{"Price":250.00}}
            PriceCapped    250.00  V1=[zahlZuLang]                           V2=[] rewritten={"Shipment":{"PriceCapped":250.00}}
            PriceFloored   250.00  V1=[]                                     V2=[zahlZuKurz] rewritten={"Shipment":{"PriceFloored":250.00}}
            Freight        250     V1=[zahlOhneDezimalTrenner]               V2=[] rewritten={"Shipment":{"Freight":250}}
            Freight        250.0   V1=[zahlFalscheAnzahlNachkommastellen]    V2=[] rewritten={"Shipment":{"Freight":250.0}}
            Freight        250.5   V1=[zahlFalscheAnzahlNachkommastellen]    V2=[] rewritten={"Shipment":{"Freight":250.5}}
            Freight        250.00  V1=[]                                     V2=[] rewritten={"Shipment":{"Freight":250.00}}
            FreightCapped  250     V1=[zahlOhneDezimalTrenner]               V2=[zahlZuLang] rewritten={"Shipment":{"FreightCapped":250}}
            FreightCapped  1       V1=[zahlOhneDezimalTrenner]               V2=[] rewritten={"Shipment":{"FreightCapped":1}}"""
            .stripIndent(), String.join("\n", report));
    }

    // ── Channel 3: FieldValueAsString(numberField) — a genuine text read, compared to a literal ──────

    @Test
    void fieldValueAsStringReadsTheSameRenderingTheFormalCheckDoes() {
        record Case(String field, String text, String literal) {
        }
        List<Case> cases = List.of(
            new Case("Price", "250.00", "250"),          // maxFrac 2, strip direction
            new Case("Price", "250.00", "250.00"),
            new Case("Freight", "250.00", "250"),        // minFrac 2 — the padded rendering
            new Case("Freight", "250.00", "250.00")
        );
        List<String> report = new ArrayList<>();
        for (Case c : cases) {
            String path = "/Shipment/" + c.field();
            String model = withRule(Dsl.fieldValueAsString(m.field(path)).eq(c.literal()));
            report.add("FieldValueAsString(%s)==%-8s stored=%s V1=%-6s V2=%s".formatted(
                    c.field(), '"' + c.literal() + '"', c.text(),
                    v1(model, withReference(path, c.text())),
                    v2(model, "{\"Shipment\":{\"Reference\":\"R\",\"" + c.field() + "\":" + c.text() + "}}")));
        }
        assertEquals("""
            FieldValueAsString(Price)=="250"    stored=250.00 V1=[]     V2=[P] rewritten={"Shipment":{"Price":250.00,"Reference":"R"}}
            FieldValueAsString(Price)=="250.00" stored=250.00 V1=[P]    V2=[] rewritten={"Shipment":{"Price":250.00,"Reference":"R"}}
            FieldValueAsString(Freight)=="250"    stored=250.00 V1=[]     V2=[] rewritten={"Shipment":{"Freight":250.00,"Reference":"R"}}
            FieldValueAsString(Freight)=="250.00" stored=250.00 V1=[P]    V2=[P] rewritten={"Shipment":{"Freight":250.00,"Reference":"R"}}"""
            .stripIndent(), String.join("\n", report));
    }

    // ── Channel 4: the semantic index [F For "key"] on a NUMBER index field ─────────────────────────

    /**
     * On a {@code minFractionalDigits: 2} index field the literal {@code "250"} is not even AUTHORABLE — the
     * kernel format-checks the {@code For} key against the index field's declaration at code generation.
     */
    @Test
    void theSemanticIndexLiteralIsFormatCheckedAgainstTheIndexFieldsDeclaration() {
        String model = withRule(m.num(LOAD).forIndex("250").gt(0));
        String message = Assertions.assertThrows(RuntimeException.class,
                () -> RuntimeLaws.jsonIngress(model,
                        "{\"Shipment\":{\"Reference\":\"R\",\"Parcels\":[{\"Tag\":250,\"Load\":7}]}}")).getMessage();
        assertTrue(message.contains("MVK_INDEX_VALUE_INVALID"), message);
    }

    @Test
    void theSemanticIndexKeyMatchesOnTheRuntimeRendering() {
        List<String> report = new ArrayList<>();
        // Tag declares minFrac 2, so "250.00" is the only authorable literal; the stored value is written back
        // as `250` yet must still match it.
        for (String stored : List.of("250", "250.00")) {
            String model = withRule(m.num(LOAD).forIndex("250.00").gt(0));
            report.add("[Load For \"250.00\"]>0  Tag=%-6s V2=%s".formatted(stored,
                    v2(model, "{\"Shipment\":{\"Reference\":\"R\",\"Parcels\":[{\"Tag\":" + stored
                            + ",\"Load\":7}]}}")));
        }
        // Code declares NO minFrac, so BOTH spellings of the key are authorable — which is what shows the
        // LITERAL is normalized too, not only the stored side.
        for (String key : List.of("250", "250.00")) {
            String model = withRule(m.num(UNITS).forIndex(key).gt(0));
            report.add("[Units For %-8s]>0 Code=250.00 V2=%s".formatted('"' + key + '"',
                    v2(model, "{\"Shipment\":{\"Reference\":\"R\",\"Crates\":[{\"Code\":250.00,\"Units\":7}]}}")));
        }
        assertEquals("""
            [Load For "250.00"]>0  Tag=250    V2=[P] rewritten={"Shipment":{"Reference":"R","Parcels":[{"Load":7,"Tag":250}]}}
            [Load For "250.00"]>0  Tag=250.00 V2=[P] rewritten={"Shipment":{"Reference":"R","Parcels":[{"Load":7,"Tag":250.00}]}}
            [Units For "250"   ]>0 Code=250.00 V2=[P] rewritten={"Shipment":{"Reference":"R","Crates":[{"Code":250.00,"Units":7}]}}
            [Units For "250.00"]>0 Code=250.00 V2=[P] rewritten={"Shipment":{"Reference":"R","Crates":[{"Code":250.00,"Units":7}]}}"""
            .stripIndent(), String.join("\n", report));
    }

    // ── Channel 5: UNIQUENESS across two rows whose write-faithful renderings differ ─────────────────

    @Test
    void bothUniquenessChannelsCompareOnTheRuntimeRendering() {
        // the built-in index-field check (`uniqueIndex`), on a minFrac-2 index field
        RuntimeLaws.Doc tags = RuntimeLaws.doc().group("/Shipment", 1).field(REFERENCE, "R", 1, 1)
                .group("/Shipment/Parcels", 1, 1).field(TAG, "250", 1, 1, 1)
                .group("/Shipment/Parcels", 1, 2).field(TAG, "250.00", 1, 2, 1);
        // the author-level `RepetitionNotUnique`, on a non-index maxFrac-2 field. The error field must sit INSIDE
        // the iterated group, else the bare reference needs an asterisk (MVK_NO_WILDCARD).
        String rnu = RuntimeLaws.withRule(MODEL, "/Shipment", "P", "../Crates/Units", "P",
                render(Dsl.repetitionNotUnique(m.field(UNITS))));
        RuntimeLaws.Doc units = RuntimeLaws.doc().group("/Shipment", 1).field(REFERENCE, "R", 1, 1)
                .group("/Shipment/Crates", 1, 1).field(CODE, "1", 1, 1, 1).field(UNITS, "250", 1, 1, 1)
                .group("/Shipment/Crates", 1, 2).field(CODE, "2", 1, 2, 1).field(UNITS, "250.00", 1, 2, 1);
        // Both are NUMERIC comparisons, so neither can force one rendering over the other: `uniqueIndex`
        // normalizes through the very same `convertFromBasicType` (V2 collides on the padded `250.00`; V1's
        // `250` is a formal error, which drops the row before uniqueness), and `RepetitionNotUnique` compares
        // decimals (V1's two DIFFERENT texts `250`/`250.00` still fire).
        assertEquals("""
            uniqueIndex Tag rows [250, 250.00]   V1=[zahlOhneDezimalTrenner] V2=[uniqueIndex, uniqueIndex] rewritten={"Shipment":{"Parcels":[{"Tag":250},{"Tag":250.00}]}}
            RepetitionNotUnique(Units) rows [250, 250.00] V1=[P] V2=[P, P] rewritten={"Shipment":{"Reference":"R","Crates":[{"Code":1,"Units":250},{"Code":2,"Units":250.00}]}}"""
                .stripIndent(), "uniqueIndex Tag rows [250, 250.00]   V1=%s V2=%s%nRepetitionNotUnique(Units) rows [250, 250.00] V1=%s V2=%s"
                .formatted(
                        new TreeSet<>(RuntimeLaws.firedCodes(MODEL, tags)),
                        v2(MODEL, "{\"Shipment\":{\"Parcels\":[{\"Tag\":250},{\"Tag\":250.00}]}}"),
                        new TreeSet<>(RuntimeLaws.firedCodes(rnu, units)),
                        v2(rnu, "{\"Shipment\":{\"Reference\":\"R\",\"Crates\":"
                                + "[{\"Code\":1,\"Units\":250},{\"Code\":2,\"Units\":250.00}]}}")));
    }

    // ── Channel 6: is a `pattern` even declarable on a NumberType? ───────────────────────────────────

    /** {@code NumberType} has no {@code pattern} property, and A12's model deserializer REJECTS the key outright —
     *  so there is no pattern channel on a NUMBER field to render text for. */
    @Test
    void aPatternIsNotPartOfTheNumberTypeSoTheChannelDoesNotExist() {
        String patched = MODEL.replace("\"maxFractionalDigits\": 2\n                    }",
                "\"maxFractionalDigits\": 2,\n                      \"pattern\": \"250\"\n                    }");
        assertNotEquals(MODEL, patched, "the probe fixture patch must land");
        assertTrue(patched.contains("\"pattern\": \"250\""), "the probe fixture patch must land");
        Throwable thrown = Assertions.assertThrows(RuntimeException.class,
                () -> RuntimeLaws.modelRoundTrip(patched));
        assertTrue(String.valueOf(thrown.getCause()).contains("pattern"),
                "the kernel's model reader must name the rejected key: " + thrown.getCause());
    }

    // ── the fixture's own preconditions: the declarations we probe must actually BIND ────────────────

    @Test
    void theFixtureDeclarationsSurviveA12sOwnModelSerializer() {
        String rewritten = RuntimeLaws.modelRoundTrip(MODEL);
        assertEquals("minFractionalDigits=3 minLength=1 maxLength=2 indexFieldName=2 consistency=[]",
                "minFractionalDigits=" + count(rewritten, "minFractionalDigits")
                        + " minLength=" + count(rewritten, "minLength")
                        + " maxLength=" + count(rewritten, "maxLength")
                        + " indexFieldName=" + count(rewritten, "indexFieldName")
                        + " consistency=" + RuntimeLaws.consistencyMessages(MODEL));
    }

    private static int count(String haystack, String needle) {
        int n = 0;
        for (int i = haystack.indexOf(needle); i >= 0; i = haystack.indexOf(needle, i + 1)) {
            n++;
        }
        return n;
    }
}
