package io.github.mbackschat.a12.rulekit.dsl;

import java.util.Arrays;

import io.github.mbackschat.a12.rulekit.model.ModelIndex;
import io.github.mbackschat.a12.rulekit.model.ValueKind;

/**
 * The entry point for authoring against a specific model (L2a runtime binding — §5a). It hands out
 * typed field handles looked up from a loaded {@link ModelIndex}, so paths and operand kinds are
 * resolved at runtime with no codegen. Unknown paths / wrong kinds fail fast at handle creation.
 */
public final class BoundModel {

    private final ModelIndex index;

    private BoundModel(ModelIndex index) {
        this.index = index;
    }

    public static BoundModel of(ModelIndex index) {
        return new BoundModel(index);
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

    /** A typed group handle (knows repeatability from the model). Throws if the path is not a group. */
    public GroupRef group(String path) {
        return new GroupRef(path, index.isRepeating(path));
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

    /** The non-throwing peek — exploring is lenient; narrowing (on {@link FieldInfo}) is strict. */
    public java.util.Optional<FieldInfo> fieldAt(String path) {
        return index.isField(path) ? java.util.Optional.of(fieldInfo(path)) : java.util.Optional.empty();
    }

    private FieldInfo fieldInfo(String path) {
        return new FieldInfo(this, path, index.nameOf(path), index.kindOf(path));
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
    public Rule.Builder rule(GroupRef group) {
        return new Rule.Builder(group, index.locales());
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

    private Condition fieldList(Quantifier quantifier, String[] paths) {
        if (paths.length == 0) {
            throw new IllegalArgumentException(quantifier.operatorId() + " needs at least one field");
        }
        return Dsl.fieldList(quantifier, Arrays.stream(paths).map(this::field).toList());
    }
}
