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
 * OTHER members: its rounding mode, its target, the shape of its alternatives, and the computations together.
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
                    .group("Lines", lines -> lines.repeatable(5).indexField("Sku")
                            .stringField("Sku", f -> { })
                            .numberField("Quantity", f -> f.fractionDigits(0))))
            .build().toJson();

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
