package io.github.mbackschat.a12.dm.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import io.github.mbackschat.a12.dm.edit.EditSession;
import io.github.mbackschat.a12.dm.rulekit.dsl.BoundModel;
import io.github.mbackschat.a12.dm.rulekit.dsl.Dsl;

/**
 * The three runtime verbs read {@code Today} and {@code Now} from an evaluation instant: the current one by
 * default, as the kernel's {@code RuntimeController} samples a calendar in the model's zone when
 * {@code DocumentProcessingConfig.currentDateForTest} is absent, or the one {@code --clock} pins. The instant is
 * read in the MODEL's zone, so the two pinned instants below — an hour apart around Berlin midnight — fall on
 * two different Berlin days and on one UTC day; a zone-blind reading fires the rule for both.
 *
 * <p>Fixture: {@code order-ruled} (zone {@code Europe/Berlin}) plus one rule and one computation over
 * {@code Today}, authored through the typed surface.
 */
class EvalClockTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** 23:30 on 2026-10-13 in Berlin (CEST, UTC+2), still 21:30 on the same day in UTC. */
    private static final String BEFORE_BERLIN_MIDNIGHT = "2026-10-13T21:30:00Z";
    /** 00:30 on 2026-10-14 in Berlin, 22:30 on 2026-10-13 in UTC. */
    private static final String AFTER_BERLIN_MIDNIGHT = "2026-10-13T22:30:00Z";

    private static Path model(Path dir) throws Exception {
        String base = CliTestSupport.orderRuledJson();
        var bound = BoundModel.ofJson(base);
        var order = bound.group("/Order");
        var orderDate = bound.date("/Order/OrderDate");
        var edit = EditSession.read(base);
        edit.addRule(m -> m.rule(order).name("OrderNotAhead").code("ORDER_AHEAD").errorField(orderDate)
                .when(orderDate.after(Dsl.today()))
                .message(Dsl.en("The order date lies in the future.")).build());
        edit.addComputation(m -> m.computation(bound.date("/Order/DeliveryDate")).name("StampDelivery")
                .calc(Dsl.today()).message(Dsl.en("Review the delivery date.")).build());
        Path file = dir.resolve("order-clock.dm.json");
        Files.writeString(file, edit.toJson());
        return file;
    }

    private static Path instance(Path dir, String orderDate) throws Exception {
        Path file = dir.resolve("order-" + orderDate + ".json");
        Files.writeString(file, MAPPER.writeValueAsString(Map.of("Order", Map.of("OrderDate", orderDate))));
        return file;
    }

    private static JsonNode data(CliTestSupport.Result result) throws Exception {
        assertEquals(0, result.code(), () -> result.out() + result.err());
        JsonNode data = MAPPER.readTree(result.out()).path("data");
        // The fixture's own custom condition stays unsupported; neither construct over the clock may join it.
        data.path("unsupported").forEach(entry -> assertFalse(entry.path("reason").asText().contains("clock"),
                () -> "the clock constructs evaluate: " + result.out()));
        return data;
    }

    private static List<String> fired(JsonNode data) {
        var codes = new ArrayList<String>();
        data.path("fired").forEach(code -> codes.add(code.asText()));
        return codes;
    }

    private static List<String> clock(String instant) {
        return instant == null ? List.of() : List.of("--clock", instant);
    }

    private static CliTestSupport.Result run(Path model, Path document, String instant, String... verb) {
        var args = new ArrayList<>(List.of("-m", model.toString()));
        args.addAll(List.of(verb));
        args.addAll(List.of("--instance", document.toString()));
        args.addAll(clock(instant));
        return CliTestSupport.run(args.toArray(String[]::new));
    }

    @Test
    void aPinnedInstantIsReadInTheModelsZone(@TempDir Path dir) throws Exception {
        Path model = model(dir);
        Path document = instance(dir, "2026-10-14");
        var observed = new LinkedHashMap<String, Object>();
        for (String instant : List.of(BEFORE_BERLIN_MIDNIGHT, AFTER_BERLIN_MIDNIGHT)) {
            observed.put("model eval " + instant,
                    fired(data(run(model, document, instant, "model", "eval"))).contains("ORDER_AHEAD"));
            observed.put("rule eval " + instant, data(run(model, document, instant,
                    "rule", "eval", "/Order/OrderNotAhead")).path("verdict").asText());
            observed.put("model compute " + instant, data(run(model, document, instant, "model", "compute"))
                    .path("computed").path(0).path("value").asText());
        }
        var expected = new LinkedHashMap<String, Object>();
        expected.put("model eval " + BEFORE_BERLIN_MIDNIGHT, true);
        expected.put("rule eval " + BEFORE_BERLIN_MIDNIGHT, "fired");
        expected.put("model compute " + BEFORE_BERLIN_MIDNIGHT, "2026-10-13");
        expected.put("model eval " + AFTER_BERLIN_MIDNIGHT, false);
        expected.put("rule eval " + AFTER_BERLIN_MIDNIGHT, "passed");
        expected.put("model compute " + AFTER_BERLIN_MIDNIGHT, "2026-10-14");
        assertEquals(expected, observed);
    }

    @Test
    void withoutAPinTheCurrentInstantIsRead(@TempDir Path dir) throws Exception {
        Path model = model(dir);
        assertEquals(List.of(true, false), List.of(
                fired(data(run(model, instance(dir, "2999-01-01"), null, "model", "eval"))).contains("ORDER_AHEAD"),
                fired(data(run(model, instance(dir, "2000-01-01"), null, "model", "eval"))).contains("ORDER_AHEAD")));
    }

    @Test
    void anInstantWithoutAnOffsetIsRefused(@TempDir Path dir) throws Exception {
        Path model = model(dir);
        Path document = instance(dir, "2026-10-14");
        for (String[] verb : List.of(new String[] {"model", "eval"}, new String[] {"model", "compute"},
                new String[] {"rule", "eval", "/Order/OrderNotAhead"})) {
            var result = run(model, document, "2026-10-14T10:00:00", verb);
            assertTrue(result.code() != 0, () -> "an offset-less --clock exits non-zero: " + result.out());
            JsonNode diagnostic = MAPPER.readTree(result.out()).path("diagnostics").path(0);
            assertEquals("RK_INVALID_VALUE", diagnostic.path("code").asText(), result::out);
            assertTrue(diagnostic.path("summary").asText().contains("--clock"), result::out);
            assertFalse(diagnostic.path("fix").asText().isBlank(), result::out);
        }
    }
}
