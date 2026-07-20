package io.github.mbackschat.a12.dm.cli.rule;

import io.github.mbackschat.a12.dm.cli.BaseCommand;
import io.github.mbackschat.a12.dm.cli.ParamKind;
import io.github.mbackschat.a12.dm.cli.Refusal;
import io.github.mbackschat.a12.dm.cli.ResultEnvelope;
import io.github.mbackschat.a12.dm.cli.SafetyChecks;
import io.github.mbackschat.a12.dm.cli.SafetyGate;
import io.github.mbackschat.a12.dm.cli.WorkspaceOption;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
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
 * does this one rule touch?" without the operator glossary / structural tree / NL gloss. The exact inverse —
 * "what references this field?" — is {@code where-used}; the same kernel primitive
 * ({@code getDirectlyReferencedEntities}) backs both, so they can't disagree (CLI-SPEC §6, KERNEL-FINDINGS §9).
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
        if (model == null) {
            return fail("pass -m <model.json> (before or after the verb)");
        }
        String json;
        try {
            json = Files.readString(Path.of(model));
        } catch (IOException e) {
            return fail("cannot read model: " + e.getMessage());
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
                return refuse("rule", "deps", refusal.get());
            }
            ReadResult read = adapter.read(rule);
            ObjectNode data = MAPPER.createObjectNode();
            data.put("rule", rule);
            String summary;
            if (read instanceof ReadResult.Converted converted) {
                // Project the same explanation rule explain emits, keeping only the dependency facets.
                JsonNode explanation = MAPPER.readTree(new ModelContext(bound).explain(converted.condition()).toJson());
                JsonNode references = explanation.get("references");
                data.set("references", references);
                data.set("iterates", explanation.get("iterates"));
                if (explanation.has("iterationGroup")) {
                    data.set("iterationGroup", explanation.get("iterationGroup"));
                }
                data.put("count", references == null ? 0 : references.size());
                summary = "dependencies of " + rule;
            } else {
                ReadResult.Opaque opaque = (ReadResult.Opaque) read;
                data.put("explained", false);
                data.put("reason", opaque.reason());
                data.put("canonicalText", opaque.canonicalText());
                summary = rule + " is Opaque (not modeled) — use `where-used` for the robust inverse";
            }
            ResultEnvelope env = ResultEnvelope.read("rule", "deps", summary, true, List.of(), data);
            out().print(env.toJsonString());
            return env.exitCode();
        } catch (IOException e) {
            return fail("deps failed: " + e.getMessage());
        } catch (RuntimeException e) {
            return failOrRethrowDomainRejection("deps failed: ", e);
        }
    }
}
