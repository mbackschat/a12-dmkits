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

    public EditSession addField(String groupPath, String name, FieldKind kind) {
        json = ModelDocument.read(json).addField(groupPath, name, kind).toJson();
        return this;
    }

    public EditSession addField(String groupPath, String name, FieldTypeSpec type) {
        json = ModelDocument.read(json).addField(groupPath, name, type).toJson();
        return this;
    }

    public EditSession setFieldType(String fieldPath, FieldTypeSpec type) {
        json = ModelDocument.read(json).setFieldType(fieldPath, type).toJson();
        return this;
    }

    public EditSession setLabel(String elementPath, Map<String, String> localeToText) {
        json = ModelDocument.read(json).setLabel(elementPath, localeToText).toJson();
        return this;
    }

    public EditSession setAnnotation(String elementPath, String name, String value) {
        json = ModelDocument.read(json).setAnnotation(elementPath, name, value).toJson();
        return this;
    }

    public EditSession addGroup(String parentPath, String name) {
        json = ModelDocument.read(json).addGroup(parentPath, name).toJson();
        return this;
    }

    public EditSession addRepeatableGroup(String parentPath, String name, int maxRepetitions) {
        json = ModelDocument.read(json).addRepeatableGroup(parentPath, name, maxRepetitions).toJson();
        return this;
    }

    public EditSession removeField(String fieldPath) {
        json = ModelDocument.read(json).removeField(fieldPath).toJson();
        return this;
    }

    public EditSession removeGroup(String groupPath) {
        json = ModelDocument.read(json).removeGroup(groupPath).toJson();
        return this;
    }

    public EditSession setRepeatable(String groupPath, int maxRepetitions) {
        json = ModelDocument.read(json).setRepeatable(groupPath, maxRepetitions).toJson();
        return this;
    }

    public EditSession addTypeDefinition(String id, FieldKind underlying) {
        json = ModelDocument.read(json).addTypeDefinition(id, underlying).toJson();
        return this;
    }

    public EditSession removeTypeDefinition(String id) {
        json = ModelDocument.read(json).removeTypeDefinition(id).toJson();
        return this;
    }

    public EditSession setDecimalSeparator(String separator) {
        json = ModelDocument.read(json).setDecimalSeparator(separator).toJson();
        return this;
    }

    public EditSession setTimeZone(String timeZone) {
        json = ModelDocument.read(json).setTimeZone(timeZone).toJson();
        return this;
    }

    public EditSession setConditionLanguage(String localeCode) {
        json = ModelDocument.read(json).setConditionLanguage(localeCode).toJson();
        return this;
    }

    public EditSession addInclude(String alias, String reference, String parentPath, String name) {
        json = ModelDocument.read(json).addInclude(alias, reference).mount(parentPath, name, alias).toJson();
        return this;
    }

    public EditSession removeInclude(String alias) {
        json = ModelDocument.read(json).removeInclude(alias).toJson();
        return this;
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
