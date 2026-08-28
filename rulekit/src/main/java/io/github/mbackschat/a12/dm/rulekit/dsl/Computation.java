package io.github.mbackschat.a12.dm.rulekit.dsl;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import io.github.mbackschat.a12.dm.core.ValueKind;
import io.github.mbackschat.a12.dm.rulekit.validate.Diagnostic;
import io.github.mbackschat.a12.dm.rulekit.validate.DraftComputation;
import io.github.mbackschat.a12.dm.rulekit.validate.PrecheckException;

/**
 * A typed computation — the F2 authoring object (study §4.3): a computed field + optional
 * {@code commonPrecondition} + alternatives. The computation element is placed in the computed
 * field's group, so {@code computedFieldRelPath} is computed, never written.
 *
 * <p><strong>Kernel-verified slot rules pre-checked at {@code build()}:</strong> alternatives are
 * either exactly one <em>unconditional</em> {@code calc(...)} or all <em>preconditioned</em>
 * {@code when(...).calc(...)} — mixing is rejected by the kernel ({@code MVK_ERROR_EMPTY_PRECONDITION});
 * the computed field must not be referenced in preconditions or operations
 * ({@code MVK_ERROR_REFERENCE_TO_CALCULATED_FIELD}, or
 * {@code MVK_ERROR_SEMANTIC_INDEX_OR_CATEGORY_FOR_ERRORFIELD} when that self-read carries a semantic
 * index or an enum category — the kernel's own separate class, which pre-empts the plain one);
 * each operation's value kind must match the
 * computed field's kind, except the kernel-defined numeric role of polymorphic {@code BaseYear}
 * (e.g. a string operation into a number field —
 * {@code MVK_INVALID_COMPARE_TO_ENUM_OR_STRING}); error text is required
 * ({@code MVK_ERROR_MESSAGE_MISSING}). Scale beyond the kind check is the kernel's call (D13).
 * Mutual exclusivity of preconditions is semantic — only the kernel/runtime can judge it.
 */
public record Computation(
        String groupPath,
        String name,
        FieldRef computedField,
        Optional<Condition> commonPrecondition,
        List<Alternative> alternatives,
        List<LocalizedText> messages,
        Comments comments,
        List<String> errorCodesToSuppress) {

    private static final java.util.Set<String> SUPPORTED_ERROR_CODES_TO_SUPPRESS =
            java.util.Set.of("MVK_INVALID_COMPARE_DEC_PLACES");

    public Computation {
        alternatives = List.copyOf(alternatives);
        messages = List.copyOf(messages);
        errorCodesToSuppress = List.copyOf(errorCodesToSuppress);
    }

    /** No suppressions — keeps pre-{@code errorCodesToSuppress} construction sites source-compatible. */
    public Computation(String groupPath, String name, FieldRef computedField,
            Optional<Condition> commonPrecondition, List<Alternative> alternatives,
            List<LocalizedText> messages, Comments comments) {
        this(groupPath, name, computedField, commonPrecondition, alternatives, messages, comments, List.of());
    }

    public record Alternative(
            Optional<Condition> precondition,
            Calculation operation,
            Optional<ToleranceBand> toleranceRangeOp) {

        public Alternative {
            precondition = precondition == null ? Optional.empty() : precondition;
            toleranceRangeOp = toleranceRangeOp == null ? Optional.empty() : toleranceRangeOp;
        }

        public Alternative(Optional<Condition> precondition, Calculation operation) {
            this(precondition, operation, Optional.empty());
        }
    }

    // Builders are created via BoundModel#computation(...) — model-aware so messages can be checked
    // against the model's DECLARED locales (which are arbitrary; en/de are not special). The
    // computation element is placed in the computed field's group.

    /** The computed field's path relative to the computation element (a sibling in the group). */
    public String computedFieldRelPath() {
        return "../" + computedField.path().value().substring(groupPath.length() + 1);
    }

    /** The kernel-measured domain accepted by A12's {@code IComputation.errorCodesToSuppress}. */
    public static java.util.Set<String> supportedErrorCodesToSuppress() {
        return SUPPORTED_ERROR_CODES_TO_SUPPRESS;
    }

    /** The bridge to the F4 validator ({@code RuleValidator.check(modelJson, computation.draft())}). */
    public DraftComputation draft() {
        return draft(ConditionLanguage.EN);
    }

    /** The F4 validator draft rendered in the destination model's condition {@code language}. */
    public DraftComputation draft(ConditionLanguage language) {
        DslRenderer renderer = new DslRenderer(language, groupPath);
        return new DraftComputation(
                groupPath,
                name,
                computedFieldRelPath(),
                commonPrecondition.map(c -> renderer.render(c, comments)).orElse(null),
                alternatives.stream()
                        .map(a -> new DraftComputation.Alternative(
                                a.precondition().map(p -> renderer.render(p, comments)).orElse(null),
                                renderer.render(a.operation(), comments),
                                a.toleranceRangeOp().orElse(null)))
                        .toList(),
                errorCodesToSuppress);
    }

    public static final class Builder {

        /**
         * The kernel's supported {@code errorCodesToSuppress} set — currently only the static
         * assignment-scale gate ({@code IComputation.getErrorCodesToSuppress}, KERNEL-FINDINGS; IF92).
         * A code outside it is refused with a corrective error rather than silently written.
         */
        private final FieldRef computedField;
        private final List<String> declaredLocales;
        private String name;
        private Condition commonPrecondition;
        private final List<Alternative> alternatives = new ArrayList<>();
        private final List<LocalizedText> messages = new ArrayList<>();
        private final List<String> errorCodesToSuppress = new ArrayList<>();
        private Comments comments = Comments.none();
        private String rootComment;

        Builder(FieldRef computedField, List<String> declaredLocales) {
            this.computedField = computedField;
            this.declaredLocales = List.copyOf(declaredLocales);
        }

        public Builder name(String name) {
            this.name = name;
            return this;
        }

        public Builder commonPrecondition(Condition condition) {
            this.commonPrecondition = condition;
            return this;
        }

        /** The single unconditional alternative — exclusive with {@code when(...).calc(...)}. */
        public Builder calc(Calculation operation) {
            alternatives.add(new Alternative(Optional.empty(), operation));
            return this;
        }

        /** The single unconditional alternative with a closed implicit-validation tolerance band. */
        public Builder calc(Calculation operation, ToleranceBand toleranceRangeOp) {
            alternatives.add(new Alternative(Optional.empty(), operation, Optional.ofNullable(toleranceRangeOp)));
            return this;
        }

        /** A preconditioned alternative; preconditions must be mutually exclusive (kernel-judged). */
        public AlternativeBuilder when(Condition precondition) {
            return new AlternativeBuilder(this, precondition);
        }

        public Builder message(LocalizedText... texts) {
            messages.addAll(List.of(texts));
            return this;
        }

        /**
         * Declare {@code errorCodesToSuppress} ({@code IComputation.getErrorCodesToSuppress}) — the kernel's
         * model-level escape hatch. Each code is domain-checked against the kernel's supported set
         * (currently only {@code MVK_INVALID_COMPARE_DEC_PLACES}, the static assignment-scale gate that lets
         * a scale-mismatched operation load — IF92); an unsupported code is refused fail-fast, not written.
         */
        public Builder errorCodesToSuppress(String... codes) {
            for (String code : codes) {
                if (!supportedErrorCodesToSuppress().contains(code)) {
                    throw new IllegalArgumentException("errorCodesToSuppress only accepts the kernel's"
                            + " supported set " + supportedErrorCodesToSuppress() + " — got '" + code + "'");
                }
            }
            errorCodesToSuppress.addAll(List.of(codes));
            return this;
        }

        /**
         * Attach a {@code ;;} comment to the whole computation — rendered as a leading line ahead of its
         * <em>first operation</em> (the "what it computes"). The kernel tolerates the comment as concrete
         * syntax; it survives the write. To comment a specific operation or precondition, use the
         * node-targeted {@link #comment(Calculation, String)} / {@link #comment(Condition, String)}.
         */
        public Builder comment(String text) {
            this.rootComment = text;
            return this;
        }

        /**
         * Attach a {@code ;;} comment to a single {@code operation}, identified by the very
         * {@link Calculation} object handed to {@link #calc(Calculation)} — rendered ahead of that operation.
         */
        public Builder comment(Calculation operation, String text) {
            this.comments = comments.before(operation, text);
            return this;
        }

        /**
         * Attach a {@code ;;} comment to a precondition (the common precondition or an alternative's),
         * identified by the {@link Condition} object handed to {@link #commonPrecondition(Condition)} or
         * {@link #when(Condition)} — rendered ahead of that precondition.
         */
        public Builder comment(Condition precondition, String text) {
            this.comments = comments.before(precondition, text);
            return this;
        }

        public Computation build() {
            require(name != null && !name.isBlank(), "a computation needs a name — name(...)");
            require(!alternatives.isEmpty(), "a computation needs at least one alternative — calc(...) or when(...).calc(...)");
            require(!messages.isEmpty(),
                    "a computation needs at least one error message (self-validation text;"
                            + " the kernel rejects message-less computations with MVK_ERROR_MESSAGE_MISSING)"
                            + " — message(en(...))");
            LocalizedText.checkDeclared(declaredLocales, messages, "computation");

            boolean anyUnconditional = alternatives.stream().anyMatch(a -> a.precondition().isEmpty());
            boolean anyConditional = alternatives.stream().anyMatch(a -> a.precondition().isPresent());
            require(!(anyUnconditional && anyConditional),
                    "alternatives must be either one unconditional calc(...) or all preconditioned"
                            + " when(...).calc(...) — the kernel rejects mixing with MVK_ERROR_EMPTY_PRECONDITION");
            require(!anyUnconditional || alternatives.size() == 1,
                    "only one unconditional alternative is possible — add preconditions to disambiguate");

            String computedPath = computedField.path().value();
            for (Alternative a : alternatives) {
                require(!isFalseConstant(a.operation()) || computedField.kind() != ValueKind.CONFIRM,
                        "the computed CONFIRM field " + computedPath + " takes only the constant True — the"
                                + " kernel rejects False with MVK_INVALID_COMPARE_TO_YES");
                require(operationMatchesComputedKind(a.operation()),
                        "the operation '" + new DslRenderer().render(a.operation()) + "' yields "
                                + a.operation().kind() + " but the computed field " + computedPath
                                + " is " + computedField.kind() + " — kinds must match");
            }
            requireNoSelfReference(strongestSelfRead(computedPath), computedPath);
            for (Alternative a : alternatives) {
                a.precondition().ifPresent(this::requireNoCustomCondition);
            }
            if (commonPrecondition != null) {
                requireNoCustomCondition(commonPrecondition);
            }

            String groupPath = computedPath.substring(0, computedPath.lastIndexOf('/'));
            // a whole-computation comment leads the first operation (always present — checked above)
            Comments finalComments = rootComment == null ? comments
                    : comments.before(alternatives.get(0).operation(), rootComment);
            return new Computation(groupPath, name, computedField,
                    Optional.ofNullable(commonPrecondition), alternatives, messages, finalComments,
                    errorCodesToSuppress);
        }

        /** One slot's reading of the computed field, kept with the slot so a rejection can name it. */
        private record SelfRead(ConditionReferences.ReadMode mode, String slot) {

            static final SelfRead NONE = new SelfRead(ConditionReferences.ReadMode.NONE, "");

            /** The stronger of two readings — {@link ConditionReferences.ReadMode} is ordered weakest-first. */
            SelfRead or(SelfRead other) {
                return other.mode.compareTo(mode) > 0 ? other : this;
            }
        }

        /**
         * The strongest reading of the computed field across <b>every</b> slot. The fold is global on purpose:
         * the kernel decides these two classes over the whole generated condition, so a projected read in a
         * precondition pre-empts a plain read in an operation (measured — a plain {@code FirstFilledValue}
         * self-read beside a projected common <em>or</em> alternative precondition reports the projected class).
         * Checking slot by slot would have reported whichever slot happened to be visited first.
         */
        private SelfRead strongestSelfRead(String computedPath) {
            SelfRead strongest = SelfRead.NONE;
            for (Alternative a : alternatives) {
                strongest = strongest.or(new SelfRead(
                        ConditionReferences.readMode(a.operation(), computedPath), "its own operation"));
                if (a.precondition().isPresent()) {
                    strongest = strongest.or(new SelfRead(
                            ConditionReferences.readMode(a.precondition().get(), computedPath), "a precondition"));
                }
            }
            if (commonPrecondition != null) {
                strongest = strongest.or(new SelfRead(
                        ConditionReferences.readMode(commonPrecondition, computedPath),
                        "the common precondition"));
            }
            return strongest;
        }

        /**
         * The self-reference ban, with the kernel's own two classes kept apart: a <b>projected</b> self-read
         * (an enum category, a semantic index, or a semantic-index key) is
         * {@code MVK_ERROR_SEMANTIC_INDEX_OR_CATEGORY_FOR_ERRORFIELD}, exactly as measured through
         * {@code checkConsistency} (KERNEL-FINDINGS KF182). Naming the plain code for a projected read pointed
         * the author at the wrong repair — dropping the reference rather than dropping the projection.
         */
        private void requireNoSelfReference(SelfRead selfRead, String computedPath) {
            switch (selfRead.mode()) {
                case PROJECTED -> fail("the computed field " + computedPath + " must not be read through a"
                        + " semantic index or category projection in " + selfRead.slot()
                        + " (MVK_ERROR_SEMANTIC_INDEX_OR_CATEGORY_FOR_ERRORFIELD)");
                case PLAIN -> fail("the computed field " + computedPath + " must not be referenced in "
                        + selfRead.slot() + " (MVK_ERROR_REFERENCE_TO_CALCULATED_FIELD)");
                case NONE -> { }
            }
        }

        /**
         * A CONFIRM field is the one asymmetric target: the kernel accepts the BOOLEAN constant {@code True}
         * into it and rejects {@code False} with {@code MVK_INVALID_COMPARE_TO_YES} ("only ... equality or
         * unequality with the constant True"). Measured on both kernel strategies through a raw-injected
         * operation that bypasses this pre-check, so the basis is the kernel and not this file's own
         * reasoning — {@code validate.laws.ConfirmComputationSourceKindLawsTest}. Plain kind equality refused
         * both, which made the legal model unauthorable; the {@code False} half is refused separately above so
         * it carries the kernel's own code instead of a kind-mismatch message.
         */
        private boolean operationMatchesComputedKind(Calculation operation) {
            return operation.kind() == computedField.kind()
                    || operation instanceof FirstFilledValue
                            && FirstFilledValue.sameOwnerFamily(operation.kind(), computedField.kind())
                    || operation instanceof BaseYear && computedField.kind() == ValueKind.NUMBER
                    || operation instanceof BoolLiteral && computedField.kind() == ValueKind.CONFIRM;
        }

        private static boolean isFalseConstant(Calculation operation) {
            return operation instanceof BoolLiteral(boolean value) && !value;
        }

        /** Lifts an {@code MVK_*}/{@code RK_*} code out of a precheck message when it names one. */
        private static final Pattern PRECHECK_CODE = Pattern.compile("(MVK|RK)_[A-Z0-9_]+");

        private void require(boolean ok, String problem) {
            if (!ok) {
                fail(problem);
            }
        }

        /**
         * Fail fast with a structured PRECHECK diagnostic (a {@link PrecheckException}, itself an
         * {@code IllegalStateException}) — parity with {@code Rule.Builder.check}. The {@code MVK_*}/
         * {@code RK_*} code is lifted from the message when it names one (as {@code Diagnostic} does for
         * kernel notifications), so a caller reads {@code diagnostic().code()} instead of scraping prose.
         */
        private void fail(String problem) {
            Matcher matcher = PRECHECK_CODE.matcher(problem);
            Optional<String> code = matcher.find() ? Optional.of(matcher.group()) : Optional.empty();
            throw new PrecheckException(Diagnostic.precheck(code, problem,
                    new Diagnostic.Where(computedField.path().value(), Optional.empty()),
                    Optional.empty(), Optional.empty()));
        }

        /** CustomCondition is forbidden in computation rules (KERNEL-SEMANTICS §14). */
        private void requireNoCustomCondition(Condition c) {
            switch (c) {
                case CustomConditionRef ignored -> fail(
                        "CustomCondition is not allowed in a computation rule"
                                + " (MVK_ERROR_INVALID_LANGUAGE_FEATURE; KERNEL-SEMANTICS §14)");
                case LogicCondition l -> {
                    requireNoCustomCondition(l.left());
                    requireNoCustomCondition(l.right());
                }
                default -> { }
            }
        }
    }

    /** The staged second half of {@code when(...)} — forces every precondition to get its operation. */
    public static final class AlternativeBuilder {

        private final Builder builder;
        private final Condition precondition;

        private AlternativeBuilder(Builder builder, Condition precondition) {
            this.builder = builder;
            this.precondition = precondition;
        }

        public Builder calc(Calculation operation) {
            builder.alternatives.add(new Alternative(Optional.of(precondition), operation));
            return builder;
        }

        /** A preconditioned alternative with its own closed implicit-validation tolerance band. */
        public Builder calc(Calculation operation, ToleranceBand toleranceRangeOp) {
            builder.alternatives.add(
                    new Alternative(Optional.of(precondition), operation, Optional.ofNullable(toleranceRangeOp)));
            return builder;
        }
    }
}
