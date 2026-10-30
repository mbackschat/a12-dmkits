package io.github.mbackschat.a12.dm.rulekit.dsl;

import java.math.BigDecimal;
import java.util.Objects;

/**
 * A numeric-valued expression that can be compared — a field value, a {@code Length(...)}, an
 * aggregate, an arithmetic result, … Centralizes the comparison builders so every numeric operand
 * compares the same way ({@link NumberRef} delegates here).
 *
 * <p>Literal overloads are <strong>scale-faithful</strong>: {@code eq("0.00")} renders exactly
 * {@code == 0.00}. Equality and inequality use the kernel's derived signed-scale summary plus its
 * independent multiplicative-constant capability. Only a capable smaller-scale side may be padded;
 * ordering deliberately bypasses this gate.
 */
public final class NumberExpr implements TemporalComponent {

    private final Calculation expression;
    private final NumericScaleSummary scaleSummary;

    /**
     * Wrap a numeric calculation whose model-derived scale metadata is not available at this boundary.
     * Self-describing literal/arithmetic/rounding/count shapes are summarized; otherwise the live kernel
     * remains the authoring oracle instead of this wrapper inventing a scale.
     */
    public NumberExpr(Calculation expression) {
        this(expression, NumericScaleSummary.from(expression));
    }

    private NumberExpr(Calculation expression, NumericScaleSummary scaleSummary) {
        this.expression = Objects.requireNonNull(expression, "expression");
        this.scaleSummary = Objects.requireNonNull(scaleSummary, "scaleSummary");
    }

    static NumberExpr field(Calculation expression, int scale) {
        return new NumberExpr(expression, NumericScaleSummary.field(scale));
    }

    static NumberExpr fixedScale(Calculation expression, int scale) {
        return new NumberExpr(expression, NumericScaleSummary.known(scale, false));
    }

    public Calculation expression() {
        return expression;
    }

    /** {@code this < literal} — a numeric ordering comparison. catalog:LessThan */
    public Condition lt(long literal) {
        return cmp(CompareOp.LT, NumberLiteral.of(literal));
    }

    public Condition lt(String literal) {
        return cmp(CompareOp.LT, NumberLiteral.of(literal));
    }

    public Condition lt(BigDecimal literal) {
        return cmp(CompareOp.LT, new NumberLiteral(literal));
    }

    /** {@code this <= literal} — a numeric ordering comparison. catalog:LessThanOrEqual */
    public Condition le(long literal) {
        return cmp(CompareOp.LE, NumberLiteral.of(literal));
    }

    public Condition le(String literal) {
        return cmp(CompareOp.LE, NumberLiteral.of(literal));
    }

    public Condition le(BigDecimal literal) {
        return cmp(CompareOp.LE, new NumberLiteral(literal));
    }

    /** {@code this > literal} — a numeric ordering comparison. catalog:GreaterThan */
    public Condition gt(long literal) {
        return cmp(CompareOp.GT, NumberLiteral.of(literal));
    }

    public Condition gt(String literal) {
        return cmp(CompareOp.GT, NumberLiteral.of(literal));
    }

    public Condition gt(BigDecimal literal) {
        return cmp(CompareOp.GT, new NumberLiteral(literal));
    }

    /** {@code this >= literal} — a numeric ordering comparison. catalog:GreaterThanOrEqual */
    public Condition ge(long literal) {
        return cmp(CompareOp.GE, NumberLiteral.of(literal));
    }

    public Condition ge(String literal) {
        return cmp(CompareOp.GE, NumberLiteral.of(literal));
    }

    public Condition ge(BigDecimal literal) {
        return cmp(CompareOp.GE, new NumberLiteral(literal));
    }

    /** {@code this == literal} — a scale-faithful numeric equality comparison using the shared derived-scale gate. catalog:Equal */
    public Condition eq(long literal) {
        return cmp(CompareOp.EQ, NumberLiteral.of(literal));
    }

    public Condition eq(String literal) {
        return cmp(CompareOp.EQ, NumberLiteral.of(literal));
    }

    public Condition eq(BigDecimal literal) {
        return cmp(CompareOp.EQ, new NumberLiteral(literal));
    }

    /** {@code this != literal} — a numeric inequality comparison. catalog:NotEqual */
    public Condition ne(long literal) {
        return cmp(CompareOp.NE, NumberLiteral.of(literal));
    }

    public Condition ne(String literal) {
        return cmp(CompareOp.NE, NumberLiteral.of(literal));
    }

    public Condition ne(BigDecimal literal) {
        return cmp(CompareOp.NE, new NumberLiteral(literal));
    }

    // --- expression-vs-expression comparison (e.g. [Quantity] < Sum(...), Sum(...) > [Cap]) ---------
    // == / != use the shared derived-scale gate whenever both summaries are available. A generic
    // expression whose model-derived scale is unavailable defers to the live kernel (F11); ordering
    // ignores scale.

    public Condition lt(NumberExpr other) {
        return exprCmp(CompareOp.LT, other);
    }

    public Condition le(NumberExpr other) {
        return exprCmp(CompareOp.LE, other);
    }

    public Condition gt(NumberExpr other) {
        return exprCmp(CompareOp.GT, other);
    }

    public Condition ge(NumberExpr other) {
        return exprCmp(CompareOp.GE, other);
    }

    public Condition eq(NumberExpr other) {
        return exprCmp(CompareOp.EQ, other);
    }

    public Condition ne(NumberExpr other) {
        return exprCmp(CompareOp.NE, other);
    }

    // A kind-typed ref on the RIGHT of an expression. Without these the two types compose only one way —
    // `ref.gt(expr)` existed, `expr.gt(ref)` did not — so an authored comparison could fail to compile purely
    // on the side its operands happened to fall (found writing the DG60 snippet). They widen and delegate, so
    // the scale gate on ==/!= is the same one the NumberExpr overloads apply.

    public Condition lt(NumberRef other) {
        return lt(other.asNumber());
    }

    public Condition le(NumberRef other) {
        return le(other.asNumber());
    }

    public Condition gt(NumberRef other) {
        return gt(other.asNumber());
    }

    public Condition ge(NumberRef other) {
        return ge(other.asNumber());
    }

    public Condition eq(NumberRef other) {
        return eq(other.asNumber());
    }

    public Condition ne(NumberRef other) {
        return ne(other.asNumber());
    }

    /**
     * {@code [a] DiffersWithToleranceRange{N} [b]} — true when the two numbers differ by more than the
     * tolerance {@code t} (the kernel's "unequal within a tolerance"). Only for numbers/amounts.
     * catalog:DiffersWithToleranceRange1,DiffersWithToleranceRange2,DiffersWithToleranceRange5,DiffersWithToleranceRange10
     */
    public Condition differsWithTolerance(Tolerance t, NumberExpr other) {
        return new ComparisonCondition(expression, t.operator(), other.expression());
    }

    // --- arithmetic (e.g. [maxLength] < [minFractionalDigits] + 2) — yields a comparable expression ---

    /** {@code a + b} — arithmetic addition, a comparable numeric expression (kernel {@code CalcOp}). catalog:Arithmetic */
    public NumberExpr plus(NumberExpr other) {
        return arith(Arithmetic.Op.ADD, other);
    }

    /** {@code a - b} — arithmetic subtraction. catalog:Arithmetic */
    public NumberExpr minus(NumberExpr other) {
        return arith(Arithmetic.Op.SUBTRACT, other);
    }

    /** {@code a * b} — arithmetic multiplication. catalog:Arithmetic */
    public NumberExpr times(NumberExpr other) {
        return arith(Arithmetic.Op.MULTIPLY, other);
    }

    /** {@code a / b} — arithmetic division. catalog:Arithmetic */
    public NumberExpr dividedBy(NumberExpr other) {
        return arith(Arithmetic.Op.DIVIDE, other);
    }

    /** {@code a ^ b} — arithmetic exponentiation. catalog:Arithmetic */
    public NumberExpr toThePowerOf(NumberExpr other) {
        return arith(Arithmetic.Op.POWER, other);
    }

    public NumberExpr plus(long literal) {
        return plus(new NumberExpr(NumberLiteral.of(literal)));
    }

    public NumberExpr minus(long literal) {
        return minus(new NumberExpr(NumberLiteral.of(literal)));
    }

    public NumberExpr times(long literal) {
        return times(new NumberExpr(NumberLiteral.of(literal)));
    }

    public NumberExpr dividedBy(long literal) {
        return dividedBy(new NumberExpr(NumberLiteral.of(literal)));
    }

    public NumberExpr toThePowerOf(long literal) {
        return toThePowerOf(new NumberExpr(NumberLiteral.of(literal)));
    }

    // A kind-typed ref as the RIGHT operand, for the reason the comparison overloads above give: without these
    // `ref.plus(expr)` compiled and `expr.plus(ref)` did not (found 2026-10-30 writing `Sum(…) + [field]`).

    public NumberExpr plus(NumberRef other) {
        return plus(other.asNumber());
    }

    public NumberExpr minus(NumberRef other) {
        return minus(other.asNumber());
    }

    public NumberExpr times(NumberRef other) {
        return times(other.asNumber());
    }

    public NumberExpr dividedBy(NumberRef other) {
        return dividedBy(other.asNumber());
    }

    public NumberExpr toThePowerOf(NumberRef other) {
        return toThePowerOf(other.asNumber());
    }

    /**
     * This expression with the {@code ==}/{@code !=} scale gate lifted, for a rule that carries A12's waiver
     * ({@link Rule.Builder#allowDifferingDecimals()}, KERNEL-FINDINGS KF75). The comparison it builds is the
     * ordinary one; only this builder's refusal is skipped, so without the waiver A12 still refuses the rule.
     */
    public NumberExpr allowingDifferingDecimals() {
        return new NumberExpr(expression, NumericScaleSummary.deferred());
    }

    private NumberExpr arith(Arithmetic.Op op, NumberExpr other) {
        var arithmetic = new Arithmetic(op, expression, other.expression());
        return new NumberExpr(arithmetic,
                NumericScaleSummary.arithmetic(op, scaleSummary, other.scaleSummary, other.expression));
    }

    private Condition exprCmp(CompareOp op, NumberExpr other) {
        requireCompatibleScale(op, other.scaleSummary);
        return new ComparisonCondition(expression, op, other.expression());
    }

    private Condition cmp(CompareOp op, NumberLiteral literal) {
        requireCompatibleScale(op, NumericScaleSummary.literal(literal));
        return new ComparisonCondition(expression, op, literal);
    }

    private void requireCompatibleScale(CompareOp op, NumericScaleSummary other) {
        if (op == CompareOp.EQ || op == CompareOp.NE) {
            scaleSummary.requireEqualityCompatible(other);
        }
    }

    @Override
    public final boolean equals(Object other) {
        return this == other || other instanceof NumberExpr that && expression.equals(that.expression);
    }

    @Override
    public final int hashCode() {
        return expression.hashCode();
    }

    @Override
    public final String toString() {
        return "NumberExpr[expression=" + expression + "]";
    }
}
