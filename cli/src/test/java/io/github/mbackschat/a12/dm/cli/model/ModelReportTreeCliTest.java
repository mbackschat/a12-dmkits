package io.github.mbackschat.a12.dm.cli.model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import io.github.mbackschat.a12.dm.cli.CliTestSupport;
import io.github.mbackschat.a12.dm.edit.EditSession;
import io.github.mbackschat.a12.dm.modelkit.DocumentModelDraft;
import io.github.mbackschat.a12.dm.rulekit.dsl.Dsl;
import io.github.mbackschat.a12.dm.rulekit.dsl.Severity;

/**
 * G44 — {@code model report --tree} is an agent-first flat node projection of the existing comprehension
 * report, with the human hierarchy rendered only by {@code --text}.
 */
class ModelReportTreeCliTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Test
    void leanTreeIsFlatOrderedScopedAndDepthCapped(@TempDir Path dir) throws Exception {
        Fixture f = fixture(dir);
        JsonNode data = data(CliTestSupport.run("-m", f.model().toString(), "-w", dir.toString(),
                "model", "report", "--tree", "--root", "/Order", "--depth", "1"));

        assertEquals("/Order", data.path("root").asText());
        assertEquals(List.of("/Order", "/Order/Channel", "/Order/ContactEmail", "/Order/Quantity",
                        "/Order/Total", "/Order/Lines", "/Order/Payment"),
                paths(data.path("nodes")), "one flat list preserves mixed field/group document order");
        assertEquals(0, node(data, "/Order").path("depth").asInt());
        assertEquals(1, node(data, "/Order/Lines").path("depth").asInt());
        assertEquals("group", node(data, "/Order/Lines").path("kind").asText());
        assertTrue(node(data, "/Order/Lines").path("repeatable").asBoolean());
        assertEquals(5, node(data, "/Order/Lines").path("maxRepetitions").asInt());
        assertFalse(data.toString().contains("/Order/Lines/Sku"), "--depth 1 omits grandchildren");

        JsonNode channel = node(data, "/Order/Channel");
        assertEquals(10, channel.path("valueCount").asInt());
        assertTrue(channel.path("truncated").asBoolean());
        assertTrue(channel.path("values").size() < 10, "large enum is previewed by default");
        assertEquals("EmailType", node(data, "/Order/ContactEmail").path("typeRef").asText());
        assertEquals("PaymentModule", node(data, "/Order/Payment").path("origin").asText());
        assertTrue(node(data, "/Order/Total").path("computed").asBoolean());
    }

    @Test
    void richFacetsAnchorLogicOnceAndEnumCanBeExpanded(@TempDir Path dir) throws Exception {
        Fixture f = fixture(dir);
        JsonNode data = data(CliTestSupport.run("-m", f.model().toString(), "-w", dir.toString(),
                "model", "report", "--tree", "--root", "/Order",
                "--include", "rules,computations,descriptions,annotations,enum"));

        JsonNode channel = node(data, "/Order/Channel");
        assertEquals(10, channel.path("values").size());
        assertFalse(channel.has("truncated"));
        assertEquals("Sales channel", channel.path("descriptions").path("internal").path("en_US").asText());
        assertEquals("Choose the intake route.",
                channel.path("descriptions").path("helper").path("en_US").asText());
        assertEquals("pii", node(data, "/Order/ContactEmail").path("annotations").path("classification").asText());

        JsonNode email = node(data, "/Order/ContactEmail");
        assertEquals(1, email.path("rules").size());
        assertEquals("/Order/EmailRequired", email.path("rules").get(0).path("name").asText());
        assertTrue(email.path("rules").get(0).path("dsl").asText().contains("FieldNotFilled"));
        assertEquals(1, countRule(data, "/Order/EmailRequired"), "a rule is anchored exactly once");

        JsonNode total = node(data, "/Order/Total");
        assertEquals("/Order/TotalComputation", total.path("computation").path("name").asText());
        assertTrue(total.path("computation").path("dsl").asText().contains("Quantity"));
        assertEquals(1, countComputation(data, "/Order/TotalComputation"),
                "a computation is anchored exactly once");
    }

    @Test
    void defaultReportContractIsUnchangedAndMissingRootRefusesCorrectively(@TempDir Path dir) throws Exception {
        Fixture f = fixture(dir);
        JsonNode ordinary = data(CliTestSupport.run("-m", f.model().toString(), "-w", dir.toString(),
                "model", "report"));
        assertTrue(ordinary.has("identity") && ordinary.has("structure") && ordinary.has("rules"));
        assertFalse(ordinary.has("nodes"), "tree is an explicit mode; default report is unchanged");

        var missing = CliTestSupport.run("-m", f.model().toString(), "-w", dir.toString(),
                "model", "report", "--tree", "--root", "/Order/Paymant");
        assertEquals(2, missing.code());
        JsonNode refusal = MAPPER.readTree(missing.out());
        assertEquals("RK_NO_SUCH_ELEMENT", refusal.path("diagnostics").path(0).path("code").asText());
        assertTrue(refusal.path("summary").asText().contains("/Order/Payment"), missing::out);
    }

    @Test
    void textModeDrawsTheSameFlatRecordsAsAHierarchy(@TempDir Path dir) throws Exception {
        Fixture f = fixture(dir);
        var text = CliTestSupport.run("--text", "-m", f.model().toString(), "-w", dir.toString(),
                "model", "report", "--tree", "--root", "/Order", "--depth", "2",
                "--include", "rules,computations");
        assertEquals(0, text.code(), text::err);
        assertTrue(text.out().contains("/Order  (group)"), text::out);
        assertTrue(text.out().contains("├─ Channel"), text::out);
        assertTrue(text.out().contains("└─ Payment"), text::out);
        assertTrue(text.out().contains("rule EmailRequired"), text::out);
        assertTrue(text.out().contains("comp TotalComputation"), text::out);
    }

    private static Fixture fixture(Path dir) throws Exception {
        String payment = DocumentModelDraft.named("PaymentModule").locale("en_US")
                .rootGroup("PaymentRoot", root -> root.stringField("Iban", field -> {}))
                .build().toJson();
        Files.writeString(dir.resolve("PaymentModule.dm.json"), payment);

        String base = DocumentModelDraft.named("OrderTree").locale("en_US")
                .include("payment", "PaymentModule")
                .typeDef("EmailType", type -> type.string(string -> string.minLength(5)))
                .rootGroup("Order", order -> order
                        .internalDescription("en_US", "Order intake")
                        .enumField("Channel", channel -> channel
                                .value("WEB").value("STORE").value("PHONE").value("PARTNER").value("APP")
                                .value("EMAIL").value("MARKET").value("DIRECT").value("BROKER").value("OTHER")
                                .helperText("en_US", "Choose the intake route.")
                                .internalDescription("en_US", "Sales channel"))
                        .typedefField("ContactEmail", "EmailType")
                        .numberField("Quantity", number -> number.fractionDigits(0))
                        .numberField("Total", number -> number.fractionDigits(0))
                        .group("Lines", lines -> lines.repeatable(5)
                                .stringField("Sku", field -> field.annotation("key", "business")))
                        .mount("Payment", "payment"))
                .build().toJson();
        EditSession session = EditSession.read(base,
                reference -> "PaymentModule".equals(reference) ? payment : null);
        session.setAnnotation("/Order/ContactEmail", "classification", "pii");
        session.addRule(m -> m.rule(m.group("/Order"))
                .name("EmailRequired").errorField(m.str("/Order/ContactEmail"))
                .when(m.str("/Order/ContactEmail").notFilled())
                .code("EMAIL_REQUIRED").severity(Severity.ERROR).message(Dsl.en("Email is required.")).build());
        session.addComputation(m -> m.computation(m.num("/Order/Total"))
                .name("TotalComputation").calc(m.num("/Order/Quantity").value())
                .message(Dsl.en("Total follows quantity.")).build());
        Path model = dir.resolve("OrderTree.dm.json");
        Files.writeString(model, session.toJson());
        return new Fixture(model);
    }

    private static JsonNode data(CliTestSupport.Result result) throws Exception {
        assertEquals(0, result.code(), result::err);
        return MAPPER.readTree(result.out()).path("data");
    }

    private static JsonNode node(JsonNode data, String path) {
        for (JsonNode node : data.path("nodes")) {
            if (path.equals(node.path("path").asText())) {
                return node;
            }
        }
        throw new AssertionError("no node " + path + " in " + data);
    }

    private static List<String> paths(JsonNode nodes) {
        List<String> out = new ArrayList<>();
        nodes.forEach(node -> out.add(node.path("path").asText()));
        return out;
    }

    private static int countRule(JsonNode data, String name) {
        int count = 0;
        for (JsonNode node : data.path("nodes")) {
            for (JsonNode rule : node.path("rules")) {
                if (name.equals(rule.path("name").asText())) {
                    count++;
                }
            }
        }
        return count;
    }

    private static int countComputation(JsonNode data, String name) {
        int count = 0;
        for (JsonNode node : data.path("nodes")) {
            if (name.equals(node.path("computation").path("name").asText())) {
                count++;
            }
        }
        return count;
    }

    private record Fixture(Path model) {
    }
}
