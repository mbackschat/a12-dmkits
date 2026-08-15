package io.github.mbackschat.a12.dm.cli.rule;

import io.github.mbackschat.a12.dm.cli.CliTestSupport;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * {@code rule check --suggest-error-field} (FEATURE-GAPS G4) — proactive error-field guidance. The error
 * field must be REFERENCED by the condition and lie inside its iteration scope; getting it wrong is a top
 * reject class ({@code MVK_ERROR_FIELD_NOT_REFERENCED}). The flag lists the valid candidates up front so an
 * agent picks one before the round-trip, instead of learning by rejection. The fixture's {@code Addons} is a
 * repeatable group with {@code Name} + {@code MonthlyFee}.
 */
class RuleCheckSuggestVerbTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static Path model(Path dir) throws IOException {
        Path f = dir.resolve("m.dm.json");
        Files.writeString(f, CliTestSupport.subscriptionJson());
        return f;
    }

    @Test
    void listsTheReferencedFieldAsACandidateForAValidRule(@TempDir Path dir) throws Exception {
        var res = CliTestSupport.run("-m", model(dir).toString(), "rule", "check",
                "--field", "/Subscription/Addons/MonthlyFee",
                "--condition", "[MonthlyFee] > 1000", "--code", "FEE_CAP", "--suggest-error-field");
        assertEquals(0, res.code(), res::err);
        JsonNode data = MAPPER.readTree(res.out()).path("data");
        assertEquals(List.of("/Subscription/Addons/MonthlyFee"), strings(data.path("candidates")), res::out);
        assertTrue(data.path("fieldIsCandidate").asBoolean(), () -> "MonthlyFee is referenced → valid: " + res.out());
    }

    @Test
    void aReferencedButUnchosenFieldStillSurfacesAsTheCorrectiveCandidate(@TempDir Path dir) throws Exception {
        // Name is inside the iterated Addons group but the condition references only MonthlyFee, so Name is
        // NOT a valid error field — the kernel would reject it. The suggestion names MonthlyFee, the fix.
        var res = CliTestSupport.run("-m", model(dir).toString(), "rule", "check",
                "--field", "/Subscription/Addons/Name",
                "--condition", "[MonthlyFee] > 1000", "--code", "FEE_CAP", "--suggest-error-field");
        JsonNode data = MAPPER.readTree(res.out()).path("data");
        assertEquals(List.of("/Subscription/Addons/MonthlyFee"), strings(data.path("candidates")), res::out);
        assertFalse(data.path("fieldIsCandidate").asBoolean(),
                () -> "Name is not referenced by the condition → not a candidate: " + res.out());
    }

    @Test
    void aModelLevelErrorFieldGetsAClarifyingNoteNotAFalseVerdict(@TempDir Path dir) throws Exception {
        // probe 06-6: `candidates` lists only iteration-scope refs, so fieldIsCandidate is false for a
        // MODEL-LEVEL error field the condition references via FieldFilled — but the kernel ACCEPTS it. The
        // output must clarify (a note) and stay valid:true; fieldIsCandidate:false is not a verdict.
        Path m = CliTestSupport.orderRuled(dir);   // /Order/CustomerName (non-repeating) + repeatable /Order/Items/Sku
        var res = CliTestSupport.run("-m", m.toString(), "rule", "check",
                "--field", "/Order/CustomerName",
                "--condition", "FieldFilled(CustomerName) And FieldValuesNotUnique(/Order/Items*/Sku)",
                "--code", "DUP", "--suggest-error-field");
        assertEquals(0, res.code(), res.err());
        JsonNode j = MAPPER.readTree(res.out());
        assertTrue(j.path("valid").asBoolean(), () -> "the kernel accepts the model-level error field: " + res.out());
        assertFalse(j.path("data").path("fieldIsCandidate").asBoolean(), "CustomerName isn't an iteration-scope ref");
        assertTrue(j.path("data").path("note").asText().contains("authoritative"),
                () -> "the note clarifies fieldIsCandidate:false is not a verdict: " + res.out());
    }

    @Test
    void withoutTheFlagThereIsNoCandidateData(@TempDir Path dir) throws Exception {
        // the flag is opt-in — a plain check must keep its existing shape (no data payload), no drift.
        var res = CliTestSupport.run("-m", model(dir).toString(), "rule", "check",
                "--field", "/Subscription/Addons/MonthlyFee",
                "--condition", "[MonthlyFee] > 1000", "--code", "FEE_CAP");
        assertEquals(0, res.code(), res::err);
        assertTrue(MAPPER.readTree(res.out()).path("data").isMissingNode()
                        || MAPPER.readTree(res.out()).path("data").isNull(),
                () -> "no --suggest-error-field → no candidate data: " + res.out());
    }

    /**
     * {@code CG16} — an ALL-GROUP condition must still offer a pick. {@code AllFieldsFilled(Addons*)} names a
     * group and no field, so the candidate list was empty and the note said <i>"the condition references no
     * field inside its iteration scope — reference one there"</i>: advice that steers the author to edit a
     * correct condition, when the kernel counts the group operand as referencing every field it expands to.
     * The {@code valid} verdict here is the kernel's, so this row also proves the offered pick is real.
     */
    @Test
    void anAllGroupConditionOffersTheFieldsTheGroupExpandsTo(@TempDir Path dir) throws Exception {
        var res = CliTestSupport.run("-m", model(dir).toString(), "rule", "check",
                "--field", "/Subscription/Addons/MonthlyFee",
                "--condition", "AllFieldsFilled(/Subscription/Addons*)", "--code", "ADDON_INCOMPLETE",
                "--suggest-error-field");
        assertEquals(0, res.code(), res::err);
        JsonNode root = MAPPER.readTree(res.out());
        assertTrue(root.path("valid").asBoolean(),
                () -> "the kernel accepts the chosen error field: " + res.out());
        assertEquals(List.of("/Subscription/Addons/MonthlyFee", "/Subscription/Addons/Name"),
                strings(root.path("data").path("candidates")),
                () -> "the group's expansion is what the condition references: " + res.out());
        assertTrue(root.path("data").path("fieldIsCandidate").asBoolean(),
                () -> "the chosen field IS among them, so no corrective note is owed: " + res.out());
    }

    private static List<String> strings(JsonNode array) {
        List<String> out = new ArrayList<>();
        array.forEach(n -> out.add(n.asText()));
        return out;
    }
}
