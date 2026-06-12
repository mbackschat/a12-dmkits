package io.github.mbackschat.a12.dm.edit;

import java.io.IOException;
import java.io.StringReader;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

import com.mgmtp.a12.kernel.md.model.api.services.IDocumentModelReferenceResolver;

import io.github.mbackschat.a12.dm.core.ModelConsistencyException;
import io.github.mbackschat.a12.dm.core.ModelLoader;
import io.github.mbackschat.a12.dm.core.ReferenceResolvers;
import io.github.mbackschat.a12.dm.modelkit.FieldKind;
import io.github.mbackschat.a12.dm.modelkit.FieldTypeSpec;
import io.github.mbackschat.a12.dm.modelkit.ModelDocument;
import io.github.mbackschat.a12.dm.modelkit.Requiredness;
import io.github.mbackschat.a12.dm.rulekit.dsl.BoundModel;
import io.github.mbackschat.a12.dm.rulekit.dsl.Computation;
import io.github.mbackschat.a12.dm.rulekit.dsl.Condition;
import io.github.mbackschat.a12.dm.rulekit.dsl.LocalizedText;
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
    private final IDocumentModelReferenceResolver resolver;

    private EditSession(String json, IDocumentModelReferenceResolver resolver) {
        this.json = json;
        this.resolver = resolver;
    }

    /** Start a session over a self-contained model. */
    public static EditSession read(String dmJson) {
        return new EditSession(dmJson, ReferenceResolvers.selfContained());
    }

    /** Start a session over a model with includes / type-def imports, resolved via {@code resolver}. */
    public static EditSession read(String dmJson, IDocumentModelReferenceResolver resolver) {
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

    public EditSession setFieldType(String fieldPath, FieldTypeSpec type) {
        return editStructure(d -> d.setFieldType(fieldPath, type));
    }

    public EditSession setRequiredness(String fieldPath, Requiredness requiredness) {
        return editStructure(d -> d.setRequiredness(fieldPath, requiredness));
    }

    public EditSession setLabel(String elementPath, Map<String, String> localeToText) {
        return editStructure(d -> d.setLabel(elementPath, localeToText));
    }

    public EditSession setAnnotation(String elementPath, String name, String value) {
        return editStructure(d -> d.setAnnotation(elementPath, name, value));
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

    public EditSession setTimeZone(String timeZone) {
        return editStructure(d -> d.setTimeZone(timeZone));
    }

    public EditSession setConditionLanguage(String localeCode) {
        return editStructure(d -> d.setConditionLanguage(localeCode));
    }

    public EditSession setFieldRefByShortNameAllowed(boolean allowed) {
        return editStructure(d -> d.setFieldRefByShortNameAllowed(allowed));
    }

    public EditSession setBaseYear(int year) {
        return editStructure(d -> d.setBaseYear(year));
    }

    public EditSession addInclude(String alias, String reference, String parentPath, String name) {
        return editStructure(d -> d.addInclude(alias, reference).mount(parentPath, name, alias));
    }

    public EditSession mount(String parentPath, String name, String alias) {
        return editStructure(d -> d.mount(parentPath, name, alias));
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

    /** The model bound to the current state — navigation and rule edits re-read this after each edit. */
    BoundModel boundModel() {
        return BoundModel.ofJson(json, resolver);
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
        try {
            ModelLoader.load(new StringReader(json), resolver);
        } catch (ModelConsistencyException e) {
            throw e;
        } catch (IOException | RuntimeException e) {
            throw new ModelConsistencyException("the edited model is not kernel-valid", e);
        }
        return this;
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
