package io.github.mbackschat.a12.dm.rulekit.catalog;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.DynamicTest.dynamicTest;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.TestFactory;

import io.github.mbackschat.a12.dm.rulekit.validate.DraftRule;
import io.github.mbackschat.a12.dm.rulekit.validate.RuleValidator;
import io.github.mbackschat.a12.dm.rulekit.validate.ValidationResult;
import io.github.mbackschat.a12.dm.modelkit.DocumentModelDraft;

/**
 * N7 — self-verifying catalog: every {@code validExample} is accepted and every {@code invalidExample}
 * is rejected by the <em>real kernel</em>, so the bundled semantics can't silently drift from the
 * engine. Each example is embedded as a rule's {@code errorCondition} in the {@code Order} fixture and
 * checked via {@link RuleValidator}.
 */
class CatalogExamplesAreKernelValidTest {

    /** Field names/paths in {@code order.dm.json}, in the order to pick a referenced error field. */
    private static final List<String> ORDER_FIELDS =
            List.of("CustomerName", "ProductCode", "Quantity", "UnitPrice", "BackorderQuantity",
                    "ExpressShipping", "TermsConfirmed", "Priority",
                    "ShippingAddress/Street", "BillingAddress/PostalCode",
                    "Items/Sku", "Items/Count", "OrderDate", "DeliveryDate", "PickupTime", "ScheduledAt",
                    "CompletedAt", "RushLevel", "ApproxDate", "BirthDay", "BirthMonth", "BirthYear");

    /**
     * Group names → a representative contained field, for examples that reference only groups.
     * Legal because an enclosing group reference counts as referencing the error field (verified —
     * see KERNEL-INTEGRATION-NOTES).
     */
    private static final java.util.Map<String, String> ORDER_GROUP_FIELDS = java.util.Map.of(
            "ShippingAddress", "ShippingAddress/Street",
            "BillingAddress", "BillingAddress/PostalCode");

    private static final OperatorCatalog CATALOG = OperatorCatalog.load();
    private static final RuleValidator VALIDATOR = new RuleValidator();

    private static String modelJson;
    private static String multiStarModelJson;

    @BeforeAll
    static void loadFixture() throws Exception {
        try (InputStream in = CatalogExamplesAreKernelValidTest.class.getClassLoader()
                .getResourceAsStream("models/order.dm.json")) {
            assertNotNull(in, "order.dm.json fixture not found");
            modelJson = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
        multiStarModelJson = DocumentModelDraft.named("warehouse").locale("en_US")
                .rootGroup("Warehouse", warehouse -> warehouse
                        .group("Aisles", aisles -> aisles.repeatable(20)
                                .stringField("Code", field -> { })
                                .group("Bins", bins -> bins.repeatable(20)
                                        .numberField("Quantity", field -> { }))))
                .build().toJson();
    }

    @TestFactory
    Stream<DynamicTest> everyValidExampleIsAcceptedByTheKernel() {
        return CATALOG.entries().stream()
                .filter(e -> e.validExample() != null)
                .map(e -> dynamicTest(e.id() + " · valid", () -> {
                    ValidationResult result = checkValid(e);
                    assertTrue(result.ok(),
                            () -> e.id() + " validExample '" + e.validExample().dsl() + "' rejected: " + result.diagnostics());
                }));
    }

    private static ValidationResult checkValid(OperatorEntry entry) {
        if (entry.validExample().dsl().contains("/Warehouse/")) {
            return checkWarehouse(entry.validExample().dsl(), "CatalogExampleRule", "CATALOG_EXAMPLE");
        }
        return check(entry.validExample().dsl());
    }

    @TestFactory
    Stream<DynamicTest> everyInvalidExampleIsRejectedByTheKernel() {
        return CATALOG.entries().stream()
                .filter(e -> e.invalidExample() != null)
                .map(e -> dynamicTest(e.id() + " · invalid", () -> {
                    OperatorEntry.InvalidExample bad = e.invalidExample();
                    ValidationResult result = bad.dsl().contains("/Warehouse/")
                            ? checkWarehouse(bad.dsl(), "CatalogInvalidExampleRule", "CATALOG_INVALID_EXAMPLE")
                            : check(bad.dsl());
                    assertFalse(result.ok(),
                            () -> e.id() + " invalidExample '" + bad.dsl() + "' was unexpectedly accepted");
                    if (bad.error() != null && bad.error().startsWith("MVK_")) {
                        assertTrue(
                                result.diagnostics().stream()
                                        .anyMatch(p -> p.code().filter(bad.error()::equals).isPresent()),
                                () -> e.id() + " expected " + bad.error() + ", got: " + result.diagnostics());
                    }
                }));
    }

    private static String warehouseErrorField(String dsl) {
        return dsl.contains("Aisles/Code") ? "Aisles/Code" : "Aisles/Bins/Quantity";
    }

    private static ValidationResult checkWarehouse(String condition, String ruleName, String code) {
        DraftRule rule = new DraftRule(
                "/Warehouse", ruleName, "../" + warehouseErrorField(condition), condition, code, "ERROR");
        return VALIDATOR.check(multiStarModelJson, rule);
    }

    /**
     * The correctives' reject lane (CATALOG-SPEC §6): each code-specific corrective carries a probe
     * DSL with exactly its trigger shape, which the kernel must reject with the corrective's {@code code}
     * — so a corrective can't document a trigger that no longer exists.
     */
    @TestFactory
    Stream<DynamicTest> everyCorrectiveProbeIsRejectedWithItsCode() {
        return CATALOG.entries().stream()
                .flatMap(e -> e.correctives().stream()
                        .map(c -> dynamicTest(e.id() + " · corrective " + c.code() + " " + c.whenOperands(), () -> {
                            ValidationResult result = check(c.probe().dsl());
                            assertFalse(result.ok(),
                                    () -> e.id() + " corrective probe '" + c.probe().dsl() + "' was unexpectedly accepted");
                            assertTrue(result.diagnostics().stream()
                                            .anyMatch(p -> p.code().filter(c.code()::equals).isPresent()),
                                    () -> e.id() + " corrective probe expected " + c.code() + ", got: " + result.diagnostics());
                        })));
    }

    private static ValidationResult check(String errorCondition) {
        String errorField = firstReferencedField(errorCondition);
        DraftRule rule = new DraftRule(
                "/Order", "CatalogExampleRule", "../" + errorField, errorCondition, "CATALOG_EXAMPLE", "ERROR");
        return VALIDATOR.check(modelJson, rule);
    }

    /** The first fixture field referenced (as a whole word) in the example — used as the rule's error entity. */
    private static String firstReferencedField(String dsl) {
        return ORDER_FIELDS.stream()
                .filter(name -> Pattern.compile("\\b" + Pattern.quote(name) + "\\b").matcher(dsl).find())
                .findFirst()
                .or(() -> ORDER_GROUP_FIELDS.entrySet().stream()
                        .filter(e -> Pattern.compile("\\b" + e.getKey() + "\\b").matcher(dsl).find())
                        .map(java.util.Map.Entry::getValue)
                        .findFirst())
                .orElseThrow(() -> new IllegalStateException(
                        "no Order field or group is referenced in example: " + dsl
                                + " — pick one that uses a fixture field/group"));
    }
}
