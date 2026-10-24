package io.github.mbackschat.a12.dm.rulekit.dsl;

import java.util.List;
import java.util.Optional;

import io.github.mbackschat.a12.dm.core.KernelCode;
import io.github.mbackschat.a12.dm.core.Refusals;

/**
 * A typed handle to one category of an enumeration field — obtained via
 * {@link EnumRef#category(String)} (which fails fast on an unknown category,
 * {@code MVK_CATEGORY_UNKOWN}). Comparisons are validated against the <em>category's</em> value
 * domain — comparing to a plain enum value is the kernel-rejected mistake
 * ({@code MVK_INVALID_STRING_CONSTANT_FOR_ENUM_COMPARISON}, verified) this handle erases.
 */
public record CategoryRef(FieldRef field, String name, List<String> values, boolean correlated,
        Optional<FirstFilledOperand.SemanticIndexKey> key)
        implements EnumListRef, NumericTextSource {

    /** The marker lives on {@link #field()}, so every node built from it reads the rule's row; see {@link FieldRef#marked}. */
    public CategoryRef {
        correlated |= field.correlated();
        field = field.marked(correlated);
        values = List.copyOf(values);
        java.util.Objects.requireNonNull(key, "key");
    }

    public CategoryRef(FieldRef field, String name, List<String> values, boolean correlated) {
        this(field, name, values, correlated, Optional.empty());
    }

    public CategoryRef(FieldRef field, String name, List<String> values) {
        this(field, name, values, false);
    }

    /**
     * The category of the row a literal key selects, {@code [field -> name For "key"]}: the category before the key,
     * as A12 orders them (KERNEL-FINDINGS KF568). Every role this handle plays reads that row.
     */
    public CategoryRef forIndex(String indexValue) {
        return withKey(new FirstFilledOperand.LiteralIndex(indexValue));
    }

    /** The field-keyed peer of {@link #forIndex(String)}, {@code [field -> name For keyField]}. */
    public CategoryRef forIndex(FieldRef keyField) {
        return withKey(new FirstFilledOperand.FieldIndex(keyField));
    }

    /** A second key is A12's {@code MVK_UNEXPECTED_TOKEN} (KF568), so the handle refuses it. */
    private CategoryRef withKey(FirstFilledOperand.SemanticIndexKey newKey) {
        if (key.isPresent()) {
            throw new IllegalStateException("category " + name + " of " + field.path() + " already has a key");
        }
        return new CategoryRef(field, name, values, correlated, Optional.of(newKey));
    }

    /**
     * Marks this reference as the {@code $} outer-iteration correlation ({@code [$field -> name]}) — it
     * binds to the enclosing rule iteration. Legal only inside a {@code Having} filter (D13).
     */
    public CategoryRef outer() {
        return new CategoryRef(field, name, values, true, key);
    }

    private EnumCategoryAccess access() {
        return new EnumCategoryAccess(field, name, correlated, key);
    }

    /**
     * This category projection as a <b>value expression</b> — {@code [field -> name]} — the operand form a
     * computation assigns ({@code Selected = [Requested -> ServiceTier]}) and a comparison reads.
     *
     * <p>The role peer of {@link #listOperand()} and {@link #firstFilledOperand()}. It was the one role a
     * category could play that the handle did not hand out, so the adapter's computation read path had no
     * typed way to rebuild the projection and erased it to the bare field (field report 2026-08-10).
     */
    public Calculation value() {
        return access();
    }

    /**
     * {@code [field -> name] + <text>} — this category projection joined to text. A12 asks no display-label question
     * of a category access in {@code +} (KERNEL-FINDINGS KF396), and the projection contributes its category value
     * (KF530). catalog:Concatenation
     */
    public StringExpr plus(StringExpr text) {
        return StringExpr.joined(access(), text.expression());
    }

    /** {@code [field -> name] + "literal"} — as {@link #plus(StringExpr)}, followed by a string literal. */
    public StringExpr plus(String literal) {
        return plus(new StringExpr(new StringLiteral(literal)));
    }

    /** {@code [field -> name] + [text]} — as {@link #plus(StringExpr)}, followed by a text field's value. */
    public StringExpr plus(StringRef text) {
        return plus(text.asString());
    }

    /** {@code [field -> name] + [other -> name]} — two category projections joined, each by its category value. */
    public StringExpr plus(CategoryRef other) {
        return StringExpr.joined(access(), other.access());
    }

    /** This category projection as an A12 entity operand of {@code FirstFilledValue}. */
    public FirstFilledOperand firstFilledOperand() {
        return FirstFilledOperand.of(access());
    }

    // --- EnumListRef: a category projection contributes its category values, and is a distinct member ---

    /** This category access as a value-list quantifier operand ({@code [field -> name]}). */
    @Override
    public ValueListOperand listOperand() {
        return access();
    }

    @Override
    public List<String> admissibleValues() {
        return values;
    }

    /** {@code [field -> name] == value}. */
    public Condition is(String value) {
        return compareTo(CompareOp.EQ, value);
    }

    /** {@code [field -> name] != value}. */
    public Condition isNot(String value) {
        return compareTo(CompareOp.NE, value);
    }

    /** {@code [field -> name] == [other -> name]} — category-vs-category (the kernel arbitrates compatibility). */
    public Condition is(CategoryRef other) {
        return new ComparisonCondition(access(), CompareOp.EQ, other.access());
    }

    public Condition isNot(CategoryRef other) {
        return new ComparisonCondition(access(), CompareOp.NE, other.access());
    }

    // --- eq/ne: the equality verbs shared with the value refs (D1); is/isNot above stay as the
    //     domain-natural category reading.

    /** {@code [field -> name] == value} — the eq peer of {@link #is(String)}. */
    public Condition eq(String value) {
        return is(value);
    }

    /** {@code [field -> name] != value} — the ne peer of {@link #isNot(String)}. */
    public Condition ne(String value) {
        return isNot(value);
    }

    public Condition eq(CategoryRef other) {
        return is(other);
    }

    public Condition ne(CategoryRef other) {
        return isNot(other);
    }

    /**
     * The category twin of {@code EnumRef.compareTo}, and it cites the same code for the same measured
     * reason: the single-comparison carrier reports
     * {@code MVK_INVALID_STRING_CONSTANT_FOR_ENUM_COMPARISON} where the value-list one reports
     * {@code MVK_INVALID_STRING_CONSTANT_FOR_ENUMERATION}
     * ({@code validate.laws.UnbackedClientRefusalLawsTest}). The MVK-LEDGER row for that code names
     * {@code EnumRef.is}/{@code CategoryRef.is} as its two pre-checks, so this is the second of the pair.
     */
    private Condition compareTo(CompareOp op, String value) {
        if (!values.contains(value)) {
            throw Refusals.of(KernelCode.MVK_INVALID_STRING_CONSTANT_FOR_ENUM_COMPARISON,
                    "'" + value + "' is not a value of category '" + name + "' on " + field.path()
                            + " — allowed: " + values);
        }
        return new ComparisonCondition(access(), op, new EnumValueLiteral(value));
    }
}
