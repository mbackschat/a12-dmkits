package io.github.mbackschat.a12.dm.rulekit.validate.laws;

import static io.github.mbackschat.a12.dm.rulekit.dsl.Dsl.en;

import java.util.List;
import java.util.function.Consumer;

import org.junit.jupiter.api.Test;

import io.github.mbackschat.a12.dm.modelkit.DateFieldBuilder;
import io.github.mbackschat.a12.dm.modelkit.DocumentModelDraft;
import io.github.mbackschat.a12.dm.modelkit.DocumentModelDraft.GroupBuilder;
import io.github.mbackschat.a12.dm.modelkit.NumberFieldBuilder;
import io.github.mbackschat.a12.dm.modelkit.StringFieldBuilder;
import io.github.mbackschat.a12.dm.rulekit.dsl.BoundModel;
import io.github.mbackschat.a12.dm.rulekit.dsl.Condition;
import io.github.mbackschat.a12.dm.rulekit.dsl.DateExpr;
import io.github.mbackschat.a12.dm.rulekit.dsl.Dsl;
import io.github.mbackschat.a12.dm.rulekit.dsl.Rule;
import io.github.mbackschat.a12.dm.rulekit.dsl.Severity;
import io.github.mbackschat.a12.dm.rulekit.dsl.TemporalComponent;

/**
 * KERNEL-FINDINGS §6 — the serializable declaration gates for a field in a {@code Date(...)} or
 * {@code Time(...)} component position (SPEC-2026-07-27-10/-08/-13).
 *
 * <p>The matrix separates a Number field's stored-text {@code maxLength} from {@code maxIntegerDigits}, locks
 * the exact/flexible positional maxima and the effective-zero/nonnegative requirements, and exercises every
 * recognized String pattern spelling. Complete Year's exact {@code yyyy} Date exception is pinned against the
 * four positions where the same field is refused. Extensible-enumeration is deliberately absent: the kernel
 * exposes it only through a client SPI and there is no DM-JSON declaration to invent (KF63).
 */
class TemporalComponentDeclarationLawsTest {

    private static final String WRONG_FORMAT = "MVK_WRONG_DATE_FORMAT_FOR_OP";
    private static final String NO_DIGIT_PATTERN = "MVK_NO_DIGIT_PATTERN";
    private static final String GROUP = "/Parts";
    private static final String COMPONENT = GROUP + "/Component";
    private static final String DATE_TARGET = GROUP + "/DateTarget";
    private static final String TIME_TARGET = GROUP + "/TimeTarget";

    @Test
    void numberStoredWidthsAdmitEveryDateAndTimePosition() {
        for (DatePosition position : DatePosition.values()) {
            accepts(numberModel(n -> n.min("0").maxLength(position.width)), dateCondition(position));
        }
        for (TimePosition position : TimePosition.values()) {
            accepts(numberModel(n -> n.min("0").maxLength(2)), timeCondition(position));
        }
    }

    @Test
    void numberPositionalMaximaIncludeTheTwoFlexibleDatePositions() {
        accepts(numberModel(n -> n.min("0").max("31")), dateCondition(DatePosition.DAY));
        accepts(numberModel(n -> n.min("0").max("12")), dateCondition(DatePosition.MONTH));
        accepts(numberModel(n -> n.min("0").max("1000")), dateCondition(DatePosition.YEAR));
        accepts(numberModel(n -> n.min("0").max("9999")), dateCondition(DatePosition.YEAR));
        accepts(numberModel(n -> n.min("0").max("10")), dateCondition(DatePosition.CENTURY));
        accepts(numberModel(n -> n.min("0").max("99")), dateCondition(DatePosition.CENTURY));
        accepts(numberModel(n -> n.min("0").max("99")), dateCondition(DatePosition.SHORT_YEAR));

        accepts(numberModel(n -> n.min("0").max("23")), timeCondition(TimePosition.HOUR));
        accepts(numberModel(n -> n.min("0").max("59")), timeCondition(TimePosition.MINUTE));
        accepts(numberModel(n -> n.min("0").max("59")), timeCondition(TimePosition.SECOND));
    }

    @Test
    void integerDigitFractionSignAndPositionNearMissesAreRefused() {
        rejects(numberModel(n -> n.min("0").maxIntegerDigits(2)), dateCondition(DatePosition.DAY), WRONG_FORMAT);
        rejects(numberModel(n -> n.min("0").maxLength(2).fractionDigits(1)),
                dateCondition(DatePosition.DAY), WRONG_FORMAT);
        rejects(numberModel(n -> n.maxLength(2)), dateCondition(DatePosition.DAY), WRONG_FORMAT);
        rejects(numberModel(n -> n.min("-1").maxLength(2)), dateCondition(DatePosition.DAY), WRONG_FORMAT);
        rejects(numberModel(n -> n.min("0").maxLength(3)), dateCondition(DatePosition.DAY), WRONG_FORMAT);
        rejects(numberModel(n -> n.min("0").max("999")), dateCondition(DatePosition.YEAR), WRONG_FORMAT);
        rejects(numberModel(n -> n.min("0").max("9")), dateCondition(DatePosition.CENTURY), WRONG_FORMAT);
        rejects(numberModel(n -> n.min("0").max("98")), dateCondition(DatePosition.SHORT_YEAR), WRONG_FORMAT);
        rejects(numberModel(n -> n.min("0").max("59")), timeCondition(TimePosition.HOUR), WRONG_FORMAT);
        rejects(numberModel(n -> n.min("0").max("23")), timeCondition(TimePosition.MINUTE), WRONG_FORMAT);
    }

    @Test
    void everyRecognizedStringPatternSpellingIsAdmitted() {
        for (String pattern : List.of("[0-9]+", "[0-9]*", "\\d+", "\\d*", "[0-9]{4}", "\\d{4}")) {
            accepts(stringModel(s -> s.maxLength(4).pattern(pattern, "digits")),
                    dateCondition(DatePosition.YEAR));
        }
    }

    @Test
    void aDigitPatternStringIsAdmittedAtEveryPositionWithItsExactStoredWidth() {
        for (DatePosition position : DatePosition.values()) {
            accepts(stringModel(s -> s.maxLength(position.width).pattern("[0-9]+", "digits")),
                    dateCondition(position));
        }
        for (TimePosition position : TimePosition.values()) {
            accepts(stringModel(s -> s.maxLength(2).pattern("\\d*", "digits")),
                    timeCondition(position));
        }
    }

    @Test
    void regexEquivalenceMissingPatternAndWrongWidthDoNotWidenTheStringArm() {
        rejects(stringModel(s -> s.maxLength(2).pattern("[0123456789]+", "digits")),
                dateCondition(DatePosition.DAY), NO_DIGIT_PATTERN);
        rejects(stringModel(s -> s.maxLength(2)), dateCondition(DatePosition.DAY), NO_DIGIT_PATTERN);
        rejects(stringModel(s -> s.maxLength(3).pattern("[0-9]+", "digits")),
                dateCondition(DatePosition.DAY), WRONG_FORMAT);
    }

    @Test
    void exactYearDateIsAdmittedOnlyAtCompleteYear() {
        accepts(dateModel(d -> d.format("yyyy")), dateCondition(DatePosition.YEAR));
        rejects(dateModel(d -> d.format("yyyy")), dateCondition(DatePosition.DAY), WRONG_FORMAT);
        rejects(dateModel(d -> d.format("yyyy")), dateCondition(DatePosition.MONTH), WRONG_FORMAT);
        rejects(dateModel(d -> d.format("yyyy")), dateCondition(DatePosition.CENTURY), WRONG_FORMAT);
        rejects(dateModel(d -> d.format("yyyy")), dateCondition(DatePosition.SHORT_YEAR), WRONG_FORMAT);
        rejects(dateModel(d -> d.format("yyyy-MM-dd")), dateCondition(DatePosition.YEAR), WRONG_FORMAT);
    }

    private static ModelCase numberModel(Consumer<NumberFieldBuilder> component) {
        return model(group -> group.numberField("Component", component));
    }

    private static ModelCase stringModel(Consumer<StringFieldBuilder> component) {
        return model(group -> group.stringField("Component", component));
    }

    private static ModelCase dateModel(Consumer<DateFieldBuilder> component) {
        return model(group -> group.dateField("Component", component));
    }

    private static ModelCase model(Consumer<GroupBuilder> component) {
        DocumentModelDraft draft = DocumentModelDraft.named("temporal-components").locale("en_US")
                .rootGroup("Parts", group -> {
                    component.accept(group);
                    group.dateField("DateTarget", field -> field.format("yyyy-MM-dd"))
                            .timeField("TimeTarget", field -> field.format("HH:mm:ss"));
                })
                .build();
        return new ModelCase(draft.toJson(), BoundModel.ofJson(draft.toJson()));
    }

    private static ConditionFactory dateCondition(DatePosition position) {
        return model -> {
            TemporalComponent component = model.field(COMPONENT);
            DateExpr constructed = switch (position) {
                case DAY -> Dsl.date(component, Dsl.temporalComponent("1"), Dsl.temporalComponent("1963"));
                case MONTH -> Dsl.date(Dsl.temporalComponent("1"), component, Dsl.temporalComponent("1963"));
                case YEAR -> Dsl.date(Dsl.temporalComponent("1"), Dsl.temporalComponent("1"), component);
                case CENTURY -> Dsl.date(Dsl.temporalComponent("1"), Dsl.temporalComponent("1"),
                        component, Dsl.temporalComponent("63"));
                case SHORT_YEAR -> Dsl.date(Dsl.temporalComponent("1"), Dsl.temporalComponent("1"),
                        Dsl.temporalComponent("19"), component);
            };
            return model.date(DATE_TARGET).eq(constructed);
        };
    }

    private static ConditionFactory timeCondition(TimePosition position) {
        return model -> {
            TemporalComponent component = model.field(COMPONENT);
            return model.time(TIME_TARGET).eq(switch (position) {
                case HOUR -> Dsl.time(component);
                case MINUTE -> Dsl.time(Dsl.temporalComponent("1"), component);
                case SECOND -> Dsl.time(Dsl.temporalComponent("1"), Dsl.temporalComponent("1"), component);
            });
        };
    }

    private static void accepts(ModelCase model, ConditionFactory condition) {
        KernelLaws.acceptsModel(model.json, rule(model, condition.create(model.model)));
    }

    private static void rejects(ModelCase model, ConditionFactory condition, String code) {
        KernelLaws.rejectsModel(model.json, rule(model, condition.create(model.model)), code);
    }

    private static Rule rule(ModelCase model, Condition condition) {
        return model.model.rule(model.model.group(GROUP))
                .name("ProbeRule")
                .errorField(model.model.field(COMPONENT))
                .when(condition)
                .code("PROBE")
                .severity(Severity.ERROR)
                .message(en("probe"))
                .build();
    }

    private record ModelCase(String json, BoundModel model) { }

    @FunctionalInterface
    private interface ConditionFactory {
        Condition create(BoundModel model);
    }

    private enum DatePosition {
        DAY(2), MONTH(2), YEAR(4), CENTURY(2), SHORT_YEAR(2);

        private final int width;

        DatePosition(int width) {
            this.width = width;
        }
    }

    private enum TimePosition { HOUR, MINUTE, SECOND }
}
