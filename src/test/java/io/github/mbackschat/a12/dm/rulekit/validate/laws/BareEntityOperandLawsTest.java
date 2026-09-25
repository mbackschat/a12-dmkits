package io.github.mbackschat.a12.dm.rulekit.validate.laws;

import static io.github.mbackschat.a12.dm.rulekit.dsl.Dsl.de;
import static io.github.mbackschat.a12.dm.rulekit.dsl.Dsl.en;
import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import io.github.mbackschat.a12.dm.ModelDrafts;
import io.github.mbackschat.a12.dm.rulekit.validate.DraftComputation;
import io.github.mbackschat.a12.dm.rulekit.validate.DraftComputation.Alternative;

/**
 * KERNEL-FINDINGS KF472 — a bare entity reference is no operation and no condition: A12's grammar takes one
 * only where an {@code entitySpec} stands.
 *
 * <p>{@code einfacheOperation} and {@code einfacheBedingung} in {@code GrammarOnly_ConditionParser.g4} list no
 * alternative that begins with a name that is not a keyword: a field's value is read through
 * {@code feldWertOp}, {@code '[' entitySpec ']'}, and a bare {@code entitySpec} appears only as an operator's
 * argument or a clause's operand — and only in an argument slot the grammar types as one, so a literal-only
 * slot such as a rounding operator's digit count refuses it as well. So a bare name in a comparison, an
 * arithmetic term, a {@code { … }} group,
 * a filter body or a computation's operation is a token the parser cannot use. The rival reading — that the
 * name is taken as a reference and resolved — predicts {@code MVK_INVALID_ENTITY} for the names that resolve
 * to nothing, and every such row here draws the token class instead. A name followed by {@code (} is no call
 * either, since every call production begins with its keyword.
 *
 * <p>Own domain (the shared e-commerce basket: {@code Total}, a repeatable {@code Lines} {@code Qty}, and a
 * fixed {@code Fees} {@code Handling}).
 */
class BareEntityOperandLawsTest {

    private static final String BASKET = ModelDrafts.basket().toJson();

    @Test
    void aBareNameWhereAnOperationOrAConditionStandsIsATokenTheParserCannotUse() {
        Map<String, String> actual = new LinkedHashMap<>();
        for (String condition : List.of("Total > 0", "NoSuchField > 0", "[Total] > Total", "[Total] > NoSuchField",
                "[Total] > {Total}", "[Total] > [Total] + Total", "[Total] > /Basket/Total", "[Total] > $Total",
                "Lines*/Qty > 0", "(Total > 0)", "RoundUpValue(Total, 0) > Total",
                "Sum(Lines*/Qty Having Lines/Qty > 0) > 0", "Foo(Total)", "Foo (Total)", "[Total] == Foo(1)",
                "RoundUpValue(Total, 0) > 0", "[Total] == Sum(Lines*/Qty)",
                "Sum(Lines*/Qty Having [Lines/Qty] > 0) > 0", "RoundUpValue(Total, NoSuchField) > 0",
                "RoundUpValue(Total, Total) > 0")) {
            actual.put(condition, KernelLaws.verdictModel(BASKET, "/Basket", "../Total",
                    "FieldFilled(Total) And " + condition));
        }
        for (String operation : List.of("Fees/Handling", "[Fees/Handling] + Fees/Handling", "NoSuchField",
                "Foo([Total])", "[Fees/Handling]")) {
            actual.put("computation: " + operation, computation(operation));
        }

        Map<String, String> expected = new LinkedHashMap<>();
        // Every row draws the token class, the rounding digit count included: an argument slot the grammar
        // types as a literal takes no entity either.
        actual.keySet().forEach(row -> expected.put(row, "MVK_UNEXPECTED_TOKEN"));
        // The controls: the same names inside an operator's entity arguments or a field-value bracket.
        expected.put("RoundUpValue(Total, 0) > 0", KernelLaws.ADMITTED);
        expected.put("[Total] == Sum(Lines*/Qty)", KernelLaws.ADMITTED);
        expected.put("Sum(Lines*/Qty Having [Lines/Qty] > 0) > 0", KernelLaws.ADMITTED);
        expected.put("computation: [Fees/Handling]", KernelLaws.ADMITTED);
        assertEquals(expected, actual, "where a bare entity reference may stand");
    }

    private static String computation(String operation) {
        DraftComputation draft = DraftComputation.builder()
                .groupPath("/Basket").name("TotalCalc").computedFieldRelPath("../Total")
                .alternatives(List.of(new Alternative(null, operation)))
                .messages(List.of(en("x"), de("y")))
                .build();
        return KernelLaws.computationVerdict(BASKET, draft);
    }
}
