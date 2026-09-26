package io.github.mbackschat.a12.dm.rulekit.validate.laws;

import io.github.mbackschat.a12.dm.modelkit.DocumentModelDraft;
import io.github.mbackschat.a12.dm.modelkit.FieldKind;
import io.github.mbackschat.a12.dm.rulekit.validate.DraftRule;
import io.github.mbackschat.a12.dm.rulekit.validate.RuleValidator;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BiConsumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import org.junit.jupiter.api.Test;

/**
 * Where a malformed path's classes come from: a reference's class is raised when its production CLOSES, and
 * a production that never closes raises none.
 *
 * <p>The mechanism, read from source (kernel {@code 30.8.1}): {@code entitySpec} runs {@code checkEntitySpec}
 * as its closing action, and the condition parser's error strategy neither recovers nor looks ahead
 * ({@code AbstractDefaultErrorStrategy} throws on the first error and its {@code sync} does nothing). So the
 * parser closes {@code entity} on the first token that cannot continue it and checks it there, and only then
 * fails to match that token.
 *
 * <p>Each rival account gets a row it predicts wrongly. "Any parse error pre-empts the reference classes"
 * predicts the token class for {@code NoSuch Code}, where the closed {@code NoSuch} draws
 * {@code MVK_INVALID_ENTITY}. "The whole run of path tokens is the reference" predicts
 * {@code MVK_INVALID_ENTITY} for {@code NoSuch/}, where the production is still open at the {@code )} and only
 * the token class is drawn. A climb chains only through a separator ({@code relUp: ('..' '/')+}), so
 * {@code .. ..} is one closed climb and a stray {@code ..}, even though the lexer reads {@code ....} the same
 * way ([KERNEL-FINDINGS KF76]).
 */
class EntityProductionCloseLawsTest {

    private static final String POLICY = DocumentModelDraft.named("close-policy").locale("en_US")
            .rootGroup("Policy", p -> p.field("Code", FieldKind.STRING)
                    .group("Claim", c -> c.field("Amount", FieldKind.STRING)
                            .group("Cover", v -> v.field("Limit", FieldKind.STRING))))
            .build().toJson();

    @Test
    void aReferenceIsCheckedWhereItsProductionClosesAndNotBefore() {
        Map<String, List<String>> actual = new LinkedHashMap<>();
        Map<String, List<String>> expected = new LinkedHashMap<>();
        row(actual, expected, "FieldFilled(../../Code)", List.of());
        row(actual, expected, "FieldFilled(.. / .. / Code)", List.of());
        row(actual, expected, "FieldFilled(NoSuch Code)", List.of("MVK_INVALID_ENTITY"));
        row(actual, expected, "FieldFilled(NoSuch/)", List.of("MVK_UNEXPECTED_TOKEN"));
        row(actual, expected, "FieldFilled(NoSuch/And)", List.of("MVK_UNEXPECTED_TOKEN"));
        row(actual, expected, "FieldFilled(..)", List.of("MVK_NO_GROUPS_ALLOWED"));
        row(actual, expected, "FieldFilled(.. ..)", List.of("MVK_UNEXPECTED_TOKEN"));
        row(actual, expected, "FieldFilled(.. .. / Code)", List.of("MVK_UNEXPECTED_TOKEN"));
        row(actual, expected, "FieldFilled(..../Code)", List.of("MVK_UNEXPECTED_TOKEN"));
        assertEquals(expected, actual, "which class each malformed path draws, from /Policy/Claim/Cover");
    }

    /**
     * The same law for an operation, and the one arithmetic spelling a bare reference has.
     *
     * {@code checkOperation} runs once an arithmetic chain is read, so a chain the parser stops inside draws the
     * token class and not its operand's type class, against the closed chain beside it. A {@code *} directly
     * after a bare name is that name's repetition STAR ({@code idNameRef: idName ('.' '*')? stern='*'?}), so
     * {@code FieldFilled(Premium * 2)} is the starred single field {@code Premium*}, refused as such before the
     * stray {@code 2}. Every other operator after a bare reference is a token the parser cannot use
     * ([KERNEL-FINDINGS KF76]). The category after {@code ->} is a name, so a keyword or nothing there is too.
     */
    @Test
    void anArithmeticChainIsCheckedOnlyOnceItCloses() {
        Map<String, List<String>> actual = new LinkedHashMap<>();
        Map<String, List<String>> expected = new LinkedHashMap<>();
        BiConsumer<String, List<String>> row = (condition, predicted) -> {
            actual.put(condition, policyCodes("FieldFilled(Code) And " + condition));
            expected.put(condition, predicted);
        };
        row.accept("[Code] * 2 == \"ABC\"", List.of("MVK_NO_NUMBER_FOR_CALC"));
        row.accept("[Code] * == \"ABC\"", List.of("MVK_UNEXPECTED_TOKEN"));
        row.accept("[Code] + * \"x\" == \"ABC\"", List.of("MVK_UNEXPECTED_TOKEN"));
        row.accept("[Premium] - == 1", List.of("MVK_UNEXPECTED_TOKEN"));
        row.accept("[Code] - PatternViolated \"[0-9]{5}\"", List.of("MVK_UNEXPECTED_TOKEN"));
        row.accept("[Premium] * Abs([Premium]) > 1", List.of());
        row.accept("MinValue(Items*/Count) > 1", List.of());
        row.accept("MinValue(Items**Count) > 1", List.of("MVK_UNEXPECTED_TOKEN"));
        row.accept("MinValue(Items*/Count - 1) > 1", List.of("MVK_UNEXPECTED_TOKEN"));
        row.accept("MinValue(Items*/Count ^ 2) > 1", List.of("MVK_UNEXPECTED_TOKEN"));
        row.accept("FieldFilled(Premium * 2)", List.of("MVK_INVALID_WILDCARD"));
        row.accept("FieldFilled(NoSuch * 2)", List.of("MVK_INVALID_ENTITY"));
        row.accept("[Code -> ] == \"x\"", List.of("MVK_UNEXPECTED_TOKEN"));
        row.accept("[Code -> And X] == \"x\"", List.of("MVK_UNEXPECTED_TOKEN"));
        assertEquals(expected, actual, "which class each malformed operation draws, from /Policy");
    }

    private static void row(Map<String, List<String>> actual, Map<String, List<String>> expected, String operand,
                            List<String> predicted) {
        actual.put(operand, codes("FieldFilled(Limit) And " + operand));
        expected.put(operand, predicted);
    }

    /** A root group with a text, a number and a repeatable group, for the operation rows. */
    private static final String ARITHMETIC = DocumentModelDraft.named("close-arithmetic").locale("en_US")
            .rootGroup("Policy", p -> p.field("Code", FieldKind.STRING).field("Premium", FieldKind.NUMBER)
                    .group("Items", i -> i.repeatable(9).field("Count", FieldKind.NUMBER)))
            .build().toJson();

    private static List<String> policyCodes(String condition) {
        return new RuleValidator().check(ARITHMETIC, new DraftRule("/Policy", "CloseRule", "../Code", condition,
                        "PROBE", "ERROR"))
                .diagnostics().stream().flatMap(d -> d.code().stream()).toList();
    }

    private static List<String> codes(String condition) {
        return new RuleValidator().check(POLICY, new DraftRule("/Policy/Claim/Cover", "CloseRule", "../Limit",
                        condition, "PROBE", "ERROR"))
                .diagnostics().stream().flatMap(d -> d.code().stream()).toList();
    }
}
