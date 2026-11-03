package io.github.mbackschat.a12.dm.rulekit.validate.laws;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import io.github.mbackschat.a12.dm.modelkit.DocumentModelDraft;

/**
 * KERNEL-FINDINGS KF375 — what a refused text does to the calculation module's classes about a computation's
 * OTHER members: its rounding mode, its target, the shape of its alternatives, and the computations together;
 * how the module's own classes rank against each other, and against the error-text classes.
 *
 * <p>Each class is written alone, then beside one text defect at a time: an arity gate and an unresolvable
 * name in the precondition, a rounding gate and an unresolvable name in the operation. Every defect is also
 * written without the class, so each row withholds, or loses to, a class that is live on its own.
 *
 * <p>Own domain (an online checkout).
 */
class CalculationMemberWithholdingLawsTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static final String CHECKOUT = DocumentModelDraft.named("checkout").locale("en_US")
            .rootGroup("Checkout", checkout -> checkout
                    .numberField("Subtotal", f -> f.fractionDigits(2))
                    .numberField("Discount", f -> f.fractionDigits(2))
                    .numberField("Total", f -> f.fractionDigits(2))
                    .numberField("Tax", f -> f.fractionDigits(2))
                    .stringField("Coupon", f -> { })
                    .stringField("Label", f -> { })
                    .enumField("Tier", e -> e.value("GOLD").value("SILVER"))
                    .group("Lines", lines -> lines.repeatable(5).indexField("Sku")
                            .stringField("Sku", f -> { })
                            .numberField("Quantity", f -> f.fractionDigits(0)))
                    .group("Parcels", parcels -> parcels.repeatable(3).indexField("Kind")
                            .enumField("Kind", e -> e.value("BOX").value("ENVELOPE"))))
            .build().toJson();

    /** A precondition carrying the one construct a computation may not use. */
    private static final String FEATURE = "FieldFilled(Coupon) And CustomCondition NotReverse";

    /** The sound precondition and operation each shape starts from; a defect replaces one of them. */
    private static final String PRECONDITION = "FieldFilled(Coupon)";

    /**
     * Each member class, alone and beside one text defect of its OWN computation. The member classes are drawn
     * only once both texts parsed and every name resolved, so each defect answers alone — and each defect is
     * also written into a computation carrying no member class, which is what makes its class live here.
     */
    @Test
    void aTextDefectWithholdsItsOwnComputationsMemberClasses() {
        Map<String, String> actual = new LinkedHashMap<>();
        Map<String, String> expected = new LinkedHashMap<>();
        for (Defect defect : Defect.values()) {
            actual.put("no member class · " + defect, verdict(plain(defect)));
            expected.put("no member class · " + defect, defect.alone());
            for (Shape shape : Shape.values()) {
                actual.put(shape + " · " + defect, verdict(shape.written(defect)));
                expected.put(shape + " · " + defect, defect == Defect.NONE ? shape.code : defect.alone());
            }
        }
        assertEquals(expected, actual, "a member class beside a defect of its own computation's texts");
    }

    /**
     * The same classes beside a defect somewhere ELSE in the model, which is where the four part: the three
     * about one computation's members stand beside it, and the cycle does not.
     *
     * <p>The cycle is raised by the code generator ({@code CodeGenCalcBuilder}, kernel-core-codegen), not by
     * the consistency check. Every other class tried withholds it — another computation's text defect or
     * member class, a rule's text defect, and a structural class naming no text at all — while the three
     * per-computation classes stand beside each kind they meet here.
     */
    @Test
    void aDefectElsewhereWithholdsOnlyTheCycle() {
        Map<String, String> actual = new LinkedHashMap<>();
        for (Defect defect : List.of(Defect.PRECONDITION_ARITY, Defect.OPERATION_NAME)) {
            for (Shape shape : Shape.values()) {
                Calc elsewhere = plain(defect).get(0).named("TaxCalc", "../Tax");
                actual.put(shape + " · another computation's " + defect,
                        verdict(beside(shape.written(Defect.NONE), elsewhere)));
            }
        }
        actual.put("CYCLE · another computation's rounding class",
                verdict(beside(Shape.CYCLE.written(Defect.NONE), taxCalc("RoundUp", SOUND))));
        actual.put("CYCLE · another computation's mixed alternatives",
                verdict(beside(Shape.CYCLE.written(Defect.NONE), taxCalc(null, UNCONDITIONAL, SOUND))));
        actual.put("CYCLE · a sound rule — the control", withRule(Shape.CYCLE, PRECONDITION));
        actual.put("CYCLE · a rule's arity gate", withRule(Shape.CYCLE, Defect.PRECONDITION_ARITY.precondition()));
        actual.put("CYCLE · a rule naming nothing", withRule(Shape.CYCLE, Defect.PRECONDITION_NAME.precondition()));
        actual.put("ROUNDING · a rule's arity gate",
                withRule(Shape.ROUNDING, Defect.PRECONDITION_ARITY.precondition()));
        actual.put("a structural class alone", withBadField(modelOf(plain(Defect.NONE))));
        actual.put("CYCLE · a structural class", withBadField(modelOf(Shape.CYCLE.written(Defect.NONE))));
        actual.put("ROUNDING · a structural class", withBadField(modelOf(Shape.ROUNDING.written(Defect.NONE))));

        Map<String, String> expected = new LinkedHashMap<>();
        expected.put("ROUNDING · another computation's PRECONDITION_ARITY",
                "MVK_ERROR_WRONG_ROUNDING_MODE,MVK_PARAMSIZE_INVALID1");
        expected.put("INDEX_TARGET · another computation's PRECONDITION_ARITY",
                "MVK_PARAMSIZE_INVALID1,MVK_ERROR_INDEX_AS_CALCULATED_FIELD");
        expected.put("MIXED · another computation's PRECONDITION_ARITY",
                "MVK_ERROR_EMPTY_PRECONDITION,MVK_PARAMSIZE_INVALID1");
        expected.put("CYCLE · another computation's PRECONDITION_ARITY", "MVK_PARAMSIZE_INVALID1");
        expected.put("ROUNDING · another computation's OPERATION_NAME",
                "MVK_ERROR_WRONG_ROUNDING_MODE,MVK_INVALID_ENTITY");
        expected.put("INDEX_TARGET · another computation's OPERATION_NAME",
                "MVK_INVALID_ENTITY,MVK_ERROR_INDEX_AS_CALCULATED_FIELD");
        expected.put("MIXED · another computation's OPERATION_NAME",
                "MVK_ERROR_EMPTY_PRECONDITION,MVK_INVALID_ENTITY");
        expected.put("CYCLE · another computation's OPERATION_NAME", "MVK_INVALID_ENTITY");
        expected.put("CYCLE · another computation's rounding class", "MVK_ERROR_WRONG_ROUNDING_MODE");
        expected.put("CYCLE · another computation's mixed alternatives", "MVK_ERROR_EMPTY_PRECONDITION");
        expected.put("CYCLE · a sound rule — the control", "MVK_ERROR_CALC_CYCLE");
        expected.put("CYCLE · a rule's arity gate", "MVK_PARAMSIZE_INVALID1");
        expected.put("CYCLE · a rule naming nothing", "MVK_INVALID_ENTITY");
        expected.put("ROUNDING · a rule's arity gate", "MVK_ERROR_WRONG_ROUNDING_MODE,MVK_PARAMSIZE_INVALID1");
        expected.put("a structural class alone", "MVK_PATH_INVALID");
        expected.put("CYCLE · a structural class", "MVK_PATH_INVALID");
        expected.put("ROUNDING · a structural class", "MVK_ERROR_WRONG_ROUNDING_MODE,MVK_PATH_INVALID");
        assertEquals(expected, actual, "a member class beside a defect elsewhere in the model");
    }

    /**
     * The calculation module's classes on ONE computation, which A12 asks in stages: the target, then the
     * normal form, then every alternative, and only once all of those passed the rounding mode
     * ({@code CalculationService.checkCalculationRule}, {@code NormalisierteBerechnungsFormHelper}). A failed
     * stage ends the check, so a later stage's class is withheld behind an earlier one, while the classes one
     * stage draws stand together — the alternatives are each judged, whatever the one before them drew.
     */
    @Test
    void theCalculationModuleAsksItsStagesInOrder() {
        Map<String, String> actual = new LinkedHashMap<>();
        actual.put("the language feature alone", verdict(List.of(total(null, new Alternative(FEATURE, "[Subtotal]")))));
        actual.put("ROUNDING · the language feature",
                verdict(List.of(total("RoundUp", new Alternative(FEATURE, "[Subtotal]")))));
        actual.put("MIXED · the language feature",
                verdict(List.of(total(null, UNCONDITIONAL, new Alternative(FEATURE, "[Subtotal]")))));
        actual.put("INDEX_TARGET · the language feature", verdict(List.of(
                new Calc("SkuCalc", "../Lines/Sku", null, List.of(new Alternative(FEATURE, "[Coupon]"))))));
        actual.put("an enumeration source alone", verdict(List.of(tier(null, new Alternative(PRECONDITION, "[Label]")))));
        actual.put("MIXED · an enumeration source",
                verdict(List.of(tier(null, new Alternative(null, "[Label]"), new Alternative(PRECONDITION, "[Label]")))));
        actual.put("INDEX_TARGET · an enumeration source", verdict(List.of(
                new Calc("KindCalc", "../Parcels/Kind", null, List.of(new Alternative(PRECONDITION, "[Label]"))))));
        actual.put("ROUNDING · an enumeration source",
                verdict(List.of(tier("RoundUp", new Alternative(PRECONDITION, "[Label]")))));
        actual.put("a read of the computed field alone", verdict(List.of(label(new Alternative(SELF_READ, "[Coupon]")))));
        actual.put("MIXED · a read of the computed field",
                verdict(List.of(label(new Alternative(null, "[Coupon]"), new Alternative(SELF_READ, "[Coupon]")))));
        actual.put("ROUNDING · a read of the computed field", verdict(List.of(new Calc("LabelCalc", "../Label",
                "RoundUp", List.of(new Alternative(SELF_READ, "[Coupon]"))))));
        actual.put("ROUNDING · a read of the computed NUMBER field",
                verdict(List.of(total("RoundUp", new Alternative("FieldFilled(Total)", "[Subtotal]")))));
        actual.put("INDEX_TARGET · a read of the computed field", KernelLaws.verdictOf(edited(modelOf(List.of(
                new Calc("SkuCalc", "../Sku", null, List.of(new Alternative("FieldFilled(Sku)", "\"x\""))))),
                root -> moveIntoLines(root, "SkuCalc"))));

        Map<String, String> expected = new LinkedHashMap<>();
        expected.put("the language feature alone", FEATURE_CLASS);
        expected.put("ROUNDING · the language feature", FEATURE_CLASS);
        expected.put("MIXED · the language feature", FEATURE_CLASS);
        expected.put("INDEX_TARGET · the language feature", Shape.INDEX_TARGET.code);
        expected.put("an enumeration source alone", ENUM_SOURCE);
        expected.put("MIXED · an enumeration source", Shape.MIXED.code + "," + ENUM_SOURCE);
        expected.put("INDEX_TARGET · an enumeration source", Shape.INDEX_TARGET.code);
        expected.put("ROUNDING · an enumeration source", ENUM_SOURCE);
        expected.put("a read of the computed field alone", SELF_READ_CLASS);
        expected.put("MIXED · a read of the computed field", Shape.MIXED.code + "," + SELF_READ_CLASS);
        expected.put("ROUNDING · a read of the computed field", SELF_READ_CLASS);
        expected.put("ROUNDING · a read of the computed NUMBER field", SELF_READ_CLASS);
        expected.put("INDEX_TARGET · a read of the computed field", Shape.INDEX_TARGET.code);
        assertEquals(expected, actual, "the calculation module's classes on one computation");
    }

    /**
     * The error-text classes beside the calculation module's. A12 withholds them behind a text the PARSER
     * refuses (KERNEL-FINDINGS KF338), and the calculation module is not the parser: every one of its classes
     * stands beside a missing or refused message, and so does the code generator's cycle.
     */
    @Test
    void theErrorTextClassesStandBesideTheCalculationModule() {
        Map<String, String> actual = new LinkedHashMap<>();
        actual.put("no message — the control", unmessaged(plain(Defect.NONE), "TotalCalc"));
        actual.put("no message · a text the parser refuses",
                unmessaged(plain(Defect.PRECONDITION_ARITY), "TotalCalc"));
        for (Shape shape : List.of(Shape.ROUNDING, Shape.INDEX_TARGET, Shape.MIXED)) {
            List<Calc> calcs = shape.written(Defect.NONE);
            actual.put("no message · " + shape, unmessaged(calcs, calcs.get(0).name()));
        }
        actual.put("no message · the language feature",
                unmessaged(List.of(total(null, new Alternative(FEATURE, "[Subtotal]"))), "TotalCalc"));
        actual.put("no message · an enumeration source",
                unmessaged(List.of(tier(null, new Alternative(PRECONDITION, "[Label]"))), "TierCalc"));
        actual.put("no message · a read of the computed field",
                unmessaged(List.of(label(new Alternative(SELF_READ, "[Coupon]"))), "LabelCalc"));
        actual.put("no message on the computation the cycle names", unmessaged(Shape.CYCLE.written(Defect.NONE),
                "DiscountCalc"));
        actual.put("a refused character · ROUNDING", KernelLaws.verdictOf(edited(modelOf(Shape.ROUNDING.written(
                Defect.NONE)), root -> ((ObjectNode) computation(root, "TotalCalc").path("errorMessage").get(0))
                .put("text", "see\tit"))));

        Map<String, String> unmessaged = new LinkedHashMap<>();
        String missing = "MVK_ERROR_MESSAGE_MISSING,RULE_HAS_NO_ERRORTEXT";
        unmessaged.put("no message — the control", missing);
        // The parser's class withholds the error-text class and not the presence one (KF338).
        unmessaged.put("no message · a text the parser refuses", "MVK_PARAMSIZE_INVALID1,MVK_ERROR_MESSAGE_MISSING");
        unmessaged.put("no message · ROUNDING", Shape.ROUNDING.code + "," + missing);
        unmessaged.put("no message · INDEX_TARGET", Shape.INDEX_TARGET.code + "," + missing);
        unmessaged.put("no message · MIXED", Shape.MIXED.code + "," + missing);
        unmessaged.put("no message · the language feature", FEATURE_CLASS + "," + missing);
        unmessaged.put("no message · an enumeration source", ENUM_SOURCE + "," + missing);
        unmessaged.put("no message · a read of the computed field", SELF_READ_CLASS + "," + missing);
        unmessaged.put("no message on the computation the cycle names", missing);
        unmessaged.put("a refused character · ROUNDING", Shape.ROUNDING.code + ",INVALID_CHARACTERS");
        assertEquals(unmessaged, actual, "the error-text classes beside the calculation module's");
    }

    private static final String FEATURE_CLASS = "MVK_ERROR_INVALID_LANGUAGE_FEATURE";

    private static final String ENUM_SOURCE = "MVK_ERROR_INVALID_ENUM_CALCULATION";

    private static final String SELF_READ_CLASS = "MVK_ERROR_REFERENCE_TO_CALCULATED_FIELD";

    /** A precondition reading the field its own computation writes. */
    private static final String SELF_READ = "FieldFilled(Label)";

    private static final Alternative SOUND = new Alternative(PRECONDITION, "[Subtotal]");

    private static final Alternative UNCONDITIONAL = new Alternative(null, "[Subtotal]");

    /** The four member classes, each written on its own model. */
    private enum Shape {
        /** `RoundUp` against the plain `!=` an alternative without a band lowers to. */
        ROUNDING("MVK_ERROR_WRONG_ROUNDING_MODE"),
        /** Into the index field of `Lines`, from the root group that contains it. */
        INDEX_TARGET("MVK_ERROR_INDEX_AS_CALCULATED_FIELD"),
        /** An unconditional alternative beside a preconditioned one, which carries the defect. */
        MIXED("MVK_ERROR_EMPTY_PRECONDITION"),
        /** Two computations each reading the other's field; the first carries the defect. */
        CYCLE("MVK_ERROR_CALC_CYCLE");

        final String code;

        Shape(String code) {
            this.code = code;
        }

        List<Calc> written(Defect defect) {
            Alternative carrying = new Alternative(defect.precondition(), defect.operation("[Subtotal]"));
            return switch (this) {
                case ROUNDING -> List.of(new Calc("TotalCalc", "../Total", "RoundUp", List.of(carrying)));
                case INDEX_TARGET -> List.of(new Calc("SkuCalc", "../Lines/Sku", null,
                        List.of(new Alternative(defect.precondition(), defect.operation("[Coupon]")))));
                case MIXED -> List.of(new Calc("TotalCalc", "../Total", null, List.of(UNCONDITIONAL, carrying)));
                case CYCLE -> List.of(
                        new Calc("TotalCalc", "../Total", null,
                                List.of(new Alternative(defect.precondition(), defect.operation("[Discount]")))),
                        new Calc("DiscountCalc", "../Discount", null,
                                List.of(new Alternative(PRECONDITION, "[Total]"))));
            };
        }
    }

    /** One text defect, or none, applied to the probed computation's precondition or operation. */
    private enum Defect {
        NONE, PRECONDITION_ARITY, PRECONDITION_NAME, OPERATION_ROUNDING, OPERATION_NAME;

        String precondition() {
            return switch (this) {
                case PRECONDITION_ARITY -> "FieldFilled(Coupon, Subtotal)";
                case PRECONDITION_NAME -> "FieldFilled(NoSuchField)";
                case NONE, OPERATION_ROUNDING, OPERATION_NAME -> PRECONDITION;
            };
        }

        /** The class this defect draws on its own, which the member classes are measured against. */
        String alone() {
            return switch (this) {
                case NONE -> KernelLaws.ADMITTED;
                case PRECONDITION_ARITY -> "MVK_PARAMSIZE_INVALID1";
                case PRECONDITION_NAME, OPERATION_NAME -> "MVK_INVALID_ENTITY";
                case OPERATION_ROUNDING -> "MVK_NO_NUMBER";
            };
        }

        String operation(String sound) {
            return switch (this) {
                case OPERATION_ROUNDING -> sound + " + RoundUpValue(Coupon, 2)";
                case OPERATION_NAME -> sound + " + [NoSuchField]";
                case NONE, PRECONDITION_ARITY, PRECONDITION_NAME -> sound;
            };
        }
    }

    private record Alternative(String precondition, String operation) { }

    private record Calc(String name, String target, String roundingMode, List<Alternative> alternatives) {
        Calc named(String otherName, String otherTarget) {
            return new Calc(otherName, otherTarget, roundingMode, alternatives);
        }
    }

    private static List<Calc> plain(Defect defect) {
        return List.of(new Calc("TotalCalc", "../Total", null,
                List.of(new Alternative(defect.precondition(), defect.operation("[Subtotal]")))));
    }

    private static Calc taxCalc(String roundingMode, Alternative... alternatives) {
        return new Calc("TaxCalc", "../Tax", roundingMode, List.of(alternatives));
    }

    private static Calc total(String roundingMode, Alternative... alternatives) {
        return new Calc("TotalCalc", "../Total", roundingMode, List.of(alternatives));
    }

    /** Into the enumeration {@code Tier}, whose sources a text field cannot be. */
    private static Calc tier(String roundingMode, Alternative... alternatives) {
        return new Calc("TierCalc", "../Tier", roundingMode, List.of(alternatives));
    }

    private static Calc label(Alternative... alternatives) {
        return new Calc("LabelCalc", "../Label", null, List.of(alternatives));
    }

    private static List<Calc> beside(List<Calc> shape, Calc other) {
        List<Calc> all = new ArrayList<>(shape);
        all.add(other);
        return all;
    }

    private static String verdict(List<Calc> calcs) {
        return KernelLaws.verdictOf(modelOf(calcs));
    }

    /** [shape]'s model with one rule on `Coupon` whose condition is [condition]. */
    private static String withRule(Shape shape, String condition) {
        return verdictWith(modelOf(shape.written(Defect.NONE)), elements -> {
            ObjectNode node = elements.addObject();
            node.put("type", "Rule").put("id", "rprobe").put("name", "Probe");
            ObjectNode body = node.putObject("Rule");
            body.put("errorCondition", condition).put("errorEntityRelPath", "../Coupon")
                    .put("errorCode", "C1").put("severity", "ERROR");
            body.putArray("errorMessage").addObject().put("locale", "en_US").put("text", "see it");
        });
    }

    /** [model] with one field no text reads, whose hyphenated name A12 refuses as a path. */
    private static String withBadField(String model) {
        return verdictWith(model, elements -> {
            ObjectNode field = elements.addObject();
            field.put("type", "Field").put("id", "fbad").put("name", "Gift-Note");
            field.putObject("Field").putObject("fieldType").put("type", "StringType");
        });
    }

    /** [calcs]' model with the error message of the computation [name] removed. */
    private static String unmessaged(List<Calc> calcs, String name) {
        return KernelLaws.verdictOf(edited(modelOf(calcs), root -> computation(root, name).remove("errorMessage")));
    }

    /** Moves the computation [name] from the root group into {@code Lines}. */
    private static void moveIntoLines(ObjectNode root, String name) {
        ArrayNode elements = elementsOf(root);
        for (int at = 0; at < elements.size(); at++) {
            if (name.equals(elements.get(at).path("name").asText())) {
                ObjectNode moved = (ObjectNode) elements.remove(at);
                groupNamed(elements, "Lines").withArray("elements").add(moved);
                return;
            }
        }
        throw new IllegalArgumentException("no computation " + name);
    }

    private static ObjectNode groupNamed(ArrayNode elements, String name) {
        for (var element : elements) {
            if (name.equals(element.path("name").asText())) {
                return (ObjectNode) element.path("Group");
            }
        }
        throw new IllegalArgumentException("no group " + name);
    }

    /** The {@code Computation} body of the root-group computation [name]. */
    private static ObjectNode computation(ObjectNode root, String name) {
        for (var element : elementsOf(root)) {
            if (name.equals(element.path("name").asText())) {
                return (ObjectNode) element.path("Computation");
            }
        }
        throw new IllegalArgumentException("no computation " + name);
    }

    private static String edited(String model, Consumer<ObjectNode> edit) {
        ObjectNode root = parse(model);
        edit.accept(root);
        return root.toString();
    }

    private static String verdictWith(String model, Consumer<ArrayNode> addition) {
        ObjectNode root = parse(model);
        addition.accept(elementsOf(root));
        return KernelLaws.verdictOf(root.toString());
    }

    private static String modelOf(List<Calc> calcs) {
        ObjectNode root = parse(CHECKOUT);
        ArrayNode elements = elementsOf(root);
        for (Calc calc : calcs) {
            ObjectNode node = elements.addObject();
            node.put("type", "Computation").put("id", "c" + calc.name()).put("name", calc.name());
            ObjectNode body = node.putObject("Computation");
            body.put("computedFieldRelPath", calc.target());
            if (calc.roundingMode() != null) {
                body.put("roundingMode", calc.roundingMode());
            }
            ArrayNode alternatives = body.putArray("computationAlternatives");
            for (Alternative alternative : calc.alternatives()) {
                ObjectNode written = alternatives.addObject();
                if (alternative.precondition() != null) {
                    written.put("precondition", alternative.precondition());
                }
                written.put("operation", alternative.operation());
            }
            body.putArray("errorMessage").addObject().put("locale", "en_US").put("text", "see it");
        }
        return root.toString();
    }

    private static ArrayNode elementsOf(ObjectNode root) {
        return (ArrayNode) root.path("content").path("modelRoot").path("rootGroups").get(0)
                .path("Group").path("elements");
    }

    private static ObjectNode parse(String model) {
        try {
            return (ObjectNode) MAPPER.readTree(model);
        } catch (IOException unreadable) {
            throw new UncheckedIOException("the fixture must parse", unreadable);
        }
    }
}
