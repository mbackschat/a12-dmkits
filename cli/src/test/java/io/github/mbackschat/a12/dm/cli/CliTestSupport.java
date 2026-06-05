package io.github.mbackschat.a12.dm.cli;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import io.github.mbackschat.a12.dm.rulekit.dsl.BoundModel;
import io.github.mbackschat.a12.dm.rulekit.dsl.Dsl;
import io.github.mbackschat.a12.dm.rulekit.dsl.Rule;
import io.github.mbackschat.a12.dm.rulekit.dsl.Severity;

/** Shared CLI test helpers — run a verb at the {@link Cli#run} seam, and build fixtures once. */
final class CliTestSupport {

    static final String RULE_FULL_NAME = "/Subscription/Addons/FeeCapRule";

    private CliTestSupport() {
    }

    /** Result of invoking the CLI in-process: exit code + captured stdout/stderr. */
    record Result(int code, String out, String err) {
    }

    static Result run(String... args) {
        StringBuilder out = new StringBuilder();
        StringBuilder err = new StringBuilder();
        int code = Cli.run(args, out, err);
        return new Result(code, out.toString(), err.toString());
    }

    /** The subscription fixture + one valid rule, as DM-JSON. */
    static String modelWithRuleJson() {
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
    static Path modelWithRule(Path dir) throws IOException {
        Path file = dir.resolve("subscription.dm.json");
        Files.writeString(file, modelWithRuleJson());
        return file;
    }

    static String subscriptionJson() {
        try (InputStream in = CliTestSupport.class.getClassLoader()
                .getResourceAsStream("models/subscription.dm.json")) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
}
