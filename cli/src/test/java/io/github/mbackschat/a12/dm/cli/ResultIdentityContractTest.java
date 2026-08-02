package io.github.mbackschat.a12.dm.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import picocli.CommandLine;
import picocli.CommandLine.Model.CommandSpec;

/** Structural lock for the command identity carried by every result envelope. */
class ResultIdentityContractTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Test
    void everyEnvelopeFactoryTakesOneOpaqueIdentityBeforeItsSummary() {
        List<Method> factories = Arrays.stream(ResultEnvelope.class.getDeclaredMethods())
                .filter(method -> Modifier.isPublic(method.getModifiers()))
                .filter(method -> Modifier.isStatic(method.getModifiers()))
                .filter(method -> method.getReturnType() == ResultEnvelope.class)
                .toList();

        assertTrue(factories.stream().anyMatch(method -> method.getName().equals("queryResult")),
                "positive control: reflection found an ordinary factory");
        assertTrue(factories.stream().anyMatch(method -> method.getName().equals("internalError")),
                "positive control: reflection reached the final result family");
        for (Method factory : factories) {
            assertTrue(factory.getParameterCount() >= 2, factory::toString);
            assertEquals(ResultIdentity.class, factory.getParameterTypes()[0], factory::toString);
            assertEquals(String.class, factory.getParameterTypes()[1],
                    () -> factory + " must take summary after the indivisible identity");
        }
    }

    @Test
    void anIdentityCannotBeForgedFromTwoAdjacentStrings() {
        for (Constructor<?> constructor : ResultIdentity.class.getDeclaredConstructors()) {
            assertTrue(Modifier.isPrivate(constructor.getModifiers()), constructor::toString);
        }
        assertFalse(Arrays.stream(ResultIdentity.class.getDeclaredMethods())
                        .anyMatch(method -> Arrays.equals(method.getParameterTypes(),
                                new Class<?>[]{String.class, String.class})),
                "a raw target/op factory only relocates the original defect");
    }

    @Test
    void everyLiveTwoTokenCommandDerivesItsIdentityFromTheCommandTree() {
        CommandLine root = new CommandLine(new RootCommand());
        List<String> derived = new ArrayList<>();
        for (Map.Entry<String, CommandLine> target : root.getSubcommands().entrySet()) {
            for (Map.Entry<String, CommandLine> op : target.getValue().getSubcommands().entrySet()) {
                ResultIdentity identity = ResultIdentity.from(op.getValue().getCommandSpec());
                derived.add(identity.target() + "/" + identity.op());
                assertEquals(target.getKey(), identity.target());
                assertEquals(op.getKey(), identity.op());
            }
        }
        assertTrue(derived.contains("model/read"), "positive control: the ordinary model route was traversed");
        assertTrue(derived.contains("workspace/check"),
                "positive control: traversal reaches a second command-tree branch");
    }

    @Test
    void aOneTokenCommandMustChooseANamedModeInsteadOfInventingAnOp() {
        CommandSpec meta = new CommandLine(new RootCommand()).getSubcommands().get("meta").getCommandSpec();
        assertThrows(IllegalArgumentException.class, () -> ResultIdentity.from(meta));
    }

    @Test
    void exceptionalIdentitiesAreNamedAndExact() {
        assertIdentity(ResultIdentity.WHERE_USED_READ, "where-used", "read");
        assertIdentity(ResultIdentity.META_READ, "meta", "read");
        assertIdentity(ResultIdentity.META_MODIFY, "meta", "modify");
        assertIdentity(ResultIdentity.PATTERNS_READ, "patterns", "read");
        assertIdentity(ResultIdentity.PATTERNS_SCAFFOLD, "patterns", "scaffold");
        assertIdentity(ResultIdentity.APPLY_RUN, "apply", "run");
        assertIdentity(ResultIdentity.BATCH_RUN, "batch", "run");
        assertIdentity(ResultIdentity.MODEL_VALIDATE, "model", "validate");
        assertIdentity(ResultIdentity.UNPARSED_FALLBACK, "model", "");
    }

    @Test
    void applySelectorsAreTheOnlyTypedDynamicIdentitySources() {
        assertTrue(ResultIdentity.Source.class.isSealed());
        assertEquals(Set.of(ApplyCommand.Op.class, ApplyCommand.ParsedSelector.class),
                Set.of(ResultIdentity.Source.class.getPermittedSubclasses()));

        for (ApplyCommand.Op op : ApplyCommand.Op.values()) {
            assertIdentity(op.identity(), op.resultTarget(), op.resultOp());
        }
        assertIdentity(new ApplyCommand.ParsedSelector("unknown-target", "unknown-op").identity(),
                "unknown-target", "unknown-op");
    }

    @Test
    void parsedExceptionIdentityPreservesTheEstablishedFallbacks() {
        CommandLine root = new CommandLine(new RootCommand());
        assertIdentity(ResultIdentity.from(root.parseArgs("model", "read")), "model", "read");
        assertIdentity(ResultIdentity.from(root.parseArgs("meta", "/Order")), "meta", "");
        assertIdentity(ResultIdentity.from(root.parseArgs()), "model", "");
    }

    @Test
    void everyExceptionalRoutePublishesItsNamedIdentity(@TempDir Path dir) throws Exception {
        Path model = CliTestSupport.orderRuled(dir);
        assertEnvelope(CliTestSupport.run("-m", model.toString(), "meta", "/Order"), "meta", "read");
        assertEnvelope(CliTestSupport.run("-m", model.toString(), "meta", "/Order",
                "--annotate", "owner=pricing", "--dry-run"), "meta", "modify");
        assertEnvelope(CliTestSupport.run("patterns"), "patterns", "read");
        assertEnvelope(CliTestSupport.run("-m", model.toString(), "patterns", "date-order",
                "--arg", "earlier=/Order/OrderDate", "--arg", "later=/Order/DeliveryDate"),
                "patterns", "scaffold");
        assertEnvelope(CliTestSupport.run("-m", model.toString(), "where-used", "/Order/Quantity"),
                "where-used", "read");

        Path batch = dir.resolve("empty-batch.json");
        Files.writeString(batch, "[]");
        assertEnvelope(CliTestSupport.run("batch", batch.toString()), "batch", "run");

        Path known = dir.resolve("known-apply.json");
        Files.writeString(known, """
                [{"target":"field","op":"read","fieldPathInModel":"/Order/Quantity"}]
                """);
        JsonNode apply = assertEnvelope(CliTestSupport.run("-m", model.toString(), "apply", known.toString()),
                "apply", "run");
        assertEnvelope(apply.path("data").path("results").path(0), "field", "read");

        Path unknown = dir.resolve("unknown-apply.json");
        Files.writeString(unknown, "[{\"target\":\"ghost\",\"op\":\"turn\"}]");
        JsonNode rejected = assertEnvelope(
                CliTestSupport.run("-m", model.toString(), "apply", unknown.toString()), "apply", "run");
        assertEnvelope(rejected.path("data").path("results").path(0), "ghost", "turn");
    }

    private static JsonNode assertEnvelope(CliTestSupport.Result result, String target, String op) throws Exception {
        return assertEnvelope(MAPPER.readTree(result.out()), target, op);
    }

    private static JsonNode assertEnvelope(JsonNode envelope, String target, String op) {
        assertEquals(target, envelope.path("target").asText(), envelope::toString);
        assertEquals(op, envelope.path("op").asText(), envelope::toString);
        return envelope;
    }

    private static void assertIdentity(ResultIdentity identity, String target, String op) {
        assertEquals(target, identity.target());
        assertEquals(op, identity.op());
    }
}
