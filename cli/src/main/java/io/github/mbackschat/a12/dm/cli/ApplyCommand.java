package io.github.mbackschat.a12.dm.cli;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.mgmtp.a12.kernel.md.model.api.services.IDocumentModelReferenceResolver;
import com.mgmtp.a12.model.notification.RankedNotification;

import io.github.mbackschat.a12.dm.core.ModelConsistencyException;
import io.github.mbackschat.a12.dm.edit.EditSession;
import io.github.mbackschat.a12.dm.modelkit.FieldKind;
import io.github.mbackschat.a12.dm.rulekit.dsl.LocalizedText;
import io.github.mbackschat.a12.dm.rulekit.validate.Diagnostic;
import io.github.mbackschat.a12.dm.rulekit.validate.KernelDiagnostics;
import picocli.CommandLine.Command;
import picocli.CommandLine.Mixin;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

/**
 * The {@code apply} session verb (CLI-SPEC §7) — the north-star edit flow: ONE read → ordered surgery on a
 * single {@link EditSession} → ONE terminal kernel gate → ONE write, with <b>atomic rollback</b>. Unlike
 * {@code batch} (stateless per-op {@code Cli.run} dispatch), {@code apply} applies a sequence of op-records
 * {@code {target, op, …args}} to a shared model, tolerating transient-invalid states between ops and gating
 * only at the end. Each op emits a per-op result envelope ({@code staged} until the terminal gate commits the
 * whole sequence); a batch summary {@code {ops, committed, failedAt, written, results[]}} wraps them. A
 * mid-sequence op failure rolls back (nothing written). This first increment handles the modelkit structure
 * ops (field/group add/remove); rule/computation ops, op-arg validation, and read ops are follow-on increments.
 */
@Command(name = "apply", mixinStandardHelpOptions = true,
        description = "Apply an ordered sequence of op-records to one model atomically (one gate, one write, rollback).")
final class ApplyCommand extends BaseCommand {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** The op-record keys each {@code target op} accepts — the param model op-arg validation checks against. */
    private static final Map<String, Set<String>> KNOWN_KEYS = Map.ofEntries(
            Map.entry("field add", Set.of("group", "name", "kind", "typedef")),
            Map.entry("field remove", Set.of("fieldPathInModel")),
            Map.entry("group add", Set.of("parent", "name", "repeatable")),
            Map.entry("group remove", Set.of("groupPathInModel")),
            Map.entry("rule add", Set.of("field", "condition", "code", "severity", "name", "messages")),
            Map.entry("rule modify", Set.of("rule", "condition")),
            Map.entry("computation add", Set.of("computedField", "alternatives", "commonPrecondition", "name", "messages")),
            Map.entry("computation modify", Set.of("computation", "computedField", "alternatives", "commonPrecondition", "name", "messages")),
            Map.entry("typedef add", Set.of("id", "kind")),
            Map.entry("typedef remove", Set.of("typeDefId")),
            Map.entry("config modify", Set.of("decimal-separator", "time-zone", "condition-language")));

    @Parameters(index = "0", paramLabel = "<ops.json>",
            description = "a JSON array of op-records: [{\"target\":\"field\",\"op\":\"add\",\"group\":…,\"name\":…,\"kind\":…}, …]")
    String opsFile;

    @Option(names = {"--output", "-o"}, paramLabel = "<file>",
            description = "write the committed model here instead of in place")
    String outputPath;

    @Option(names = "--dry-run", description = "run the whole sequence + the terminal gate, but write NOTHING")
    boolean dryRun;

    @Mixin
    IncludeDirOption includes = new IncludeDirOption();

    @Override
    public Integer call() {
        String model = model();
        if (model == null) {
            return fail("pass -m <model.json> (before or after the verb)");
        }
        JsonNode ops;
        String modelJson;
        try {
            ops = MAPPER.readTree(Files.readString(Path.of(opsFile)));
            modelJson = Files.readString(Path.of(model));
        } catch (IOException e) {
            return fail("cannot read input: " + e.getMessage());
        }
        if (!ops.isArray()) {
            return fail("the ops file must be a JSON array of op-records");
        }
        IDocumentModelReferenceResolver resolver = includes.resolverFor(model);
        EditSession session = EditSession.read(modelJson, resolver);

        ArrayNode results = MAPPER.createArrayNode();
        Integer failedAt = null;
        int i = 0;
        for (JsonNode op : ops) {
            List<Diagnostic> argProblems = validateArgs(op);
            if (!argProblems.isEmpty()) {
                results.add(argRejection(op, i, argProblems).toJson());
                failedAt = i;
                break; // a typo'd/unknown arg self-corrects via the diagnostic — roll back, write nothing
            }
            try {
                ObjectNode changed = dispatch(session, op);
                String label = op.path("target").asText() + " " + op.path("op").asText();
                results.add(new ResultEnvelope(op.path("target").asText(), op.path("op").asText(),
                        ResultEnvelope.Outcome.STAGED, "staged " + label, changed, null, List.of(), null, false, null)
                        .toJson());
            } catch (RuntimeException e) {
                results.add(opFailure(op, i, e).toJson());
                failedAt = i;
                break; // rollback — stop the sequence, write nothing
            }
            i++;
        }

        boolean committed = false;
        boolean written = false;
        String output = null;
        if (failedAt == null) {
            try {
                String resultJson = session.validate().toJson();   // the ONE terminal kernel gate
                committed = true;
                if (!dryRun) {
                    output = outputPath != null ? outputPath : model;
                    Files.writeString(Path.of(output), resultJson);
                    written = true;
                }
            } catch (ModelConsistencyException e) {
                results.add(gateRejection(e).toJson());   // terminal gate rejected the committed sequence → rollback
            } catch (IOException e) {
                return fail("cannot write output: " + e.getMessage());
            }
        }

        ObjectNode summary = MAPPER.createObjectNode();
        summary.put("verb", "apply");
        summary.put("ops", ops.size());
        summary.put("committed", committed);
        if (failedAt != null) {
            summary.put("failedAt", failedAt);
        } else {
            summary.putNull("failedAt");
        }
        summary.put("written", written);
        if (output != null) {
            summary.put("output", output);
        }
        summary.set("results", results);
        out().print(summary.toPrettyString() + "\n");
        return committed ? 0 : 1;
    }

    /** Apply one op-record to the shared session and return its {@code changed} delta. Throws on a bad op (→ rollback). */
    private ObjectNode dispatch(EditSession session, JsonNode op) {
        String target = op.path("target").asText();
        String name = op.path("op").asText();
        ObjectNode changed = MAPPER.createObjectNode();
        switch (target + " " + name) {
            case "field add" -> {
                String group = req(op, "group");
                String field = req(op, "name");
                session.addField(group, field, FieldKind.valueOf(req(op, "kind")));
                changed.put("added", group + "/" + field);
                changed.put("kind", req(op, "kind"));
            }
            case "field remove" -> {
                String path = req(op, "fieldPathInModel");
                session.removeField(path);
                changed.put("removed", path);
            }
            case "group add" -> {
                String parent = req(op, "parent");
                String groupName = req(op, "name");
                if (op.hasNonNull("repeatable")) {
                    session.addRepeatableGroup(parent, groupName, op.get("repeatable").asInt());
                    changed.put("repeatable", true);
                } else {
                    session.addGroup(parent, groupName);
                }
                changed.put("added", parent + "/" + groupName);
            }
            case "group remove" -> {
                String path = req(op, "groupPathInModel");
                session.removeGroup(path);
                changed.put("removed", path);
            }
            case "rule add" -> {
                String field = req(op, "field");
                int s = field.lastIndexOf('/');
                if (s <= 0) {
                    throw new IllegalArgumentException("rule add 'field' must be an absolute path (e.g. /Group/Field)");
                }
                String group = field.substring(0, s);
                String errorEntityRelPath = "../" + field.substring(group.length() + 1);
                String code = req(op, "code");
                String ruleName = op.hasNonNull("name") ? op.get("name").asText() : code;
                String severity = op.hasNonNull("severity") ? op.get("severity").asText() : "ERROR";
                List<LocalizedText> messages = messages(op);
                if (messages.isEmpty()) {
                    throw new IllegalArgumentException("rule add needs messages [{locale,text}, …]");
                }
                session.addRule(group, ruleName, errorEntityRelPath, code, req(op, "condition"), severity, messages);
                changed.put("rule", group + "/" + ruleName);
            }
            case "rule modify" -> {
                String rule = req(op, "rule");
                session.modifyRule(rule, req(op, "condition"));
                changed.put("modified", rule);
            }
            case "computation add" -> {
                ComputationSpec.Parsed parsed = ComputationSpec.parse(op);
                if (parsed.error() != null) {
                    throw new IllegalArgumentException(parsed.error());
                }
                session.addComputation(parsed.draft(), parsed.messages());
                changed.put("computation", parsed.fullName());
            }
            case "computation modify" -> {
                String comp = req(op, "computation");
                ComputationSpec.Parsed parsed = ComputationSpec.parse(op);
                if (parsed.error() != null) {
                    throw new IllegalArgumentException(parsed.error());
                }
                session.modifyComputation(comp, parsed.draft(), parsed.messages());
                changed.put("modified", comp);
            }
            case "typedef add" -> {
                String id = req(op, "id");
                session.addTypeDefinition(id, FieldKind.valueOf(req(op, "kind")));
                changed.put("added", id);
            }
            case "typedef remove" -> {
                String id = req(op, "typeDefId");
                session.removeTypeDefinition(id);
                changed.put("removed", id);
            }
            case "config modify" -> {
                if (op.hasNonNull("decimal-separator")) {
                    session.setDecimalSeparator(op.get("decimal-separator").asText());
                    changed.put("decimalSeparator", op.get("decimal-separator").asText());
                }
                if (op.hasNonNull("time-zone")) {
                    session.setTimeZone(op.get("time-zone").asText());
                    changed.put("timeZone", op.get("time-zone").asText());
                }
                if (op.hasNonNull("condition-language")) {
                    session.setConditionLanguage(op.get("condition-language").asText());
                    changed.put("conditionLanguage", op.get("condition-language").asText());
                }
                if (changed.isEmpty()) {
                    throw new IllegalArgumentException("config modify needs one of "
                            + "decimal-separator, time-zone, condition-language");
                }
            }
            default -> throw new IllegalArgumentException("apply does not yet support op '" + target + " " + name
                    + "' (built: field/group/typedef add·remove, config modify, rule/computation add·modify)");
        }
        return changed;
    }

    private static String req(JsonNode op, String key) {
        if (!op.hasNonNull(key)) {
            throw new IllegalArgumentException("op missing required arg '" + key + "'");
        }
        return op.get(key).asText();
    }

    /**
     * Op-arg validation (CLI-SPEC §7) — check the op-record's keys against the verb's param model and emit a
     * corrective diagnostic (the most common batch mistake self-corrects instead of failing opaquely): an
     * {@code op} not valid for its {@code target} → {@code RK_UNKNOWN_OP}; a typo'd/unknown key →
     * {@code RK_UNKNOWN_ARG} with a {@code where.arg} and a did-you-mean {@code fix}.
     */
    private static List<Diagnostic> validateArgs(JsonNode op) {
        List<Diagnostic> out = new ArrayList<>();
        // cross-type corrective: a `batch` op-record ({verb,args}) was fed to `apply`
        if (op.has("verb") || op.has("argv")) {
            out.add(Diagnostic.precheck(Optional.of("RK_WRONG_BATCH_KIND"),
                    "this op has verb/argv — it is a `batch` op-record, not an `apply` op",
                    new Diagnostic.Where((String) null, Optional.empty()),
                    Optional.of("use the `batch` verb for {verb,args} ops; `apply` ops are {target,op,…args}"),
                    Optional.empty()));
            return out;
        }
        String label = op.path("target").asText() + " " + op.path("op").asText();
        Set<String> valid = KNOWN_KEYS.get(label);
        if (valid == null) {
            out.add(Diagnostic.precheck(Optional.of("RK_UNKNOWN_OP"),
                    "apply does not support op '" + label + "'",
                    new Diagnostic.Where((String) null, Optional.empty()),
                    Optional.of("supported ops: " + String.join(", ", new TreeSet<>(KNOWN_KEYS.keySet()))),
                    Optional.empty()));
            return out;
        }
        for (Iterator<String> it = op.fieldNames(); it.hasNext();) {
            String f = it.next();
            if (f.equals("target") || f.equals("op") || valid.contains(f)) {
                continue;
            }
            String near = nearest(f, valid);
            out.add(Diagnostic.precheck(Optional.of("RK_UNKNOWN_ARG"),
                    "unknown arg '" + f + "' for op '" + label + "'",
                    Diagnostic.Where.arg(null, f),
                    Optional.of(near != null ? "did you mean '" + near + "'?"
                            : "valid args: " + String.join(", ", new TreeSet<>(valid))),
                    Optional.empty()));
        }
        return out;
    }

    /** A failing op-arg envelope (the offending op rolled the sequence back). */
    private ResultEnvelope argRejection(JsonNode op, int index, List<Diagnostic> diagnostics) {
        return new ResultEnvelope(op.path("target").asText(), op.path("op").asText(),
                ResultEnvelope.Outcome.REJECTED, "op " + index + " has an invalid arg — sequence rolled back",
                null, null, diagnostics, null, false, null);
    }

    /** The closest valid key to {@code key} by edit distance, if reasonably near (else null — no false suggestion). */
    private static String nearest(String key, Set<String> valid) {
        String best = null;
        int bestD = Integer.MAX_VALUE;
        for (String v : valid) {
            int d = levenshtein(key, v);
            if (d < bestD) {
                bestD = d;
                best = v;
            }
        }
        return (best != null && bestD <= Math.max(2, best.length() / 2)) ? best : null;
    }

    private static int levenshtein(String a, String b) {
        int[] prev = new int[b.length() + 1];
        int[] cur = new int[b.length() + 1];
        for (int j = 0; j <= b.length(); j++) {
            prev[j] = j;
        }
        for (int i = 1; i <= a.length(); i++) {
            cur[0] = i;
            for (int j = 1; j <= b.length(); j++) {
                int cost = a.charAt(i - 1) == b.charAt(j - 1) ? 0 : 1;
                cur[j] = Math.min(Math.min(cur[j - 1] + 1, prev[j] + 1), prev[j - 1] + cost);
            }
            int[] t = prev;
            prev = cur;
            cur = t;
        }
        return prev[b.length()];
    }

    private static List<LocalizedText> messages(JsonNode op) {
        List<LocalizedText> messages = new ArrayList<>();
        for (JsonNode m : op.path("messages")) {
            if (m.hasNonNull("locale") && m.hasNonNull("text")) {
                messages.add(new LocalizedText(m.get("locale").asText(), m.get("text").asText()));
            }
        }
        return messages;
    }

    /** A failing op's per-op envelope — corrective: names the op index, the cause, and the offending locus. */
    private ResultEnvelope opFailure(JsonNode op, int index, RuntimeException e) {
        String target = op.path("target").asText();
        String name = op.path("op").asText();
        Diagnostic d = Diagnostic.precheck(Optional.of("RK_APPLY_OP_FAILED"),
                "op " + index + " (" + target + " " + name + ") failed: " + e.getMessage(),
                new Diagnostic.Where((String) null, Optional.empty()),
                Optional.of("fix this op and re-run; the whole sequence rolled back (nothing was written)"),
                Optional.empty());
        return new ResultEnvelope(target, name, ResultEnvelope.Outcome.REJECTED,
                "op " + index + " failed — sequence rolled back", null, null, List.of(d), null, false, null);
    }

    /** The terminal gate rejected the committed sequence — surface the kernel's diagnostics; nothing written. */
    private ResultEnvelope gateRejection(ModelConsistencyException e) {
        List<Diagnostic> diagnostics = new ArrayList<>();
        for (RankedNotification n : e.notifications()) {
            diagnostics.add(KernelDiagnostics.enriched(n, null));
        }
        if (diagnostics.isEmpty()) {
            diagnostics.add(Diagnostic.precheck(Optional.empty(), e.getMessage(),
                    new Diagnostic.Where((String) null, Optional.empty()), Optional.empty(), Optional.empty()));
        }
        return new ResultEnvelope("doc", "validate", ResultEnvelope.Outcome.REJECTED,
                "the terminal kernel gate rejected the sequence — rolled back", null, null, diagnostics, false, false, null);
    }
}
