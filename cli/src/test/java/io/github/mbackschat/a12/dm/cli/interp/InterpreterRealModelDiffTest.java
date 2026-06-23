package io.github.mbackschat.a12.dm.cli.interp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.junit.jupiter.api.Test;

import io.github.mbackschat.a12.dm.adapter.ComputedField;
import io.github.mbackschat.a12.dm.adapter.KernelAdapter;
import io.github.mbackschat.a12.dm.interpreter.model.Computed;
import io.github.mbackschat.a12.dm.interpreter.DmInterpreter;
import io.github.mbackschat.a12.dm.interpreter.eval.Document;
import io.github.mbackschat.a12.dm.interpreter.model.Message;
import io.github.mbackschat.a12.dm.interpreter.model.ModelDef;
import io.github.mbackschat.a12.dm.interpreter.model.RuleDef;
import io.github.mbackschat.a12.dm.interpreter.model.ValidationOutcome;

/**
 * The strongest differential: load a REAL DM-JSON model into the kernel-free interpreter (via
 * {@link InterpreterModelLoader}) and assert it agrees with the live kernel ({@link KernelAdapter}) over
 * the same instance — the same models the {@code examples/cli-runtime.md} demo uses. This is the keystone
 * for the native runtime verbs: it proves the load → interpret path matches the kernel end to end, with
 * no hand-built {@code ModelDef}. Rules the interpreter doesn't model (e.g. {@code CustomCondition}) are
 * excluded from the comparison — they are reported {@code unsupported}, not silently wrong.
 */
class InterpreterRealModelDiffTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String EFF = "/Subscription/Billing/EffectiveFee";

    @Test
    void orderRuledViolationFiresLikeKernel() {
        // delivery 2024-05-15 BEFORE order 2024-06-01 → DifferenceInDays(...) < 0 fires
        assertValidationMatches("models/order-ruled.dm.json",
                "{ \"fields\": { \"Order\": { \"OrderDate\": \"2024-06-01\", \"DeliveryDate\": \"2024-05-15\" } } }",
                Set.of("DELIVERY_BEFORE_ORDER"));
    }

    @Test
    void orderRuledCompliantFiresNothingLikeKernel() {
        // delivery after order → no supported rule fires
        assertValidationMatches("models/order-ruled.dm.json",
                "{ \"fields\": { \"Order\": { \"OrderDate\": \"2024-06-01\", \"DeliveryDate\": \"2024-06-15\" } } }",
                Set.of());
    }

    @Test
    void postalCodePatternFiresLikeKernel() {
        // a malformed billing postal code trips PostalCodeFormat (PatternViolated), nested under BillingAddress
        assertValidationMatches("models/order-ruled.dm.json",
                "{ \"fields\": { \"Order\": { \"BillingAddress\": { \"PostalCode\": \"12\" } } } }",
                Set.of("POSTAL_CODE_MALFORMED"));
    }

    @Test
    void subscriptionComputeMatchesKernel() {
        assertComputeMatches("{ \"fields\": { \"Subscription\": { \"Billing\": { \"BaseFee\": \"49.90\" } } } }", "49.9");
    }

    @Test
    void subscriptionComputeEmptyOperandMatchesKernel() {
        // BaseFee absent, Billing present but EMPTY ({}) — the loader must instantiate the empty group so the
        // computation runs and empty-as-0 yields "0" (the cli-runtime trap; a loader regression guard).
        assertComputeMatches("{ \"fields\": { \"Subscription\": { \"Billing\": { } } } }", "0");
    }

    private void assertComputeMatches(String instanceJson, String expectedValue) {
        String model = resource("models/subscription-computed.dm.json");
        Map<String, Object> fields = innerFields(instanceJson);

        String kernelValue = KernelAdapter.ofJson(model).compute(fields, Locale.US).computed().stream()
                .filter(c -> c.path().equals(EFF)).findFirst().flatMap(ComputedField::value).orElse(null);

        ModelDef def = InterpreterModelLoader.load(model);
        Document doc = InterpreterModelLoader.document(fields, def);
        String interpValue = new DmInterpreter(def).compute(doc, "en_US").stream()
                .filter(c -> c.getPath().equals(EFF)).findFirst().map(Computed::getValue).orElse(null);

        assertEquals(expectedValue, kernelValue, "kernel computes the expected value");
        assertEquals(kernelValue, interpValue, "interpreter computes the same value as the kernel over the real model");
    }

    /** Assert the interpreter fires exactly the kernel's codes, restricted to the rules it supports. */
    private void assertValidationMatches(String modelResource, String instanceJson, Set<String> expectedSupportedFired) {
        String model = resource(modelResource);
        Map<String, Object> fields = innerFields(instanceJson);

        Set<String> kernelFired = KernelAdapter.ofJson(model).evaluate(fields, Locale.US).firedCodes();

        ModelDef def = InterpreterModelLoader.load(model);
        Document doc = InterpreterModelLoader.document(fields, def);
        ValidationOutcome outcome = new DmInterpreter(def).validate(doc, "en_US");

        Set<String> unsupportedCodes = outcome.getUnsupported().stream()
                .map(u -> codeOf(def, u.getRulePath())).collect(Collectors.toSet());
        Set<String> supportedCodes = def.getRules().stream().map(RuleDef::getErrorCode)
                .filter(c -> !unsupportedCodes.contains(c)).collect(Collectors.toSet());

        Set<String> kernelSupportedFired = kernelFired.stream().filter(supportedCodes::contains).collect(Collectors.toSet());
        Set<String> interpFired = outcome.getMessages().stream().map(Message::getCode).collect(Collectors.toSet());

        assertEquals(kernelSupportedFired, interpFired,
                "interpreter vs kernel over " + modelResource + " (kernel-all=" + kernelFired + ", supported=" + supportedCodes + ")");
        assertEquals(expectedSupportedFired, interpFired, "expected supported firings");
        // the CustomCondition rule must be reported unsupported, never silently dropped
        assertTrue(outcome.getUnsupported().stream().anyMatch(u -> u.getRulePath().equals("/Order/EligibilityCheck")),
                "the external CustomCondition rule is reported unsupported");
    }

    private static String codeOf(ModelDef def, String rulePath) {
        return def.getRules().stream().filter(r -> r.getPath().equals(rulePath))
                .map(RuleDef::getErrorCode).findFirst().orElse("");
    }

    private static Map<String, Object> innerFields(String instanceJson) {
        try {
            JsonNode root = MAPPER.readTree(instanceJson);
            JsonNode fields = root.has("fields") ? root.get("fields") : root;
            return MAPPER.convertValue(fields, new TypeReference<Map<String, Object>>() { });
        } catch (Exception e) {
            throw new IllegalArgumentException("bad instance JSON", e);
        }
    }

    private static String resource(String path) {
        try (InputStream in = InterpreterRealModelDiffTest.class.getClassLoader().getResourceAsStream(path)) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (Exception e) {
            throw new RuntimeException("missing test resource " + path, e);
        }
    }
}
