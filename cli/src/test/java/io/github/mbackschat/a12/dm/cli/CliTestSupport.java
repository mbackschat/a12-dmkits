package io.github.mbackschat.a12.dm.cli;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import io.github.mbackschat.a12.dm.rulekit.dsl.BoundModel;
import io.github.mbackschat.a12.dm.rulekit.dsl.Computation;
import io.github.mbackschat.a12.dm.rulekit.dsl.Dsl;
import io.github.mbackschat.a12.dm.rulekit.dsl.Rule;
import io.github.mbackschat.a12.dm.rulekit.dsl.Severity;

/** Shared CLI test helpers — run a verb at the {@link Cli#run} seam, and build fixtures once. */
public final class CliTestSupport {

    public static final String RULE_FULL_NAME = "/Subscription/Addons/FeeCapRule";
    public static final String COMPUTATION_FULL_NAME = "/Subscription/Billing/EffectiveFeeComp";

    private CliTestSupport() {
    }

    /** Result of invoking the CLI in-process: exit code + captured stdout/stderr. */
    public record Result(int code, String out, String err) {
    }

    public static Result run(String... args) {
        StringBuilder out = new StringBuilder();
        StringBuilder err = new StringBuilder();
        int code = Cli.run(args, out, err);
        return new Result(code, out.toString(), err.toString());
    }

    /** Run with the <b>native build profile</b> forced (the JVM-only verbs hidden) — for the native-hiding guard. */
    public static Result runNative(String... args) {
        StringBuilder out = new StringBuilder();
        StringBuilder err = new StringBuilder();
        int code = Cli.run(args, out, err, true);
        return new Result(code, out.toString(), err.toString());
    }

    /** The subscription fixture + one valid rule, as DM-JSON. */
    public static String modelWithRuleJson() {
        BoundModel base = BoundModel.ofJson(subscriptionJson());
        Rule rule = base.rule(base.group("/Subscription/Addons"))
                .name("FeeCapRule")
                .errorField(base.num("/Subscription/Addons/MonthlyFee"))
                .when(base.num("/Subscription/Addons/MonthlyFee").gt(1000))
                .code("FEE_CAP").severity(Severity.ERROR)
                .message(Dsl.en("Too high."), Dsl.de("Zu hoch.")).build();
        return base.add(rule).toJson();
    }

    /** Write {@link #modelWithRuleJson()} to a temp file the CLI can read. */
    public static Path modelWithRule(Path dir) throws IOException {
        Path file = dir.resolve("subscription.dm.json");
        Files.writeString(file, modelWithRuleJson());
        return file;
    }

    /** The subscription fixture + the {@link #modelWithRuleJson()} rule + a computation (EffectiveFee = BaseFee). */
    public static String modelWithRuleAndComputationJson() {
        BoundModel base = BoundModel.ofJson(modelWithRuleJson());
        Computation comp = base.computation(base.num("/Subscription/Billing/EffectiveFee"))
                .name("EffectiveFeeComp")
                .calc(base.num("/Subscription/Billing/BaseFee").value())
                .message(Dsl.en("EffectiveFee equals BaseFee."), Dsl.de("Effektivgebühr = Basisgebühr.")).build();
        return base.add(comp).toJson();
    }

    /** Write {@link #modelWithRuleAndComputationJson()} to a temp file the CLI can read. */
    public static Path modelWithRuleAndComputation(Path dir) throws IOException {
        Path file = dir.resolve("subscription.dm.json");
        Files.writeString(file, modelWithRuleAndComputationJson());
        return file;
    }

    public static String subscriptionJson() {
        return resource("models/subscription.dm.json");
    }

    /** The committed {@code order-ruled} fixture (rules: POSTAL_CODE_MALFORMED, DELIVERY_BEFORE_ORDER, INELIGIBLE_ORDER). */
    public static String orderRuledJson() {
        return resource("models/order-ruled.dm.json");
    }

    /** Write the {@code order-ruled} fixture to a temp file the CLI can read. */
    public static Path orderRuled(Path dir) throws IOException {
        Path file = dir.resolve("order-ruled.dm.json");
        Files.writeString(file, orderRuledJson());
        return file;
    }

    /** Write the {@code subscription-computed} fixture (en_US + de_DE, a computation) to a temp file. */
    public static Path subscriptionComputed(Path dir) throws IOException {
        Path file = dir.resolve("subscription-computed.dm.json");
        Files.writeString(file, resource("models/subscription-computed.dm.json"));
        return file;
    }

    private static String resource(String classpathPath) {
        try (InputStream in = CliTestSupport.class.getClassLoader().getResourceAsStream(classpathPath)) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
}
