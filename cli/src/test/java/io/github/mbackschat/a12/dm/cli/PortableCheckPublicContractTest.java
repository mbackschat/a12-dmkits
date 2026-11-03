package io.github.mbackschat.a12.dm.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.mbackschat.a12.dm.edit.EditSession;
import io.github.mbackschat.a12.dm.modelkit.DocumentModelDraft;
import io.github.mbackschat.a12.dm.rulekit.dsl.Dsl;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;

/** The packaged launcher's {@code --engine portable} check, executed against the exact launcher selected by Gradle. */
@Execution(ExecutionMode.CONCURRENT)
class PortableCheckPublicContractTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Test
    void packagedPortableCheckPreservesSelectedAncestorBinding(@TempDir Path dir) throws Exception {
        String base = DocumentModelDraft.named("depot-check-scope").locale("en_US")
                .rootGroup("Store", store -> store.group("Depots", depot -> depot.repeatable(2)
                        .numberField("Marker", f -> {})
                        .group("Bins", bin -> bin.repeatable(3).indexField("Code").stringField("Code", f -> {})
                                .numberField("Stock", f -> {}).numberField("Reserve", f -> {}))))
                .build().toJson();
        for (String guard : List.of("ancestor", "sibling", "own")) {
            var session = EditSession.read(base);
            var store = session.group("/Store");
            var depot = session.group("/Store/Depots");
            var stock = depot.num("Bins/Stock");
            var guarded = switch (guard) {
                case "ancestor" -> depot.num("Marker");
                case "sibling" -> depot.num("Bins/Reserve");
                default -> stock;
            };
            var rule = store.rule(builder -> builder.name("Selected").code("Selected").errorField(stock)
                    .when(guarded.filled().and(stock.forIndex("b").gt(0)))
                    .message(Dsl.en("Check selected stock.")).build());
            Path model = dir.resolve(guard + ".dm.json");
            Files.writeString(model, new io.github.mbackschat.a12.dm.rulekit.validate.RuleValidator().inject(base, rule.draft()));
            var result = new PackagedDmtool(dir).run("-m", model.toString(), "model", "check", "--engine", "portable");
            boolean rejected = guard.equals("sibling");
            assertEquals(rejected ? 1 : 3, result.code(), result::out);
            var envelope = MAPPER.readTree(result.out());
            assertEquals("PORTABLE_CHECKER", envelope.path("engineExecuted").asText(), result::out);
            assertEquals(rejected ? "INVALID" : "INCONCLUSIVE", envelope.path("data").path("status").asText(), result::out);
        }
    }

    @Test
    void packagedPortableCheckReportsTheKeyAddressFailure(@TempDir Path dir) throws Exception {
        String base = DocumentModelDraft.named("depot-key-address").locale("en_US")
                .rootGroup("Store", store -> store.group("Depots", depot -> depot.repeatable(2)
                        .stringField("Wanted", f -> {}))
                        .group("Catalog", catalog -> catalog.repeatable(3).indexField("Code")
                                .stringField("Code", f -> {}).numberField("Stock", f -> {}).numberField("Reserve", f -> {})))
                .build().toJson();
        var session = EditSession.read(base);
        var store = session.group("/Store");
        var rule = store.rule(builder -> builder.name("Selected").code("Selected")
                .errorField(store.field("Catalog/Reserve"))
                .when(Dsl.groupFilled(store.ref()).and(store.num("Catalog/Stock").forIndex(store.field("Depots/Wanted")).gt(0)))
                .message(Dsl.en("Check selected stock.")).build());
        Path model = dir.resolve("key-address.dm.json");
        Files.writeString(model, new io.github.mbackschat.a12.dm.rulekit.validate.RuleValidator().inject(base, rule.draft()));
        var result = new PackagedDmtool(dir).run("-m", model.toString(), "model", "check", "--engine", "portable");
        assertEquals(1, result.code(), result::out);
        var codes = MAPPER.readTree(result.out()).findValuesAsText("code");
        assertTrue(codes.contains("MVK_SEMANTIC_INDEX_INVALID_ITER"), result::out);
        assertFalse(codes.contains("MVK_ERROR_FIELDGROUP_ITERATION5_INVALID"), result::out);
    }
}
