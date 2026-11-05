package io.github.mbackschat.a12.dm.cli;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Pattern;

import io.github.mbackschat.a12.dm.edit.EditSession;
import io.github.mbackschat.a12.dm.modelkit.DocumentModelDraft;
import io.github.mbackschat.a12.dm.modelkit.FieldKind;
import io.github.mbackschat.a12.dm.rulekit.dsl.BoundModel;
import io.github.mbackschat.a12.dm.rulekit.dsl.Computation;
import io.github.mbackschat.a12.dm.rulekit.dsl.Dsl;
import io.github.mbackschat.a12.dm.rulekit.dsl.Rule;
import io.github.mbackschat.a12.dm.rulekit.dsl.Severity;
import picocli.CommandLine;

/** Shared CLI test helpers — run a verb at the {@link Cli#run} seam, and build fixtures once. */
public final class CliTestSupport {

    public static final String RULE_FULL_NAME = "/Subscription/Addons/FeeCapRule";
    public static final String COMPUTATION_FULL_NAME = "/Subscription/Billing/EffectiveFeeComp";

    private CliTestSupport() {
    }

    /** Result of invoking the CLI in-process: exit code + captured stdout/stderr. */
    public record Result(int code, String out, String err) {
    }

    /**
     * A kernel-vocabulary class, as it appears in text. Deliberately not anchored to the live {@code MVK_*}
     * inventory: the point is the SHAPE a reader recognizes as the kernel's, so a code retired upstream or one
     * invented by a future pre-check is caught the same way.
     */
    private static final Pattern KERNEL_VOCABULARY =
            Pattern.compile("\\bMVK_[A-Z0-9_]+");

    public static Result run(String... args) {
        StringBuilder out = new StringBuilder();
        StringBuilder err = new StringBuilder();
        int code = Cli.run(args, out, err);
        Result result = new Result(code, out.toString(), err.toString());
        refuseUnattributedKernelVocabulary(args, result);
        return result;
    }

    /**
     * <b>An {@code MVK_*} code must never reach the ATTRIBUTION-FREE channel.</b> dmtool has two ways to refuse:
     * the result envelope, where every diagnostic carries a {@code source} saying who decided, and
     * {@code BaseCommand.fail(...)} prose on stderr, which carries no attribution at all. A refusal that knows
     * a kernel code and is routed to the second one is indistinguishable from a kernel verdict — which is the
     * defect a12-kernel-lean reported twice: {@code field add} attributing a local cap to "the kernel's cap"
     * (2026-08-29), and {@code patterns} printing a bare {@code MVK_ERROR_REFERENCE_TO_CALCULATED_FIELD} from
     * {@code Computation.Builder}'s pre-check (2026-08-30, found by review after the first fix claimed the
     * class was closed).
     *
     * <p><b>Why it lives HERE rather than in a test of its own.</b> A dedicated probe needs a corpus of
     * refusing inputs and can only ever cover the ones someone thought of — which is exactly how the second
     * instance survived the first fix. Every in-process CLI test in the estate goes through this method, so
     * putting the invariant at the shared sink makes the whole suite the detector, including tests written
     * later for unrelated reasons. Carrying an {@code MVK_*} code is fine and often right; carrying it with no
     * {@code source} beside it is not, so the envelope route is unaffected.
     *
     * <p>Locked both ways by {@code UnattributedKernelVocabularyGuardTest}.
     */
    static void refuseUnattributedKernelVocabulary(String[] args, Result result) {
        java.util.regex.Matcher token = KERNEL_VOCABULARY.matcher(result.err());
        if (!token.find() || result.out().contains("\"source\"")) {
            return;
        }
        // Two carve-outs, both about text that MENTIONS a code rather than reporting one as a verdict. They
        // are shaped by what the channel is FOR, not by which cases happened to trip: help must be free to
        // document the reject it prevents, and one verb's whole contract is explaining the vocabulary.
        if (result.err().contains("Usage: dmtool")) {
            return;     // picocli usage/help — `--suggest-error-field`'s help text names the code it avoids
        }
        if (java.util.List.of(args).contains("diagnostics")) {
            return;     // the code-explaining verb; naming MVK_* codes as catalog entries is its job
        }
        throw new AssertionError("`dmtool " + String.join(" ", args) + "` put the kernel-vocabulary token "
                + token.group() + " on STDERR with no envelope to attribute it, so a consumer cannot tell this "
                + "local refusal from a kernel verdict. Publish it instead — rejectPrecheck(...) for a "
                + "PrecheckException, which already carries the structured diagnostic, or rejectSpecValue(...) "
                + "for a builder refusal. stderr was: " + result.err().strip());
    }

    /** Obtain a test identity from the live command tree; tests do not get a raw production identity factory. */
    static ResultIdentity identity(String target, String op) {
        CommandLine targetCommand = new CommandLine(new RootCommand()).getSubcommands().get(target);
        if (targetCommand == null || !targetCommand.getSubcommands().containsKey(op)) {
            throw new IllegalArgumentException("no live two-token command `" + target + " " + op + "`");
        }
        return ResultIdentity.from(targetCommand.getSubcommands().get(op).getCommandSpec());
    }

    /** Run with the <b>native build profile</b> forced — for the native-surface guard. */
    public static Result runNative(String... args) {
        StringBuilder out = new StringBuilder();
        StringBuilder err = new StringBuilder();
        int code = Cli.run(args, out, err, true);
        return new Result(code, out.toString(), err.toString());
    }

    /**
     * Run a runtime-eval verb on the <b>kernel</b> engine, the kernel LEG of the CLI-level
     * interpreter-vs-kernel differential. The user-facing {@code --kernel} eval flag was retired (Set V,
     * INTERPRETER-DROPIN-CERTIFICATE): production {@code dmtool} evaluates only through the native-safe
     * interpreter, so the kernel-fidelity verb/parity tests reach the kernel through {@link KernelEval} — the
     * {@link io.github.mbackschat.a12.dm.adapter.KernelAdapter} API directly, as the {@code :adapter}
     * differential tests do — rendering the identical {@code data} envelope.
     */
    public static Result runKernel(String... args) {
        return KernelEval.run(args);
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

    /**
     * An e-commerce order-pricing model with two computations carrying <b>preconditions</b> — one gated by a
     * <b>common precondition</b> (ShippingFee), one with <b>per-alternative</b> preconditions (Discount) —
     * authored through the typed surface. The fixture that exercises {@code model report}'s computation
     * preconditions and their parity with {@code computation read} (Set RP task 2).
     */
    public static Path orderPricingComputations(Path dir) throws IOException {
        String base = DocumentModelDraft.named("order-pricing").locale("en_US")
                .rootGroup("Order", g -> g
                        .field("Subtotal", FieldKind.NUMBER)
                        .field("BaseShipping", FieldKind.NUMBER)
                        .field("MemberRate", FieldKind.NUMBER)
                        .field("GuestRate", FieldKind.NUMBER)
                        .field("ShippingFee", FieldKind.NUMBER)
                        .field("Discount", FieldKind.NUMBER))
                .build().toJson();
        EditSession s = EditSession.read(base);
        s.addComputation(m -> m.computation(m.num("/Order/ShippingFee"))
                .name("ShippingFeeComp")
                .commonPrecondition(m.num("/Order/Subtotal").gt(0))
                .when(m.num("/Order/Subtotal").gt(500)).calc(m.num("/Order/GuestRate").value())
                .when(m.num("/Order/Subtotal").le(500)).calc(m.num("/Order/BaseShipping").value())
                .message(Dsl.en("Shipping fee applies once the order has a subtotal.")).build());
        s.addComputation(m -> m.computation(m.num("/Order/Discount"))
                .name("DiscountComp")
                .when(m.num("/Order/Subtotal").gt(1000)).calc(m.num("/Order/MemberRate").value())
                .when(m.num("/Order/Subtotal").le(1000)).calc(m.num("/Order/GuestRate").value())
                .message(Dsl.en("Discount depends on the order subtotal.")).build());
        Path file = dir.resolve("order-pricing.dm.json");
        Files.writeString(file, s.toJson());
        return file;
    }

    public static String subscriptionJson() {
        return resource("models/subscription.dm.json");
    }

    /** The committed {@code order-ruled} fixture (rules: POSTAL_CODE_MALFORMED, DELIVERY_BEFORE_ORDER, INELIGIBLE_ORDER). */
    public static String orderRuledJson() {
        return resource("models/order-ruled.dm.json");
    }

    public static String orderJson() {
        return resource("models/order.dm.json");
    }

    /** Write the broad operator-domain order fixture to a temp file the CLI can read. */
    public static Path order(Path dir) throws IOException {
        Path file = dir.resolve("order.dm.json");
        Files.writeString(file, orderJson());
        return file;
    }

    /** Write the {@code order-ruled} fixture to a temp file the CLI can read. */
    public static Path orderRuled(Path dir) throws IOException {
        Path file = dir.resolve("order-ruled.dm.json");
        Files.writeString(file, orderRuledJson());
        return file;
    }

    /**
     * A policy model whose only rule names a GROUP and no field inside it — the shape that separates a
     * forward-dependency question ("what does this rule name?") from a reachability one ("what does it read?").
     * Built here rather than committed as a fixture because it exists for exactly that one distinction.
     */
    public static Path groupOperandRuled(Path dir) throws IOException {
        String base = io.github.mbackschat.a12.dm.modelkit.DocumentModelDraft.named("policy").locale("en_US")
                .rootGroup("Policy", p -> p
                        .field("Reference", FieldKind.STRING)
                        .group("Assessment", a -> a.field("Observed",
                                FieldKind.NUMBER)))
                .build().toJson();
        EditSession session =
                EditSession.read(base);
        session.addRule(m -> m.rule(m.group("/Policy")).name("AssessmentComplete")
                .errorField(m.str("/Policy/Reference"))
                .when(m.str("/Policy/Reference").filled().and(io.github.mbackschat.a12.dm.rulekit.dsl.Dsl
                        .notAllFieldsFilled(m.flattenGroup("/Policy/Assessment"))))
                .code("ASSESS_INCOMPLETE")
                .severity(io.github.mbackschat.a12.dm.rulekit.dsl.Severity.ERROR)
                .message(new io.github.mbackschat.a12.dm.rulekit.dsl.LocalizedText(
                        "en_US", "The assessment is incomplete"))
                .build());
        Path file = dir.resolve("policy-group-operand.dm.json");
        Files.writeString(file, session.validate().toJson());
        return file;
    }

    /** Write the {@code payment-customtype} fixture (a {@code CustomFieldType "Iban"} field) to a temp file. */
    public static Path paymentCustomType(Path dir) throws IOException {
        Path file = dir.resolve("payment-customtype.dm.json");
        Files.writeString(file, resource("models/payment-customtype.dm.json"));
        return file;
    }

    /** Write the {@code subscription-computed} fixture (en_US + de_DE, a computation) to a temp file. */
    public static Path subscriptionComputed(Path dir) throws IOException {
        Path file = dir.resolve("subscription-computed.dm.json");
        Files.writeString(file, resource("models/subscription-computed.dm.json"));
        return file;
    }

    /** Write the {@code typedefs} fixture (a local type definition {@code PostalCodeType}) to a temp file. */
    public static Path typedefs(Path dir) throws IOException {
        Path file = dir.resolve("typedefs.dm.json");
        Files.writeString(file, resource("models/typedefs.dm.json"));
        return file;
    }

    /** Write the {@code customer-register} fixture (one document uniqueness criterion, {@code UniqueCustomerNumber}). */
    public static Path customerRegister(Path dir) throws IOException {
        Path file = dir.resolve("customer-register.dm.json");
        Files.writeString(file, resource("models/customer-register.dm.json"));
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
