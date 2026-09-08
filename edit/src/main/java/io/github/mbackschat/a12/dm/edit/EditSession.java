package io.github.mbackschat.a12.dm.edit;

import java.io.IOException;
import java.io.StringReader;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;

import io.github.mbackschat.a12.dm.core.ModelConsistencyException;
import io.github.mbackschat.a12.dm.core.ModelInputException;
import io.github.mbackschat.a12.dm.core.ModelLoader;
import io.github.mbackschat.a12.dm.core.ModelResolver;
import io.github.mbackschat.a12.dm.core.ReferenceResolvers;
import io.github.mbackschat.a12.dm.core.UnresolvedReferenceException;
import io.github.mbackschat.a12.dm.core.Verification;
import io.github.mbackschat.a12.dm.modelkit.DocumentModelDraft;
import io.github.mbackschat.a12.dm.modelkit.ElementDraft;
import io.github.mbackschat.a12.dm.modelkit.FieldKind;
import io.github.mbackschat.a12.dm.modelkit.FieldModification;
import io.github.mbackschat.a12.dm.modelkit.FieldTypeSpec;
import io.github.mbackschat.a12.dm.modelkit.ModelDocument;
import io.github.mbackschat.a12.dm.modelkit.MountOptions;
import io.github.mbackschat.a12.dm.modelkit.Requiredness;
import io.github.mbackschat.a12.dm.rulekit.dsl.BoundModel;
import io.github.mbackschat.a12.dm.rulekit.dsl.Computation;
import io.github.mbackschat.a12.dm.rulekit.dsl.Condition;
import io.github.mbackschat.a12.dm.rulekit.dsl.LocalizedText;
import io.github.mbackschat.a12.dm.rulekit.dsl.Severity;
import io.github.mbackschat.a12.dm.rulekit.dsl.Rule;
import io.github.mbackschat.a12.dm.rulekit.validate.DraftComputation;
import io.github.mbackschat.a12.dm.rulekit.write.ModelWriter;

/**
 * A single read → edit → write-back session over one document model, composing <b>structure</b> edits
 * (modelkit's {@link ModelDocument}) and <b>rule/computation</b> edits (rulekit's {@link BoundModel}).
 * It is the orchestration layer <em>above</em> the two sibling kits — which, by the {@code dm.modelkit ⊥
 * dm.rulekit} boundary, cannot call each other; this session threads the same DM-JSON through both.
 *
 * <p>The shared rep at the session boundary is the authored DM-JSON: each edit reads the current text,
 * mutates, and writes it back, so structure and rule edits interleave freely (add a field, then a rule
 * that references it). Rule/computation edits re-bind through {@code checkConsistency} as they apply (a
 * bad one fails fast); {@link #validate()} re-confirms the whole model. Includes are preserved.
 */
public final class EditSession {

    private String json;
    private final ModelResolver resolver;

    /** Memoized bind of the current {@code json} (M6): reused across navigations until a write replaces {@code json}. */
    private BoundModel cachedBoundModel;
    private String cachedForJson;

    /** The exact DM-JSON {@link #validate()} last gated, and what it decided — see {@link #consistencyGate()}. */
    private String gatedJson;
    private Verification gatedBy;

    private EditSession(String json, ModelResolver resolver) {
        this.json = json;
        this.resolver = resolver;
    }

    /** Start a session over a self-contained model. */
    public static EditSession read(String dmJson) {
        return new EditSession(dmJson, ReferenceResolvers.selfContained());
    }

    /** Start a session over a model with includes / type-def imports, resolved via a <b>kernel-free</b>
     *  {@link ModelResolver} ({@code id → DM-JSON}) — the common multi-model case without touching
     *  {@code com.mgmtp.a12.kernel.*}. */
    public static EditSession read(String dmJson, ModelResolver resolver) {
        return new EditSession(dmJson, resolver);
    }

    // --- structure edits (modelkit) -------------------------------------------------------------

    /** Apply one modelkit structure edit to the current DM-JSON and write it back (the shared rep). */
    private EditSession editStructure(Function<ModelDocument, ModelDocument> op) {
        json = op.apply(ModelDocument.read(json)).toJson();
        return this;
    }

    public EditSession addField(String groupPath, String name, FieldKind kind) {
        return editStructure(d -> d.addField(groupPath, name, kind));
    }

    public EditSession addField(String groupPath, String name, FieldTypeSpec type) {
        return editStructure(d -> d.addField(groupPath, name, type));
    }

    /** Add a fully-configured field — type + requiredness + labels + annotations + descriptions (built by a
     *  per-type field builder, e.g. via {@link GroupEditor#numberField}). */
    public EditSession addField(String groupPath, ElementDraft.Field field) {
        return editStructure(d -> d.addField(groupPath, field));
    }

    public EditSession setFieldType(String fieldPath, FieldTypeSpec type) {
        return editStructure(d -> d.setFieldType(fieldPath, type));
    }

    /**
     * Apply a partial field edit (type and/or metadata) — the MODIFY peer of {@link #addField}. Each present
     * aspect of {@code modification} is set, each absent one left as-is (see {@link ModelDocument#applyModification}).
     */
    public EditSession modifyField(String fieldPath, FieldModification modification) {
        return editStructure(d -> d.applyModification(fieldPath, modification));
    }

    public EditSession setRequiredness(String fieldPath, Requiredness requiredness) {
        return editStructure(d -> d.setRequiredness(fieldPath, requiredness));
    }

    /** Set the per-locale custom error shown when a REQUIRED field is left empty (G23); the field must already
     *  be required, and every declared locale must be covered. */
    public EditSession setRequiredErrorMessages(String fieldPath, Map<String, String> localeToText) {
        return editStructure(d -> d.setRequiredErrorMessages(fieldPath, localeToText));
    }

    /** Set a field's per-locale helper text (need not cover every locale, unlike a label). */
    public EditSession setHelperText(String fieldPath, Map<String, String> localeToText) {
        return editStructure(d -> d.setHelperText(fieldPath, localeToText));
    }

    /** Set a field's {@code global} flag (shared across repetitions); {@code false} clears it (the kernel default). */
    public EditSession setGlobal(String fieldPath, boolean global) {
        return editStructure(d -> d.setGlobal(fieldPath, global));
    }

    /** Set a field's {@code transient} flag (not persisted); {@code false} clears it (the kernel default). */
    public EditSession setTransient(String fieldPath, boolean isTransient) {
        return editStructure(d -> d.setTransient(fieldPath, isTransient));
    }

    public EditSession setLabel(String elementPath, Map<String, String> localeToText) {
        return editStructure(d -> d.setLabel(elementPath, localeToText));
    }

    public EditSession setAnnotation(String elementPath, String name, String value) {
        return editStructure(d -> d.setAnnotation(elementPath, name, value));
    }

    /** Remove the annotation {@code name} from a describable element/type definition. */
    public EditSession removeAnnotation(String elementPath, String name) {
        return editStructure(d -> d.removeAnnotation(elementPath, name));
    }

    /** Upsert one per-locale {@code internalDescription} on a describable element/type definition. */
    public EditSession setInternalDescription(String elementPath, String locale, String text) {
        return editStructure(d -> d.setInternalDescription(elementPath, locale, text));
    }

    /** Upsert one per-locale {@code externalDescription} on the element at {@code elementPath}. */
    public EditSession setExternalDescription(String elementPath, String locale, String text) {
        return editStructure(d -> d.setExternalDescription(elementPath, locale, text));
    }

    /** Remove the {@code internalDescription} for {@code locale} from the element at {@code elementPath}. */
    public EditSession removeInternalDescription(String elementPath, String locale) {
        return editStructure(d -> d.removeInternalDescription(elementPath, locale));
    }

    /** Remove the {@code externalDescription} for {@code locale} from the element at {@code elementPath}. */
    public EditSession removeExternalDescription(String elementPath, String locale) {
        return editStructure(d -> d.removeExternalDescription(elementPath, locale));
    }

    public EditSession addGroup(String parentPath, String name) {
        return editStructure(d -> d.addGroup(parentPath, name));
    }

    public EditSession addRepeatableGroup(String parentPath, String name, int maxRepetitions) {
        return editStructure(d -> d.addRepeatableGroup(parentPath, name, maxRepetitions));
    }

    public EditSession removeField(String fieldPath) {
        return editStructure(d -> d.removeField(fieldPath));
    }

    public EditSession removeGroup(String groupPath) {
        return editStructure(d -> d.removeGroup(groupPath));
    }

    public EditSession setRepeatable(String groupPath, int maxRepetitions) {
        return editStructure(d -> d.setRepeatable(groupPath, maxRepetitions));
    }

    public EditSession setNonRepeatable(String groupPath) {
        return editStructure(d -> d.setNonRepeatable(groupPath));
    }

    public EditSession setIndexField(String groupPath, String fieldName) {
        return editStructure(d -> d.setIndexField(groupPath, fieldName));
    }

    public EditSession clearIndexField(String groupPath) {
        return editStructure(d -> d.clearIndexField(groupPath));
    }

    public EditSession setSortFields(String groupPath, List<ElementDraft.SortField> fields) {
        return editStructure(d -> d.setSortFields(groupPath, fields));
    }

    public EditSession clearSortFields(String groupPath) {
        return editStructure(d -> d.clearSortFields(groupPath));
    }

    public EditSession addTypeDefinition(String id, FieldKind underlying) {
        return editStructure(d -> d.addTypeDefinition(id, underlying));
    }

    public EditSession addTypeDefinition(String id, FieldTypeSpec underlying) {
        return editStructure(d -> d.addTypeDefinition(id, underlying));
    }

    public EditSession addTypedefField(String groupPath, String name, String typeDefId) {
        return editStructure(d -> d.addTypedefField(groupPath, name, typeDefId));
    }

    public EditSession setTypeDefinitionType(String id, FieldTypeSpec type) {
        return editStructure(d -> d.setTypeDefinitionType(id, type));
    }

    public EditSession renameField(String fieldPath, String newName) {
        return editStructure(d -> d.renameField(fieldPath, newName));
    }

    public EditSession renameGroup(String groupPath, String newName) {
        return editStructure(d -> d.renameGroup(groupPath, newName));
    }

    public EditSession renameRule(String ruleFullName, String newName) {
        return editStructure(d -> d.renameRule(ruleFullName, newName));
    }

    public EditSession moveField(String fieldPath, String newParentPath) {
        return editStructure(d -> d.moveField(fieldPath, newParentPath));
    }

    public EditSession moveGroup(String groupPath, String newParentPath) {
        return editStructure(d -> d.moveGroup(groupPath, newParentPath));
    }

    public EditSession renameTypeDefinition(String oldId, String newId) {
        return editStructure(d -> d.renameTypeDefinition(oldId, newId));
    }

    public EditSession extractTypeDefinition(String newId, List<String> fieldPaths) {
        return editStructure(d -> d.extractTypeDefinition(newId, fieldPaths));
    }

    public EditSession inlineTypeDefinition(String id) {
        return editStructure(d -> d.inlineTypeDefinition(id));
    }

    public EditSession removeTypeDefinition(String id) {
        return editStructure(d -> d.removeTypeDefinition(id));
    }

    public EditSession setDecimalSeparator(String separator) {
        return editStructure(d -> d.setDecimalSeparator(separator));
    }

    /** Rename the model itself (its {@code content.modelRoot} id / header id). */
    public EditSession setModelId(String newId) {
        return editStructure(d -> d.setModelId(newId));
    }

    public EditSession setModelLabel(String locale, String text) {
        return editStructure(d -> d.setModelLabel(locale, text));
    }

    /** Upsert a model-level header annotation. */
    public EditSession setModelAnnotation(String name, String value) {
        return editStructure(d -> d.setModelAnnotation(name, value));
    }

    /** Set the model-level {@code comment} ({@code modelInfo.comment} — a free-text model note). */
    public EditSession setModelComment(String text) {
        return editStructure(d -> d.setModelComment(text));
    }

    /** Remove the model-level {@code comment} ({@code modelInfo.comment}) — no-op if absent. */
    public EditSession clearModelComment() {
        return editStructure(d -> d.clearModelComment());
    }

    /** Remove a model-level header annotation (no-op if absent). */
    public EditSession removeModelAnnotation(String name) {
        return editStructure(d -> d.removeModelAnnotation(name));
    }

    /** Register a display locale on the model ({@code header.locales}; idempotent). */
    public EditSession addLocale(String code) {
        return editStructure(d -> d.addLocale(code));
    }

    /** Remove a display locale from the model (the inverse of {@link #addLocale}; refuses the last one). */
    public EditSession removeLocale(String code) {
        return editStructure(d -> d.removeLocale(code));
    }

    /** Read the model-level header annotations (name→value) — the read peer of {@link #setModelAnnotation}. */
    public java.util.Map<String, String> modelAnnotations() {
        return ModelDocument.read(json).modelAnnotations();
    }

    /** Read the model's registered display locales ({@code header.locales}) — the read peer of {@link #addLocale}. */
    public List<String> locales() {
        return ModelDocument.read(json).locales();
    }

    /**
     * Set {@code modelConfig.timeZone}: UTC/GMT/Europe/Berlin are interpreter-supported; another known Java
     * id remains kernel-valid but evaluator-unsupported, and null/blank/unknown ids fail the kernel gate.
     * Omitting the slot, rather than setting an empty value, selects the UTC default.
     */
    public EditSession setTimeZone(String timeZone) {
        return editStructure(d -> d.setTimeZone(timeZone));
    }

    public EditSession setConditionLanguage(String localeCode) {
        return editStructure(d -> d.setConditionLanguage(localeCode));
    }

    public EditSession setFieldRefByShortNameAllowed(boolean allowed) {
        return editStructure(d -> d.setFieldRefByShortNameAllowed(allowed));
    }

    public EditSession setSupportedCharacters(List<String> chars) {
        return editStructure(d -> d.setSupportedCharacters(chars));
    }

    public EditSession setBaseYear(int year) {
        return editStructure(d -> d.setBaseYear(year));
    }

    /** Remove the model base year — the inverse of {@link #setBaseYear}. */
    public EditSession clearBaseYear() {
        return editStructure(ModelDocument::clearBaseYear);
    }

    public EditSession addInclude(String alias, String reference, String parentPath, String name) {
        return editStructure(d -> d.addInclude(alias, reference).mount(parentPath, name, alias));
    }

    /** Declare a header include ({@code alias} → {@code reference}) without mounting it yet — mount via {@link #include(String)}. */
    public EditSession addInclude(String alias, String reference) {
        return editStructure(d -> d.addInclude(alias, reference));
    }

    /** Import the type definitions of {@code reference} ({@code purpose: "typeDefinitions"}); merged by id, not mounted. */
    public EditSession addImport(String reference) {
        return editStructure(d -> d.addImport(reference));
    }

    /** Remove a type-definition import (the inverse of {@link #addImport}); fails fast if not declared. */
    public EditSession removeImport(String reference) {
        return editStructure(d -> d.removeImport(reference));
    }

    /** Add a Multi-Select group template (FEATURE-GAPS) under {@code parentPath}; see {@link ModelDocument#addMultiSelectGroup}. */
    public EditSession addMultiSelectGroup(String parentPath, String name,
            java.util.function.Consumer<DocumentModelDraft.MultiSelectBuilder> body) {
        return editStructure(d -> d.addMultiSelectGroup(parentPath, name, body));
    }

    /** Add an Attachment group template (FEATURE-GAPS) under {@code parentPath}; see {@link ModelDocument#addAttachmentGroup}. */
    public EditSession addAttachmentGroup(String parentPath, String name,
            java.util.function.Consumer<DocumentModelDraft.AttachmentBuilder> body) {
        return editStructure(d -> d.addAttachmentGroup(parentPath, name, body));
    }

    public EditSession mount(String parentPath, String name, String alias) {
        return editStructure(d -> d.mount(parentPath, name, alias));
    }

    /** Mount a declared include as a repeatable sub-form ({@code repeatability} rows; FEATURE-GAPS). */
    public EditSession mount(String parentPath, String name, String alias, int repeatability) {
        return editStructure(d -> d.mount(parentPath, name, alias, repeatability));
    }

    /** Mount a declared include with explicit {@link MountOptions} (repeatability + rule/computation exclusions). */
    public EditSession mount(String parentPath, String name, String alias, MountOptions options) {
        return editStructure(d -> d.mount(parentPath, name, alias, options));
    }

    public EditSession removeInclude(String alias) {
        return editStructure(d -> d.removeInclude(alias));
    }

    /**
     * Extract the group at {@code groupPath} into a standalone sub-model (returned as DM-JSON), replacing it
     * in this session's model with a mount at the same name (task #7). The session now holds the slimmed
     * base; validating it needs a resolver that serves the returned sub-model under id {@code reference}.
     */
    public String extractGroupAsInclude(String groupPath, String reference) {
        ModelDocument doc = ModelDocument.read(json);
        String subModel = doc.extractGroupAsInclude(groupPath, reference);
        json = doc.toJson();
        return subModel;
    }

    /**
     * Inline the include aliased {@code alias} — the inverse of {@link #extractGroupAsInclude}: fold the
     * included model's content (supplied as {@code includedModelJson} — the caller's, since this session is
     * resolver-agnostic) back into every mount as a real group, then drop the header reference.
     */
    public EditSession inlineInclude(String alias, String includedModelJson) {
        return editStructure(d -> d.inlineInclude(alias, includedModelJson));
    }

    public EditSession setIncludeReference(String alias, String newReference) {
        return editStructure(d -> d.setIncludeReference(alias, newReference));
    }

    public EditSession renameInclude(String oldAlias, String newAlias) {
        return editStructure(d -> d.renameInclude(oldAlias, newAlias));
    }

    // --- L2c: the session-bound editor (refs as currency) ---------------------------------------

    /**
     * A session-bound {@link GroupEditor} on the group at {@code path} — the L2c editing idiom: fluent
     * structure edits that chain, relative navigation to a pure ref for any child (existing or
     * just-created), and an explicit downgrade to the pure {@code GroupRef} for the AST. Fails fast if
     * {@code path} is not a group in the current model.
     */
    public GroupEditor group(String path) {
        return new GroupEditor(this, boundModel().group(path));
    }

    /**
     * A session-bound {@link FieldEditor} on the field at {@code path} — re-type / label / annotate it,
     * then downgrade via {@link FieldEditor#ref()}. Fails fast if {@code path} is not a field.
     */
    public FieldEditor field(String path) {
        return new FieldEditor(this, boundModel().field(path));
    }

    /**
     * A held reference to the rule at {@code fullName} (group-path + name) — re-express its condition or
     * remove it without re-typing the path. The rule's existence is checked when the edit applies.
     */
    public RuleEditor rule(String fullName) {
        return new RuleEditor(this, fullName);
    }

    /**
     * A held reference to the computation at {@code fullName} (group-path + name) — remove it without
     * re-typing the path. Existence is checked when the edit applies.
     */
    public ComputationEditor computation(String fullName) {
        return new ComputationEditor(this, fullName);
    }

    /** A single chaining handle over the model's doc-config — the {@code setX} verbs under one surface. */
    public ConfigEditor config() {
        return new ConfigEditor(this);
    }

    /** A held reference to the type definition {@code id} — re-configure its type or remove it. */
    public TypeDefEditor typeDef(String id) {
        return new TypeDefEditor(this, id);
    }

    /** A held reference to the declared include {@code alias} — mount it (again) or remove it. */
    public IncludeEditor include(String alias) {
        return new IncludeEditor(this, alias);
    }

    /** The model bound to the current state — navigation and rule edits re-read this after each edit.
     *  Memoized per unchanged JSON (M6): a {@link BoundModel} is an immutable snapshot, so navigations
     *  between writes reuse one kernel bind (measured ~28 ms each on a 200-field model). Every edit
     *  reassigns {@code json} to a fresh string, so reference identity detects the change and rebinds —
     *  snapshot semantics are unchanged. */
    BoundModel boundModel() {
        if (cachedBoundModel == null || json != cachedForJson) {
            cachedBoundModel = BoundModel.ofJson(json, resolver);
            cachedForJson = json;
        }
        return cachedBoundModel;
    }

    // --- rule / computation edits (rulekit) -----------------------------------------------------

    /** Add a rule; {@code builder} receives the model bound to the current state to author against it. */
    public EditSession addRule(Function<BoundModel, Rule> builder) {
        BoundModel m = boundModel();
        json = m.add(builder.apply(m)).toJson();
        return this;
    }

    /** Add a computation; {@code builder} authors it against the current model. */
    public EditSession addComputation(Function<BoundModel, Computation> builder) {
        BoundModel m = boundModel();
        json = m.add(builder.apply(m)).toJson();
        return this;
    }

    /** Re-express the condition of the rule at {@code ruleFullName}; {@code builder} authors the new one. */
    public EditSession modifyRule(String ruleFullName, Function<BoundModel, Condition> builder) {
        BoundModel m = boundModel();
        json = m.replaceCondition(ruleFullName, builder.apply(m)).toJson();
        return this;
    }

    /**
     * Add a rule from its spec parts (raw condition <em>text</em>) — the {@code apply}/spec path, distinct
     * from the DSL-builder {@link #addRule(Function)}. Text injection only (no per-op kernel gate), so a
     * sequence can stay transiently invalid until {@link #validate()} confirms the whole model.
     */
    public EditSession addRule(String groupPath, String name, String errorEntityRelPath,
            String code, String condition, String severity, List<LocalizedText> messages) {
        json = ModelWriter.addRule(json, groupPath, name, errorEntityRelPath, code, condition, severity, messages);
        return this;
    }

    /** Re-express a rule's condition from raw DSL <em>text</em> (the {@code apply}/spec path; no per-op gate). */
    public EditSession modifyRule(String ruleFullName, String condition) {
        json = ModelWriter.replaceCondition(json, ruleFullName, condition);
        return this;
    }

    /** Re-set a rule's {@code severity} in place — condition and messages untouched (FEATURE-GAPS). */
    public EditSession modifyRuleSeverity(String ruleFullName, Severity severity) {
        json = ModelWriter.replaceSeverity(json, ruleFullName, severity.name());
        return this;
    }

    /** Replace a rule's per-locale messages in place — condition and severity untouched. */
    public EditSession modifyRuleMessages(String ruleFullName, List<LocalizedText> messages) {
        json = ModelWriter.replaceMessages(json, ruleFullName, messages);
        return this;
    }

    /** Add a computation from a parsed spec (the {@code apply}/spec path; text injection, no per-op gate). */
    public EditSession addComputation(DraftComputation draft, List<LocalizedText> messages) {
        json = ModelWriter.addComputation(json, draft, messages);
        return this;
    }

    /** Re-express the computation at {@code computationFullName} from a parsed spec (the {@code apply}/spec path). */
    public EditSession modifyComputation(String computationFullName, DraftComputation draft, List<LocalizedText> messages) {
        json = ModelWriter.replaceComputation(json, computationFullName, draft, messages);
        return this;
    }

    /**
     * Re-express the computation at {@code computationFullName} via the typed DSL builder — the mirror of
     * {@link #modifyRule(String, Function)}; {@code builder} authors against the current model and the
     * result re-binds through the kernel gate as it applies.
     */
    public EditSession modifyComputation(String computationFullName, Function<BoundModel, Computation> builder) {
        BoundModel m = boundModel();
        json = m.replaceComputation(computationFullName, builder.apply(m)).toJson();
        return this;
    }

    public EditSession removeRule(String ruleFullName) {
        json = boundModel().removeRule(ruleFullName).toJson();
        return this;
    }

    public EditSession removeComputation(String computationFullName) {
        json = boundModel().removeComputation(computationFullName).toJson();
        return this;
    }

    // --- gate / output --------------------------------------------------------------------------

    /** The kernel gate: re-bind the whole model (deserialize → expand → checkConsistency), once. */
    public EditSession validate() {
        Verification gate;
        try {
            gate = ModelLoader.loadAndCheck(json, resolver);
        } catch (ModelConsistencyException e) {
            throw e;
        } catch (UnresolvedReferenceException | ModelInputException e) {
            // Input loading failed before consistency checking; wrapping it would fabricate a gate receipt.
            throw e;
        } catch (RuntimeException e) {
            // deserialize/expand tier — surface the cause via the shared factory (probe 07: the terminal
            // gate used to drop it, unlike the per-edit gate; one factory keeps them from drifting).
            throw ModelConsistencyException.notKernelValid(e);
        }
        gatedJson = json;
        gatedBy = gate;
        return this;
    }

    /**
     * The terminal gate's <b>receipt</b> for the model this session holds <em>right now</em> — empty until
     * {@link #validate()} has run, and empty again after any edit that follows it.
     *
     * <p>Same contract and same reason as {@link ModelDocument#consistencyGate()}: a session is mutable, so a
     * stored {@code Verification} would go on vouching for a model that has since been edited. Here the check is
     * free — the session's rep <em>is</em> the DM-JSON text, so the receipt is kept against the very string the
     * gate ran on.
     *
     * <p>This is what lets {@code apply} publish provenance honestly: its per-op steps are ungated by design and
     * attest nothing, while the one terminal gate attests for the state that actually gets written.
     */
    public Optional<Verification> consistencyGate() {
        return json.equals(gatedJson) ? Optional.of(gatedBy) : Optional.empty();
    }

    /** The current model as DM-JSON. */
    public String toJson() {
        return json;
    }

    /** Persist the current model to {@code path}. */
    public void writeTo(Path path) {
        try {
            Files.writeString(path, json);
        } catch (IOException e) {
            throw new UncheckedIOException("failed to write model to " + path, e);
        }
    }
}
