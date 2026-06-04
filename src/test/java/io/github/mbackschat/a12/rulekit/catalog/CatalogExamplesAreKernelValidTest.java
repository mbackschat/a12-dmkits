package io.github.mbackschat.a12.rulekit.catalog;

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

import io.github.mbackschat.a12.rulekit.validate.DraftRule;
import io.github.mbackschat.a12.rulekit.validate.RuleValidator;
import io.github.mbackschat.a12.rulekit.validate.ValidationResult;

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
                    "Items/Sku", "Items/Count");

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

    @BeforeAll
    static void loadFixture() throws Exception {
        try (InputStream in = CatalogExamplesAreKernelValidTest.class.getClassLoader()
                .getResourceAsStream("models/order.dm.json")) {
            assertNotNull(in, "order.dm.json fixture not found");
            modelJson = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    @TestFactory
    Stream<DynamicTest> everyValidExampleIsAcceptedByTheKernel() {
        return CATALOG.entries().stream()
                .filter(e -> e.validExample() != null)
                .map(e -> dynamicTest(e.id() + " · valid", () -> {
                    ValidationResult result = check(e.validExample().dsl());
                    assertTrue(result.ok(),
                            () -> e.id() + " validExample '" + e.validExample().dsl() + "' rejected: " + result.problems());
                }));
    }

    @TestFactory
    Stream<DynamicTest> everyInvalidExampleIsRejectedByTheKernel() {
        return CATALOG.entries().stream()
                .filter(e -> e.invalidExample() != null)
                .map(e -> dynamicTest(e.id() + " · invalid", () -> {
                    OperatorEntry.InvalidExample bad = e.invalidExample();
                    ValidationResult result = check(bad.dsl());
                    assertFalse(result.ok(),
                            () -> e.id() + " invalidExample '" + bad.dsl() + "' was unexpectedly accepted");
                    if (bad.error() != null && bad.error().startsWith("MVK_")) {
                        assertTrue(
                                result.problems().stream()
                                        .anyMatch(p -> p.mvkCode().filter(bad.error()::equals).isPresent()),
                                () -> e.id() + " expected " + bad.error() + ", got: " + result.problems());
                    }
                }));
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
