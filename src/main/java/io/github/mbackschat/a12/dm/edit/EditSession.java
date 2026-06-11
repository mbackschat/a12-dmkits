package io.github.mbackschat.a12.dm.edit;

import java.io.IOException;
import java.io.StringReader;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.function.Function;

import com.mgmtp.a12.kernel.md.model.api.services.IDocumentModelReferenceResolver;

import io.github.mbackschat.a12.dm.core.ModelConsistencyException;
import io.github.mbackschat.a12.dm.core.ModelLoader;
import io.github.mbackschat.a12.dm.core.ReferenceResolvers;
import io.github.mbackschat.a12.dm.modelkit.FieldKind;
import io.github.mbackschat.a12.dm.modelkit.ModelDocument;
import io.github.mbackschat.a12.dm.rulekit.dsl.BoundModel;
import io.github.mbackschat.a12.dm.rulekit.dsl.Computation;
import io.github.mbackschat.a12.dm.rulekit.dsl.Condition;
import io.github.mbackschat.a12.dm.rulekit.dsl.Rule;

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

    public EditSession removeTypeDefinition(String id) {
        json = ModelDocument.read(json).removeTypeDefinition(id).toJson();
        return this;
    }

    public EditSession removeInclude(String alias) {
        json = ModelDocument.read(json).removeInclude(alias).toJson();
        return this;
    }

    // --- rule / computation edits (rulekit) -----------------------------------------------------

    /** Add a rule; {@code builder} receives the model bound to the current state to author against it. */
    public EditSession addRule(Function<BoundModel, Rule> builder) {
        BoundModel m = BoundModel.ofJson(json, resolver);
        json = m.add(builder.apply(m)).toJson();
        return this;
    }

    /** Add a computation; {@code builder} authors it against the current model. */
    public EditSession addComputation(Function<BoundModel, Computation> builder) {
        BoundModel m = BoundModel.ofJson(json, resolver);
        json = m.add(builder.apply(m)).toJson();
        return this;
    }

    /** Re-express the condition of the rule at {@code ruleFullName}; {@code builder} authors the new one. */
    public EditSession modifyRule(String ruleFullName, Function<BoundModel, Condition> builder) {
        BoundModel m = BoundModel.ofJson(json, resolver);
        json = m.replaceCondition(ruleFullName, builder.apply(m)).toJson();
        return this;
    }

    public EditSession removeRule(String ruleFullName) {
        json = BoundModel.ofJson(json, resolver).removeRule(ruleFullName).toJson();
        return this;
    }

    public EditSession removeComputation(String computationFullName) {
        json = BoundModel.ofJson(json, resolver).removeComputation(computationFullName).toJson();
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
