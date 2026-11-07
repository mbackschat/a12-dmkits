package io.github.mbackschat.a12.dm.cli.rule;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import io.github.mbackschat.a12.dm.cli.CliTestSupport;
import io.github.mbackschat.a12.dm.edit.EditSession;
import io.github.mbackschat.a12.dm.modelkit.DocumentModelDraft;
import io.github.mbackschat.a12.dm.rulekit.dsl.Dsl;

/**
 * A firing publishes A12's groups of possibly-causing entities as {@code possiblyCausing}, on both the
 * {@code model eval} message and the {@code rule eval} firing, in the group order and the address form of
 * {@code referenced}. Each row is also held to the kernel leg ({@link CliTestSupport#runKernel}); the grid of what a
 * group holds is {@code adapter.laws.PossiblyCausingEntitiesLawsTest} (KERNEL-FINDINGS KF683).
 */
class MessagePossiblyCausingCliTest {

    private static final ObjectMapper M = new ObjectMapper();

    @Test
    void anOmissionNamesTheEmptyEntityFirstAndTheGivenOneSecond(@TempDir Path dir) throws Exception {
        Path model = CliTestSupport.orderRuled(dir);
        Path spec = dir.resolve("spec.json");
        Files.writeString(spec, """
                {"field": "/Order/DeliveryDate", "condition": "FieldFilled(OrderDate) And FieldNotFilled(DeliveryDate)",
                 "code": "DELIVERY_MISSING", "messages": [{"locale": "en_US", "text": "An order needs a delivery date."}]}""");
        assertEquals(0, CliTestSupport.run("-m", model.toString(), "rule", "add", spec.toString()).code());
        Path instance = instance(dir, "{ \"Order\": { \"OrderDate\": \"2026-01-10\" } }");
        String[] args = {"-m", model.toString(), "model", "eval", "--instance", instance.toString()};

        JsonNode message = message(data(CliTestSupport.run(args)), "DELIVERY_MISSING");
        assertEquals("OMISSION_ERROR", message.path("type").asText(), message::toString);
        assertEquals(List.of(Set.of("Order/DeliveryDate"), Set.of("Order/OrderDate")), groups(message), message::toString);
        assertEquals(groups(message(data(CliTestSupport.runKernel(args)), "DELIVERY_MISSING")), groups(message),
                "interpreter vs kernel");
    }

    /** A fired {@code rule eval} carries the same channel as a {@code model eval} message. */
    @Test
    void aRuleEvalFiringCarriesTheSameGroups(@TempDir Path dir) throws Exception {
        Path model = CliTestSupport.orderRuled(dir);
        Path instance = instance(dir,
                "{ \"Order\": { \"OrderDate\": \"2026-01-10\", \"DeliveryDate\": \"2026-01-01\" } }");
        String[] args = {"-m", model.toString(), "rule", "eval", "/Order/DeliveryNotBeforeOrder",
                "--instance", instance.toString()};

        JsonNode firing = data(CliTestSupport.run(args));
        assertEquals(List.of(Set.of("Order/DeliveryDate", "Order/OrderDate")), groups(firing), firing::toString);
        assertEquals(groups(data(CliTestSupport.runKernel(args))), groups(firing), "interpreter vs kernel");
    }

    @Test
    void aValueErrorNamesEveryEntityInOneGroup(@TempDir Path dir) throws Exception {
        Path model = CliTestSupport.orderRuled(dir);
        Path instance = instance(dir,
                "{ \"Order\": { \"OrderDate\": \"2026-01-10\", \"DeliveryDate\": \"2026-01-01\" } }");

        String[] args = {"-m", model.toString(), "model", "eval", "--instance", instance.toString()};

        JsonNode message = message(data(CliTestSupport.run(args)), "DELIVERY_BEFORE_ORDER");
        assertEquals("VALUE_ERROR", message.path("type").asText(), message::toString);
        assertEquals(List.of(Set.of("Order/DeliveryDate", "Order/OrderDate")), groups(message), message::toString);
        assertEquals(groups(message(data(CliTestSupport.runKernel(args)), "DELIVERY_BEFORE_ORDER")), groups(message),
                "interpreter vs kernel");
    }

    /**
     * Two keys that select no row are two members of A12's group, equal but for the missed key the address does not
     * render ({@code adapter.laws.UnknownIndexKeyLawsTest}); the published group is a set of addresses, so each is
     * named once, as the kernel leg names it.
     */
    @Test
    void twoMissedKeysNameTheirAddressOnce(@TempDir Path dir) throws Exception {
        var session = EditSession.read(DocumentModelDraft.named("portfolio").locale("en_US")
                .rootGroup("Portfolio", p -> p.stringField("Owner", f -> { }).stringField("Wanted", f -> { })
                        .group("Claims", c -> c.repeatable(3).indexField("Code").stringField("Code", f -> { })
                                .numberField("Cost", f -> { })))
                .build().toJson());
        var portfolio = session.group("/Portfolio");
        var owner = portfolio.str("Owner");
        var cost = portfolio.num("Claims/Cost");
        portfolio.addRule(r -> r.name("Misses").code("MISSES").errorField(owner)
                .when(cost.forIndex("A").gt(1).or(cost.forIndex(portfolio.str("Wanted").field()).gt(1)).or(owner.notFilled()))
                .message(Dsl.en("The portfolio needs an owner.")).build());
        Path model = dir.resolve("portfolio.dm.json");
        Files.writeString(model, session.toJson());
        Path instance = instance(dir, "{ \"Portfolio\": { \"Wanted\": \"B\" } }");
        String[] args = {"-m", model.toString(), "model", "eval", "--instance", instance.toString()};

        JsonNode message = message(data(CliTestSupport.run(args)), "MISSES");
        message.path("possiblyCausing").forEach(group ->
                assertEquals(new TreeSet<>(texts(group)).size(), group.size(), message::toString));
        assertEquals(true, groups(message).get(0).stream().anyMatch(address -> address.contains("Claims")), message::toString);
        assertEquals(groups(message(data(CliTestSupport.runKernel(args)), "MISSES")), groups(message),
                "interpreter vs kernel");
    }

    private static List<String> texts(JsonNode group) {
        List<String> texts = new java.util.ArrayList<>();
        group.forEach(address -> texts.add(address.asText()));
        return texts;
    }

    private static JsonNode message(JsonNode data, String code) {
        for (JsonNode message : data.path("messages")) {
            if (code.equals(message.path("code").asText())) {
                return message;
            }
        }
        throw new AssertionError("no " + code + " message: " + data);
    }

    private static JsonNode data(CliTestSupport.Result res) throws Exception {
        assertEquals(0, res.code(), () -> res.out() + res.err());
        return M.readTree(res.out()).path("data");
    }

    private static Path instance(Path dir, String json) throws Exception {
        Path file = dir.resolve("instance.json");
        Files.writeString(file, json);
        return file;
    }

    /** Each group as a set: A12's groups are sets, so their order within a group is no contract. */
    private static List<Set<String>> groups(JsonNode firing) {
        List<Set<String>> groups = new java.util.ArrayList<>();
        firing.path("possiblyCausing").forEach(group -> {
            Set<String> addresses = new TreeSet<>();
            group.forEach(address -> addresses.add(address.asText()));
            groups.add(addresses);
        });
        return groups;
    }
}
