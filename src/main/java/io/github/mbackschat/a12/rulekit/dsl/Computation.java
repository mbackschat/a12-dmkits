package io.github.mbackschat.a12.rulekit.dsl;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import io.github.mbackschat.a12.rulekit.validate.DraftComputation;

/**
 * A typed computation — the F2 authoring object (study §4.3): a computed field + optional
 * {@code commonPrecondition} + alternatives. The computation element is placed in the computed
 * field's group, so {@code computedFieldRelPath} is computed, never written.
 *
 * <p><strong>Kernel-verified slot rules pre-checked at {@code build()}:</strong> alternatives are
 * either exactly one <em>unconditional</em> {@code calc(...)} or all <em>preconditioned</em>
 * {@code when(...).calc(...)} — mixing is rejected by the kernel ({@code MVK_ERROR_EMPTY_PRECONDITION});
 * the computed field must not be referenced in preconditions or operations
 * ({@code MVK_ERROR_REFERENCE_TO_CALCULATED_FIELD}); each operation's value kind must match the
 * computed field's kind (e.g. a string operation into a number field —
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
        List<LocalizedText> messages) {

    public Computation {
        alternatives = List.copyOf(alternatives);
        messages = List.copyOf(messages);
    }

    public record Alternative(Optional<Condition> precondition, Calculation operation) {
    }

    // Builders are created via BoundModel#computation(...) — model-aware so messages can be checked
    // against the model's DECLARED locales (which are arbitrary; en/de are not special). The
    // computation element is placed in the computed field's group.

    /** The computed field's path relative to the computation element (a sibling in the group). */
    public String computedFieldRelPath() {
        return "../" + computedField.path().substring(groupPath.length() + 1);
    }

    /** The bridge to the F4 validator ({@code RuleValidator.check(modelJson, computation.draft())}). */
    public DraftComputation draft() {
        DslRenderer renderer = new DslRenderer();
        return new DraftComputation(
                groupPath,
                name,
                computedFieldRelPath(),
                commonPrecondition.map(renderer::render).orElse(null),
                alternatives.stream()
                        .map(a -> new DraftComputation.Alternative(
                                a.precondition().map(renderer::render).orElse(null),
                                renderer.render(a.operation())))
                        .toList());
    }

    public static final class Builder {

        private final FieldRef computedField;
        private final List<String> declaredLocales;
        private String name;
        private Condition commonPrecondition;
        private final List<Alternative> alternatives = new ArrayList<>();
        private final List<LocalizedText> messages = new ArrayList<>();

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

        /** A preconditioned alternative; preconditions must be mutually exclusive (kernel-judged). */
        public AlternativeBuilder when(Condition precondition) {
            return new AlternativeBuilder(this, precondition);
        }

        public Builder message(LocalizedText... texts) {
            messages.addAll(List.of(texts));
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

            String computedPath = computedField.path();
            for (Alternative a : alternatives) {
                require(a.operation().kind() == computedField.kind(),
                        "the operation '" + new DslRenderer().render(a.operation()) + "' yields "
                                + a.operation().kind() + " but the computed field " + computedPath
                                + " is " + computedField.kind() + " — kinds must match");
                require(!ConditionReferences.of(a.operation()).covers(computedPath),
                        "the computed field " + computedPath + " must not be referenced in its own"
                                + " operation (MVK_ERROR_REFERENCE_TO_CALCULATED_FIELD)");
                a.precondition().ifPresent(p -> {
                    require(!ConditionReferences.of(p).covers(computedPath),
                            "the computed field " + computedPath + " must not be referenced in a"
                                    + " precondition (MVK_ERROR_REFERENCE_TO_CALCULATED_FIELD)");
                    requireNoCustomCondition(p);
                });
            }
            if (commonPrecondition != null) {
                require(!ConditionReferences.of(commonPrecondition).covers(computedPath),
                        "the computed field " + computedPath + " must not be referenced in the"
                                + " common precondition (MVK_ERROR_REFERENCE_TO_CALCULATED_FIELD)");
                requireNoCustomCondition(commonPrecondition);
            }

            String groupPath = computedPath.substring(0, computedPath.lastIndexOf('/'));
            return new Computation(groupPath, name, computedField,
                    Optional.ofNullable(commonPrecondition), alternatives, messages);
        }

        private static void require(boolean ok, String problem) {
            if (!ok) {
                throw new IllegalStateException(problem);
            }
        }

        /** CustomCondition is forbidden in computation rules (KERNEL-SEMANTICS §14). */
        private static void requireNoCustomCondition(Condition c) {
            switch (c) {
                case CustomConditionRef ignored -> throw new IllegalStateException(
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
    }
}
