package io.github.mbackschat.a12.dm.rulekit.validate.laws;

import static io.github.mbackschat.a12.dm.rulekit.dsl.Dsl.de;
import static io.github.mbackschat.a12.dm.rulekit.dsl.Dsl.en;
import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import io.github.mbackschat.a12.dm.Fixtures;
import io.github.mbackschat.a12.dm.rulekit.write.ModelWriter;

/**
 * Which CASES of a connective A12's lexer resolves, and that it judges every one of them alike.
 *
 * <p>A12 resolves an operator name dynamically against one language's terminal bundle, and that bundle carries
 * three spellings of each connective — {@code And}, {@code AND}, {@code and}. The generated keyword table
 * already reflects that and {@code ConditionKeywordProjectionTest} measures the union against the bundle in
 * both directions, so which spellings EXIST is settled. What was never measured is whether A12 then judges
 * them identically, and a checker that reports a mix or an unfinished text in a non-canonical case is
 * REFUSING input — so it owes a measured basis rather than the plausible assumption that case cannot matter.
 *
 * <p><b>The {@code aNd} rows are the point of the grid, not decoration.</b> A fourth case is NOT in the
 * bundle, so A12 reads it as an ordinary entity name — and the model then fails for an entirely different
 * reason, at a different tier. A checker matching case-insensitively without consulting the terminal set
 * would call it a connective and invent a verdict A12 does not give. These rows are what separate
 * "case-insensitive" from "the three spellings the lexer carries".
 *
 * <p>Own domain (the shared subscription fixture).
 */
class ConnectiveCaseLawsTest {

    private static final String A = "FieldFilled(Tier)";
    private static final String B = "FieldFilled(AutoRenew)";
    private static final String C = "FieldFilled(TermsAccepted)";

    @Test
    void everyCaseOfAConnectiveJoinsAConditionTheSameWay() {
        Map<String, String> actual = new LinkedHashMap<>();
        actual.put("And — the canonical control", verdict(A + " And " + B));
        actual.put("AND", verdict(A + " AND " + B));
        actual.put("and", verdict(A + " and " + B));
        actual.put("Or — the canonical control", verdict(A + " Or " + B));
        actual.put("OR", verdict(A + " OR " + B));
        actual.put("or", verdict(A + " or " + B));
        // The fourth case: not a terminal, so A12 reads it as an entity name rather than a connective.
        actual.put("aNd — a case the bundle does not carry", verdict(A + " aNd " + B));

        Map<String, String> expected = new LinkedHashMap<>();
        expected.put("And — the canonical control", KernelLaws.ADMITTED);
        expected.put("AND", KernelLaws.ADMITTED);
        expected.put("and", KernelLaws.ADMITTED);
        expected.put("Or — the canonical control", KernelLaws.ADMITTED);
        expected.put("OR", KernelLaws.ADMITTED);
        expected.put("or", KernelLaws.ADMITTED);
        expected.put("aNd — a case the bundle does not carry", "MVK_UNEXPECTED_TOKEN");
        assertEquals(expected, actual, "which cases of a connective A12 joins a condition with");
    }

    @Test
    void aMixIsRefusedInEveryCaseTheLexerResolves() {
        Map<String, String> actual = new LinkedHashMap<>();
        actual.put("And then Or — the canonical control", verdict(A + " And " + B + " Or " + C));
        actual.put("AND then Or", verdict(A + " AND " + B + " Or " + C));
        actual.put("and then Or", verdict(A + " and " + B + " Or " + C));
        actual.put("And then OR", verdict(A + " And " + B + " OR " + C));
        actual.put("both non-canonical", verdict(A + " and " + B + " OR " + C));
        actual.put("aNd then Or — a case the bundle does not carry", verdict(A + " aNd " + B + " Or " + C));

        Map<String, String> expected = new LinkedHashMap<>();
        expected.put("And then Or — the canonical control", "MVK_BRACKET_MISSING");
        expected.put("AND then Or", "MVK_BRACKET_MISSING");
        expected.put("and then Or", "MVK_BRACKET_MISSING");
        expected.put("And then OR", "MVK_BRACKET_MISSING");
        expected.put("both non-canonical", "MVK_BRACKET_MISSING");
        expected.put("aNd then Or — a case the bundle does not carry", "MVK_UNEXPECTED_TOKEN");
        assertEquals(expected, actual, "which cases of a mixed connective A12 refuses");
    }

    @Test
    void textEndingOnAConnectiveIsUnfinishedInEveryCaseTheLexerResolves() {
        Map<String, String> actual = new LinkedHashMap<>();
        actual.put("ends on And — the canonical control", verdict(A + " And"));
        actual.put("ends on AND", verdict(A + " AND"));
        actual.put("ends on and", verdict(A + " and"));
        actual.put("ends on aNd — a case the bundle does not carry", verdict(A + " aNd"));

        Map<String, String> expected = new LinkedHashMap<>();
        expected.put("ends on And — the canonical control", "MVK_INCOMPLETE_INPUT");
        expected.put("ends on AND", "MVK_INCOMPLETE_INPUT");
        expected.put("ends on and", "MVK_INCOMPLETE_INPUT");
        // A DIFFERENT code, and the sharpest row in the class: read as an entity name, the text is not
        // unfinished at all — it is a complete condition followed by a stray name. So the fourth case parts
        // from the three on every grid here, each time by landing on the code a NAME would draw.
        expected.put("ends on aNd — a case the bundle does not carry", "MVK_UNEXPECTED_TOKEN");
        assertEquals(expected, actual, "which cases of a trailing connective leave the text unfinished");
    }

    private static String verdict(String condition) {
        return KernelLaws.verdictOf(ModelWriter.addRule(Fixtures.subscriptionJson(), "/Subscription",
                "ConnectiveCaseProbe", "../Tier", "PROBE", condition, "ERROR", List.of(en("x"), de("y"))));
    }
}
