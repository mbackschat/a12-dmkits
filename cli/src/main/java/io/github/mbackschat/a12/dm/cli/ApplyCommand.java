package io.github.mbackschat.a12.dm.cli;

import io.github.mbackschat.a12.dm.core.EntityPath;
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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.mbackschat.a12.dm.core.EntityPaths;
import io.github.mbackschat.a12.dm.core.ModelResolver;

import io.github.mbackschat.a12.dm.adapter.KernelAdapter;
import io.github.mbackschat.a12.dm.core.ModelConsistencyException;
import io.github.mbackschat.a12.dm.edit.EditSession;
import io.github.mbackschat.a12.dm.modelkit.FieldKind;
import io.github.mbackschat.a12.dm.modelkit.FieldModification;
import io.github.mbackschat.a12.dm.modelkit.ModelDocument;
import io.github.mbackschat.a12.dm.modelkit.MountOptions;
import io.github.mbackschat.a12.dm.rulekit.dsl.Comments;
import io.github.mbackschat.a12.dm.rulekit.dsl.ConditionDirectives;
import io.github.mbackschat.a12.dm.rulekit.dsl.ConditionLanguage;
import io.github.mbackschat.a12.dm.rulekit.dsl.LocalizedText;
import io.github.mbackschat.a12.dm.rulekit.dsl.Severity;
import io.github.mbackschat.a12.dm.rulekit.write.ModelWriter;
import io.github.mbackschat.a12.dm.rulekit.validate.Diagnostic;
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
 * <b>Local</b> ops — field/group/typedef/include add·remove, rule/computation add·modify·remove·read, config modify, and
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
    enum Op implements ResultIdentity.Source {
        FIELD_ADD("field", "add", FieldSpec.ADD_KEYS),
        FIELD_MODIFY("field", "modify", FieldSpec.MODIFY_KEYS),
        FIELD_REMOVE("field", "remove", Set.of("fieldPathInModel", "cascade")),
        GROUP_ADD("group", "add", Set.of("parent", "name", "repeatable")),
        GROUP_MODIFY("group", "modify", withLocator("groupPathInModel", GroupModification.DIRECTIVE_KEYS)),
        GROUP_REMOVE("group", "remove", Set.of("groupPathInModel", "cascade")),
        RULE_ADD("rule", "add", Set.of("field", "group", "condition", "comment", "code", "severity", "name", "messages",
                "allowDifferingDecimals", "annotation", "internal-description", "external-description")),
        RULE_MODIFY("rule", "modify",
                Set.of("rulePathInModel", "condition", "comment", "severity", "messages")),
        RULE_REMOVE("rule", "remove", Set.of("rulePathInModel")),
        COMPUTATION_ADD("computation", "add", ComputationSpec.KEYS),
        COMPUTATION_MODIFY("computation", "modify", withLocator("computationPathInModel", ComputationSpec.KEYS)),
        COMPUTATION_REMOVE("computation", "remove", Set.of("computationPathInModel")),
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
        META_MODIFY("meta", "modify", withLocator("ref", MetaModification.KEYS)),
        CONFIG_MODIFY("config", "modify", ConfigModification.KEYS),
        FIELD_RENAME("field", "rename", Set.of("fieldPathInModel", "to")),
        FIELD_MOVE("field", "move", Set.of("fieldPathInModel", "to")),
        GROUP_RENAME("group", "rename", Set.of("groupPathInModel", "to")),
        GROUP_MOVE("group", "move", Set.of("groupPathInModel", "to")),
        RULE_RENAME("rule", "rename", Set.of("rulePathInModel", "to")),
        TYPEDEF_RENAME("typedef", "rename", Set.of("typeDefId", "to")),
        FIELD_READ("field", "read", Set.of("fieldPathInModel")),
        GROUP_READ("group", "read", Set.of("groupPathInModel")),
        RULE_READ("rule", "read", Set.of("rulePathInModel")),
        COMPUTATION_READ("computation", "read", Set.of("computationPathInModel")),
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

        @Override
        public String resultTarget() {
            return target;
        }

        @Override
        public String resultOp() {
            return op;
        }

        ResultIdentity identity() {
            return ResultIdentity.from(this);
        }

        static Op of(JsonNode opRecord) {
            return of(ParsedSelector.from(opRecord));
        }

        static Op of(ParsedSelector selector) {
            for (Op o : values()) {
                if (o.target.equals(selector.resultTarget()) && o.op.equals(selector.resultOp())) {
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

    /**
     * The named selector read from an op-record before it is known to be one of {@link Op}. Keeping the two JSON
     * fields in one value preserves the rejected record's echo without reopening a positional String-pair factory.
     */
    record ParsedSelector(String resultTarget, String resultOp) implements ResultIdentity.Source {
        static ParsedSelector from(JsonNode opRecord) {
            return new ParsedSelector(opRecord.path("target").asText(), opRecord.path("op").asText());
        }

        ResultIdentity identity() {
            return ResultIdentity.from(this);
        }
    }

    @Parameters(index = "0", paramLabel = "<ops.json>",
            description = "a JSON array of op-records [{\"target\",\"op\",…args}, …] — the per-op fields are that "
                    + "verb's params (copy from `<target> <op> --help`). See `dmtool schema apply` for the frame "
                    + "+ worked examples (incl. rule/group ops, not just field add)."
                    + JsonInput.HELP)
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
        JsonNode ops;
        try {
            ops = MAPPER.readTree(JsonInput.read(opsFile, "<ops.json>"));
        } catch (IOException e) {
            return refuseUnreadableInput(ResultIdentity.APPLY_RUN, "<ops.json>", opsFile, e);
        }
        // Read separately from the ops list: sharing one catch attributed an unreadable MODEL to `<ops.json>`,
        // naming an argument that was fine and a path the caller never gave for it.
        String modelJson = readModelJson(ResultIdentity.APPLY_RUN, model);
        if (modelJson == null) {
            return 2;
        }
        if (!ops.isArray()) {
            return fail("the ops file must be a JSON array of op-records");
        }
        ModelResolver resolver = workspace.resolverFor(model);
        EditSession session = EditSession.read(modelJson, resolver);

        ArrayNode results = MAPPER.createArrayNode();
        Integer failedAt = null;
        // WHICH KIND of failure stopped the sequence, not merely that one did. The three mid-sequence exits are not
        // interchangeable: a bad arg and an op that threw are input REJECTIONS (exit 1), a §6 safety gate is a
        // REFUSAL (exit 2). The aggregate has to mirror the failing op, because collapsing them is what made the old
        // `committed ? 0 : 1` report "input rejected" for a refusal.
        Stoppage stoppage = null;
        int i = 0;
        for (JsonNode op : ops) {
            List<Diagnostic> argProblems = validateArgs(op);
            if (!argProblems.isEmpty()) {
                results.add(argRejection(op, i, argProblems).toJson());
                failedAt = i;
                stoppage = Stoppage.REJECTED;
                break; // a typo'd/unknown arg self-corrects via the diagnostic — roll back, write nothing
            }
            try {
                Applied applied = dispatch(session, op, resolver);
                session = applied.session();          // a refactor op re-homes the session; Local ops keep it
                results.add(applied.env().toJson());
            } catch (OperationRefused r) {
                results.add(r.envelope().toJson());   // the §6 safety gate refused — same corrective diagnostics
                failedAt = i;
                stoppage = Stoppage.REFUSED;
                break; // rollback — the refactor's gate stops the sequence, nothing written
            } catch (RuntimeException e) {
                results.add(opFailure(op, i, e).toJson());
                failedAt = i;
                stoppage = Stoppage.REJECTED;
                break; // rollback — stop the sequence, write nothing
            }
            i++;
        }

        boolean committed = false;
        boolean written = false;
        String output = null;
        ModelConsistencyException gateRejection = null;
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
                gateRejection = e;
                results.add(gateRejection(e).toJson());   // terminal gate rejected the committed sequence → rollback
            } catch (IOException e) {
                return refuseUnwritableOutput(ResultIdentity.APPLY_RUN, "-o <file>", outputPath, e);
            }
        }

        // `data` carries the aggregate facts and the per-op envelopes. `written`/`output` are deliberately NOT
        // repeated here — they are members of the standard envelope this now returns.
        ObjectNode data = MAPPER.createObjectNode();
        data.put("ops", ops.size());
        data.put("committed", committed);
        if (failedAt != null) {
            data.put("failedAt", failedAt);
        } else {
            data.putNull("failedAt");
        }
        data.set("results", results);

        // The net effect in one human line — every per-op result is `staged`/`written:false` (the write is
        // ATOMIC at this wrapper, not per op), which misreads as "nothing happened". `committed`/`written` are
        // the authoritative signals; this restates them so an agent doesn't infer failure from a per-op flag.
        String summary = committed
                ? (written ? ops.size() + " op(s) committed, written to " + output
                           : ops.size() + " op(s) committed (dry-run — nothing written)")
                : (failedAt != null ? "rolled back at op " + failedAt + " — nothing written (atomic: all-or-nothing)"
                                    : "the terminal kernel gate rejected the sequence — rolled back, nothing written");
        ResultEnvelope env = aggregate(summary, data,
                new Terminal(committed, written, output, stoppage, gateRejection, ConsistencyEvidence.of(session)));
        out().print(env.toJsonString());
        return env.exitCode();
    }

    /**
     * The transaction's own outcome as one standard envelope.
     *
     * <p>{@code apply} used to print a bespoke object — {@code {verb, ops, committed, summary, failedAt, written,
     * output, results[]}} — with no {@code outcome} key at all. That made it the one verb an agent had to learn a
     * second output shape for, and it silently disabled {@code --text}, whose renderer requires an object carrying a
     * textual {@code outcome}: {@code apply --text} emitted raw JSON, in both flag placements and on both success and
     * failure.
     *
     * <p><b>Unlike {@code batch}, this DOES derive its status from its parts</b> — because the parts are steps of one
     * transaction with a single all-or-nothing result, not independent invocations. So a mid-sequence gate refusal is
     * a {@code refused} aggregate and a terminal kernel rejection is a {@code rejected} one carrying
     * {@code valid:false} plus the gate that decided it. <b>That moves the refusal case's exit from 1 to 2</b>, which
     * is intended: the previous {@code committed ? 0 : 1} collapsed every failure into 1, so a refusal reported the
     * code for "input rejected". Preserving that would require the outer {@code outcome} to say {@code refused} while
     * the exit said otherwise.
     */
    private ResultEnvelope aggregate(String summary, ObjectNode data, Terminal terminal) {
        if (terminal.committed()) {
            return ResultEnvelope.transaction(ResultIdentity.APPLY_RUN, summary, data,
                    terminal.written() ? ResultEnvelope.MutationDisposition.APPLIED
                            : ResultEnvelope.MutationDisposition.PREVIEW,
                    terminal.written() ? terminal.output() : null, terminal.evidence());
        }
        if (terminal.gateRejection() != null) {
            // The one gate apply runs, and it rejected: valid:false plus the arbitrator that decided it. The
            // kernel's own diagnostics are NOT hoisted here: they already ride the terminal gate's own nested
            // envelope in data.results[] (see #gateRejection), which is where a per-step verdict belongs in this
            // aggregate. Copying them up would state the same finding twice with two different loci.
            return ResultEnvelope.rejected(ResultIdentity.APPLY_RUN, summary, data, List.of(),
                    RejectionBasis.kernel(ConsistencyEvidence.of(terminal.gateRejection())));
        }
        // A mid-sequence stop never reached the terminal gate, so it carries neither a verdict nor evidence —
        // only the KIND of stop, which decides the exit code.
        return switch (terminal.stoppage() == null ? Stoppage.REJECTED : terminal.stoppage()) {
            case REFUSED -> ResultEnvelope.refused(ResultIdentity.APPLY_RUN, summary, data, List.of());
            case REJECTED -> ResultEnvelope.rejected(ResultIdentity.APPLY_RUN, summary, data, List.of(),
                    RejectionBasis.preflight());
        };
    }

    /**
     * How the transaction ended — the one value {@link #aggregate} switches on.
     *
     * <p>Introduced when the terminal gate's receipt had to reach the aggregate: the helper already took two
     * {@code String}s and two adjacent {@code boolean}s, and an eighth positional argument would have been the
     * silent-swap shape the project's own rule forbids. These six members are decided together at a single point
     * and read together at a single point, so they are one value rather than a parameter list.
     */
    private record Terminal(boolean committed, boolean written, String output, Stoppage stoppage,
            ModelConsistencyException gateRejection, ConsistencyEvidence evidence) {}

    /** Why the sequence stopped before the terminal gate — a refusal and a rejection exit differently. */
    private enum Stoppage { REFUSED, REJECTED }

    /**
     * Apply one op-record to the shared session and return its {@code changed} delta. Throws on a bad op
     * (→ rollback). {@code Op.of} is non-null here — {@link #validateArgs} has already rejected an unknown
     * {@code target}/{@code op}; the switch is a compiler-checked exhaustive expression over {@link Op}.
     */
    private Applied dispatch(EditSession session, JsonNode op, ModelResolver resolver) {
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
                Map<String, String> requiredMessages = FieldSpec.requiredMessages(op);
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
                boolean cascade = op.path("cascade").asBoolean(false);
                // the SAME safe-delete gate as the standalone `field remove` verb, run against the live
                // (transiently-edited) session — this op previously ran NO gate at all, so a batch skipped §6
                // for fields while its GROUP_REMOVE sibling three cases down enforced it (CG13)
                KernelAdapter adapter = KernelAdapter.ofJson(session.toJson(), resolver);
                FieldRemoval.Plan plan = FieldRemoval.plan(adapter, path, cascade,
                        "set \"cascade\": true to remove the referrers too, or remove them first");
                if (plan.refused()) {
                    throw new OperationRefused(which.identity(), plan.refusal().get());
                }
                ArrayNode fieldCascaded = changed.putArray("cascaded");
                for (String r : plan.cascadeRules()) {
                    session.removeRule(r);
                    fieldCascaded.add(r);
                }
                for (String c : plan.cascadeComputations()) {
                    session.removeComputation(c);
                    fieldCascaded.add(c);
                }
                session.removeField(path);
                changed.put("removed", path);
                yield staged(which, changed, session);
            }
            case GROUP_ADD -> {
                // `/` is the root level here too. The standalone verbs normalize through the same helper, and
                // an op-record surface that did not would leave the undocumented empty string as the only
                // route to a second root on the very path documented as being at parity with them.
                String parent = ModelPaths.rootLevelParent(req(op, "parent"));
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
                // the same repeatability (G15) + index-field (G24) + row-sort edits as the standalone
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
                            + "index-field / clear-index-field / sort-field / clear-sort-fields");
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
                    throw new OperationRefused(which.identity(), plan.refusal().get());
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
                if (!ModelPaths.isAbsoluteEntityPath(field)) {
                    // This op's refusal reaches the author as a message and nothing else, so the corrective has
                    // to ride in it — the standalone verb carries the same text in the diagnostic's `fix`.
                    throw new IllegalArgumentException(
                            ModelPaths.notAnAbsoluteEntityPath("rule add 'field'", field) + " — "
                            + ModelPaths.absoluteEntityPathFix("field", field,
                                    op.hasNonNull("group") ? op.get("group").asText() : null));
                }
                // the rule group defaults to the error field's parent; `group` overrides it (mirrors the standalone
                // verb + `rule check --group`) for RepetitionNotUnique-style rules that sit at the repeated group's parent.
                String group = op.hasNonNull("group") ? op.get("group").asText() : field.substring(0, s);
                if (!EntityPaths.isStrictlyInside(field, group)) {
                    throw new IllegalArgumentException("rule add 'field' " + field + " must be inside the rule group " + group);
                }
                String errorEntityRelPath = EntityPath.of(group).elementReference(EntityPath.of(field));
                String code = req(op, "code");
                String ruleName = op.hasNonNull("name") ? op.get("name").asText() : code;
                String severity = op.hasNonNull("severity") ? op.get("severity").asText() : "ERROR";
                List<LocalizedText> messages = messages(op);
                if (messages.isEmpty()) {
                    throw new IllegalArgumentException("rule add needs messages [{locale,text}, …]");
                }
                String condition = Comments.withLeadingComment(opt(op, "comment"), req(op, "condition"));
                if (op.path("allowDifferingDecimals").asBoolean(false)) {
                    condition = ConditionDirectives.allowDifferingDecimals(ConditionLanguage.ofModelCode(
                            ModelDocument.read(session.toJson()).config().conditionLanguage()), condition);
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
                if (!any) {
                    throw new IllegalArgumentException("rule modify needs one of condition / severity / messages");
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
                // The same identity gate the standalone verb applies. Without it a re-expression naming a
                // different `group`/`computedField`/`name` REMOVES the located computation and adds another
                // one elsewhere — a silent MOVE, which `computation modify` refuses by contract. Latent while
                // only `computedField` could shift the derived name; the declaring `group` key makes it one key.
                if (!parsed.fullName().equals(comp)) {
                    throw new IllegalArgumentException("the op re-expresses " + parsed.fullName()
                            + ", not the computation " + comp + " — a re-expression keeps the same computation"
                            + " (match computedField/name, and `group` if it is declared outside the computed"
                            + " field's parent); use remove + add to relocate one");
                }
                session.modifyComputation(comp, parsed.draft(), parsed.messages());
                changed.put("modified", comp);
                yield staged(which, changed, session);
            }
            case COMPUTATION_REMOVE -> {
                // a computation has no dependents of its own (its computed field may, but removing the
                // computation leaves the field in place), so no cascade gate — the peer of RULE_REMOVE. The
                // standalone verb's existence gate is redundant here: an unknown name throws out of
                // removeComputation, which fails this op and rolls the whole sequence back.
                String comp = req(op, "computationPathInModel");
                session.removeComputation(comp);
                changed.put("removed", comp);
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
                String parent = ModelPaths.rootLevelParent(req(op, "parent"));
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
            case META_MODIFY -> {
                String ref = req(op, "ref");
                MetaModification modification = MetaModification.fromJson(op);
                if (modification.isEmpty()) {
                    throw new IllegalArgumentException("meta modify needs one of label, annotation, "
                            + "remove-annotation, internal-description, external-description, "
                            + "remove-internal-description, remove-external-description");
                }
                ModelDocument current = ModelDocument.read(session.toJson());
                Optional<Refusal> refusal = new SafetyGate()
                        .require(SafetyChecks.elementExists(current, ref))
                        .require(SafetyChecks.labelSupported(current, ref, !modification.labels().isEmpty()))
                        .firstFailure();
                if (refusal.isPresent()) {
                    throw new OperationRefused(which.identity(), refusal.get());
                }
                modification.applyTo(ref, current, session, changed);
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
                Optional<Refusal> refusal = mod.firstFailure();
                if (refusal.isPresent()) {
                    throw new OperationRefused(which.identity(), refusal.get());
                }
                mod.applyTo(session, changed);
                yield staged(which, changed, mod.advisories(), session);
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
                yield readEnv(which, ReadPayloads.field(doc, path), session);
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
            case RULE_READ -> {
                String path = req(op, "rulePathInModel");
                KernelAdapter adapter = KernelAdapter.ofJson(session.toJson(), resolver);
                if (!adapter.ruleFullNames().contains(path)) {
                    throw new IllegalArgumentException("no rule '" + path + "' in the model");
                }
                yield readEnv(which, ReadPayloads.rule(adapter, ModelDocument.read(session.toJson()), path),
                        session);
            }
            case COMPUTATION_READ -> {
                String path = req(op, "computationPathInModel");
                KernelAdapter adapter = KernelAdapter.ofJson(session.toJson(), resolver);
                if (!adapter.computationFullNames().contains(path)) {
                    throw new IllegalArgumentException("no computation '" + path + "' in the model");
                }
                yield readEnv(which, ReadPayloads.computation(adapter, path), session);
            }
            case TYPEDEF_READ -> {
                // T3: the shared no-arg list shape (typedefs + importedFrom), was ids-only here
                yield readEnv(which, ReadPayloads.typedefList(ModelDocument.read(session.toJson())), session);
            }
            case INCLUDE_READ -> {
                yield readEnv(which, ReadPayloads.includeList(ModelDocument.read(session.toJson())), session);
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
        return staged(which, changed, List.of(), session);
    }

    /** A staged mutation carrying nonblocking authoring diagnostics. */
    private static Applied staged(
            Op which, ObjectNode changed, List<Diagnostic> diagnostics, EditSession session) {
        return new Applied(ResultEnvelope.staged(which.identity(), "staged " + which.label(), changed,
                diagnostics), session);
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
        // A read of the TRANSIENT session state; apply defers validation to one terminal gate, so this step
        // has no gate of its own to attest to.
        return new Applied(ResultEnvelope.queryResult(which.identity(), "read " + which.label(), data,
                ConsistencyEvidence.none()), session);
    }

    /**
     * Apply a refactor op (rename/move, CLI-SPEC §6) to the shared session: run the op's safety gate against
     * the CURRENT (transiently-edited) model — so it sees prior ops' effects — then rewrite every reference +
     * apply the node change, and re-home the result into a fresh session for the next op. A failing
     * precondition throws {@link OperationRefused} → the loop rolls the whole sequence back, nothing written.
     * Validation is deferred to the one terminal gate (apply's invariant), like every other op.
     */
    private Applied applyRefactor(EditSession session, ModelResolver resolver, StructureRefactor r) {
        String modelJson = session.toJson();
        ModelDocument doc = ModelDocument.read(modelJson);
        KernelAdapter adapter = KernelAdapter.ofJson(modelJson, resolver);
        Optional<Refusal> refusal = r.firstFailure(doc, adapter);
        Op which = Op.of(new ParsedSelector(r.target(), r.op()));
        if (which == null) {
            throw new IllegalStateException("an apply refactor must name a declared op: " + r.target() + " " + r.op());
        }
        if (refusal.isPresent()) {
            throw new OperationRefused(which.identity(), refusal.get());
        }
        Set<String> rewrote = r.referrers(adapter, doc);
        EditSession next = EditSession.read(r.rewriteAndMutate(modelJson).toJson(), resolver);
        ObjectNode changed = MAPPER.createObjectNode();
        changed.put(r.changedKey(), r.source());
        changed.put("to", r.newPath());
        ArrayNode refs = changed.putArray("rewroteReferences");
        rewrote.forEach(refs::add);
        return new Applied(ResultEnvelope.staged(which.identity(), "staged " + which.label(),
                changed, List.of()), next);
    }

    /**
     * An op's authoring/safety gate refused before mutation — carried out of {@link #dispatch} as an exception
     * so the loop rolls the whole sequence back, surfacing the same structured corrective diagnostic as the
     * standalone verb.
     */
    private static final class OperationRefused extends RuntimeException {
        private final transient ResultEnvelope envelope;

        OperationRefused(ResultIdentity identity, Refusal refusal) {
            super(refusal.summary());
            this.envelope = ResultEnvelope.refused(identity, refusal.summary(), refusal.data(),
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
            out.add(Diagnostic.precheck(Optional.of("RK_UNKNOWN_OP"), unknownOpSummary(op),
                    new Diagnostic.Where((String) null, Optional.empty()),
                    Optional.of(unknownOpFix(op)),
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
            Optional<String> relocation = MetadataRelocation.fix(which.target, which.op, f);
            out.add(Diagnostic.precheck(Optional.of("RK_UNKNOWN_ARG"),
                    "unknown arg '" + f + "' for op '" + label + "'",
                    Diagnostic.Where.arg(null, f),
                    relocation.isPresent() ? relocation
                            : Optional.of(near != null ? "did you mean '" + near + "'?"
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
    private static final Map<Op, String> NESTED_SCHEMA = Map.of(
            Op.FIELD_ADD, "field-add", Op.FIELD_MODIFY, "field-modify",
            Op.RULE_ADD, "rule-add", Op.RULE_MODIFY, "rule-add",
            Op.COMPUTATION_ADD, "computation-add", Op.COMPUTATION_MODIFY, "computation-add",
            Op.TYPEDEF_ADD, "typedef-add", Op.TYPEDEF_MODIFY, "typedef-add",
            Op.META_MODIFY, "apply#/$defs/metaModify");

    /** The supported {@code target op} labels, sorted — for the unknown-op diagnostic. */
    private static String supportedOps() {
        TreeSet<String> labels = new TreeSet<>();
        for (Op o : Op.values()) {
            labels.add(o.label());
        }
        return String.join(", ", labels);
    }

    /** The op whose {@link Op#label} is exactly {@code text}, or {@code null} — asked of the enum, not of JSON. */
    private static Op labelOf(String text) {
        for (Op o : Op.values()) {
            if (o.label().equals(text)) {
                return o;
            }
        }
        return null;
    }

    /**
     * The unknown-op summary, which must quote what the author WROTE.
     *
     * <p>It used to concatenate {@code target + " " + op} unconditionally, so a record carrying only
     * {@code {"op": "rule add"}} was reported as {@code op ' rule add'} — a value with a leading space that
     * appears nowhere in the input. Measured 2026-09-04: reading that, one agent could not tell whether the
     * space was its own typo, the tool's formatting, or a real parse of its file.
     */
    private static String unknownOpSummary(JsonNode op) {
        String target = op.path("target").asText("");
        String verb = op.path("op").asText("");
        boolean hasTarget = op.hasNonNull("target");
        if (target.isEmpty() && verb.isEmpty()) {
            return hasTarget || op.hasNonNull("op")
                    ? "this op-record's target/op pair is empty"
                    : "this op-record names no target/op pair";
        }
        if (target.isEmpty()) {
            return "apply does not support op '" + verb + "' with " + (hasTarget ? "an empty" : "no") + " target";
        }
        if (verb.isEmpty()) {
            // Never `target + " " + verb` here: that printed `op 'rule '`, a trailing-space value the author
            // did not write — the same defect as the leading-space case below, found by a cold review one
            // commit after it was fixed in only one direction.
            return "apply does not support target '" + target + "' with no op";
        }
        if (labelOf(verb) != null) {
            // `op` already holds a whole label. Concatenating produced `op 'rule rule add'`, a value assembled
            // from two keys and quoted as if written — so each key is quoted as itself and the fix splits it.
            return "apply does not support target '" + target + "' with op '" + verb + "'";
        }
        return "apply does not support op '" + target + " " + verb + "'";
    }

    /**
     * …and the corrective, which must not hand back a spelling the tool then refuses.
     *
     * <p>{@link #supportedOps} lists SPACE-JOINED labels ({@code rule add}), while an op-record spells the same
     * op as two keys ({@code {"target":"rule","op":"add"}}). Offering the label alone as the answer to a bad
     * {@code op} value therefore CONFIRMS the mistake it is correcting — the exact loop that cost this session
     * a run on 2026-09-04, where {@code {"op":"rule add"}} was refused and then told "supported ops: …, rule
     * add, …". When the value the author put in {@code op} is itself a valid label, the corrective splits it
     * for them; otherwise it names the shape before listing the labels.
     */
    private static String unknownOpFix(JsonNode op) {
        String verb = op.path("op").asText("");
        // The split hint fires whenever `op` HOLDS a label, whether or not a target came with it: an author who
        // wrote both {"target":"rule","op":"rule add"} has demonstrably confused the two spellings, and that is
        // exactly when withholding the correction is worst. (It was withheld there until a cold review, because
        // the branch was gated on the target being absent.)
        if (labelOf(verb) != null) {
            String[] halves = verb.split(" ", 2);
            return "an op-record spells that as TWO keys: {\"target\": \"" + halves[0] + "\", \"op\": \""
                    + halves[1] + "\", …} — the space-joined form is the label, not the `op` value";
        }
        // The list leads because a reader truncating this string is looking for the valid set; calling them
        // "(target op) pairs" is what stops the space-joined label from reading as an `op` value.
        return "supported (target op) pairs: " + supportedOps()
                + " — an op-record spells one as {\"target\": …, \"op\": …, …args}";
    }

    /** A failing op-arg envelope (the offending op rolled the sequence back). */
    private ResultEnvelope argRejection(JsonNode op, int index, List<Diagnostic> diagnostics) {
        return ResultEnvelope.rejected(ParsedSelector.from(op).identity(),
                "op " + index + " has an invalid arg — sequence rolled back", diagnostics,
                RejectionBasis.preflight());
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
     * Apply optional add-time rule metadata from {@code op}. Post-creation metadata is owned exclusively by
     * {@code META_MODIFY} (G45); this helper remains only because creation metadata is part of one complete
     * {@code RULE_ADD} operation.
     */
    private static void metadataOps(JsonNode op, EditSession session, String path, ObjectNode changed) {
        if (op.hasNonNull("annotation")) {
            var it = op.get("annotation").fields();
            while (it.hasNext()) {
                var e = it.next();
                session.setAnnotation(path, e.getKey(), e.getValue().asText());
                changed.put("annotation." + e.getKey(), e.getValue().asText());
            }
        }
        if (op.hasNonNull("internal-description")) {
            var it = op.get("internal-description").fields();
            while (it.hasNext()) {
                var e = it.next();
                session.setInternalDescription(path, e.getKey(), e.getValue().asText());
                changed.put("internalDescription." + e.getKey(), e.getValue().asText());
            }
        }
        if (op.hasNonNull("external-description")) {
            var it = op.get("external-description").fields();
            while (it.hasNext()) {
                var e = it.next();
                session.setExternalDescription(path, e.getKey(), e.getValue().asText());
                changed.put("externalDescription." + e.getKey(), e.getValue().asText());
            }
        }
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
        LinkedHashMap<String, String> merged = new LinkedHashMap<>();
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
        return ResultEnvelope.rejected(ParsedSelector.from(op).identity(),
                "op " + index + " failed — sequence rolled back", List.of(d),
                RejectionBasis.preflight());
    }

    /**
     * The terminal gate's rejection: {@code valid:false} plus the arbitrator that decided it (the previous shape
     * reported the verdict but never who reached it). Built by the shared {@link BaseCommand} path — this method was a
     * verbatim copy of it, so a correction to the notification-free tier landed there and not here.
     */
    private ResultEnvelope gateRejection(ModelConsistencyException e) {
        return BaseCommand.kernelRejectionEnvelope(ResultIdentity.MODEL_VALIDATE,
                "the terminal kernel gate rejected the sequence — rolled back", e);
    }
}
