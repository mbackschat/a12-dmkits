package io.github.mbackschat.a12.dm.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The {@code --instance} boundary speaks A12's canonical Document JSON — the SHAPE and the I/O BEHAVIOUR
 * (CLAUDE.md "…and adopt A12's JSON I/O BEHAVIOUR exactly, not just its shape"). Every numeric row below is a
 * measured row of {@code adapter … A12JsonIngressKernelTest}, so these are end-to-end assertions that the tool
 * agrees with the real Kernel's own ingress, not merely with itself.
 *
 * <p><b>The defect this locks.</b> dmtool used to walk the instance tree itself, storing the authored text
 * verbatim. That is the Kernel's V1 regime, not its Document-JSON regime, so the tool reported formal errors on
 * documents A12 accepts silently: {@code 1e5} drew {@code zahlHatUngueltigeZeichen}, {@code "+5"}
 * {@code zahlMitPlus}, {@code "007"} {@code zahlHatFuehrendeNullen}. Reading through the interpreter's canonical
 * codec — the one implementation of the shape — makes the value normalization A12 performs at ingestion apply
 * here too. {@code /Order/Quantity} is {@code maxFractionalDigits: 0} and declares no
 * {@code leadingZerosAllowed}, which is what makes each row discriminating.
 */
class DocumentInstanceIngressTest {

    private static final ObjectMapper M = new ObjectMapper();

    @TempDir
    Path dir;

    /** The codes `model eval` reports for one authored {@code /Order/Quantity} value. */
    private List<String> firedForQuantity(String authoredJson) throws Exception {
        Path model = CliTestSupport.orderRuled(dir);
        Path instance = dir.resolve("instance.json");
        Files.writeString(instance, "{ \"Order\": { \"Quantity\": " + authoredJson + " } }");

        CliTestSupport.Result res =
                CliTestSupport.run("-m", model.toString(), "model", "eval", "--instance", instance.toString());

        assertEquals(0, res.code(), () -> "model eval must read the document: " + res.err() + res.out());
        JsonNode fired = M.readTree(res.out()).path("data").path("fired");
        return M.convertValue(fired, new TypeReference<List<String>>() { });
    }

    @Test
    void aSpellingA12NormalizesAwayDrawsNoFormalErrorHereEither() throws Exception {
        // A12's converter is `new BigDecimal(String)` and every later text is re-rendered from the VALUE, so
        // these four spellings are stored 100000 / 100000 / 5 / 7 and are formally clean. Each of them used to
        // fire — the regression this test exists for.
        assertEquals(List.of(), firedForQuantity("1e5"), "a native exponent literal normalizes, as in A12");
        assertEquals(List.of(), firedForQuantity("\"1e5\""), "quoted and native spellings agree");
        assertEquals(List.of(), firedForQuantity("\"+5\""), "A12's converter accepts a leading plus and drops it");
        assertEquals(List.of(), firedForQuantity("\"007\""),
                "leading zeros are not part of the unscaled value, and this field allows none");
    }

    @Test
    void aDecimalBeyondDoublePrecisionReachesTheFormalCheckIntact() throws Exception {
        // The discriminating half of the old number-fidelity lock, moved to the boundary that now owns it: a
        // double round-trip would mangle this to 1.2345678901234568E21, whose text has non-numeric characters —
        // so `stelligkeitZuLang` (too many digits) is only reachable if the full precision survived.
        assertEquals(List.of("stelligkeitZuLang"), firedForQuantity("1234567890123456789012.5"));
    }

    @Test
    void aSmallDecimalKeepsItsPlainFormRatherThanBecomingScientific() throws Exception {
        // `zahlHatDezimalTrenner` needs a decimal separator in the stored text; 1.0E-7 has none, so this row
        // pins the plain rendering A12 writes (`WRITE_BIGDECIMAL_AS_PLAIN`).
        assertEquals(List.of("zahlHatDezimalTrenner"), firedForQuantity("0.0000001"));
    }

    @Test
    void anAuthoredTrailingZeroSurvivesTheReadAndIsSeenByTheFormalCheck() throws Exception {
        // The scale is retained, which is exactly why the separator check fires. NOTE the Kernel fires NOTHING
        // here: its runtime strips the fractional trailing zeros to `250` while its serializer writes `250.00`,
        // and one stored text cannot be both. That is IG85, a pre-existing residual of the interpreter's cell
        // model (docs/INTERPRETER-GAPS.md), not something this boundary introduces — the row is characterized in
        // `A12JsonIngressDiffTest.KNOWN_DIVERGENCES`. It is asserted here because a scale-LOSING read would
        // store `250` and fire nothing, which is the failure this test must still catch.
        assertEquals(List.of("zahlHatDezimalTrenner"), firedForQuantity("250.00"));
    }

    @Test
    void aCommentedInstanceDocumentIsAcceptedBecauseA12AcceptsOne() throws Exception {
        // A12 builds its document mapper with `JsonParser.Feature.ALLOW_COMMENTS`
        // (`ObjectMapperProvider.createObjectMapperForDocSerializer`), so a commented document is a LEGAL A12
        // artifact — refusing one would make a file A12 reads unreadable here. The retired `DocInstance` mapper
        // configured this; the lock is restored at the boundary that now owns the parse.
        Path model = CliTestSupport.orderRuled(dir);
        Path instance = dir.resolve("commented.json");
        Files.writeString(instance, """
                {
                  // the quantity ordered
                  "Order": { "Quantity": 5 /* a block comment, after the value */ }
                }
                """);

        CliTestSupport.Result res =
                CliTestSupport.run("-m", model.toString(), "model", "eval", "--instance", instance.toString());

        assertEquals(0, res.code(), () -> "a commented document reads: " + res.err());
        assertEquals(firedForQuantity("5"), M.convertValue(
                M.readTree(res.out()).path("data").path("fired"), new TypeReference<List<String>>() { }),
                "and evaluates identically to the same document without comments");
    }

    @Test
    void theRetiredFieldsWrapperFailsWithTheMigrationCorrective() throws Exception {
        Path model = CliTestSupport.orderRuled(dir);
        Path instance = dir.resolve("wrapped.json");
        Files.writeString(instance, "{ \"fields\": { \"Order\": { \"Quantity\": 5 } } }");

        CliTestSupport.Result res =
                CliTestSupport.run("-m", model.toString(), "model", "eval", "--instance", instance.toString());

        assertEquals(2, res.code(), () -> "the wrapper is a clean break, not a silent unwrap: " + res.out());
        assertTrue(res.err().contains("wrapper is gone"), () -> "it corrects the caller: " + res.err());
        assertTrue(res.err().contains("schema model eval"), () -> "and points at the shape: " + res.err());
    }

    @Test
    void aGroupCarryingAValueIsRefusedInsteadOfSilentlyIgnored() throws Exception {
        // The old walker had no model to check against, so a scalar at a declared group was quietly dropped and
        // the document evaluated as if the group were absent. The canonical reader names the position.
        Path model = CliTestSupport.orderRuled(dir);
        Path instance = dir.resolve("badgroup.json");
        Files.writeString(instance, "{ \"Order\": { \"Items\": \"notanarray\" } }");

        CliTestSupport.Result res =
                CliTestSupport.run("-m", model.toString(), "model", "eval", "--instance", instance.toString());

        assertEquals(2, res.code(), () -> "a group cannot carry a value: " + res.out());
        assertTrue(res.err().contains("Order/Items"), () -> "the A12 pointer locates it: " + res.err());
        assertTrue(res.err().contains("declared group"), () -> "and names the mismatch: " + res.err());
    }

    @Test
    void seedWritesTheShapeTheRuntimeVerbsReadWithNativeTypedValues() throws Exception {
        // One codec, both directions: what `model seed` writes must round-trip straight back in. A number and a
        // boolean are NATIVE JSON (A12 does not quote them) and there is no wrapper key.
        Path model = CliTestSupport.orderRuled(dir);
        CliTestSupport.Result seed =
                CliTestSupport.run("-m", model.toString(), "model", "seed", "--seed", "1", "--rows", "/Order/Items:2");
        assertEquals(0, seed.code(), () -> "seed succeeds: " + seed.err());

        JsonNode document = M.readTree(seed.out());
        assertFalse(document.has("fields"), () -> "no wrapper — a root group name is top-level: " + seed.out());
        JsonNode order = document.path("Order");
        assertTrue(order.path("Quantity").isNumber(), () -> "a NUMBER is native JSON: " + seed.out());
        assertTrue(order.path("ExpressShipping").isBoolean(), () -> "a BOOLEAN is native JSON: " + seed.out());
        assertTrue(order.path("OrderDate").isTextual(), () -> "a DATE is a model-format string: " + seed.out());
        assertTrue(order.path("Items").isArray() && order.path("Items").size() == 2,
                () -> "a repeatable group is an array, one element per row: " + seed.out());

        Path instance = dir.resolve("seeded.json");
        Files.writeString(instance, seed.out());
        CliTestSupport.Result eval =
                CliTestSupport.run("-m", model.toString(), "model", "eval", "--instance", instance.toString());
        assertEquals(0, eval.code(), () -> "the seeded document reads straight back in: " + eval.err());
    }
}
