package io.github.mbackschat.a12.dm.cli.interp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import io.github.mbackschat.a12.dm.cli.CliTestSupport;
import io.github.mbackschat.a12.dm.edit.EditSession;
import io.github.mbackschat.a12.dm.modelkit.DocumentModelDraft;
import io.github.mbackschat.a12.dm.modelkit.FieldKind;
import io.github.mbackschat.a12.dm.rulekit.dsl.Arithmetic;
import io.github.mbackschat.a12.dm.rulekit.dsl.Dsl;

/**
 * A repeated computed field's rows must be identifiable from the output itself — the reported defect.
 *
 * <p>{@code model compute} published the bare declaration path for every outcome, so four rows of one computed
 * field came out as four entries with the <b>same</b> {@code field} and different values: associable only by list
 * position, and ambiguous outright once a second computation interleaves. The indices were never missing from the
 * evaluator ({@code Computed.getIndices()} has carried them since the corpus work); the CLI projection dropped
 * them. That is why this test asserts on the published envelope rather than on the engine.
 *
 * <p>The summary is the same defect one level up: it counted <em>instances</em> and called them "field(s)", so a
 * run over 2 declarations × 4 rows reported a number that was wrong on both axes. Neither count implies the other,
 * so both are published and both are asserted here.
 */
class ComputeRowIdentityTest {

    private static final ObjectMapper M = new ObjectMapper();

    /**
     * Two computations over one 4-row repeatable group, so the case discriminates on both axes at once: row
     * identity within a declaration, and declaration identity within the list.
     */
    private static Path model(Path dir) throws Exception {
        String base = DocumentModelDraft.named("orders").locale("en_US")
                .rootGroup("Order", order -> order
                        .field("Currency", FieldKind.STRING)
                        .group("Lines", lines -> lines.repeatable(20)
                                .numberField("Quantity", q -> { })
                                .numberField("UnitPrice", u -> { })
                                .numberField("LineTotal", t -> { })
                                .numberField("Doubled", d -> { })))
                .build().toJson();
        EditSession session = EditSession.read(base);
        session.addComputation(m -> m.computation(m.num("/Order/Lines/LineTotal"))
                .name("LineTotalComp")
                .when(m.num("/Order/Lines/Quantity").gt(0))
                .calc(new Arithmetic(Arithmetic.Op.MULTIPLY,
                        m.num("/Order/Lines/Quantity").value(), m.num("/Order/Lines/UnitPrice").value()))
                .message(Dsl.en("Line total is quantity times unit price."))
                .build());
        session.addComputation(m -> m.computation(m.num("/Order/Lines/Doubled"))
                .name("DoubledComp")
                .when(m.num("/Order/Lines/Quantity").gt(0))
                .calc(new Arithmetic(Arithmetic.Op.ADD,
                        m.num("/Order/Lines/Quantity").value(), m.num("/Order/Lines/Quantity").value()))
                .message(Dsl.en("Doubled is quantity plus itself."))
                .build());
        Path model = dir.resolve("orders.dm.json");
        Files.writeString(model, session.toJson());
        return model;
    }

    private static Path document(Path dir) throws Exception {
        Path doc = dir.resolve("doc.json");
        Files.writeString(doc, """
                { "Order": { "Currency": "EUR", "Lines": [
                    { "Quantity": "2", "UnitPrice": "10" },
                    { "Quantity": "3", "UnitPrice": "10" },
                    { "Quantity": "4", "UnitPrice": "10" },
                    { "Quantity": "5", "UnitPrice": "10" } ] } }""");
        return doc;
    }

    @Test
    void everyComputedRowCarriesItsOwnCanonicalPointer(@TempDir Path dir) throws Exception {
        var res = CliTestSupport.run("-m", model(dir).toString(), "model", "compute",
                "--instance", document(dir).toString());

        assertEquals(0, res.code(), res::err);
        JsonNode computed = M.readTree(res.out()).path("data").path("computed");
        List<String> lineTotals = fieldsOf(computed, "Order/Lines/LineTotal");
        // Row ONE carries no bracket — canonical A12 omits `[1]`, so the first row's instance pointer and the
        // declaration coincide as strings. That is the format, not a gap: an absent bracket *means* repetition 1,
        // which is also why omission can never be read as "all rows" (the RelevantSelection break).
        assertEquals(List.of("Order/Lines/LineTotal", "Order/Lines[2]/LineTotal",
                        "Order/Lines[3]/LineTotal", "Order/Lines[4]/LineTotal"), lineTotals,
                () -> "each row addresses its own cell, in canonical A12 form: " + res.out());
    }

    /** And the value really does differ per row, so the pointers are distinguishing something real. */
    @Test
    void andTheRowsAreDistinguishableByTheirValues(@TempDir Path dir) throws Exception {
        var res = CliTestSupport.run("-m", model(dir).toString(), "model", "compute",
                "--instance", document(dir).toString());

        JsonNode computed = M.readTree(res.out()).path("data").path("computed");
        assertEquals(List.of("20", "30", "40", "50"),
                valuesOf(computed, "Order/Lines/LineTotal"),
                () -> "quantity × 10, per row — the association the old shape could only imply by position: "
                        + res.out());
    }

    /**
     * The declaration is published beside the instance so a consumer can fold rows back without parsing indices
     * out of a string — the reason both keys exist rather than one.
     */
    @Test
    void andEachEntryNamesTheComputationItCameFrom(@TempDir Path dir) throws Exception {
        var res = CliTestSupport.run("-m", model(dir).toString(), "model", "compute",
                "--instance", document(dir).toString());

        JsonNode computed = M.readTree(res.out()).path("data").path("computed");
        assertEquals(8, computed.size(), () -> "2 computations × 4 rows: " + res.out());
        assertTrue(computed.findValuesAsText("declaration").stream()
                        .allMatch(d -> d.equals("Order/Lines/LineTotal") || d.equals("Order/Lines/Doubled")),
                () -> "every entry names one of the two declarations: " + res.out());
    }

    /**
     * The two counts, and the summary that states both. A single number cannot describe this run: it is 2
     * computations and 8 instances, and the old summary said "computed 8 field(s)" — the wrong noun for the wrong
     * count.
     */
    @Test
    void theSummaryCountsDeclarationsAndInstancesSeparately(@TempDir Path dir) throws Exception {
        var res = CliTestSupport.run("-m", model(dir).toString(), "model", "compute",
                "--instance", document(dir).toString());

        JsonNode env = M.readTree(res.out());
        assertEquals(2, env.path("data").path("declarations").asInt(), res::out);
        assertEquals(8, env.path("data").path("instances").asInt(), res::out);
        assertEquals("computed 2 computation(s) over 8 field instance(s)", env.path("summary").asText(), res::out);
    }

    private static List<String> fieldsOf(JsonNode computed, String declaration) {
        return entriesOf(computed, declaration).stream().map(c -> c.path("field").asText()).toList();
    }

    private static List<String> valuesOf(JsonNode computed, String declaration) {
        return entriesOf(computed, declaration).stream().map(c -> c.path("value").asText()).toList();
    }

    private static List<JsonNode> entriesOf(JsonNode computed, String declaration) {
        List<JsonNode> out = new java.util.ArrayList<>();
        computed.forEach(c -> {
            if (c.path("declaration").asText().equals(declaration)) {
                out.add(c);
            }
        });
        return out;
    }
}
