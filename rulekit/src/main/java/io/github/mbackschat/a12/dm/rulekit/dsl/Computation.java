package io.github.mbackschat.a12.dm.rulekit.dsl;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import io.github.mbackschat.a12.dm.core.EntityPath;
import io.github.mbackschat.a12.dm.core.KernelCode;
import io.github.mbackschat.a12.dm.core.Refusals;
import io.github.mbackschat.a12.dm.core.ValueKind;
import io.github.mbackschat.a12.dm.predicates.ElementNamePredicatesKt;
import io.github.mbackschat.a12.dm.rulekit.validate.Diagnostic;
import io.github.mbackschat.a12.dm.rulekit.validate.DraftComputation;
import io.github.mbackschat.a12.dm.rulekit.validate.PrecheckException;

/**
 * A typed computation — the F2 authoring object (study §4.3): a computed field + optional
 * {@code commonPrecondition} + alternatives.
 *
 * <p><strong>The declaring group is independent of the computed field's parent.</strong> It defaults to that
 * parent, which is the shape almost every computation wants, and {@link Builder#group(GroupReference)} places
 * the element elsewhere — in an ancestor, or in a subtree that does not contain the computed field at all.
 * {@code computedFieldRelPath} is derived from the two paths either way, never written by the author.
 *
 * <p><b>The one refusal is containment under DERIVED ITERATION</b> ({@code MVK_ERROR_FIELD_NOT_IN_RULEGROUP}):
 * whenever the computation iterates, the computed field must lie AT OR BELOW the declaring group — containment,
 * not parenthood, so any ancestor of the target is fine. It iterates for either of two independent reasons: an
 * operation reading a PER-ROW field of a repeatable declaring group, or a computed field that is itself in a
 * repeatable group — the latter with no operand at all, so a bare constant into a repeating target is refused
 * from an unrelated group. A rule shares the gate but cannot show the second reason alone, since its error field
 * must be referenced by its condition. Measured through {@code checkConsistency} in
 * {@code validate.laws.ComputationDeclaringGroupLawsTest}.
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

    private static final Set<String> SUPPORTED_ERROR_CODES_TO_SUPPRESS =
            Set.of(KernelCode.MVK_INVALID_COMPARE_DEC_PLACES.name());

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
    // computation element is placed in the computed field's group unless Builder#group says otherwise.

    /**
     * The computed field's path <em>relative to the computation element</em> — {@code ../Field} for the common
     * case where the element sits beside the field, and a walk out of the declaring group and back down when
     * {@link Builder#group(GroupReference)} put it elsewhere.
     *
     * <p>Relative to the ELEMENT, not to the group: the leading {@code ..} climbs off the computation itself,
     * which is how {@code ModelReader.resolveRelPath} reads the stored reference back.
     */
    public String computedFieldRelPath() {
        return EntityPath.of(groupPath + "/" + name).relativize(computedField.path());
    }

    /** The kernel-measured domain accepted by A12's {@code IComputation.errorCodesToSuppress}. */
    public static Set<String> supportedErrorCodesToSuppress() {
        return SUPPORTED_ERROR_CODES_TO_SUPPRESS;
    }

    /** The bridge to the F4 validator ({@code RuleValidator.check(modelJson, computation.draft())}). */
    public DraftComputation draft() {
        return draft(ConditionLanguage.EN);
    }

    /** The F4 validator draft rendered in the destination model's condition {@code language}. */
    public DraftComputation draft(ConditionLanguage language) {
        DslRenderer renderer = new DslRenderer(language, groupPath);
        return DraftComputation.builder().groupPath(groupPath).name(name)
                .computedFieldRelPath(computedFieldRelPath())
                .commonPrecondition(commonPrecondition.map(c -> renderer.render(c, comments)).orElse(null))
                .alternatives(alternatives.stream()
                        .map(a -> new DraftComputation.Alternative(
                                a.precondition().map(p -> renderer.render(p, comments)).orElse(null),
                                renderer.render(a.operation(), comments),
                                a.toleranceRangeOp().orElse(null)))
                        .toList())
                .errorCodesToSuppress(errorCodesToSuppress).messages(messages).build();
    }

    public static final class Builder {

        /**
         * The kernel's supported {@code errorCodesToSuppress} set — currently only the static
         * assignment-scale gate ({@code IComputation.getErrorCodesToSuppress}, KERNEL-FINDINGS; IF92).
         * A code outside it is refused with a corrective error rather than silently written.
         */
        private final FieldRef computedField;
        private final List<String> declaredLocales;
        /** The computed field's declared temporal {@code format}, or null when it declares none / is not
         *  temporal. The kernel's assignment gate reads this string and never the field's kind (KF222), so
         *  the builder needs it to avoid refusing cells the kernel admits. */
        private final String computedFieldFormat;
        private String name;
        private GroupRef group;
        private Condition commonPrecondition;
        private final List<Alternative> alternatives = new ArrayList<>();
        private final List<LocalizedText> messages = new ArrayList<>();
        private final List<String> errorCodesToSuppress = new ArrayList<>();
        private Comments comments = Comments.none();
        private String rootComment;

        Builder(FieldRef computedField, List<String> declaredLocales, String computedFieldFormat) {
            this.computedField = computedField;
            this.declaredLocales = List.copyOf(declaredLocales);
            this.computedFieldFormat = computedFieldFormat;
        }

        public Builder name(String name) {
            this.name = name;
            return this;
        }

        /**
         * Declare the computation in {@code group} instead of in the computed field's parent — A12's own
         * independence between where a computation <em>lives</em> and where it <em>writes</em>.
         *
         * <p>The computed field may sit in an entirely different subtree; the derived
         * {@link #computedFieldRelPath()} walks out and back down. Taking a {@link GroupReference} rather than
         * a path string is what keeps the group model-resolved — a {@code GroupRef} only exists for a path
         * {@code BoundModel} found to be a group.
         *
         * <p>Placement is an iteration-scope decision, not a filing one: this group is the base the operand
         * paths render against, so a repeatable group's per-row operand pulls the computed field back inside it
         * (see the class doc), and the runtime scope question it raises is the subject of
         * {@code adapter/.../laws/CrossGroupConstantComputeScopeDiffTest}.
         */
        public Builder group(GroupReference group) {
            this.group = group == null ? null : group.ref();
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
            // The reserved namespace is not the rule element's alone, despite the code's name: the kernel
            // rejects a computation called vk_vk_* with exactly this code (measured 2026-09-12 on both
            // carriers, locked by validate.laws.ComputationSlotLawsTest). Rule.Builder has pre-checked it
            // since 2026-06-10; this side was missed because the code reads as a rule's law.
            require(!ElementNamePredicatesKt.isReservedElementName(name), KernelCode.MVK_RULE_NAME_INVALID,
                    "computation names beginning with vk_vk (any letter case) are reserved by the kernel"
                            + " — pick a different name");
            require(!alternatives.isEmpty(), "a computation needs at least one alternative — calc(...) or when(...).calc(...)");
            require(!messages.isEmpty(), KernelCode.MVK_ERROR_MESSAGE_MISSING,
                    "a computation needs at least one error message (self-validation text; the kernel rejects"
                            + " message-less computations) — message(en(...))");
            LocalizedText.checkDeclared(declaredLocales, messages, "computation");

            boolean anyUnconditional = alternatives.stream().anyMatch(a -> a.precondition().isEmpty());
            boolean anyConditional = alternatives.stream().anyMatch(a -> a.precondition().isPresent());
            require(!(anyUnconditional && anyConditional), KernelCode.MVK_ERROR_EMPTY_PRECONDITION,
                    "alternatives must be either one unconditional calc(...) or all preconditioned"
                            + " when(...).calc(...) — the kernel rejects mixing");
            require(!anyUnconditional || alternatives.size() == 1,
                    "only one unconditional alternative is possible — add preconditions to disambiguate");

            String computedPath = computedField.path().value();
            for (Alternative a : alternatives) {
                require(!isFalseConstant(a.operation()) || computedField.kind() != ValueKind.CONFIRM,
                        KernelCode.MVK_INVALID_COMPARE_TO_YES, "the computed CONFIRM field " + computedPath
                                + " takes only the constant True — the kernel rejects False");
                require(operationMatchesComputedKind(a.operation()),
                        assignmentMismatchMessage(a.operation(), computedPath));
            }
            requireNoSelfReference(strongestSelfRead(computedPath), computedPath);
            for (Alternative a : alternatives) {
                a.precondition().ifPresent(this::requireNoCustomCondition);
            }
            if (commonPrecondition != null) {
                requireNoCustomCondition(commonPrecondition);
            }

            String groupPath = group != null ? group.path().value()
                    : computedPath.substring(0, computedPath.lastIndexOf('/'));
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
                case PROJECTED -> fail(KernelCode.MVK_ERROR_SEMANTIC_INDEX_OR_CATEGORY_FOR_ERRORFIELD,
                        "the computed field " + computedPath + " must not be read through a semantic index"
                                + " or category projection in " + selfRead.slot());
                case PLAIN -> fail(KernelCode.MVK_ERROR_REFERENCE_TO_CALCULATED_FIELD, "the computed field "
                        + computedPath + " must not be referenced in " + selfRead.slot());
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
            if (operation instanceof FirstFilledValue
                    && FirstFilledValue.sameOwnerFamily(operation.kind(), computedField.kind())) {
                return true;
            }
            if (operation instanceof BaseYear && computedField.kind() == ValueKind.NUMBER) {
                return true;
            }
            if (operation instanceof BoolLiteral && computedField.kind() == ValueKind.CONFIRM) {
                return true;
            }
            TemporalFormatFamily wanted = TemporalFormatFamily.ofDefaultFor(operation.kind());
            TemporalFormatFamily target = temporalTargetFamily();
            if (wanted != null && target != null) {
                // On a temporal pair the format REPLACES kind equality rather than joining it: equal kinds are
                // not a pass either, because a DATE-kind field declared `HH:mm:ss` refuses a date value. Both
                // directions are measured (KF222, `adapter.laws.ConstantTargetFormatLawsTest`).
                return wanted == target;
            }
            return operation.kind() == computedField.kind();
        }

        /**
         * The family the computed field actually accepts: its declared temporal FORMAT's, falling back to its
         * kind's default when it declares none — the case where kind and format say the same thing anyway.
         * Null when the field's kind is outside the governed set, which keeps plain kind equality in charge
         * there instead of silently widening it.
         */
        private TemporalFormatFamily temporalTargetFamily() {
            TemporalFormatFamily declared = TemporalFormatFamily.of(computedFieldFormat);
            return declared != null ? declared : TemporalFormatFamily.ofDefaultFor(computedField.kind());
        }

        /**
         * The refusal text, naming whichever gate actually decided. On a temporal target with a declared
         * format the kind is not the reason and saying "kinds must match" would send the author to change the
         * wrong declaration — the format is what the kernel reads, so the message names it and what it takes.
         */
        private String assignmentMismatchMessage(Calculation operation, String computedPath) {
            String rendered = new DslRenderer().render(operation);
            TemporalFormatFamily target = temporalTargetFamily();
            if (computedFieldFormat != null && target != null
                    && TemporalFormatFamily.ofDefaultFor(operation.kind()) != null) {
                return "the operation '" + rendered + "' yields " + operation.kind() + " but the computed"
                        + " field " + computedPath + " declares format '" + computedFieldFormat + "', which"
                        + " takes " + target + " values — the kernel's assignment gate reads the declared"
                        + " FORMAT, never the field's kind";
            }
            return "the operation '" + rendered + "' yields " + operation.kind() + " but the computed field "
                    + computedPath + " is " + computedField.kind() + " — kinds must match";
        }

        private static boolean isFalseConstant(Calculation operation) {
            return operation instanceof BoolLiteral(boolean value) && !value;
        }

        /** A refusal that names the kernel code it claims. */
        private void require(boolean ok, KernelCode code, String problem) {
            if (!ok) {
                fail(code, problem);
            }
        }

        /**
         * A refusal with NO kernel code, for a shape whose kernel counterpart has not been measured. The code
         * used to be lifted out of the message by regex, so a message that happened to name one carried it
         * and a typo silently carried nothing; naming it explicitly is what makes the difference visible.
         */
        private void require(boolean ok, String problem) {
            if (!ok) {
                throw new PrecheckException(Diagnostic.precheck(Optional.empty(), problem,
                        new Diagnostic.Where(computedField.path().value(), Optional.empty()),
                        Optional.empty(), Optional.empty()));
            }
        }

        /**
         * Fail fast with a structured PRECHECK diagnostic (a {@link PrecheckException}, itself an
         * {@code IllegalStateException}) — parity with {@code Rule.Builder.check}. The {@code MVK_*}/
         * The code is named by the caller, so a reader gets it from {@code diagnostic().code()} rather than
         * by scraping prose.
         */
        private void fail(KernelCode code, String problem) {
            throw new PrecheckException(Diagnostic.precheck(Optional.of(code.name()),
                    Refusals.message(code, problem),
                    new Diagnostic.Where(computedField.path().value(), Optional.empty()),
                    Optional.empty(), Optional.empty()));
        }

        /** CustomCondition is forbidden in computation rules (KERNEL-SEMANTICS §14). */
        private void requireNoCustomCondition(Condition c) {
            switch (c) {
                case CustomConditionRef ignored -> fail(KernelCode.MVK_ERROR_INVALID_LANGUAGE_FEATURE,
                        "CustomCondition is not allowed in a computation rule (KERNEL-SEMANTICS §14)");
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
