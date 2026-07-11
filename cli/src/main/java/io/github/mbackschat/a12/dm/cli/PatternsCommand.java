package io.github.mbackschat.a12.dm.cli;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.mbackschat.a12.dm.core.ModelResolver;

import io.github.mbackschat.a12.dm.cli.field.FieldSpec;
import io.github.mbackschat.a12.dm.core.ModelConsistencyException;
import io.github.mbackschat.a12.dm.modelkit.ModelDocument;
import io.github.mbackschat.a12.dm.rulekit.catalog.PatternCatalog;
import io.github.mbackschat.a12.dm.rulekit.catalog.PatternEntry;
import io.github.mbackschat.a12.dm.rulekit.dsl.BoundModel;
import io.github.mbackschat.a12.dm.rulekit.dsl.Computation;
import io.github.mbackschat.a12.dm.rulekit.dsl.LocalizedText;
import io.github.mbackschat.a12.dm.rulekit.validate.DraftComputation;
import io.github.mbackschat.a12.dm.rulekit.validate.DraftRule;
import io.github.mbackschat.a12.dm.rulekit.validate.KernelDiagnostics;
import io.github.mbackschat.a12.dm.rulekit.validate.RuleValidator;
import io.github.mbackschat.a12.dm.rulekit.validate.ValidationResult;
import io.github.mbackschat.a12.dm.rulekit.write.ModelWriter;
import picocli.CommandLine.Command;
import picocli.CommandLine.Mixin;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

/**
 * {@code dmtool patterns} — the validation-idiom catalog (FEATURE-GAPS G2), the rule-level peer of {@code
 * operators}. A BA's recurring tasks (date-order, required-when, mutually-exclusive, at-least-one-of,
 * sum-of-line-items) re-risk the polarity + error-field traps each time; a pattern bakes the <b>correct
 * violation polarity</b> and a <b>referenced error field</b> in, so scaffolding one is a parameter fill.
 *
 * <p>Three modes, self-describing (emits its own JSON, like {@code operators}):
 * <ul>
 *   <li>{@code patterns} — list every idiom (id, summary, params);</li>
 *   <li>{@code patterns <id>} — one idiom in full (params, polarity note, skeleton, example);</li>
 *   <li>{@code patterns <id> --arg k=v …} (needs {@code -m}) — instantiate it into a <b>rule-spec</b> (the
 *       shape {@code rule add} consumes) and <b>auto-check</b> it against the kernel; {@code --apply} persists
 *       the rule directly (kernel-validated; {@code --dry-run}/{@code -o} as on the edit verbs).</li>
 * </ul>
 */
@Command(name = "patterns", mixinStandardHelpOptions = true,
        description = "List validation idioms and scaffold a kernel-valid rule from one "
                + "(correct polarity + a referenced error field baked in).")
final class PatternsCommand extends BaseCommand {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final PatternCatalog CATALOG = PatternCatalog.load();

    @Parameters(index = "0", arity = "0..1", paramLabel = "<patternId>",
            description = "the idiom id (e.g. date-order); omit to list every idiom")
    String id;

    @Option(names = "--arg", paramLabel = "<name=value>",
            description = "a pattern parameter (repeatable), e.g. --arg earlier=/Order/OrderDate; needs -m <model>")
    Map<String, String> args = new LinkedHashMap<>();

    @Option(names = "--code", paramLabel = "<errorCode>",
            description = "the scaffolded rule's error code (default: the pattern's suggested code)")
    String code;

    @Option(names = "--message", paramLabel = "<locale=text>",
            description = "the error message per locale (repeatable); a placeholder per declared locale is used if omitted")
    Map<String, String> messages = new LinkedHashMap<>();

    @Option(names = "--name", paramLabel = "<name>",
            description = "the scaffolded rule's name (default: derived from the pattern id)")
    String name;

    @Option(names = "--apply", description = "persist the scaffolded rule into the model (kernel-validated)")
    boolean apply;

    @Option(names = "--dry-run", description = "with --apply: preview only — write NOTHING")
    boolean dryRun;

    @Option(names = {"--output", "-o"}, paramLabel = "<file>",
            description = "with --apply: write the result here instead of in place")
    String outputPath;

    @Mixin
    WorkspaceOption workspace = new WorkspaceOption();

    @Override
    public Integer call() {
        if (id == null) {
            return emit(list());
        }
        PatternEntry pattern = CATALOG.byId(id).orElse(null);
        if (pattern == null) {
            return fail("no pattern '" + id + "' — known patterns: "
                    + CATALOG.entries().stream().map(PatternEntry::id).toList());
        }
        if (args.isEmpty()) {
            return emit(detail(pattern));
        }
        return scaffold(pattern);
    }

    /** List mode — every idiom, compact. */
    private ObjectNode list() {
        ObjectNode root = MAPPER.createObjectNode();
        ArrayNode arr = root.putArray("patterns");
        for (PatternEntry p : CATALOG.entries()) {
            ObjectNode n = arr.addObject();
            n.put("id", p.id());
            n.put("kind", p.kind().name().toLowerCase(java.util.Locale.ROOT));
            n.put("summary", p.summary());
            ArrayNode params = n.putArray("params");
            p.params().forEach(param -> params.add(param.name()));
        }
        root.put("count", CATALOG.size());
        return root;
    }

    /** Detail mode — one idiom in full. */
    private ObjectNode detail(PatternEntry p) {
        ObjectNode n = MAPPER.createObjectNode();
        n.put("id", p.id());
        n.put("kind", p.kind().name().toLowerCase(java.util.Locale.ROOT));
        n.put("summary", p.summary());
        n.put("polarityNote", p.polarityNote());
        n.put("skeleton", p.skeleton());
        ArrayNode params = n.putArray("params");
        p.params().forEach(param -> {
            ObjectNode pn = params.addObject();
            pn.put("name", param.name());
            pn.put("kind", param.kind().name().toLowerCase(java.util.Locale.ROOT));
            pn.put("doc", param.doc());
        });
        ObjectNode example = n.putObject("example");
        p.example().forEach(example::put);
        return n;
    }

    /** Scaffold mode — instantiate the idiom, auto-check, and (with --apply) persist. Dispatches on the artifact kind. */
    private Integer scaffold(PatternEntry pattern) {
        String model = model();
        if (model == null) {
            return fail("pass -m <model.json> to scaffold from a pattern");
        }
        String modelJson;
        try {
            modelJson = Files.readString(Path.of(model));
        } catch (IOException e) {
            return fail("cannot read model: " + e.getMessage());
        }
        ModelResolver resolver = workspace.resolverFor(model);

        PatternEntry.Instantiation inst;
        BoundModel bound;
        try {
            bound = BoundModel.ofJson(modelJson, resolver);
            inst = pattern.instantiate(bound, args);
        } catch (IllegalArgumentException bad) {
            return fail(bad.getMessage());          // missing --arg, unresolved path, bad enum value, …
        } catch (RuntimeException e) {
            return fail("cannot scaffold from '" + pattern.id() + "': " + e.getMessage());
        }

        return switch (inst) {
            case PatternEntry.RuleInstantiation r -> scaffoldRule(pattern, r, bound, modelJson, resolver, model);
            case PatternEntry.ComputationInstantiation c -> scaffoldComputation(pattern, c, bound, modelJson, resolver, model);
            case PatternEntry.FieldInstantiation f -> scaffoldField(pattern, f, modelJson, resolver, model);
        };
    }

    /** Scaffold a RULE pattern — build the rule-spec, auto-check, and (with --apply) persist via {@code addRule}. */
    private Integer scaffoldRule(PatternEntry pattern, PatternEntry.RuleInstantiation inst,
            BoundModel bound, String modelJson, ModelResolver resolver, String model) {
        String errorField = inst.errorField();
        int slash = errorField.lastIndexOf('/');
        String group = errorField.substring(0, slash);
        String rel = "../" + errorField.substring(slash + 1);
        String ruleName = name != null ? name : derivedName(pattern.id());
        String errorCode = code != null ? code : inst.suggestedCode();
        List<LocalizedText> msgs = messages(bound, pattern.summary());

        DraftRule draft = new DraftRule(group, ruleName, rel, inst.condition(), errorCode, "ERROR");
        ValidationResult check = new RuleValidator().check(modelJson, draft, resolver);

        ObjectNode root = MAPPER.createObjectNode();
        root.put("pattern", pattern.id());
        ObjectNode spec = root.putObject("spec");
        spec.put("field", errorField);
        spec.put("name", ruleName);
        spec.put("condition", inst.condition());
        spec.put("code", errorCode);
        ArrayNode specMsgs = spec.putArray("messages");
        msgs.forEach(m -> specMsgs.addObject().put("locale", m.locale()).put("text", m.text()));
        root.put("valid", check.ok());
        root.set("diagnostics", diagnostics(check));

        if (!apply) {
            return emit(root);                       // review the spec (+ kernel verdict), then `rule add`
        }
        if (!check.ok()) {
            root.put("applied", false);
            root.put("reason", "the scaffolded rule is rejected by the kernel — fix the diagnostics before --apply");
            return emit(root, 1);
        }
        String persisted = ModelWriter.addRule(modelJson, group, ruleName, rel, errorCode,
                inst.condition(), "ERROR", msgs);
        if (dryRun) {
            root.put("applied", false);
            root.put("previewed", true);
            return emit(root);
        }
        String dest = outputPath != null ? outputPath : model;
        try {
            Files.writeString(Path.of(dest), persisted);
        } catch (IOException e) {
            return fail("cannot write output: " + e.getMessage());
        }
        root.put("applied", true);
        root.put("written", dest);
        root.put("rule", group + "/" + ruleName);
        return emit(root);
    }

    /**
     * Scaffold a COMPUTATION pattern — the idiom built a typed {@link Computation} (correct by construction:
     * mutually-exclusive preconditions, kind-matched operations). Apply the caller's name/messages over the
     * idiom's placeholders, auto-check, and (with --apply) persist via {@code addComputation}. ({@code --code}
     * is a rule concept; a fired computation's code is its own name, so it is not used here.)
     */
    private Integer scaffoldComputation(PatternEntry pattern, PatternEntry.ComputationInstantiation inst,
            BoundModel bound, String modelJson, ModelResolver resolver, String model) {
        Computation computation = inst.computation();
        DraftComputation built = computation.draft();
        String compName = name != null ? name : built.name();
        // re-stamp the caller's chosen name over the idiom's placeholder (the structural draft is unchanged)
        DraftComputation draft = new DraftComputation(built.groupPath(), compName,
                built.computedFieldRelPath(), built.commonPrecondition(), built.alternatives());
        List<LocalizedText> msgs = messages(bound, pattern.summary());

        ValidationResult check = new RuleValidator().check(modelJson, draft, resolver);

        ObjectNode root = MAPPER.createObjectNode();
        root.put("pattern", pattern.id());
        ObjectNode spec = root.putObject("spec");
        spec.put("computedField", computation.computedField().path());
        spec.put("name", compName);
        if (draft.commonPrecondition() != null) {
            spec.put("commonPrecondition", draft.commonPrecondition());
        }
        ArrayNode alts = spec.putArray("alternatives");
        for (DraftComputation.Alternative a : draft.alternatives()) {
            ObjectNode an = alts.addObject();
            if (a.precondition() != null) {
                an.put("precondition", a.precondition());
            }
            an.put("operation", a.operation());
        }
        ArrayNode specMsgs = spec.putArray("messages");
        msgs.forEach(m -> specMsgs.addObject().put("locale", m.locale()).put("text", m.text()));
        root.put("valid", check.ok());
        root.set("diagnostics", diagnostics(check));

        if (!apply) {
            return emit(root);                       // review the computation-spec (+ kernel verdict), then `computation add`
        }
        if (!check.ok()) {
            root.put("applied", false);
            root.put("reason", "the scaffolded computation is rejected by the kernel — fix the diagnostics before --apply");
            return emit(root, 1);
        }
        String persisted = ModelWriter.addComputation(modelJson, draft, msgs);
        if (dryRun) {
            root.put("applied", false);
            root.put("previewed", true);
            return emit(root);
        }
        String dest = outputPath != null ? outputPath : model;
        try {
            Files.writeString(Path.of(dest), persisted);
        } catch (IOException e) {
            return fail("cannot write output: " + e.getMessage());
        }
        root.put("applied", true);
        root.put("written", dest);
        root.put("computation", draft.groupPath() + "/" + compName);
        return emit(root);
    }

    /**
     * Scaffold a FIELD pattern — a constrained field (the field-level alternative to a rule, the twin R2's
     * {@code seeAlso} bridge names). The rulekit idiom returns a modelkit-free spec (kind + per-kind config);
     * here we assemble it into the {@code field add} shape, build the field via {@link FieldSpec}, add it, and
     * auto-check via the kernel re-bind ({@code ModelDocument.validate}); {@code --apply} persists. Name/code/
     * message overrides are rule concepts and do not apply — a field carries its own config.
     */
    private Integer scaffoldField(PatternEntry pattern, PatternEntry.FieldInstantiation inst,
            String modelJson, ModelResolver resolver, String model) {
        ObjectNode fieldSpec = MAPPER.createObjectNode();
        fieldSpec.put("group", inst.group());
        fieldSpec.put("name", inst.name());
        fieldSpec.put("kind", inst.kind());
        fieldSpec.set(inst.kind().toLowerCase(java.util.Locale.ROOT), MAPPER.valueToTree(inst.config()));
        String target = inst.group() + "/" + inst.name();

        ModelDocument doc = ModelDocument.read(modelJson);
        try {
            FieldSpec.Parsed parsed = FieldSpec.parse(fieldSpec);
            doc.addField(parsed.group(), parsed.field());
        } catch (IllegalArgumentException bad) {
            return fail(bad.getMessage());       // no such group, duplicate name, malformed config, …
        }

        boolean valid;
        com.fasterxml.jackson.databind.JsonNode diagnostics;
        String resultJson = null;
        try {
            resultJson = doc.validate(resolver).toJson();
            valid = true;
            diagnostics = MAPPER.createArrayNode();
        } catch (ModelConsistencyException e) {
            valid = false;
            diagnostics = MAPPER.valueToTree(e.notifications().stream()
                    .map(n -> KernelDiagnostics.enriched(n, null)).toList());
        }

        ObjectNode root = MAPPER.createObjectNode();
        root.put("pattern", pattern.id());
        root.set("spec", fieldSpec);
        root.put("valid", valid);
        root.set("diagnostics", diagnostics);

        if (!apply) {
            return emit(root);                   // review the field-spec (+ kernel verdict), then `field add`
        }
        if (!valid) {
            root.put("applied", false);
            root.put("reason", "the scaffolded field is rejected by the kernel — fix the diagnostics before --apply");
            return emit(root, 1);
        }
        if (dryRun) {
            root.put("applied", false);
            root.put("previewed", true);
            return emit(root);
        }
        String dest = outputPath != null ? outputPath : model;
        try {
            Files.writeString(Path.of(dest), resultJson);
        } catch (IOException e) {
            return fail("cannot write output: " + e.getMessage());
        }
        root.put("applied", true);
        root.put("written", dest);
        root.put("field", target);
        return emit(root);
    }

    /** The per-locale messages — the caller's {@code --message locale=text}, with a placeholder for any gap. */
    private List<LocalizedText> messages(BoundModel bound, String placeholder) {
        List<String> locales = bound.locales();
        if (locales.isEmpty()) {
            locales = List.of("en_US");
        }
        List<LocalizedText> out = new ArrayList<>();
        for (String locale : locales) {
            out.add(new LocalizedText(locale, messages.getOrDefault(locale, placeholder)));
        }
        return out;
    }

    private com.fasterxml.jackson.databind.JsonNode diagnostics(ValidationResult check) {
        try {
            return MAPPER.readTree(check.toJson()).path("diagnostics");
        } catch (JsonProcessingException e) {
            return MAPPER.createArrayNode();
        }
    }

    /** "date-order" → "DateOrderRule" — a readable default rule name. */
    private static String derivedName(String patternId) {
        StringBuilder b = new StringBuilder();
        for (String part : patternId.split("-")) {
            if (!part.isEmpty()) {
                b.append(Character.toUpperCase(part.charAt(0))).append(part.substring(1));
            }
        }
        return b.append("Rule").toString();
    }

    private Integer emit(ObjectNode node) {
        return emit(node, 0);
    }

    private Integer emit(ObjectNode node, int exitCode) {
        try {
            out().print(MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(node) + "\n");
            return exitCode;
        } catch (JsonProcessingException e) {
            return fail("cannot serialize patterns output: " + e.getMessage());
        }
    }
}
