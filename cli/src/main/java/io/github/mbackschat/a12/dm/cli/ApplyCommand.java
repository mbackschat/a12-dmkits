package io.github.mbackschat.a12.dm.cli;

import io.github.mbackschat.a12.dm.cli.computation.ComputationSpec;
import io.github.mbackschat.a12.dm.cli.config.ConfigModification;
import io.github.mbackschat.a12.dm.cli.field.FieldSpec;
import io.github.mbackschat.a12.dm.cli.group.GroupModification;
import io.github.mbackschat.a12.dm.cli.group.SortFieldArg;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.mgmtp.a12.kernel.md.model.api.services.IDocumentModelReferenceResolver;
import com.mgmtp.a12.model.notification.RankedNotification;

import io.github.mbackschat.a12.dm.adapter.KernelAdapter;
import io.github.mbackschat.a12.dm.core.ModelConsistencyException;
import io.github.mbackschat.a12.dm.edit.EditSession;
import io.github.mbackschat.a12.dm.modelkit.FieldKind;
import io.github.mbackschat.a12.dm.modelkit.FieldModification;
import io.github.mbackschat.a12.dm.modelkit.ModelDocument;
import io.github.mbackschat.a12.dm.modelkit.MountOptions;
import io.github.mbackschat.a12.dm.rulekit.dsl.Comments;
import io.github.mbackschat.a12.dm.rulekit.dsl.LocalizedText;
import io.github.mbackschat.a12.dm.rulekit.dsl.Severity;
import io.github.mbackschat.a12.dm.rulekit.write.ModelWriter;
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
 * mid-sequence op failure rolls back (nothing written). The op surface (the {@link Op} enum) covers the
 * <b>Local</b> ops — field/group/typedef/include add·remove, rule/computation add·modify, config modify, and
 * the structure reads (a {@code group remove} takes the same {@code cascade} safe-delete gate as the
 * standalone verb, and {@code rule remove} deletes a rule) — plus the reference-rewriting <b>Refactor</b>
 * ops field/group rename·move, rule rename,
 * and typedef rename: each runs its §6 safety gate at its turn against the live (transiently-edited) model, so
 * a refused refactor rolls the whole sequence back. Op-arg validation ({@code RK_UNKNOWN_ARG}/{@code RK_UNKNOWN_OP}
 * + did-you-mean) guards every op-record. (extract/inline stay standalone verbs — they produce a second artifact.)
 */
@Command(name = "apply", mixinStandardHelpOptions = true,
        description = "Apply an ordered sequence of op-records to one model atomically (one gate, one write, rollback).")
final class ApplyCommand extends BaseCommand {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /**
     * The apply op-record kinds — the single source of truth for each op's {@code (target, op)} selector and
     * its valid arg keys. The dispatch switches on this enum (compiler-checked exhaustive), and op-arg
     * validation reads {@link #keys} from it — so a new op-kind can't be added without a dispatch case, and
     * the valid-key set can never drift from the dispatch (the brittleness of parallel string lists is gone).
     */
    private enum Op {
        FIELD_ADD("field", "add", FieldSpec.ADD_KEYS),
        FIELD_MODIFY("field", "modify", FieldSpec.MODIFY_KEYS),
        FIELD_REMOVE("field", "remove", Set.of("fieldPathInModel")),
        GROUP_ADD("group", "add", Set.of("parent", "name", "repeatable")),
        GROUP_MODIFY("group", "modify", withLocator("groupPathInModel", GroupModification.DIRECTIVE_KEYS)),
        GROUP_REMOVE("group", "remove", Set.of("groupPathInModel", "cascade")),
        RULE_ADD("rule", "add", Set.of("field", "group", "condition", "comment", "code", "severity", "name", "messages",
                "allowDifferingDecimals", "annotation", "internal-description", "external-description")),
        RULE_MODIFY("rule", "modify", Set.of("rulePathInModel", "condition", "comment", "severity", "messages",
                "annotation", "remove-annotation", "internal-description", "external-description",
                "remove-internal-description", "remove-external-description")),
        RULE_REMOVE("rule", "remove", Set.of("rulePathInModel")),
        COMPUTATION_ADD("computation", "add", Set.of("computedField", "alternatives", "commonPrecondition", "comment", "name", "messages")),
        COMPUTATION_MODIFY("computation", "modify", Set.of("computationPathInModel", "computedField", "alternatives", "commonPrecondition", "comment", "name", "messages")),
        TYPEDEF_ADD("typedef", "add", Set.of("id", "kind", "string", "number", "enum", "custom",
                "date", "time", "dateTime", "dateRange", "dateFragment")),
        TYPEDEF_MODIFY("typedef", "modify", Set.of("id", "kind", "string", "number", "enum", "custom",
                "date", "time", "dateTime", "dateRange", "dateFragment")),
        TYPEDEF_REMOVE("typedef", "remove", Set.of("typeDefId")),
        INCLUDE_ADD("include", "add", Set.of("alias", "reference", "parent", "name",
                "repeatability", "exclude-rules", "exclude-computations")),
        INCLUDE_SET_REFERENCE("include", "set-reference", Set.of("alias", "reference")),
        INCLUDE_RENAME("include", "rename", Set.of("alias", "to")),
        INCLUDE_REMOVE("include", "remove", Set.of("alias")),
        CONFIG_MODIFY("config", "modify", ConfigModification.KEYS),
        FIELD_RENAME("field", "rename", Set.of("fieldPathInModel", "to")),
        FIELD_MOVE("field", "move", Set.of("fieldPathInModel", "to")),
        GROUP_RENAME("group", "rename", Set.of("groupPathInModel", "to")),
        GROUP_MOVE("group", "move", Set.of("groupPathInModel", "to")),
        RULE_RENAME("rule", "rename", Set.of("rulePathInModel", "to")),
        TYPEDEF_RENAME("typedef", "rename", Set.of("typeDefId", "to")),
        FIELD_READ("field", "read", Set.of("fieldPathInModel")),
        GROUP_READ("group", "read", Set.of("groupPathInModel")),
        TYPEDEF_READ("typedef", "read", Set.of()),
        INCLUDE_READ("include", "read", Set.of()),
        CONFIG_READ("config", "read", Set.of());

        final String target;
        final String op;
        final Set<String> keys;

        Op(String target, String op, Set<String> keys) {
            this.target = target;
            this.op = op;
            this.keys = keys;
        }

        String label() {
            return target + " " + op;
        }

        static Op of(JsonNode opRecord) {
            String target = opRecord.path("target").asText();
            String op = opRecord.path("op").asText();
            for (Op o : values()) {
                if (o.target.equals(target) && o.op.equals(op)) {
                    return o;
                }
            }
            return null;
        }

        /** The op's valid keys: a locator plus a modification value object's own directive keys (T4 single-source). */
        private static Set<String> withLocator(String locator, Set<String> directiveKeys) {
            Set<String> keys = new java.util.HashSet<>(directiveKeys);
            keys.add(locator);
            return Set.copyOf(keys);
        }
    }

    @Parameters(index = "0", paramLabel = "<ops.json>",
            description = "a JSON array of op-records [{\"target\",\"op\",…args}, …] — the per-op fields are that "
                    + "verb's params (copy from `<target> <op> --help`). See `dmtool schema apply` for the frame "
                    + "+ worked examples (incl. rule/group ops, not just field add).")
    String opsFile;

    @Option(names = {"--output", "-o"}, paramLabel = "<file>",
            description = "write the committed model here instead of in place")
    String outputPath;

    @Option(names = "--dry-run", description = "run the whole sequence + the terminal gate, but write NOTHING")
    boolean dryRun;

    @Mixin
    WorkspaceOption workspace = new WorkspaceOption();

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
        IDocumentModelReferenceResolver resolver = workspace.resolverFor(model);
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
                Applied applied = dispatch(session, op, resolver);
                session = applied.session();          // a refactor op re-homes the session; Local ops keep it
                results.add(applied.env().toJson());
            } catch (RefactorRefused r) {
                results.add(r.envelope().toJson());   // the §6 safety gate refused — same corrective diagnostics
                failedAt = i;
                break; // rollback — the refactor's gate stops the sequence, nothing written
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
        // The net effect in one human line — every per-op result is `staged`/`written:false` (the write is
        // ATOMIC at this wrapper, not per op), which misreads as "nothing happened". `committed`/`written` are
        // the authoritative signals; this restates them so an agent doesn't infer failure from a per-op flag.
        summary.put("summary", committed
                ? (written ? ops.size() + " op(s) committed, written to " + output
                           : ops.size() + " op(s) committed (dry-run — nothing written)")
                : (failedAt != null ? "rolled back at op " + failedAt + " — nothing written (atomic: all-or-nothing)"
                                    : "the terminal kernel gate rejected the sequence — rolled back, nothing written"));
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

    /**
     * Apply one op-record to the shared session and return its {@code changed} delta. Throws on a bad op
     * (→ rollback). {@code Op.of} is non-null here — {@link #validateArgs} has already rejected an unknown
     * {@code target}/{@code op}; the switch is a compiler-checked exhaustive expression over {@link Op}.
     */
    private Applied dispatch(EditSession session, JsonNode op, IDocumentModelReferenceResolver resolver) {
        Op which = Op.of(op);
        ObjectNode changed = MAPPER.createObjectNode();
        return switch (which) {
            case FIELD_ADD -> {
                // the same rich <spec> the standalone `field add` takes — kind + per-kind config + field
                // metadata, single-sourced through FieldSpec.parse (a bare {group,name,kind} stays the
                // simple case). So a constrained-field bulk add is one atomic gated write (G8), no
                // add-then-modify dance.
                FieldSpec.Parsed parsed = FieldSpec.parse(op);
                String path = parsed.group() + "/" + parsed.field().name();
                session.addField(parsed.group(), parsed.field());
                // G23: the custom required-field message attaches to the (now-created) requirednessConfig
                java.util.Map<String, String> requiredMessages = FieldSpec.requiredMessages(op);
                if (!requiredMessages.isEmpty()) {
                    session.modifyField(path, FieldModification.builder().requiredMessages(requiredMessages).build());
                }
                changed.put("added", path);
                if (op.hasNonNull("typedef")) {
                    changed.put("typedef", op.get("typedef").asText());
                }
                changed.put("kind", stagedFieldKind(session, path));
                yield staged(which, changed, session);
            }
            case FIELD_MODIFY -> {
                // a PARTIAL in-place edit — re-type AND/OR change metadata — the same rich spec as `field
                // modify`; every present aspect is applied, never silently dropped. The terminal gate re-binds
                // the whole sequence.
                FieldSpec.ModificationSpec parsed = FieldSpec.parseModification(op);
                FieldModification mod = parsed.modification();
                // reject an orphaned per-kind config block (no `kind`/`typedef`) whether or not other aspects
                // are present — never SILENTLY DROP it (05-modify-drop); same corrective as the empty case.
                if (FieldSpec.configBlockWithoutKind(op) != null || mod.isEmpty()) {
                    throw new IllegalArgumentException(FieldSpec.emptyModificationCorrective(op));
                }
                String path = parsed.group() + "/" + parsed.name();
                session.modifyField(path, mod);
                changed.put("modified", path);
                if (mod.type().isPresent()) {
                    if (op.hasNonNull("typedef")) {
                        changed.put("typedef", op.get("typedef").asText());
                    }
                    changed.put("kind", stagedFieldKind(session, path));
                }
                ArrayNode aspects = changed.putArray("aspects");
                mod.changedAspects().forEach(aspects::add);
                yield staged(which, changed, session);
            }
            case FIELD_REMOVE -> {
                String path = req(op, "fieldPathInModel");
                session.removeField(path);
                changed.put("removed", path);
                yield staged(which, changed, session);
            }
            case GROUP_ADD -> {
                String parent = req(op, "parent");
                String groupName = req(op, "name");
                if (op.hasNonNull("repeatable")) {
                    session.addRepeatableGroup(parent, groupName, op.get("repeatable").asInt());
                    changed.put("repeatable", true);
                } else {
                    session.addGroup(parent, groupName);
                }
                changed.put("added", parent + "/" + groupName);
                yield staged(which, changed, session);
            }
            case GROUP_MODIFY -> {
                // the same repeatability (G15) + index-field (G24) + metadata (G38) edits as the standalone
                // `group modify` — the setter cascade + `changed` report is single-sourced through
                // GroupModification (T4); only the exclusivity/empty guards' apply-worded messages live here.
                String path = req(op, "groupPathInModel");
                GroupModification mod = GroupModification.fromJson(op);
                if (mod.repeatable() != null && mod.nonRepeatable()) {
                    throw new IllegalArgumentException("group modify: pass at most one of repeatable / non-repeatable");
                }
                if (mod.indexField() != null && mod.clearIndexField()) {
                    throw new IllegalArgumentException("group modify: pass at most one of index-field / clear-index-field");
                }
                if (!mod.sortFields().isEmpty() && mod.clearSortFields()) {
                    throw new IllegalArgumentException("group modify: pass at most one of sort-field / clear-sort-fields");
                }
                if (mod.isEmpty()) {
                    throw new IllegalArgumentException("group modify needs one of repeatable / non-repeatable / "
                            + "index-field / clear-index-field / sort-field / clear-sort-fields / label / "
                            + "annotation / remove-annotation / internal-description / external-description");
                }
                mod.applyTo(path, session, changed);
                yield staged(which, changed, session);
            }
            case GROUP_REMOVE -> {
                String path = req(op, "groupPathInModel");
                boolean cascade = op.path("cascade").asBoolean(false);
                // the SAME safe-delete gate as the standalone `group remove` verb (G12), run against the live
                // (transiently-edited) session: a group still referenced from outside its subtree is refused
                // unless cascade — and a refused gate rolls the whole apply sequence back.
                KernelAdapter adapter = KernelAdapter.ofJson(session.toJson(), resolver);
                GroupRemoval.Plan plan = GroupRemoval.plan(adapter, path, cascade,
                        "set \"cascade\": true to remove the referrers too, or remove them first");
                if (plan.refused()) {
                    throw new RefactorRefused("group", "remove", plan.refusal().get());
                }
                changed.put("removed", path);
                ArrayNode cascaded = changed.putArray("cascaded");
                for (String r : plan.cascadeRules()) {
                    session.removeRule(r);
                    cascaded.add(r);
                }
                for (String c : plan.cascadeComputations()) {
                    session.removeComputation(c);
                    cascaded.add(c);
                }
                session.removeGroup(path);
                yield staged(which, changed, session);
            }
            case RULE_ADD -> {
                String field = req(op, "field");
                int s = field.lastIndexOf('/');
                if (s <= 0) {
                    throw new IllegalArgumentException("rule add 'field' must be an absolute path (e.g. /Group/Field)");
                }
                // the rule group defaults to the error field's parent; `group` overrides it (mirrors the standalone
                // verb + `rule check --group`) for RepetitionNotUnique-style rules that sit at the repeated group's parent.
                String group = op.hasNonNull("group") ? op.get("group").asText() : field.substring(0, s);
                if (!field.startsWith(group + "/")) {
                    throw new IllegalArgumentException("rule add 'field' " + field + " must be inside the rule group " + group);
                }
                String errorEntityRelPath = "../" + field.substring(group.length() + 1);
                String code = req(op, "code");
                String ruleName = op.hasNonNull("name") ? op.get("name").asText() : code;
                String severity = op.hasNonNull("severity") ? op.get("severity").asText() : "ERROR";
                List<LocalizedText> messages = messages(op);
                if (messages.isEmpty()) {
                    throw new IllegalArgumentException("rule add needs messages [{locale,text}, …]");
                }
                String condition = Comments.withLeadingComment(opt(op, "comment"), req(op, "condition"));
                if (op.path("allowDifferingDecimals").asBoolean(false)) {
                    condition = "@SuppressWarning(MVK_INVALID_COMPARE_DEC_PLACES) " + condition;
                }
                session.addRule(group, ruleName, errorEntityRelPath, code, condition, severity, messages);
                // G39: optional rule metadata at add time (annotation / internal-/external-description)
                metadataOps(op, session, group + "/" + ruleName, changed);
                changed.put("rule", group + "/" + ruleName);
                yield staged(which, changed, session);
            }
            case RULE_MODIFY -> {
                // parity with the standalone `rule modify`: edit condition AND/OR severity AND/OR messages —
                // at least one. Messages MERGE per locale (G17), so a one-locale edit can't drop the rest. The
                // terminal gate re-binds the whole sequence.
                String rule = req(op, "rulePathInModel");
                boolean any = false;
                if (op.hasNonNull("condition")) {
                    session.modifyRule(rule, Comments.withLeadingComment(opt(op, "comment"), op.get("condition").asText()));
                    any = true;
                }
                if (op.hasNonNull("severity")) {
                    session.modifyRuleSeverity(rule, severity(op.get("severity").asText()));
                    changed.put("severity", op.get("severity").asText());
                    any = true;
                }
                if (op.has("messages")) {
                    List<LocalizedText> provided = messages(op);
                    if (provided.isEmpty()) {
                        throw new IllegalArgumentException("rule modify 'messages' must be [{locale,text}, …]");
                    }
                    session.modifyRuleMessages(rule, mergeRuleMessages(session, rule, provided));
                    ArrayNode m = changed.putArray("messages");
                    provided.forEach(t -> m.addObject().put("locale", t.locale()).put("text", t.text()));
                    any = true;
                }
                // G39: rule metadata (annotation / remove-annotation / internal-/external-description)
                if (metadataOps(op, session, rule, changed)) {
                    any = true;
                }
                if (!any) {
                    throw new IllegalArgumentException(
                            "rule modify needs one of condition / severity / messages / metadata");
                }
                changed.put("modified", rule);
                yield staged(which, changed, session);
            }
            case RULE_REMOVE -> {
                // a rule has no dependents (unlike a field/group), so no cascade gate — just remove it (G12)
                String rule = req(op, "rulePathInModel");
                session.removeRule(rule);
                changed.put("removed", rule);
                yield staged(which, changed, session);
            }
            case COMPUTATION_ADD -> {
                ComputationSpec.Parsed parsed = ComputationSpec.parse(op);
                if (parsed.error() != null) {
                    throw new IllegalArgumentException(parsed.error());
                }
                session.addComputation(parsed.draft(), parsed.messages());
                changed.put("computation", parsed.fullName());
                yield staged(which, changed, session);
            }
            case COMPUTATION_MODIFY -> {
                String comp = req(op, "computationPathInModel");
                ComputationSpec.Parsed parsed = ComputationSpec.parse(op);
                if (parsed.error() != null) {
                    throw new IllegalArgumentException(parsed.error());
                }
                session.modifyComputation(comp, parsed.draft(), parsed.messages());
                changed.put("modified", comp);
                yield staged(which, changed, session);
            }
            case TYPEDEF_ADD -> {
                // the same rich per-kind config as the standalone `typedef add <spec>` (G25), via the shared
                // FieldSpec.parseType — a bare {id,kind} stays the simple case, an `enum`/`string` block makes
                // an ENUM/restricted type. Single-sourced so the op and the verb can't drift.
                String id = req(op, "id");
                session.addTypeDefinition(id, FieldSpec.parseType(op, id));
                changed.put("added", id);
                changed.put("kind", stagedTypedefKind(session, id));
                yield staged(which, changed, session);
            }
            case TYPEDEF_MODIFY -> {
                // change a shared type's config in one place — typed fields inherit it (by reference, no
                // rewrite). Same rich spec as TYPEDEF_ADD via FieldSpec.parseType; the standalone `typedef
                // modify` verb shares this path. A missing id throws → the op fails and the sequence rolls back.
                String id = req(op, "id");
                session.setTypeDefinitionType(id, FieldSpec.parseType(op, id));
                changed.put("modified", id);
                changed.put("kind", stagedTypedefKind(session, id));
                yield staged(which, changed, session);
            }
            case TYPEDEF_REMOVE -> {
                String id = req(op, "typeDefId");
                session.removeTypeDefinition(id);
                changed.put("removed", id);
                yield staged(which, changed, session);
            }
            case INCLUDE_ADD -> {
                String alias = req(op, "alias");
                String parent = req(op, "parent");
                String name = req(op, "name");
                // mirror the standalone verb's mount options — repeatability + rule/computation exclusions —
                // so an apply session can do what `include add` can (was: option-less 4-arg addInclude dropped
                // them, and the key-set rejected them as RK_UNKNOWN_ARG)
                MountOptions options = MountOptions.repeatable(
                        op.hasNonNull("repeatability") ? op.get("repeatability").asInt() : 1);
                if (op.path("exclude-rules").asBoolean(false)) {
                    options = options.excludingRules();
                }
                if (op.path("exclude-computations").asBoolean(false)) {
                    options = options.excludingComputations();
                }
                session.addInclude(alias, req(op, "reference")).mount(parent, name, alias, options);
                changed.put("added", alias);
                changed.put("mountedAt", parent + "/" + name);
                if (options.repeatability() != 1) {
                    changed.put("repeatability", options.repeatability());
                }
                if (options.excludeRules()) {
                    changed.put("excludeRules", true);
                }
                if (options.excludeComputations()) {
                    changed.put("excludeComputations", true);
                }
                yield staged(which, changed, session);
            }
            case INCLUDE_SET_REFERENCE -> {
                String alias = req(op, "alias");
                String reference = req(op, "reference");
                session.setIncludeReference(alias, reference);
                changed.put("alias", alias);
                changed.put("reference", reference);
                yield staged(which, changed, session);
            }
            case INCLUDE_REMOVE -> {
                String alias = req(op, "alias");
                session.removeInclude(alias);
                changed.put("removed", alias);
                yield staged(which, changed, session);
            }
            case CONFIG_MODIFY -> {
                // the setter cascade + `changed` report is single-sourced with the standalone `config modify`
                // through ConfigModification (T4); only the empty-guard's apply-worded message lives here.
                ConfigModification mod = ConfigModification.fromJson(op);
                if (mod.isEmpty()) {
                    throw new IllegalArgumentException("config modify needs one of decimal-separator, time-zone, "
                            + "condition-language, label, annotation, comment, clear-comment, add-locale, remove-locale, "
                            + "role, clear-roles, supported-character, base-year, field-ref-by-short-name, remove-annotation");
                }
                mod.applyTo(session, changed);
                yield staged(which, changed, session);
            }
            case FIELD_RENAME -> applyRefactor(session, resolver,
                    StructureRefactor.fieldRename(req(op, "fieldPathInModel"), req(op, "to")));
            case FIELD_MOVE -> applyRefactor(session, resolver,
                    StructureRefactor.fieldMove(req(op, "fieldPathInModel"), req(op, "to")));
            case GROUP_RENAME -> applyRefactor(session, resolver,
                    StructureRefactor.groupRename(req(op, "groupPathInModel"), req(op, "to")));
            case GROUP_MOVE -> applyRefactor(session, resolver,
                    StructureRefactor.groupMove(req(op, "groupPathInModel"), req(op, "to")));
            case RULE_RENAME -> applyRefactor(session, resolver,
                    StructureRefactor.ruleRename(req(op, "rulePathInModel"), req(op, "to")));
            case TYPEDEF_RENAME -> applyRefactor(session, resolver,
                    StructureRefactor.typedefRename(req(op, "typeDefId"), req(op, "to")));
            case INCLUDE_RENAME -> applyRefactor(session, resolver,
                    StructureRefactor.includeRename(req(op, "alias"), req(op, "to")));
            case FIELD_READ -> {
                String path = req(op, "fieldPathInModel");
                ModelDocument doc = ModelDocument.read(session.toJson());
                String kind = doc.fieldKind(path)
                        .orElseThrow(() -> new IllegalArgumentException("no field '" + path + "' in the model"));
                ObjectNode data = MAPPER.createObjectNode();
                data.put("field", path);
                // mirror the standalone `field read`: the clean ValueKind name (not the kernel discriminator)
                data.put("kind", kind);
                // surface the per-kind config so a read INSIDE a session verifies a staged constraint the
                // same way (the read-gap fix is consistent across both paths)
                doc.fieldConfig(path).ifPresent(data::setAll);
                yield readEnv(which, data, session);
            }
            case GROUP_READ -> {
                String path = req(op, "groupPathInModel");
                ModelDocument doc = ModelDocument.read(session.toJson());
                if (!doc.groupPaths().contains(path)) {
                    throw new IllegalArgumentException("no group '" + path + "' in the model");
                }
                // T3: the same shared shape the standalone `group read` emits (was a {group,repeatable} subset)
                yield readEnv(which, ReadPayloads.group(doc, path), session);
            }
            case TYPEDEF_READ -> {
                // T3: the shared no-arg list shape (typedefs + importedFrom), was ids-only here
                yield readEnv(which, ReadPayloads.typedefList(ModelDocument.read(session.toJson())), session);
            }
            case INCLUDE_READ -> {
                ObjectNode data = MAPPER.createObjectNode();
                ArrayNode arr = data.putArray("includes");
                for (ModelDocument.Include inc : ModelDocument.read(session.toJson()).includes()) {
                    arr.addObject().put("alias", inc.alias()).put("reference", inc.reference());
                }
                yield readEnv(which, data, session);
            }
            case CONFIG_READ -> {
                // T3: the same shared full config shape the standalone `config read` emits (was 4 scalars)
                yield readEnv(which, ReadPayloads.config(ModelDocument.read(session.toJson())), session);
            }
        };
    }

    /** A dispatched op's outcome: its per-op envelope + the session to carry forward (a refactor re-homes it). */
    private record Applied(ResultEnvelope env, EditSession session) {}

    /** An edit op's per-op envelope — its mutation is {@code staged} until the terminal gate commits the sequence. */
    private static Applied staged(Op which, ObjectNode changed, EditSession session) {
        return new Applied(new ResultEnvelope(which.target, which.op, ResultEnvelope.Outcome.STAGED,
                "staged " + which.label(), changed, null, List.of(), null, false, null), session);
    }

    /** The clean ValueKind name of a just-staged field, read back from the session — the SAME resolver
     *  the standalone {@code field add}/{@code field read} use, so apply echoes one vocabulary (mirrors the
     *  FIELD_READ op's read-back; the per-op serialize is the price of staged read-back, paid by reads too). */
    private static String stagedFieldKind(EditSession session, String path) {
        return ModelDocument.read(session.toJson()).fieldKind(path).orElseThrow();
    }

    /** The clean ValueKind name of a just-staged type definition, read back from the session. */
    private static String stagedTypedefKind(EditSession session, String id) {
        return ModelDocument.read(session.toJson()).typeDefinitionKind(id).orElseThrow();
    }

    /** A read op's per-op envelope — its payload (the current session state) rides {@code data}. */
    private static Applied readEnv(Op which, ObjectNode data, EditSession session) {
        return new Applied(ResultEnvelope.read(which.target, which.op, "read " + which.label(), true, List.of(), data),
                session);
    }

    /**
     * Apply a refactor op (rename/move, CLI-SPEC §6) to the shared session: run the op's safety gate against
     * the CURRENT (transiently-edited) model — so it sees prior ops' effects — then rewrite every reference +
     * apply the node change, and re-home the result into a fresh session for the next op. A failing
     * precondition throws {@link RefactorRefused} → the loop rolls the whole sequence back, nothing written.
     * Validation is deferred to the one terminal gate (apply's invariant), like every other op.
     */
    private Applied applyRefactor(EditSession session, IDocumentModelReferenceResolver resolver, StructureRefactor r) {
        String modelJson = session.toJson();
        ModelDocument doc = ModelDocument.read(modelJson);
        KernelAdapter adapter = KernelAdapter.ofJson(modelJson, resolver);
        Optional<Refusal> refusal = r.firstFailure(doc, adapter);
        if (refusal.isPresent()) {
            throw new RefactorRefused(r.target(), r.op(), refusal.get());
        }
        Set<String> rewrote = r.referrers(adapter, doc);
        EditSession next = EditSession.read(r.rewriteAndMutate(modelJson).toJson(), resolver);
        ObjectNode changed = MAPPER.createObjectNode();
        changed.put(r.changedKey(), r.source());
        changed.put("to", r.newPath());
        ArrayNode refs = changed.putArray("rewroteReferences");
        rewrote.forEach(refs::add);
        return new Applied(new ResultEnvelope(r.target(), r.op(), ResultEnvelope.Outcome.STAGED,
                "staged " + r.target() + " " + r.op(), changed, null, List.of(), null, false, null), next);
    }

    /**
     * A refactor op's safety gate refused (CLI-SPEC §6) — carried out of {@link #dispatch} as an exception so
     * the loop rolls the whole sequence back, surfacing the gate's structured {@code data} + corrective
     * diagnostic in the failing op's {@code refused} envelope (exactly as the standalone verb would).
     */
    private static final class RefactorRefused extends RuntimeException {
        private final transient ResultEnvelope envelope;

        RefactorRefused(String target, String op, Refusal refusal) {
            super(refusal.summary());
            this.envelope = ResultEnvelope.refused(target, op, refusal.summary(), refusal.data(),
                    refusal.diagnostics());
        }

        ResultEnvelope envelope() {
            return envelope;
        }
    }

    private static String req(JsonNode op, String key) {
        if (!op.hasNonNull(key)) {
            throw new IllegalArgumentException("op missing required arg '" + key + "'");
        }
        return op.get(key).asText();
    }

    /** An optional string arg, or {@code null} if absent. */
    private static String opt(JsonNode op, String key) {
        return op.hasNonNull(key) ? op.get(key).asText() : null;
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
        Op which = Op.of(op);
        if (which == null) {
            out.add(Diagnostic.precheck(Optional.of("RK_UNKNOWN_OP"),
                    "apply does not support op '" + op.path("target").asText() + " " + op.path("op").asText() + "'",
                    new Diagnostic.Where((String) null, Optional.empty()),
                    Optional.of("supported ops: " + supportedOps()),
                    Optional.empty()));
            return out;
        }
        String label = which.label();
        Set<String> valid = which.keys;
        for (Iterator<String> it = op.fieldNames(); it.hasNext();) {
            String f = it.next();
            if (f.equals("target") || f.equals("op") || valid.contains(f)) {
                continue;
            }
            String near = Suggestions.nearest(f, valid);
            out.add(Diagnostic.precheck(Optional.of("RK_UNKNOWN_ARG"),
                    "unknown arg '" + f + "' for op '" + label + "'",
                    Diagnostic.Where.arg(null, f),
                    Optional.of(near != null ? "did you mean '" + near + "'?"
                            : "valid args: " + String.join(", ", new TreeSet<>(valid))),
                    Optional.empty()));
        }
        // the flat key-set above covers the op-record's TOP level; for ops with a rich nested spec, add the
        // schema-sourced NESTED check (e.g. a typo inside a `string` config block, or a message-item key) —
        // the gap a flat key-set can't see (the same SpecKeys the standalone spec verbs use).
        String nestedSchema = NESTED_SCHEMA.get(which);
        if (nestedSchema != null) {
            out.addAll(SpecKeys.forSchema(nestedSchema).validateNested(op));
        }
        return out;
    }

    /** Ops whose spec has nested objects worth schema-validating beyond the flat top-level key-set above. */
    private static final java.util.Map<Op, String> NESTED_SCHEMA = java.util.Map.of(
            Op.FIELD_ADD, "field-add", Op.FIELD_MODIFY, "field-add",
            Op.RULE_ADD, "add", Op.RULE_MODIFY, "add", Op.COMPUTATION_ADD, "add", Op.COMPUTATION_MODIFY, "add",
            Op.TYPEDEF_ADD, "typedef-add", Op.TYPEDEF_MODIFY, "typedef-add");

    /** The supported {@code target op} labels, sorted — for the unknown-op diagnostic. */
    private static String supportedOps() {
        TreeSet<String> labels = new TreeSet<>();
        for (Op o : Op.values()) {
            labels.add(o.label());
        }
        return String.join(", ", labels);
    }

    /** A failing op-arg envelope (the offending op rolled the sequence back). */
    private ResultEnvelope argRejection(JsonNode op, int index, List<Diagnostic> diagnostics) {
        return new ResultEnvelope(op.path("target").asText(), op.path("op").asText(),
                ResultEnvelope.Outcome.REJECTED, "op " + index + " has an invalid arg — sequence rolled back",
                null, null, diagnostics, null, false, null);
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

    /**
     * The values of a repeatable op-record key — a JSON array's elements OR a single scalar. So
     * {@code "add-locale":"de_DE"} binds the same as {@code ["de_DE"]} (and {@code "role":"admin"} like
     * {@code ["admin"]}), reconciling the op-record with the manifest's {@code SCALAR} kind for these
     * repeatable params — probe Issue A, where a scalar was silently dropped + drew a self-contradictory
     * "needs one of … add-locale …".
     */
    private static List<String> stringValues(JsonNode op, String key) {
        List<String> out = new ArrayList<>();
        JsonNode n = op.path(key);
        if (n.isArray()) {
            n.forEach(e -> { if (!e.isNull()) out.add(e.asText()); });
        } else if (n.isValueNode() && !n.isNull()) {
            out.add(n.asText());
        }
        return out;
    }

    /**
     * Apply the shared element-metadata op keys — {@code annotation} / {@code remove-annotation} /
     * {@code internal-description} / {@code external-description} — from {@code op} to {@code session} at
     * {@code path} (a field / group / rule name-path), recording each under {@code changed}. Returns whether any
     * metadata key was present (so a rule modify can count it toward its at-least-one-directive gate). The
     * {@code label} key is element-specific (groups/config only) and stays inline at its call site.
     */
    private static boolean metadataOps(JsonNode op, EditSession session, String path, ObjectNode changed) {
        boolean any = false;
        if (op.hasNonNull("annotation")) {
            var it = op.get("annotation").fields();
            while (it.hasNext()) {
                var e = it.next();
                session.setAnnotation(path, e.getKey(), e.getValue().asText());
                changed.put("annotation." + e.getKey(), e.getValue().asText());
                any = true;
            }
        }
        for (String name : stringValues(op, "remove-annotation")) {
            session.removeAnnotation(path, name);
            changed.putNull("annotation." + name);
            any = true;
        }
        if (op.hasNonNull("internal-description")) {
            var it = op.get("internal-description").fields();
            while (it.hasNext()) {
                var e = it.next();
                session.setInternalDescription(path, e.getKey(), e.getValue().asText());
                changed.put("internalDescription." + e.getKey(), e.getValue().asText());
                any = true;
            }
        }
        if (op.hasNonNull("external-description")) {
            var it = op.get("external-description").fields();
            while (it.hasNext()) {
                var e = it.next();
                session.setExternalDescription(path, e.getKey(), e.getValue().asText());
                changed.put("externalDescription." + e.getKey(), e.getValue().asText());
                any = true;
            }
        }
        for (String locale : stringValues(op, "remove-internal-description")) {
            session.removeInternalDescription(path, locale);
            changed.putNull("internalDescription." + locale);
            any = true;
        }
        for (String locale : stringValues(op, "remove-external-description")) {
            session.removeExternalDescription(path, locale);
            changed.putNull("externalDescription." + locale);
            any = true;
        }
        return any;
    }

    /** Parse a severity arg, with a clean corrective for a bad value (not the raw enum-{@code valueOf} message). */
    private static Severity severity(String raw) {
        try {
            return Severity.valueOf(raw);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("severity must be ERROR|WARNING|INFO (got: " + raw + ")");
        }
    }

    /**
     * Merge the provided per-locale messages into the rule's existing ones (G17 upsert): keep every existing
     * locale, overwrite only the ones provided — so a one-locale edit via {@code apply} can't silently drop the
     * rest, exactly as the standalone {@code rule modify --message} does. Reads the existing set with a <b>raw</b>
     * (non-binding) read ({@link ModelWriter#readMessages}) from the live (staged) session — NOT a validating
     * bind: mid-sequence the model can be transiently invalid (e.g. a locale just added, this message not yet
     * merged), and apply defers consistency to the terminal gate (probe Issue B — a validating read here threw
     * MVK_ERROR_MESSAGE_FOR_LANGUAGE_MISSING in the very op that supplies the message).
     */
    private static List<LocalizedText> mergeRuleMessages(EditSession session, String rule, List<LocalizedText> provided) {
        java.util.LinkedHashMap<String, String> merged = new java.util.LinkedHashMap<>();
        for (LocalizedText existing : ModelWriter.readMessages(session.toJson(), rule)) {
            merged.put(existing.locale(), existing.text());
        }
        for (LocalizedText p : provided) {
            merged.put(p.locale(), p.text());
        }
        return merged.entrySet().stream().map(e -> new LocalizedText(e.getKey(), e.getValue())).toList();
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
        return new ResultEnvelope("model", "validate", ResultEnvelope.Outcome.REJECTED,
                "the terminal kernel gate rejected the sequence — rolled back", null, null, diagnostics, false, false, null);
    }
}
