package io.github.mbackschat.a12.dm.cli;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.cfg.JsonNodeFeature;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import io.github.mbackschat.a12.dm.adapter.ComputeResult;
import io.github.mbackschat.a12.dm.adapter.ComputedField;
import io.github.mbackschat.a12.dm.adapter.EvalMessage;
import io.github.mbackschat.a12.dm.adapter.EvalResult;
import io.github.mbackschat.a12.dm.adapter.FieldPointer;
import io.github.mbackschat.a12.dm.adapter.KernelAdapter;
import io.github.mbackschat.a12.dm.adapter.RuleTestOutcome;
import io.github.mbackschat.a12.dm.core.ModelResolver;
import io.github.mbackschat.a12.dm.core.ReferenceResolvers;
import io.github.mbackschat.a12.dm.interpreter.eval.PointerKt;
import io.github.mbackschat.a12.dm.rulekit.dsl.BoundModel;
import io.github.mbackschat.a12.dm.rulekit.validate.DraftRule;
import io.github.mbackschat.a12.dm.rulekit.validate.RuleValidator;
import io.github.mbackschat.a12.dm.rulekit.validate.ValidationResult;

/**
 * Test-only KERNEL evaluation harness — reaches the A12 kernel through the {@link KernelAdapter} API directly
 * (exactly as the {@code :adapter} differential tests do), rendering the SAME {@code data} envelope the CLI's
 * runtime-eval verbs emit. It is the kernel LEG of the CLI-level interpreter-vs-kernel differential after the
 * user-facing {@code --kernel} eval flag was retired (Set V, INTERPRETER-DROPIN-CERTIFICATE): production
 * {@code :cli} now carries only the native-safe interpreter engine, so the kernel-fidelity verb/parity tests
 * drive the kernel through this harness instead of through a removed flag. It mirrors the former kernel branch
 * of {@code ModelEvalCommand}/{@code ModelComputeCommand}/{@code RuleEvalCommand} — the SAME public building
 * blocks ({@link ResultEnvelope}, {@link SafetyGate}, {@link RuleValidator}, {@link KernelAdapter}) — so the
 * tests keep asserting the identical envelope shape and see interpreter-vs-kernel parity.
 *
 * <p>The kernel is verification-only here; production {@code dmtool} never renders this. Parses only the fixed
 * runtime-eval argument vocabulary the parity tests use ({@code -m}, {@code -w/--workspace}, {@code --instance},
 * {@code --rule}, the candidate {@code --condition}/{@code --field}/{@code --group}/{@code --code}/{@code
 * --severity}/{@code --name}, {@code --locale}, {@code --no-computations}).
 */
final class KernelEval {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /**
     * Configured exactly like A12's own document mapper ({@code ObjectMapperProvider
     * .createObjectMapperForDocSerializer()}): {@code USE_BIG_DECIMAL_FOR_FLOATS} with
     * {@code STRIP_TRAILING_BIGDECIMAL_ZEROES} disabled, under the Kernel-team comment "we want to preserve
     * trailing zeroes and the scale of the value", plus {@code ALLOW_COMMENTS} because A12 accepts them. On a
     * DEFAULT mapper a native {@code 250.00} becomes a {@code DoubleNode} and reaches the document as
     * {@code "250.0"} — the scale a NumberType's formal check and every comparison read.
     */
    private static final ObjectMapper DOCUMENT_MAPPER = new ObjectMapper()
            .enable(JsonParser.Feature.ALLOW_COMMENTS)
            .enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS)
            .configure(JsonNodeFeature.STRIP_TRAILING_BIGDECIMAL_ZEROES, false);

    private KernelEval() {
    }

    /**
     * A12 Document JSON → the nested field map {@link KernelAdapter} takes. Production {@code :cli} no longer
     * parses a document at all (the interpreter's canonical codec does), but the kernel adapter's public API
     * still consumes {@code Map<String, Object>} — a different boundary, recorded as a step-5 row in
     * CONFORMANCE-CORPUS-SPEC §13 "one ingress regime on both legs", deliberately not changed here. This harness is one of its two remaining callers — there is no production one.
     */
    private static Map<String, Object> readInstanceFields(String docPath) throws IOException {
        JsonNode root = DOCUMENT_MAPPER.readTree(Files.readString(Path.of(docPath)));
        if (!root.isObject()) {
            throw new IllegalArgumentException("a document instance is a JSON object keyed by root group name");
        }
        return DOCUMENT_MAPPER.convertValue(root, new TypeReference<Map<String, Object>>() { });
    }

    /** Run a runtime-eval verb on the kernel engine; returns the same {@code {code, out, err}} shape the CLI does. */
    static CliTestSupport.Result run(String... args) {
        Args a = Args.parse(args);
        StringBuilder out = new StringBuilder();
        StringBuilder err = new StringBuilder();
        int code;
        try {
            code = switch (a.op()) {
                case "model eval" -> modelEval(a, out, err);
                case "model compute" -> modelCompute(a, out, err);
                case "rule eval" -> ruleEval(a, out, err);
                default -> throw new IllegalArgumentException("KernelEval harness: unsupported verb '" + a.op() + "'");
            };
        } catch (IllegalArgumentException badDoc) {
            code = fail(err, badDoc.getMessage());
        }
        return new CliTestSupport.Result(code, out.toString(), err.toString());
    }

    // ---- verbs (each mirrors the retired kernel branch of the matching command) ----

    private static int modelEval(Args a, StringBuilder out, StringBuilder err) {
        if (a.model() == null) {
            return fail(err, "pass -m <model.json> (before or after the verb)");
        }
        boolean candidate = a.condition() != null || a.field() != null;
        if (candidate && a.rule() != null) {
            return fail(err, "pass either --rule <name> OR a candidate (--condition + --field), not both");
        }
        if (candidate && (a.condition() == null || a.field() == null)) {
            return fail(err, "a candidate rule needs both --condition and --field");
        }
        String modelJson;
        Map<String, Object> fields;
        try {
            modelJson = Files.readString(Path.of(a.model()));
        } catch (IOException e) {
            return fail(err, "cannot read model: " + e.getMessage());
        }
        try {
            fields = readInstanceFields(a.instance());
        } catch (IllegalArgumentException badShape) {
            return fail(err, badShape.getMessage());
        } catch (IOException e) {
            return fail(err, "cannot read document: " + e.getMessage());
        }
        ModelResolver resolver = resolver(a);
        Locale locale = locale(a);

        String effectiveGroup = a.group();
        String errorEntityRelPath = null;
        String targetRule = a.rule();
        if (candidate) {
            if (effectiveGroup == null) {
                int slash = a.field().lastIndexOf('/');
                if (slash <= 0) {
                    return fail(err, "--field must be an absolute path (e.g. /Group/Field)");
                }
                effectiveGroup = a.field().substring(0, slash);
            }
            if (!a.field().startsWith(effectiveGroup + "/")) {
                return fail(err, "--field " + a.field() + " must be inside --group " + effectiveGroup);
            }
            errorEntityRelPath = "../" + a.field().substring(effectiveGroup.length() + 1);
            targetRule = effectiveGroup + "/" + a.name();
        }

        ObjectNode data;
        try {
            if (candidate) {
                DraftRule draft = new DraftRule(effectiveGroup, a.name(), errorEntityRelPath,
                        a.condition(), a.code(), a.severity());
                ValidationResult check = new RuleValidator().check(modelJson, draft, resolver);
                if (!check.ok()) {
                    ResultEnvelope env = ResultEnvelope.rejected("model", "eval",
                            "the candidate rule is rejected by the kernel (use `rule check` for diagnostics)",
                            check.diagnostics(), RejectionBasis.kernel(ConsistencyEvidence.of(check)));
                    out.append(env.toJsonString());
                    return env.exitCode();
                }
                String evalJson = new RuleValidator().inject(modelJson, draft);
                KernelAdapter ka = KernelAdapter.ofJson(evalJson, resolver);
                data = kernelEvalData(a.noComputations() ? ka.evaluate(fields, locale)
                        : ka.evaluateApplied(fields, locale), targetRule);
            } else {
                KernelAdapter ka = KernelAdapter.ofJson(modelJson, resolver);
                if (a.rule() != null) {
                    Optional<Refusal> refusal = new SafetyGate()
                            .require(SafetyChecks.ruleExists(ka, a.rule()))
                            .firstFailure();
                    if (refusal.isPresent()) {
                        return refuse(out, "model", "eval", refusal.get());
                    }
                }
                data = kernelEvalData(a.noComputations() ? ka.evaluate(fields, locale)
                        : ka.evaluateApplied(fields, locale), targetRule);
            }
        } catch (IllegalArgumentException badDoc) {
            return fail(err, badDoc.getMessage());
        } catch (RuntimeException e) {
            return fail(err, "evaluation failed: " + e.getMessage());
        }

        ResultEnvelope env = ResultEnvelope.runtimeObservation("model", "eval",
                data.get("fired").size() + " rule(s) fired", data, RuntimeEvidence.kernel());
        out.append(env.toJsonString());
        return env.exitCode();
    }

    private static int modelCompute(Args a, StringBuilder out, StringBuilder err) {
        if (a.model() == null) {
            return fail(err, "pass -m <model.json> (before or after the verb)");
        }
        String modelJson;
        Map<String, Object> fields;
        try {
            modelJson = Files.readString(Path.of(a.model()));
        } catch (IOException e) {
            return fail(err, "cannot read model: " + e.getMessage());
        }
        try {
            fields = readInstanceFields(a.instance());
        } catch (IllegalArgumentException badShape) {
            return fail(err, badShape.getMessage());
        } catch (IOException e) {
            return fail(err, "cannot read document: " + e.getMessage());
        }
        ObjectNode data;
        try {
            ComputeResult result = KernelAdapter.ofJson(modelJson, resolver(a)).compute(fields, locale(a));
            data = MAPPER.createObjectNode();
            ArrayNode computed = data.putArray("computed");
            for (ComputedField c : result.computed()) {
                ObjectNode cn = computed.addObject();
                cn.put("field", c.path());
                cn.put("outcome", c.outcome());
                c.value().ifPresent(v -> cn.put("value", v));
            }
        } catch (IllegalArgumentException badDoc) {
            return fail(err, badDoc.getMessage());
        } catch (RuntimeException e) {
            return fail(err, "computation failed: " + e.getMessage());
        }
        ResultEnvelope env = ResultEnvelope.runtimeObservation("model", "compute",
                "computed " + data.get("computed").size() + " field(s)", data, RuntimeEvidence.kernel());
        out.append(env.toJsonString());
        return env.exitCode();
    }

    private static int ruleEval(Args a, StringBuilder out, StringBuilder err) {
        if (a.model() == null) {
            return fail(err, "pass -m <model.json> (before or after the verb)");
        }
        String modelJson;
        try {
            modelJson = Files.readString(Path.of(a.model()));
        } catch (IOException e) {
            return fail(err, "cannot read model: " + e.getMessage());
        }
        Map<String, Object> fields;
        try {
            fields = readInstanceFields(a.instance());
        } catch (IllegalArgumentException badShape) {
            return fail(err, badShape.getMessage());
        } catch (IOException e) {
            return fail(err, "cannot read document: " + e.getMessage());
        }
        try {
            BoundModel bound = BoundModel.ofJson(modelJson, resolver(a));
            KernelAdapter adapter = KernelAdapter.of(bound);
            Optional<Refusal> refusal = new SafetyGate()
                    .require(SafetyChecks.ruleExists(adapter, a.rulePath()))
                    .firstFailure();
            if (refusal.isPresent()) {
                return refuse(out, "rule", "eval", refusal.get());
            }
            ObjectNode data = ruleTestData(adapter.testRule(a.rulePath(), fields, locale(a), !a.noComputations()));
            ResultEnvelope env = ResultEnvelope.runtimeObservation("rule", "eval", ruleSummary(data), data,
                    RuntimeEvidence.kernel());
            out.append(env.toJsonString());
            return env.exitCode();
        } catch (IllegalArgumentException badDoc) {
            return fail(err, badDoc.getMessage());
        } catch (RuntimeException e) {
            return fail(err, "rule eval failed: " + e.getMessage());
        }
    }

    // ---- kernel -> data rendering (relocated verbatim from the retired command kernel branches) ----

    private static ObjectNode kernelEvalData(EvalResult result, String targetRule) {
        ObjectNode data = MAPPER.createObjectNode();
        ArrayNode fired = data.putArray("fired");
        result.firedCodes().forEach(fired::add);
        ArrayNode messages = data.putArray("messages");
        for (EvalMessage m : result.messages()) {
            ObjectNode mn = messages.addObject();
            mn.put("code", m.errorCode());
            m.rulePath().ifPresent(r -> mn.put("rule", r));
            mn.put("field", pointer(m.field()));
            mn.put("severity", m.severity());
            mn.put("type", m.messageType());
            mn.put("message", m.message());
            ArrayNode referenced = mn.putArray("referenced");
            m.referenced().forEach(p -> referenced.add(pointer(p)));
            if (!m.fillToFix().isEmpty()) {
                ArrayNode fillToFix = mn.putArray("fillToFix");
                m.fillToFix().forEach(p -> fillToFix.add(pointer(p)));
            }
        }
        if (targetRule != null) {
            boolean ruleFired = result.messages().stream()
                    .anyMatch(m -> m.rulePath().filter(targetRule::equals).isPresent());
            ObjectNode rule = data.putObject("rule");
            rule.put("name", targetRule);
            rule.put("fired", ruleFired);
        }
        return data;
    }

    private static ObjectNode ruleTestData(RuleTestOutcome outcome) {
        ObjectNode data = MAPPER.createObjectNode();
        data.put("rule", outcome.rule());
        data.put("fired", outcome.fired());
        data.put("verdict", outcome.verdict().name().toLowerCase(Locale.ROOT));
        outcome.message().ifPresent(m -> data.put("message", m));
        if (!outcome.suppressedBy().isEmpty()) {
            ArrayNode arr = data.putArray("suppressedBy");
            for (RuleTestOutcome.Suppression s : outcome.suppressedBy()) {
                ObjectNode node = arr.addObject();
                node.put("field", s.field());
                node.put("formalErrorCode", s.formalErrorCode());
            }
        }
        return data;
    }

    private static String ruleSummary(ObjectNode data) {
        String r = data.path("rule").asText();
        return switch (data.path("verdict").asText()) {
            case "fired" -> r + " FIRED on this instance (a violation)";
            case "passed" -> r + " passed (evaluated; condition false)";
            case "suppressed" -> r + " was NOT evaluated — a referenced field is formally invalid";
            case "unsupported" -> r + " could NOT be evaluated — it uses a construct the engine doesn't model "
                    + "(e.g. a CustomCondition with no impl)";
            default -> r;
        };
    }

    private static String pointer(FieldPointer p) {
        return PointerKt.formatPointer(p.path(), p.reps());
    }

    // ---- shared emit helpers (match BaseCommand.fail / refuse) ----

    private static int fail(StringBuilder err, String message) {
        err.append(message).append('\n');
        return 2;
    }

    private static int refuse(StringBuilder out, String target, String op, Refusal refusal) {
        ResultEnvelope env = ResultEnvelope.refused(target, op, refusal.summary(), refusal.data(),
                refusal.diagnostics());
        out.append(env.toJsonString());
        return env.exitCode();
    }

    private static ModelResolver resolver(Args a) {
        List<Path> roots = WorkspaceOption.roots(a.workspace(), System.getenv(WorkspaceOption.WORKSPACE_ENV), a.model());
        return ReferenceResolvers.directory(roots.toArray(Path[]::new));
    }

    private static Locale locale(Args a) {
        return Locale.forLanguageTag(a.localeTag().replace('_', '-'));
    }

    // ---- the fixed runtime-eval argument vocabulary the parity tests use ----

    private record Args(String op, String rulePath, String model, List<Path> workspace, String instance,
            String rule, String condition, String field, String group, String code, String severity,
            String name, String localeTag, boolean noComputations) {

        static Args parse(String[] args) {
            Map<String, String> opt = new HashMap<>();
            List<Path> workspace = new ArrayList<>();
            List<String> positionals = new ArrayList<>();
            boolean noComputations = false;
            for (int i = 0; i < args.length; i++) {
                switch (args[i]) {
                    case "-m", "--instance", "--rule", "--condition", "--field", "--group",
                            "--code", "--severity", "--name", "--locale" -> opt.put(args[i], args[++i]);
                    case "-w", "--workspace" -> workspace.add(Path.of(args[++i]));
                    case "--no-computations" -> noComputations = true;
                    default -> positionals.add(args[i]);
                }
            }
            String target = positionals.isEmpty() ? "" : positionals.get(0);
            String verb = positionals.size() < 2 ? "" : positionals.get(1);
            String op = (target + " " + verb).trim();
            String rulePath = positionals.size() > 2 ? positionals.get(2) : null;
            return new Args(op, rulePath, opt.get("-m"), workspace, opt.get("--instance"),
                    opt.get("--rule"), opt.get("--condition"), opt.get("--field"), opt.get("--group"),
                    opt.getOrDefault("--code", "EVAL_DOC"), opt.getOrDefault("--severity", "ERROR"),
                    opt.getOrDefault("--name", "EvalDocCandidate"), opt.getOrDefault("--locale", "en_US"),
                    noComputations);
        }
    }
}
