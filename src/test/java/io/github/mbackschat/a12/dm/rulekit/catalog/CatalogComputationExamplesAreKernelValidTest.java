package io.github.mbackschat.a12.dm.rulekit.catalog;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.DynamicTest.dynamicTest;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.stream.Stream;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.TestFactory;

import io.github.mbackschat.a12.dm.rulekit.validate.DraftComputation;
import io.github.mbackschat.a12.dm.rulekit.validate.RuleValidator;
import io.github.mbackschat.a12.dm.rulekit.validate.ValidationResult;

/**
 * N7 peer for <b>computation-only</b> operators: every catalog {@code computationExample} is accepted by
 * the real kernel — injected as a computation that assigns {@code computedField} (one unconditional
 * alternative) and checked via {@link RuleValidator}. Covers operators with no condition form (the
 * {@code DateRange} constructor) that {@code CatalogExamplesAreKernelValidTest} can't reach.
 */
class CatalogComputationExamplesAreKernelValidTest {

    private static final OperatorCatalog CATALOG = OperatorCatalog.load();
    private static final RuleValidator VALIDATOR = new RuleValidator();

    private static String modelJson;

    @BeforeAll
    static void loadFixture() throws Exception {
        try (InputStream in = CatalogComputationExamplesAreKernelValidTest.class.getClassLoader()
                .getResourceAsStream("models/order.dm.json")) {
            assertNotNull(in, "order.dm.json fixture not found");
            modelJson = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    @TestFactory
    Stream<DynamicTest> everyComputationExampleIsAcceptedByTheKernel() {
        return CATALOG.entries().stream()
                .filter(e -> e.computationExample() != null)
                .map(e -> dynamicTest(e.id() + " · computation", () -> {
                    OperatorEntry.ComputationExample ex = e.computationExample();
                    int slash = ex.computedField().lastIndexOf('/');
                    String groupPath = ex.computedField().substring(0, slash);
                    String fieldName = ex.computedField().substring(slash + 1);
                    DraftComputation comp = new DraftComputation(groupPath, e.id() + "Example", "../" + fieldName,
                            null, List.of(new DraftComputation.Alternative(null, ex.operation())));
                    ValidationResult result = VALIDATOR.check(modelJson, comp);
                    assertTrue(result.ok(),
                            () -> e.id() + " computationExample '" + ex.operation() + "' rejected: " + result.diagnostics());
                }));
    }
}
