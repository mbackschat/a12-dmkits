package io.github.mbackschat.a12.dm.cli.rule;

import io.github.mbackschat.a12.dm.cli.BaseCommand;
import io.github.mbackschat.a12.dm.cli.ConsistencyEvidence;
import io.github.mbackschat.a12.dm.cli.ParamKind;
import io.github.mbackschat.a12.dm.cli.Refusal;
import io.github.mbackschat.a12.dm.cli.ResultEnvelope;
import io.github.mbackschat.a12.dm.cli.SafetyChecks;
import io.github.mbackschat.a12.dm.cli.SafetyGate;
import io.github.mbackschat.a12.dm.cli.WorkspaceOption;

import java.util.List;
import java.util.Optional;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import io.github.mbackschat.a12.dm.adapter.KernelAdapter;
import io.github.mbackschat.a12.dm.rulekit.read.ReadResult;
import io.github.mbackschat.a12.dm.rulekit.context.ModelContext;
import io.github.mbackschat.a12.dm.rulekit.dsl.BoundModel;
import picocli.CommandLine.Command;
import picocli.CommandLine.Mixin;
import picocli.CommandLine.Parameters;

/**
 * {@code dmtool rule deps <rulePath>} — a rule's <b>forward dependencies</b>: the fields/groups its condition
 * directly references (each with its model {@code kind}) plus whether it iterates a repeatable group. A
 * focused projection of {@code rule explain} — same reference set, smaller payload — for the question "what
 * does this one rule touch?" without the operator glossary / structural tree / NL gloss. The rough inverse —
 * "what references this field?" — is {@code where-used}.
 *
 * <p><b>They are NOT backed by the same primitive, despite what this javadoc claimed until 2026-08-14.</b>
 * {@code where-used} reads the kernel's {@code getDirectlyReferencedEntities}; this verb projects
 * {@code ModelContext.explain(converted.condition())} — the <em>converted</em> rulekit AST (see the
 * {@code references} assignment below). Whether the two therefore produce different answers is <b>not
 * measured</b>; only the "same primitive" premise is refuted, by reading. Tracked in {@code CG16}
 * (CLI-SPEC §6, KERNEL-FINDINGS §9).
 * A read op: the payload rides {@code data}. A condition the converter doesn't model comes back tolerant as
 * {@code explained:false} + reason (never an error) — use {@code where-used} for the robust inverse there.
 */
@Command(name = "deps", mixinStandardHelpOptions = true,
        description = "List a rule's forward dependencies — the fields/groups its condition references (+ iteration).")
final class RuleDepsCommand extends BaseCommand {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @ParamKind(ParamKind.Kind.RULE_PATH)
    @Parameters(index = "0", paramLabel = "<rulePathInModel>",
            description = "the rule's full name — its slash-path in the model, e.g. /Order/SomeRule")
    String rule;

    @Mixin
    WorkspaceOption workspace = new WorkspaceOption();

    @Override
    public Integer call() {
        String model = model();
        String json = readModelJson(model);
        if (json == null) {
            return 2;
        }
        try {
            BoundModel bound = BoundModel.ofJson(json, workspace.resolverFor(model));
            KernelAdapter adapter = KernelAdapter.of(bound);
            // an unknown rule is a coded, structured refusal (AP1 — never a bare stderr error), exactly as
            // `rule remove` gates it; the conformance fuzzer holds every entity-ref verb to this.
            Optional<Refusal> refusal = new SafetyGate()
                    .require(SafetyChecks.ruleExists(adapter, rule))
                    .firstFailure();
            if (refusal.isPresent()) {
                return refuse(refusal.get());
            }
            ReadResult read = adapter.read(rule);
            ObjectNode data = MAPPER.createObjectNode();
            data.put("rule", rule);
            String summary;
            // Exhaustive over the sealed read: the cast this replaced meant "not Converted is Opaque", which
            // a third arm turns into a ClassCastException on a rule the kernel accepts. Both unconverted
            // arms give the same advice — `where-used` is the inverse that needs no typed condition — and
            // the refused one additionally names its code, so a consumer can tell them apart (DG1001).
            switch (read) {
                case ReadResult.Converted converted -> {
                    // Project the same explanation rule explain emits, keeping only the dependency facets.
                    JsonNode explanation =
                            readGeneratedJson(new ModelContext(bound).explain(converted.condition()).toJson());
                    JsonNode references = explanation.get("references");
                    data.set("references", references);
                    data.set("iterates", explanation.get("iterates"));
                    if (explanation.has("iterationGroup")) {
                        data.set("iterationGroup", explanation.get("iterationGroup"));
                    }
                    data.put("count", references == null ? 0 : references.size());
                    summary = "dependencies of " + rule;
                }
                case ReadResult.Opaque opaque -> {
                    data.put("explained", false);
                    data.put("reason", opaque.reason());
                    data.put("canonicalText", opaque.canonicalText());
                    summary = rule + " is Opaque (not modeled) — use `where-used` for the robust inverse";
                }
                case ReadResult.Refused refused -> {
                    data.put("explained", false);
                    data.put("reason", refused.reason());
                    data.put("canonicalText", refused.canonicalText());
                    data.put("refusedCode", refused.code().name());
                    summary = rule + " is Refused (" + refused.code().name()
                            + ") — use `where-used` for the robust inverse";
                }
            }
            // `bound` is a gated ofJson bind; the adapter beside it is KernelAdapter.of (gate SKIPped), so the
            // BINDING is the witness here — the discriminating pair the derivation rule exists for.
            ResultEnvelope env = ResultEnvelope.queryResult(resultIdentity(), summary, data,
                    ConsistencyEvidence.of(bound));
            out().print(env.toJsonString());
            return env.exitCode();
        } catch (RuntimeException e) {
            throw withFailureContext("deps failed: ", e);
        }
    }
}
