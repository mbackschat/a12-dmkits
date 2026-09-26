package io.github.mbackschat.a12.dm.rulekit.validate.laws;

import io.github.mbackschat.a12.dm.modelkit.DocumentModelDraft;
import io.github.mbackschat.a12.dm.modelkit.FieldKind;
import io.github.mbackschat.a12.dm.rulekit.validate.DraftRule;
import io.github.mbackschat.a12.dm.rulekit.validate.RuleValidator;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

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

    private static void row(Map<String, List<String>> actual, Map<String, List<String>> expected, String operand,
                            List<String> predicted) {
        actual.put(operand, codes("FieldFilled(Limit) And " + operand));
        expected.put(operand, predicted);
    }

    private static List<String> codes(String condition) {
        return new RuleValidator().check(POLICY, new DraftRule("/Policy/Claim/Cover", "CloseRule", "../Limit",
                        condition, "PROBE", "ERROR"))
                .diagnostics().stream().flatMap(d -> d.code().stream()).toList();
    }
}
