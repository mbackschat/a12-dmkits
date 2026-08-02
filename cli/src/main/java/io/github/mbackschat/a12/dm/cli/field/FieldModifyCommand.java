package io.github.mbackschat.a12.dm.cli.field;

import io.github.mbackschat.a12.dm.cli.BaseCommand;
import io.github.mbackschat.a12.dm.cli.ConsistencyEvidence;
import io.github.mbackschat.a12.dm.cli.ModelWrite;
import io.github.mbackschat.a12.dm.cli.Refusal;
import io.github.mbackschat.a12.dm.cli.SafetyChecks;
import io.github.mbackschat.a12.dm.cli.SafetyGate;
import io.github.mbackschat.a12.dm.cli.WorkspaceOption;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.mbackschat.a12.dm.core.ModelResolver;

import io.github.mbackschat.a12.dm.adapter.KernelAdapter;
import io.github.mbackschat.a12.dm.core.ModelConsistencyException;
import io.github.mbackschat.a12.dm.modelkit.FieldModification;
import io.github.mbackschat.a12.dm.modelkit.ModelDocument;
import io.github.mbackschat.a12.dm.rulekit.validate.Diagnostic;
import io.github.mbackschat.a12.dm.rulekit.validate.RkCode;
import picocli.CommandLine.Command;
import picocli.CommandLine.Mixin;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

/**
 * {@code dmtool field modify <spec.json>} — change an existing field <b>in place</b> and write the model
 * back. The field is located by the spec's {@code group} + {@code name}; it is a <b>partial</b> edit — every
 * aspect the spec carries is applied, every absent one is left untouched: a {@code kind}/{@code typedef}
 * re-types it (add a STRING {@code pattern}, tighten a NUMBER's scale, …), and field-specific properties
 * change requiredness, helper text, required messages, or the global/transient flags. Common metadata uses
 * {@code meta <ref>} (G45). An aspect the spec carries is never silently dropped (a spec that would change
 * nothing is refused). Its directional contract is {@code schema field modify}; the result is re-bound
 * through the kernel, so a kernel-invalid change is a {@code rejected} envelope (nothing written). In place
 * by default; {@code -o} redirects, {@code --dry-run} previews.
 */
@Command(name = "modify", mixinStandardHelpOptions = true,
        description = "Change a field in place via a <spec.json> — re-type (kind + config) and/or edit field "
                + "properties (required/helperText/requiredMessages/global/transient); use `meta <ref>` for "
                + "common metadata. Partial, no delete-and-recreate; -o redirects, --dry-run previews. "
                + "A KIND change warns which rules/computations read the field.")
final class FieldModifyCommand extends BaseCommand {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Parameters(index = "0", paramLabel = "<spec.json>",
            description = "a rich field spec (JSON): group + name locate the field; kind + per-kind config "
                    + "become its new type (see `schema field modify`)")
    String specPath;

    @Option(names = {"--output", "-o"}, paramLabel = "<file>",
            description = "write the result here instead of in place (overwriting the model)")
    String outputPath;

    @Option(names = "--dry-run", description = "preview only — write NOTHING")
    boolean dryRun;

    @Mixin
    WorkspaceOption workspace = new WorkspaceOption();

    @Override
    public Integer call() {
        String model = model();
        if (model == null) {
            return fail("pass -m <model.json> (before or after the verb)");
        }
        if (specPath == null) {
            return fail("pass a <spec.json> (group + name locate the field; kind + config become its new type)");
        }
        String modelJson;
        try {
            modelJson = Files.readString(Path.of(model));
        } catch (IOException e) {
            return fail("cannot read model: " + e.getMessage());
        }
        ModelResolver resolver = workspace.resolverFor(model);
        ModelDocument doc = ModelDocument.read(modelJson);

        String fieldPath;
        ObjectNode changed = MAPPER.createObjectNode();
        List<Diagnostic> advisories;
        try {
            JsonNode spec = MAPPER.readTree(Files.readString(Path.of(specPath)));
            Optional<Integer> rejected = rejectUnknownKeys("field-modify", spec);
            if (rejected.isPresent()) {
                return rejected.get();
            }
            FieldSpec.ModificationSpec parsed = FieldSpec.parseModification(spec);
            FieldModification mod = parsed.modification();
            // an orphaned per-kind config block (no `kind`/`typedef` to select it) can't take effect — reject it
            // whether or not OTHER aspects are present, so it is never SILENTLY DROPPED (05-modify-drop: a
            // co-present `label` made the modify non-empty, masking the dropped `string`/`number` block). The
            // empty-modify case shares the same context-aware corrective.
            if (FieldSpec.configBlockWithoutKind(spec) != null || mod.isEmpty()) {
                return fail(FieldSpec.emptyModificationCorrective(spec));
            }
            fieldPath = parsed.group() + "/" + parsed.name();
            // the field-exists gate runs first, so modifying a missing field gets the structured refusal
            // (not delete-and-recreate, not a kernel stack trace)
            Optional<Refusal> refusal = new SafetyGate()
                    .require(SafetyChecks.exists(doc, fieldPath, false))
                    .firstFailure();
            if (refusal.isPresent()) {
                return refuse(refusal.get());
            }
            // a KIND change (vs a within-kind config tweak or a field-property-only edit) can invalidate conditions
            // that read the field; surface the readers up front — only when re-typing. The kernel re-validate
            // stays the hard gate (§6).
            advisories = mod.type().isPresent()
                    ? reTypeAdvisories(modelJson, resolver, fieldPath,
                            doc.fieldType(fieldPath).orElse(null), mod.type().get().jsonType())
                    : List.of();
            doc.applyModification(fieldPath, mod);
            changed.put("modified", fieldPath);
            if (mod.type().isPresent()) {
                if (spec.hasNonNull("typedef")) {
                    changed.put("typedef", spec.get("typedef").asText());
                }
                // echo the new persisted kind through the resolver (clean ValueKind name, typedef → underlying)
                changed.put("kind", doc.fieldKind(fieldPath).orElseThrow());
            }
            ArrayNode aspects = changed.putArray("aspects");
            mod.changedAspects().forEach(aspects::add);
        } catch (IllegalArgumentException badSpec) {
            return fail("invalid field spec: " + badSpec.getMessage());
        } catch (IOException e) {
            return fail("cannot read field spec: " + e.getMessage());
        }

        String resultJson;
        try {
            resultJson = doc.validate(resolver).toJson();
        } catch (ModelConsistencyException e) {
            return emitKernelRejection("the change is rejected by the kernel", e);
        } catch (RuntimeException e) {
            return failEditGate(e);
        }
        return emitMutation(ModelWrite.of(resultIdentity())
                .summaries("would modify " + fieldPath,
                        "modified " + fieldPath)
                .changed(changed)
                .advisories(advisories)
                .json(resultJson)
                .gatedBy(ConsistencyEvidence.of(doc))
                .dryRun(dryRun)
                .destination(outputPath, model));
    }

    /**
     * The reader advisory for a re-type: empty unless the re-type changes the field's KIND (the DM-JSON
     * {@code fieldType.type} discriminator) <em>and</em> a rule/computation reads the field. A WARNING, not a
     * refusal — a kind change with only kind-agnostic readers (e.g. {@code FieldFilled}) is fine, and the
     * kernel re-validate authoritatively rejects any that genuinely break.
     */
    private static List<Diagnostic> reTypeAdvisories(String modelJson, ModelResolver resolver,
            String fieldPath, String oldType, String newType) {
        if (oldType == null || oldType.equals(newType)) {
            return List.of();
        }
        KernelAdapter adapter = KernelAdapter.ofJson(modelJson, resolver);
        Set<String> readers = new TreeSet<>(adapter.whereUsed(fieldPath));
        readers.addAll(adapter.whereUsedInComputations(fieldPath));
        if (readers.isEmpty()) {
            return List.of();
        }
        Diagnostic advisory = Diagnostic.precheckWarning(
                Optional.of(RkCode.RETYPE_AFFECTS_READERS.code()),
                "re-typing " + fieldPath + " (" + oldType + " → " + newType + ") changes its kind; "
                        + readers.size() + " rule(s)/computation(s) read it: " + readers,
                Diagnostic.Where.path(fieldPath),
                Optional.of(RkCode.RETYPE_AFFECTS_READERS.fix()),
                Optional.empty());
        return List.of(advisory);
    }
}
