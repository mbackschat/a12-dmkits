package io.github.mbackschat.a12.dm.rulekit.validate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import java.util.List;
import java.util.Optional;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.junit.jupiter.api.Test;

/**
 * The {@code Where} extension (CLI-SPEC §4): a diagnostic can point at a model-element {@code path} and an
 * input {@code arg}, not only a {@code rule} — so structure ops (modelkit) and arg-level errors have a
 * locus. A rule-condition diagnostic still serializes {@code rule} exactly as before.
 */
class DiagnosticWhereTest {

    private static final ObjectMapper M = new ObjectMapper();

    private static JsonNode where(Diagnostic d) throws Exception {
        return M.readTree(ValidationResult.of(List.of(d)).toJson()).get(0).get("where");
    }

    @Test
    void structureLocusSerializesPathAndArgNotRule() throws Exception {
        Diagnostic d = Diagnostic.precheck(
                Optional.of("RK_NO_SUCH_GROUP"),
                "no group /Order/Lines in the model",
                Diagnostic.Where.arg("/Order/Lines", "--group"),
                Optional.of("list groups with `dmtool -m order.dm.json group read --all`"),
                Optional.empty());
        JsonNode w = where(d);
        assertEquals("/Order/Lines", w.get("path").asText());
        assertEquals("--group", w.get("arg").asText());
        assertFalse(w.has("rule"), "a structure locus has no rule");
    }

    @Test
    void pathOnlyLocus() throws Exception {
        Diagnostic d = Diagnostic.precheck(Optional.of("RK_X"), "x",
                Diagnostic.Where.path("/Order/Quantity"), Optional.empty(), Optional.empty());
        JsonNode w = where(d);
        assertEquals("/Order/Quantity", w.get("path").asText());
        assertFalse(w.has("rule"));
        assertFalse(w.has("arg"));
    }

    @Test
    void ruleLocusStillSerializesRuleNotPath() throws Exception {
        Diagnostic d = Diagnostic.precheck(Optional.of("MVK_X"), "x",
                new Diagnostic.Where("/Order/R", Optional.empty()), Optional.empty(), Optional.empty());
        JsonNode w = where(d);
        assertEquals("/Order/R", w.get("rule").asText());
        assertFalse(w.has("path"));
    }
}
