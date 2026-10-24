package io.github.mbackschat.a12.dm.rulekit.dsl;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.LinkedHashMap;
import java.util.Map;

import org.junit.jupiter.api.Test;

import io.github.mbackschat.a12.dm.modelkit.DocumentModelDraft;
import io.github.mbackschat.a12.dm.modelkit.FieldKind;
import io.github.mbackschat.a12.dm.rulekit.validate.DraftRule;
import io.github.mbackschat.a12.dm.rulekit.validate.RuleValidator;

/**
 * A read that only a {@code Having} filter's body makes does not reference a rule's error entity unless it carries
 * the {@code $} marker, because A12 keeps a reference made with the filter flag on only when it is marked
 * (KERNEL-FINDINGS KF488, the {@code MVK_ERROR_FIELD_NOT_REFERENCED} rows; DEV-API-GAPS DG153).
 *
 * <p>The oracle is the kernel, asked per row, and each row's verdict is also pinned so a kernel that admitted
 * everything could not make the advisory's silence pass. The control rows name the target outside the filter.
 */
class FilterBodyErrorReferenceTest {

    private static final BoundModel M = BoundModel.ofJson(
            DocumentModelDraft.named("filter-body-reference").locale("en_US")
                    .rootGroup("Shipment", s -> s
                            .group("Received", r -> r.repeatable(20)
                                    .field("Quantity", FieldKind.NUMBER)
                                    .field("Note", FieldKind.STRING)
                                    .group("Parts", p -> p.field("PartNo", FieldKind.STRING))))
                    .build().toJson());

    private static final GroupRef RECEIVED = M.group("/Shipment/Received");
    private static final NumberRef QUANTITY = M.num("/Shipment/Received/Quantity");
    private static final StringRef NOTE = M.str("/Shipment/Received/Note");
    private static final GroupRef PARTS = M.group("/Shipment/Received/Parts");

    private static Condition sumHaving(Condition filter) {
        return Dsl.sum(M.flatten("/Shipment/Received*/Quantity").having(filter)).gt(0);
    }

    private static Rule rule(ErrorEntity target, Condition condition) {
        return M.rule(RECEIVED).name("ReceivedCheck").errorEntity(target).when(condition)
                .code("RECEIVED_CHECK").message(Dsl.en("Review the receipt.")).build();
    }

    private static boolean kernelRefuses(Rule rule) {
        return new RuleValidator().check(M.toJson(), new DraftRule("/Shipment/Received", "ReceivedCheck",
                rule.errorEntityRelPath(), rule.render(), "RECEIVED_CHECK", "ERROR")).diagnostics().stream()
                .anyMatch(d -> d.code().filter("MVK_ERROR_FIELD_NOT_REFERENCED"::equals).isPresent());
    }

    private static boolean advises(Rule rule) {
        return rule.deferredChecks().stream()
                .anyMatch(d -> d.code().filter("MVK_ERROR_FIELD_NOT_REFERENCED"::equals).isPresent());
    }

    @Test
    void onlyAMarkedFilterBodyReadReferencesTheErrorEntity() {
        Map<String, Rule> rows = new LinkedHashMap<>();
        rows.put("field read only in the body", rule(NOTE.field(), sumHaving(NOTE.eq("x"))));
        rows.put("field read marked in the body", rule(NOTE.field(),
                sumHaving(QUANTITY.gt(1).and(NOTE.outer().eq("x")))));
        rows.put("field read outside the filter too", rule(NOTE.field(), NOTE.filled().and(sumHaving(NOTE.eq("x")))));
        rows.put("group named only in the body", rule(PARTS, sumHaving(PARTS.filled())));
        rows.put("group named outside the filter too", rule(PARTS, PARTS.filled().and(sumHaving(PARTS.filled()))));

        Map<String, Boolean> kernel = new LinkedHashMap<>();
        Map<String, Boolean> advisory = new LinkedHashMap<>();
        rows.forEach((label, rule) -> {
            kernel.put(label, kernelRefuses(rule));
            advisory.put(label, advises(rule));
        });
        assertEquals(kernel, advisory, "the advisory names exactly the rows A12 refuses");
        assertEquals(Map.of(
                "field read only in the body", true,
                "field read marked in the body", false,
                "field read outside the filter too", false,
                "group named only in the body", true,
                "group named outside the filter too", false), kernel, "the pinned kernel verdicts");
    }
}
