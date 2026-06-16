package io.github.mbackschat.a12.dm.rulekit.dsl;

import java.util.Arrays;

import io.github.mbackschat.a12.dm.core.ModelIndex;
import io.github.mbackschat.a12.dm.core.ModelLoader;
import io.github.mbackschat.a12.dm.core.ValueKind;

/**
 * The entry point for authoring against a specific model (L2a runtime binding — §5a). It hands out
 * typed field handles looked up from a loaded {@link ModelIndex}, so paths and operand kinds are
 * resolved at runtime with no codegen. Unknown paths / wrong kinds fail fast at handle creation.
 */
public final class BoundModel {

    private final ModelIndex index;
    // Retained for the F6 write path; null for read-only bindings. This is the AUTHORED (unexpanded)
    // DM-JSON — writes edit it and never the kernel's expanded model, so includes/modelReferences are
    // preserved verbatim, never inlined. The resolver is kept so re-binding a model with includes works.
    private final String sourceJson;
    private final com.mgmtp.a12.kernel.md.model.api.services.IDocumentModelReferenceResolver resolver;

    private BoundModel(ModelIndex index, String sourceJson,
            com.mgmtp.a12.kernel.md.model.api.services.IDocumentModelReferenceResolver resolver) {
        this.index = index;
        this.sourceJson = sourceJson;
        this.resolver = resolver;
    }

    public static BoundModel of(ModelIndex index) {
        return new BoundModel(index, null, null);
    }

    /**
     * Bind from <em>self-contained</em> DM-JSON, retaining the source so the model can be
     * {@link #add(Rule) written} back (F6). For a model with includes / {@code modelReferences}, use
     * {@link #ofJson(String, com.mgmtp.a12.kernel.md.model.api.services.IDocumentModelReferenceResolver)}.
     */
    public static BoundModel ofJson(String modelJson) {
        return ofJson(modelJson,
                io.github.mbackschat.a12.dm.core.ReferenceResolvers.selfContained());
    }

    /**
     * Bind from DM-JSON with a {@code resolver} for includes / type-def imports, retaining both so
     * the write path round-trips an included model correctly (loads → expands against the resolver →
     * {@code checkConsistency}). The retained source is the unexpanded authored JSON, so
     * {@link #toJson()} / {@link #writeTo} preserve the includes rather than inlining them.
     */
    public static BoundModel ofJson(String modelJson,
            com.mgmtp.a12.kernel.md.model.api.services.IDocumentModelReferenceResolver resolver) {
        try (var reader = new java.io.StringReader(modelJson)) {
            return new BoundModel(ModelIndex.of(ModelLoader.load(reader, resolver)), modelJson, resolver);
        } catch (java.io.IOException e) {
            throw new java.io.UncheckedIOException("failed to bind model from JSON", e);
        }
    }

    /**
     * Bind <b>without</b> the kernel consistency gate (expand-only) — for <em>read-only</em> inspection of a
     * model that may be inconsistent (e.g. a candidate rule whose error field is not yet valid, G4: read its
     * condition / references before it would validate). Makes <b>no validity promise</b>; never use it for the
     * write path. See {@link ModelLoader#expandOnly}.
     */
    public static BoundModel ofJsonLenient(String modelJson,
            com.mgmtp.a12.kernel.md.model.api.services.IDocumentModelReferenceResolver resolver) {
        try (var reader = new java.io.StringReader(modelJson)) {
            return new BoundModel(ModelIndex.of(ModelLoader.expandOnly(reader, resolver)), modelJson, resolver);
        } catch (java.io.IOException e) {
            throw new java.io.UncheckedIOException("failed to bind model from JSON (lenient)", e);
        }
    }

    // --- write path (F6) — every write yields a NEW binding (snapshot semantics, F0) -----------

    /**
     * Adds {@code rule} to the model and returns a <em>new</em> binding over the updated DM-JSON;
     * this binding is unchanged. The result is re-validated (deserialize → expand →
     * {@code checkConsistency}), so a write that would break the model fails loudly here.
     */
    public BoundModel add(Rule rule) {
        // re-bind with the SAME resolver, so a model with includes re-expands correctly
        return ofJson(io.github.mbackschat.a12.dm.rulekit.write.ModelWriter.addRule(requireWritable(), rule), resolver);
    }

    /**
     * Adds {@code computation} to the model (in its computed field's group) and returns a <em>new</em>
     * binding over the updated DM-JSON; this binding is unchanged. Re-validated on re-bind (a write that
     * breaks the model fails loudly here), the computation twin of {@link #add(Rule)}.
     */
    public BoundModel add(Computation computation) {
        return ofJson(io.github.mbackschat.a12.dm.rulekit.write.ModelWriter.addComputation(requireWritable(),
                computation), resolver);
    }

    /**
     * Removes the rule at {@code ruleFullName}, returning a <em>new</em> binding (this one is unchanged).
     * Re-validated on re-bind. Throws if no rule exists at that path.
     */
    public BoundModel removeRule(String ruleFullName) {
        return ofJson(io.github.mbackschat.a12.dm.rulekit.write.ModelWriter.removeRule(requireWritable(),
                ruleFullName), resolver);
    }

    /**
     * Removes the computation at {@code computationFullName}, returning a <em>new</em> binding (this one
     * is unchanged). Re-validated on re-bind. The computation twin of {@link #removeRule(String)}.
     */
    public BoundModel removeComputation(String computationFullName) {
        return ofJson(io.github.mbackschat.a12.dm.rulekit.write.ModelWriter.removeComputation(requireWritable(),
                computationFullName), resolver);
    }

    /**
     * Replaces the computation at {@code computationFullName} with the re-expressed {@code computation},
     * returning a <em>new</em> binding (this one is unchanged); the F8 #3 write-back for computations
     * (remove the old element, add the new). Re-validated on re-bind, so a write that breaks the
     * computation fails loudly here. The computation twin of {@link #replaceCondition(String, Condition)}.
     */
    public BoundModel replaceComputation(String computationFullName, Computation computation) {
        return ofJson(io.github.mbackschat.a12.dm.rulekit.write.ModelWriter.replaceComputation(
                requireWritable(), computationFullName, computation), resolver);
    }

    /**
     * Replaces the condition of the rule at {@code ruleFullName} with {@code newCondition}, returning
     * a <em>new</em> binding (this one is unchanged); the F8 #3 write-back that applies a reviewed
     * re-express. The condition is rendered relative to the rule's group (fork-9), like any authored
     * rule; only the {@code errorCondition} slot changes. Re-validated on re-bind, so a write that
     * breaks the rule fails loudly here.
     */
    public BoundModel replaceCondition(String ruleFullName, Condition newCondition) {
        String groupPath = ruleFullName.substring(0, ruleFullName.lastIndexOf('/'));
        String conditionText = new DslRenderer(ConditionLanguage.EN, groupPath).render(newCondition);
        return ofJson(io.github.mbackschat.a12.dm.rulekit.write.ModelWriter.replaceCondition(
                requireWritable(), ruleFullName, conditionText), resolver);
    }

    /** The model as DM-JSON text (the retained source, with any writes applied). */
    public String toJson() {
        return requireWritable();
    }

    /** Persists the model's DM-JSON to {@code path} (the portable artifact). */
    public void writeTo(java.nio.file.Path path) {
        try {
            java.nio.file.Files.writeString(path, requireWritable());
        } catch (java.io.IOException e) {
            throw new java.io.UncheckedIOException("failed to write model to " + path, e);
        }
    }

    private String requireWritable() {
        if (sourceJson == null) {
            throw new IllegalStateException("this binding has no source JSON — use BoundModel.ofJson(...) "
                    + "to enable the write path (F6)");
        }
        return sourceJson;
    }

    /**
     * The reference resolver this binding loads / re-binds against (F-RES) — used so a consumer (e.g.
     * the kernel adapter) expands the model against the <em>same</em> resolver. Null for a binding made
     * directly from a {@code ModelIndex} (no source JSON); non-null whenever {@link #toJson()} works.
     */
    public com.mgmtp.a12.kernel.md.model.api.services.IDocumentModelReferenceResolver resolver() {
        return resolver;
    }

    /** A generic handle to the field at {@code path} (any kind). Throws if the path is not a field. */
    public FieldRef field(String path) {
        return new FieldRef(path, index.kindOf(path));
    }

    /** A typed number handle. Throws if the field at {@code path} is not a number. */
    public NumberRef num(String path) {
        ValueKind kind = index.kindOf(path);
        if (kind != ValueKind.NUMBER) {
            throw new IllegalArgumentException(path + " is not a number field (kind=" + kind + ")");
        }
        return new NumberRef(field(path), index.numberScale(path));
    }

    /** A typed string handle. Throws if the field at {@code path} is not a string. */
    public StringRef str(String path) {
        ValueKind kind = index.kindOf(path);
        if (kind != ValueKind.STRING) {
            throw new IllegalArgumentException(path + " is not a string field (kind=" + kind + ")");
        }
        return new StringRef(field(path));
    }

    /** A typed date handle. Throws if the field at {@code path} is not a date. */
    public DateRef date(String path) {
        ValueKind kind = index.kindOf(path);
        if (kind != ValueKind.DATE) {
            throw new IllegalArgumentException(path + " is not a date field (kind=" + kind + ")");
        }
        return new DateRef(field(path));
    }

    /** A typed time-of-day handle. Throws if the field at {@code path} is not a time. */
    public TimeRef time(String path) {
        ValueKind kind = index.kindOf(path);
        if (kind != ValueKind.TIME) {
            throw new IllegalArgumentException(path + " is not a time field (kind=" + kind + ")");
        }
        return new TimeRef(field(path));
    }

    /** A typed date-time handle (the source a date-time is decomposed from). Throws if not a date-time. */
    public DateTimeRef dateTime(String path) {
        ValueKind kind = index.kindOf(path);
        if (kind != ValueKind.DATE_TIME) {
            throw new IllegalArgumentException(path + " is not a date-time field (kind=" + kind + ")");
        }
        return new DateTimeRef(field(path));
    }

    /** A typed date-fragment handle (a year-month partial date; an extraction source). Throws if not a date fragment. */
    public DateFragmentRef dateFragment(String path) {
        ValueKind kind = index.kindOf(path);
        if (kind != ValueKind.DATE_FRAGMENT) {
            throw new IllegalArgumentException(path + " is not a date-fragment field (kind=" + kind + ")");
        }
        return new DateFragmentRef(field(path));
    }

    /** A typed date-range handle (an interval of two dates; source of Start/EndOfDateRange). Throws if not a date range. */
    public DateRangeRef dateRange(String path) {
        ValueKind kind = index.kindOf(path);
        if (kind != ValueKind.DATE_RANGE) {
            throw new IllegalArgumentException(path + " is not a date-range field (kind=" + kind + ")");
        }
        return new DateRangeRef(field(path));
    }

    /** A typed group handle (knows repeatability from the model). Throws if the path is not a group. */
    public GroupRef group(String path) {
        return new GroupRef(path, index.isRepeating(path));
    }

    /**
     * Whether a rule whose error entity is {@code errorFieldPath} <em>iterates</em> — i.e. a
     * repeatable group lies on the path to it (the §5a error-field rule). The F4a lint layer uses
     * this to pre-warn the kernel-delegated {@code MVK_NEG_CONDITION_IN_ITERATION}.
     */
    public boolean iteratesForErrorField(String errorFieldPath) {
        return nearestRepeatableAncestor(errorFieldPath).isPresent();
    }

    /** The nearest (deepest) repeatable group on the path to {@code fieldPath}, if any. */
    public java.util.Optional<String> nearestRepeatableAncestor(String fieldPath) {
        String path = fieldPath;
        int slash;
        while ((slash = path.lastIndexOf('/')) > 0) {
            path = path.substring(0, slash);
            if (index.isRepeating(path)) {
                return java.util.Optional.of(path);
            }
        }
        return java.util.Optional.empty();
    }

    /**
     * A flattened repetition list from a star path, e.g. {@code /Subscription/Addons*&#47;Name}
     * (KERNEL-SEMANTICS §9). Every starred segment must be a repeatable group — a star on a
     * non-repeatable group is rejected here with the kernel's code named
     * ({@code MVK_INVALID_WILDCARD}); the base path must resolve to a field.
     */
    public FieldListRef flatten(String starPath) {
        if (!starPath.contains("*")) {
            throw new IllegalArgumentException("no '*' in " + starPath
                    + " — a plain field reference is field(path)/num(path)/…");
        }
        var flattened = new java.util.ArrayList<GroupRef>();
        StringBuilder base = new StringBuilder();
        for (String segment : starPath.substring(1).split("/")) {
            boolean starred = segment.endsWith("*");
            String name = starred ? segment.substring(0, segment.length() - 1) : segment;
            base.append('/').append(name);
            if (starred) {
                String groupPath = base.toString();
                if (!index.isRepeating(groupPath)) {
                    throw new IllegalArgumentException("'" + groupPath + "' is not repeatable —"
                            + " a star is only allowed on repeatable groups (MVK_INVALID_WILDCARD)");
                }
                flattened.add(new GroupRef(groupPath, true));
            }
        }
        return new FieldListRef(field(base.toString()), flattened);
    }

    /**
     * A group scope for the field-fill quantifiers from a (possibly starred) group path, e.g.
     * {@code /MVK/Categories*&#47;Values*} or a bare {@code /MVK/SortFields}. Every starred segment must
     * be a repeatable group ({@code MVK_INVALID_WILDCARD}); the path resolves to a group (the group
     * analogue of {@link #flatten(String)}).
     */
    public GroupListRef flattenGroup(String starGroupPath) {
        var flattened = new java.util.ArrayList<GroupRef>();
        StringBuilder base = new StringBuilder();
        for (String segment : starGroupPath.substring(1).split("/")) {
            boolean starred = segment.endsWith("*");
            String name = starred ? segment.substring(0, segment.length() - 1) : segment;
            base.append('/').append(name);
            if (starred) {
                String groupPath = base.toString();
                if (!index.isRepeating(groupPath)) {
                    throw new IllegalArgumentException("'" + groupPath + "' is not repeatable —"
                            + " a star is only allowed on repeatable groups (MVK_INVALID_WILDCARD)");
                }
                flattened.add(new GroupRef(groupPath, true));
            }
        }
        return new GroupListRef(group(base.toString()), flattened);
    }

    // --- navigation (Level 1, study finding 9): streams of info handles; filter is the matcher --

    /** Every field, in model order, as rich {@link FieldInfo} descriptors. */
    public java.util.stream.Stream<FieldInfo> fields() {
        return index.fieldPaths().map(this::fieldInfo);
    }

    /** The fields directly contained in {@code group}, in model order. */
    public java.util.stream.Stream<FieldInfo> fields(GroupRef group) {
        return index.fieldPaths(group.path()).map(this::fieldInfo);
    }

    /** Every group (root groups included), in model order. */
    public java.util.stream.Stream<GroupInfo> groups() {
        return index.groupPaths()
                .map(p -> new GroupInfo(this, p, index.nameOf(p), index.isRepeating(p)));
    }

    /** The declared message-locale codes (header {@code locales}), in document order — every rule and
     *  computation must carry a message for each (MVK_ERROR_MESSAGE_FOR_LANGUAGE_MISSING). */
    public java.util.List<String> locales() {
        return index.locales();
    }

    /** The non-throwing peek — exploring is lenient; narrowing (on {@link FieldInfo}) is strict. */
    public java.util.Optional<FieldInfo> fieldAt(String path) {
        return index.isField(path) ? java.util.Optional.of(fieldInfo(path)) : java.util.Optional.empty();
    }

    private FieldInfo fieldInfo(String path) {
        return new FieldInfo(this, path, index.nameOf(path), index.kindOf(path));
    }

    /** A field's element-level annotations, read back through the kernel {@link com.mgmtp.a12.kernel.md.model.api.IField}
     *  (the slot the silent-ignore deserializer would otherwise hide). Backs {@link FieldInfo#annotations()}. */
    java.util.List<Annotation> annotationsOf(String fieldPath) {
        return map(index.field(fieldPath).getAnnotations());
    }

    /** A group's element-level annotations (kernel {@link com.mgmtp.a12.kernel.md.model.api.IGroup}). Backs
     *  {@link GroupInfo#annotations()}. */
    java.util.List<Annotation> annotationsOfGroup(String groupPath) {
        return map(index.group(groupPath).getAnnotations());
    }

    private static java.util.List<Annotation> map(java.util.List<com.mgmtp.a12.model.header.Annotation> as) {
        return as.stream().map(a -> new Annotation(a.getName(), a.getValue())).toList();
    }

    /** A typed boolean handle ({@code isTrue()}/{@code isFalse()}). Throws if not a boolean field. */
    public BoolRef bool(String path) {
        ValueKind kind = index.kindOf(path);
        if (kind != ValueKind.BOOLEAN) {
            throw new IllegalArgumentException(path + " is not a boolean field (kind=" + kind + ")");
        }
        return new BoolRef(field(path));
    }

    /**
     * A typed confirm handle — a distinct kind from boolean (§5b): offers {@code confirmed()} /
     * {@code notConfirmed()} only (confirm never compares to {@code False}). Throws if not a
     * confirm field.
     */
    public ConfirmRef confirm(String path) {
        ValueKind kind = index.kindOf(path);
        if (kind != ValueKind.CONFIRM) {
            throw new IllegalArgumentException(path + " is not a confirm field (kind=" + kind + ")");
        }
        return new ConfirmRef(field(path));
    }

    /** A typed enumeration handle carrying the model's allowed values. Throws if not an enum. */
    public EnumRef enumOf(String path) {
        ValueKind kind = index.kindOf(path);
        if (kind != ValueKind.ENUMERATION) {
            throw new IllegalArgumentException(path + " is not an enumeration field (kind=" + kind + ")");
        }
        return new EnumRef(field(path), index.enumValues(path), index.enumCategories(path));
    }

    // --- authoring objects (F2/F2a) — model-aware so messages check against DECLARED locales ----

    /** Start a rule in {@code group}; messages are checked against the model's declared locales. */
    public Rule.Builder rule(GroupReference group) {
        return new Rule.Builder(group.ref(), index.locales());
    }

    /** Start a computation for {@code computed}; the element is placed in the field's group. */
    public Computation.Builder computation(NumberRef computed) {
        return computation(computed.field());
    }

    public Computation.Builder computation(StringRef computed) {
        return computation(computed.field());
    }

    public Computation.Builder computation(FieldRef computed) {
        return new Computation.Builder(computed, index.locales());
    }

    // --- field-list quantifiers (proposal §5) — each validates every path against the model -------

    public Condition allFieldsFilled(String... paths) {
        return fieldList(Quantifier.ALL_FIELDS_FILLED, paths);
    }

    public Condition noFieldFilled(String... paths) {
        return fieldList(Quantifier.NO_FIELD_FILLED, paths);
    }

    public Condition atLeastOneFieldFilled(String... paths) {
        return fieldList(Quantifier.AT_LEAST_ONE_FIELD_FILLED, paths);
    }

    public Condition notAllFieldsFilled(String... paths) {
        return fieldList(Quantifier.NOT_ALL_FIELDS_FILLED, paths);
    }

    public Condition fieldsNotCollectivelyFilled(String... paths) {
        return fieldList(Quantifier.FIELDS_NOT_COLLECTIVELY_FILLED, paths);
    }

    public Condition notExactlyOneFieldFilled(String... paths) {
        return fieldList(Quantifier.NOT_EXACTLY_ONE_FIELD_FILLED, paths);
    }

    public Condition moreThanOneFieldFilled(String... paths) {
        return fieldList(Quantifier.MORE_THAN_ONE_FIELD_FILLED, paths);
    }

    private Condition fieldList(Quantifier quantifier, String[] paths) {
        if (paths.length == 0) {
            throw new IllegalArgumentException(quantifier.operatorId() + " needs at least one field");
        }
        return Dsl.fieldList(quantifier, Arrays.stream(paths).map(this::field).toList());
    }
}
